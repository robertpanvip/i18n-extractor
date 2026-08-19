package com.pan.extractor

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.impl.JSPsiElementFactory
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlElement
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

/**
 * 无状态 PSI / 文本辅助工具（从 I18nProcessor 拆分出的顶层 object）。
 *
 * 这些方法原先作为 I18nProcessor 的成员，但实现中不访问任何实例字段、也不调用
 * 依赖实例状态的方法，因此被安全地抽到此处。I18nProcessor 内保留同名的一行委托，
 * 保证 class 内外的调用点与行为 100% 不变。
 */
internal object I18nPsiTools {

    fun isMustache(text: String): Boolean {
        return text.contains("{{") && text.contains("}}")
    }

    fun isVueFile(psiFile: PsiFile): Boolean {
        return psiFile.name.endsWith(".vue", ignoreCase = true)
    }

    fun rm(element: PsiElement): String {
        return element.text.replace("{{", "\${")  // 替换左符号（注意$需要转义）
            .replace("}}", "}")
    }

    fun extractStringArgText(expr: PsiElement): String? {
        return when (expr) {
            is JSLiteralExpression -> {
                if (expr.isStringLiteral) expr.value as? String else null
            }
            is JSStringTemplateExpression -> {
                // 纯文本模板字面量（无插值），去反引号
                if (expr.text.startsWith("`") && expr.text.endsWith("`")) {
                    expr.text.substring(1, expr.text.length - 1)
                } else null
            }
            else -> null
        }
    }

    /**
     * 判断 [decl]（一个 ES6ImportDeclaration）是否已经从 [moduleName] 导入了 [wantedName]。
     *
     * 文本级宽松匹配（见 I18nProcessor 内原注释）：不同版本 IntelliJ 对
     * ES6ImportDeclaration 内部属性名变化很大，`decl.text` 即源代码字符串是稳定的。
     */
    fun hasImportedSpecifier(decl: ES6ImportDeclaration, moduleName: String, wantedName: String): Boolean {
        val text = decl.text.replace("\\s+".toRegex(), " ")
        // 1. from 路径检查（单双引号 / 分号 / 末尾空白 / index 尾缀 都容忍）
        val want = moduleName.lowercase()
        val fromMatch = Regex("""from\s*['"]([^'"]+)['"]""").find(text)
        val from = fromMatch?.groupValues?.get(1)?.trim()?.lowercase()?.removeSuffix("/index")
        if (from != want) return false

        val cleaned = text

        // 2. namespace import：`import * as X from` —— 只把命名空间别名 X 绑定为自由名，
        //    并不把裸 wantedName（如 useI18n）变为自由名；只有 wantedName 恰好是该 namespace 别名时才视为已导入。
        //    （P0：旧实现一律 return true，导致 `import * as NS from 'vue-i18n'` 的文件被误判为
        //     useI18n 已导入、跳过注入，而生成的裸 useI18n() 调用在运行时未定义。）
        val nsMatch = Regex("""import\s+\*\s+as\s+([A-Za-z_][\w\$]*)""").find(cleaned)
        if (nsMatch != null && nsMatch.groupValues[1] == wantedName) return true

        // 3. named import: `{ ... , useI18n , ... }` 或 `{ useI18n as xxx }`
        val curlyIdxS = cleaned.indexOf('{')
        val curlyIdxE = cleaned.lastIndexOf('}')
        if (curlyIdxS in 0 until curlyIdxE) {
            val inner = cleaned.substring(curlyIdxS + 1, curlyIdxE)
            // 匹配 `useI18n` 本身，或 `useI18n as`（别名）
            val re = Regex("""(^|[,\s])\Q$wantedName\E(\s+as\b|$|[,\s])""")
            if (re.containsMatchIn(inner)) return true
        }

        // 4. default import：import useI18n from 'vue-i18n'
        //    import 关键字后 到 from / { / * 之间的首个标识符
        val defaultMatch = Regex("""import\s+([A-Za-z_][\w\$]*)""").find(cleaned)
        if (defaultMatch != null && defaultMatch.groupValues[1] == wantedName) return true

        return false
    }

    /**
     * 判断 [scope] 范围内是否已经存在"[callee]() 函数调用 + 把 [destructureAlias] 绑定到解构"。
     * 文本级宽松匹配（见 I18nProcessor 内原注释）。
     *
     * 【Import/Symbol Collision 修复】旧实现的"近似形式"只要解构里出现 [destructureNameFrom]（如 `t`）
     * 就认为已处理，导致用户手写 `const { t } = useI18n()`（只绑定 `t`）时，插件以为 `$t` 别名已存在、
     * 跳过注入 `const { t: $t } = useI18n()`，而生成的 key 用的是 `$t(...)` → 运行时 `$t` 未定义。
     * 这里收窄到：只有当解构**确实绑定了目标别名 [destructureAlias]（`$t`）**时才算已处理。
     */
    fun scopeHasDestructuredCall(
        scope: PsiElement,
        callee: String,
        destructureNameFrom: String,
        destructureAlias: String,
    ): Boolean {
        val text = scope.text.replace("\\s+".toRegex(), " ")
        if (!text.contains("$callee(")) return false

        // 插件注入的规范形式：const { t: $t } = useI18n()（用户手写同款也算）
        val canonical = "{$destructureNameFrom: $destructureAlias}"
        if (text.contains(canonical)) return true

        // 近似形式：`const { ... } = [ns.]callee(` 的整条解构里**确实绑定了 destructureAlias（$t）** 才算。
        // （例如 const { $t } = useI18n()、const { t: $t, n } = useI18n() —— 都能让 $t 可用）
        val reChain = Regex("""\{\s*([^}]*)\}\s*=\s*[A-Za-z_$][\w$]*\s*\.\s*\Q$callee\E\(""")
        val reDirect = Regex("""\{\s*([^}]*)\}\s*=\s*\Q$callee\E\(""")
        for (re in listOf(reChain, reDirect)) {
            val m = re.find(text) ?: continue
            val inner = m.groupValues[1]
            if (inner.contains(destructureAlias)) return true
        }

        return false
    }

    /**
     * 检查文件是否已导入 react-i18next 的 getI18n（React 新模板）。
     * 匹配形式：
     *   - `import { getI18n } from 'react-i18next'`
     *   - `import { useTranslation, getI18n } from ...'`
     *   - 路径中含 `react-i18next`（容忍引号/反引号差异）
     */
    fun hasReactGetI18nImported(root: PsiElement): Boolean {
        val imports = PsiTreeUtil.findChildrenOfType(root, ES6ImportDeclaration::class.java)
        val namedSpec = Regex("""import\s*\{[^}]*\bgetI18n\b[^}]*\}""")
        return imports.any { imp ->
            val t = imp.text
            namedSpec.containsMatchIn(t) && t.contains("react-i18next")
        }
    }

    /** 找到第一个非空白符、非注释的子元素 */
    fun findFirstNonWhitespaceChild(element: PsiElement): PsiElement? {
        var child = element.firstChild
        while (child != null) {
            if (child !is PsiWhiteSpace && child !is PsiComment) {
                return child
            }
            child = child.nextSibling
        }
        return null
    }

    fun getCharactersText(textNode: XmlElement): List<XmlToken> {
        val textChild = textNode.children.filterIsInstance<XmlToken>()
            .filter { it.tokenType == XmlTokenType.XML_DATA_CHARACTERS }
        return textChild
    }

    /**
     * 收集从 [start] 开始、相邻且仅被空白分隔的文本节点序列。
     * 用于把 JSX 中被空白拆开的英文短语（"Hello" / "world"）合并成一段。
     */
    fun collectTextRun(start: XmlText): List<XmlText> {
        val result = mutableListOf(start)
        var cur: PsiElement? = start.nextSibling
        var pendingWhitespace = false
        while (cur != null) {
            when (cur) {
                is PsiWhiteSpace -> pendingWhitespace = true
                is XmlText -> {
                    if (pendingWhitespace && cur.text.trim().isNotEmpty()) {
                        result.add(cur)
                        pendingWhitespace = false
                    } else {
                        break // 无空白分隔，或该节点为空文本，不再向后合并
                    }
                }
                else -> break // 遇到表达式/标签等，停止合并
            }
            cur = cur.nextSibling
        }
        return result
    }

    /** 文本是否包含任一已启用目标语言的字符（由全局设置决定，默认仅中文）。 */
    fun containsTargetLanguage(text: String): Boolean = Util.containsTargetLanguage(text)

    /** 按站点上下文（Approach A）判定文本是否命中任一已启用目标语言。 */
    fun containsTargetLanguage(text: String, site: SiteKind): Boolean = Util.containsTargetLanguage(text, site)

    fun isJSTemplateLiteral(text: String): Boolean {
        return text.startsWith("`") && text.contains("\${")
    }

    /**
     * 如果内容是纯字符串字面量（无插值的反引号、单引号、双引号字符串），
     * 返回去掉外层引号后的内容；否则返回 null。
     */
    fun extractPureStringContent(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < 2) return null
        // 双引号字符串
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return unescapeStringLiteral(trimmed.substring(1, trimmed.length - 1))
        }
        // 单引号字符串
        if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return unescapeStringLiteral(trimmed.substring(1, trimmed.length - 1))
        }
        // 反引号字符串（必须不含 ${} 插值才算纯字符串）
        if (trimmed.startsWith("`") && trimmed.endsWith("`") && !trimmed.contains("\${")) {
            return unescapeStringLiteral(trimmed.substring(1, trimmed.length - 1))
        }
        return null
    }

    /** 单遍还原字符串字面量的常见 JS 转义，避免 key 出现字面 `\n`/`\'`/`\"`（P1）。 */
    private fun unescapeStringLiteral(content: String): String {
        // 快速路径：无反斜杠则无需还原
        if (!content.contains('\\')) return content
        val sb = StringBuilder(content.length)
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c == '\\' && i + 1 < content.length) {
                val n = content[i + 1]
                when (n) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    'b' -> { sb.append('\b'); i += 2; continue }
                    'f' -> { sb.append('\u000c'); i += 2; continue }
                    'v' -> { sb.append('\u000b'); i += 2; continue }
                    '\\' -> { sb.append('\\'); i += 2; continue }
                    '\'' -> { sb.append('\''); i += 2; continue }
                    '"' -> { sb.append('"'); i += 2; continue }
                    '0' -> { sb.append('\u0000'); i += 2; continue }
                    else -> { /* \x / \u 等复杂转义不还原，保持原样 */ }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    fun isBlock(originalText: String): Boolean {
        return originalText.startsWith('{') && originalText.endsWith('}')
    }

    /** Vue 核心指令列表（用于属性判断） */
    private val vueCoreDirectives = setOf(
        // 基础指令
        "v-text", "v-html", "v-show", "v-if", "v-else", "v-else-if",
        "v-for", "v-on", "v-bind", "v-model", "v-slot", "v-pre",
        "v-cloak", "v-once", "v-memo",
        // 指令缩写
        "@", ":", "#"
    )

    fun isVueDirective(targetStr: String): Boolean {
        // 通用判断逻辑：覆盖「v-开头指令」+「核心指令」+「指令缩写」
        // 1. 匹配所有以 v- 开头的指令（覆盖自定义指令/未枚举的v-指令）
        return targetStr.startsWith("v-")
                || targetStr.startsWith(':')
                || targetStr.startsWith('#')
                || targetStr.startsWith('@')
                // 2. 匹配核心指令（包含无v-前缀的特殊指令/缩写）
                || targetStr in vueCoreDirectives
                // 3. 兼容指令带参数的情况（比如 v-on:click、v-bind:class）
                || targetStr.split(":").first() in vueCoreDirectives
    }

    /** 去掉字符串两侧成对的引号（' / " / `）。若不成对则原样返回。 */
    fun stripSurroundingQuotes(s: String): String {
        if (s.length < 2) return s
        val first = s.first()
        val last = s.last()
        val matched = (first == '\'' && last == '\'') ||
                (first == '"' && last == '"') ||
                (first == '`' && last == '`')
        return if (matched) s.substring(1, s.length - 1) else s
    }

    fun createStringExpressionNode(text: String, context: PsiElement): PsiElement {
        val dummyLiteral = JSPsiElementFactory.createJSExpression("''", context)
        val elementType: IElementType = JSTokenTypes.STRING_LITERAL
        // 创建纯文本 LeafPsiElement（无语法解析，保留原始文本）
        val textNode = LeafPsiElement(elementType, text)

        dummyLiteral.node.addChild(textNode.node)

        // 返回挂载后的完整节点（此时文本节点已关联 CharTable）
        return dummyLiteral.lastChild
    }

    /**
     * 从文本创建 JS 语句（使用 PsiFileFactory 构造完整 PSI 语句节点）。
     * 相比直接操作 AST 节点，这种方式创建的语句结构完整，不会导致 Document is locked 异常。
     */
    fun createJSStatementFromText(text: String, context: PsiElement): PsiElement {
        val project = context.project
        val language = context.containingFile?.language
            ?: error("Cannot determine language for context element")
        val dummyFile = PsiFileFactory.getInstance(project).createFileFromText(
            "dummy.js",
            language,
            text
        )
        // 跳过空白符，取第一个有效语句
        var child: PsiElement? = dummyFile.firstChild
        while (child != null && child is PsiWhiteSpace) {
            child = child.nextSibling
        }
        return child ?: dummyFile.firstChild
    }

    /**
     * 【Bug A10】排除「同名本地普通函数」的 t/tc 调用。
     * 若引用名 `t`/`tc` 解析到**本文件内**声明的普通函数（function t / function tc），
     * 说明它不是 i18n 翻译函数，其参数里的中文仍应被提取，而不是被当成「已翻译」跳过。
     *
     * 除函数声明外，也覆盖“本地只读函数变量”形态：`const t = (s) => s` / `const tc = function(){}`。
     * 这类 t/tc 同样是本文件内定义的普通函数（JSVariable 的 initializer 是 JSFunction 或箭头函数），
     * 若被当成 i18n 调用会漏提参数中的中文（PROJECT_ANALYSIS §2 symbol collision / local t VS real i18n）。
     */
    fun isLocalFunctionNamedTCall(call: JSCallExpression): Boolean {
        val method = call.methodExpression as? JSReferenceExpression ?: return false
        val name = method.referenceName
        if (name != "t" && name != "tc") return false
        val resolved = method.resolve() ?: return false
        // 本文件内的函数声明（function t / function tc）
        if (resolved is JSFunction) return resolved.containingFile == call.containingFile
        // 本文件内的只读函数变量：const t = () => … / let tc = function(){}
        if (resolved is JSVariable) {
            if (resolved.containingFile != call.containingFile) return false
            val initializer = resolved.initializer ?: return false
            // initializer 本身是函数 / 箭头函数
            if (initializer is JSFunction) return true
            // initializer 是表达式且其内嵌含函数体（如 (x) => x 解析形态）
            return PsiTreeUtil.findChildOfType(initializer, JSFunction::class.java) != null
        }
        return false
    }

    /**
     * BUG_ANALYSIS 3.2 — 确认链式调用 `X.t()/X.tc()` 是否为**已确认的 i18n 全局实例**。
     *
     * 之前的实现用文本 `callee.endsWith(".t")` / `endsWith(".tc")` 一刀切，会把任意普通对象的
     * `obj.t('中文')`、`foo.bar.t('中文')` 误判为「已翻译」调用，导致其中的中文被错误跳过（漏提）。
     * 这里收窄到已确认的 i18n 实例接收者：接收者标识符须为 `i18n`（内部可再 `.global`）。
     * 这样 `i18n.t()` / `i18n.global.t()` 照常命中，而 `obj.t()` / `foo.bar.t()` 视为普通方法调用，
     * 其参数中的中文会正常进入提取（宁可多提，也不漏提）。
     */
    @JvmStatic
    fun isConfirmedI18nGlobalChainCall(call: JSCallExpression): Boolean {
        val method = call.methodExpression
        if (method !is JSReferenceExpression) return false
        val name = method.referenceName
        if (name != "t" && name != "tc") return false
        // 简单引用 t / tc 不算链式；只有带接收者的链式才会走到这里校验实例名
        // #38：若接收者解析成本地普通对象/函数（`const i18n = { t: … }` / `const i18n = () => …`），
        // 它并非真实 i18n 实例 → 视为普通调用，参数中的中文正常进入提取（宁可多提不漏提）。
        if (isLocalPlainReceiverShadowingBase(method)) return false
        // 多行链式（如 i18n\n.global\n.t）的 text 会含空白，需剥除后再匹配。
        val text = method.text?.replace("\\s".toRegex(), "")
        // 接收者必须是 i18n（支持 i18n.t / i18n.global.t / i18n.tc / i18n.global.tc 等）
        return text != null && text.startsWith("i18n.") && (text.endsWith(".t") || text.endsWith(".tc"))
    }

    /**
     * #38：沿 qualifier 链下钻到最底层的接收者标识符（i18n.global.t → i18n.global → i18n），
     * 解析该标识符；若它是本地普通对象 / 函数变量（不是真实 i18n 实例），返回 true。
     * 用于判定 `X.t('中文')` 是否应被当作「已翻译」而跳过——本地 shadow 时不应跳过。
     */
    private fun isLocalPlainReceiverShadowingBase(method: JSReferenceExpression): Boolean {
        var qualifier: JSExpression? = method.qualifier
        var base: JSReferenceExpression? = null
        while (qualifier is JSReferenceExpression) {
            base = qualifier
            qualifier = qualifier.qualifier
        }
        if (base == null) return false
        val resolved = base.resolve() ?: return false
        if (resolved !is JSVariable) return false
        val init = resolved.initializer ?: return false
        // 本地对象字面量：const i18n = { t: … }（issue #38 根因场景）
        if (init is JSObjectLiteralExpression) return true
        // 本地普通函数：const i18n = function / const i18n = () => …
        if (init is JSFunction) return true
        return false
    }

    /**
     * 【新判定模型】「t 是弱特征，不是语义证明」：判断 [call] 是否为**已证明**的 i18n 翻译调用。
     *
     * 委托 [com.pan.extractor.analyzer.TranslationAnalyzer]：CallExpression → callee →
     * Reference Resolution → symbol 来源分类（i18n 框架 import / hook 或工厂产物 / 插件 \$t /
     * 本地 shadow / 非 i18n / unknown）。只有 [com.pan.extractor.analyzer.TranslationCallStatus.TRANSLATION]
     * 返回 true；无法证明的调用（UNKNOWN）返回 false，由调用方按保守策略处理（不提取也不改写）。
     *
     * 旧实现（名字兜底 `name == "t" || name == "tc"`）已废弃——本地普通函数、对象、非 i18n
     * import 的 t/tc 不再被误判为「已翻译」，旧辅助方法 [isLocalFunctionNamedTCall] /
     * [isConfirmedI18nGlobalChainCall] 一并被 [com.pan.extractor.analyzer.SymbolAnalyzer] 取代
     * （保留仅为兼容外部委托调用）。
     */
    @JvmStatic
    fun isI18nTranslationCall(call: JSCallExpression): Boolean =
        com.pan.extractor.analyzer.TranslationAnalyzer.isTranslationCall(call)

    /**
     * 【新判定模型】字符串字面量在其外层调用上下文中的位置（提取 / 替换策略依据）。
     *
     * 委托 [com.pan.extractor.analyzer.TranslationAnalyzer.contextOf] 并映射回兼容的
     * [I18nProcessor.TSem]：新增 [I18nProcessor.TSem.INSIDE_UNKNOWN]——字面量位于
     * 无法证明来源的调用参数内部时，调用方应保守跳过（既不提取也不改写，零误改）。
     */
    fun detectTSemantic(stringExpr: JSLiteralExpression): I18nProcessor.TSem {
        return when (com.pan.extractor.analyzer.TranslationAnalyzer.contextOf(stringExpr)) {
            com.pan.extractor.analyzer.StringContext.DIRECT_TRANSLATION_ARG -> I18nProcessor.TSem.DIRECT_ARG
            com.pan.extractor.analyzer.StringContext.INSIDE_TRANSLATION_EXPRESSION -> I18nProcessor.TSem.OUTER_T_EXPRESSION
            com.pan.extractor.analyzer.StringContext.INSIDE_UNKNOWN_CALL -> I18nProcessor.TSem.INSIDE_UNKNOWN
            com.pan.extractor.analyzer.StringContext.NONE -> I18nProcessor.TSem.NONE
        }
    }

    /** 旧名兼容：DIRECT_ARG（已翻译直接参数）与 INSIDE_UNKNOWN（无法证明）都跳过处理。 */
    fun isTransformedCalled(stringExpr: JSLiteralExpression): Boolean =
        detectTSemantic(stringExpr).let { it == I18nProcessor.TSem.DIRECT_ARG || it == I18nProcessor.TSem.INSIDE_UNKNOWN }

    /**
     * 提取 XmlText 中的纯文本（过滤注释、空白符、换行符）
     * 处理场景：<h1>123<!-- 注释 -->这是我的测试</h1> → 输出 "123这是我的测试"
     */
    fun getPureXmlText(xmlText: XmlText): String {
        val stringBuilder = StringBuilder()

        // 遍历 XmlText 的所有子节点
        xmlText.children.forEach { child ->
            // 跳过注释节点
            when (child) {
                is XmlComment -> return@forEach
                // 跳过纯空白符（换行、空格、制表符）
                is PsiWhiteSpace -> {
                    stringBuilder.append(child.text) // 保留单个空格
                }
                // 有效文本节点：拼接内容
                else -> stringBuilder.append(child.text ?: "")
            }
        }

        // 去掉多余空格，合并连续空格为一个
        return stringBuilder.toString()
            .trim() // 去掉首尾空格
    }

    fun hasEqInExpression(expr: PsiElement?): Boolean {
        if (expr == null) return false
        if (expr !is JSBinaryExpression) {
            return false
        }
        val op = expr.operationNode?.elementType
        return op == JSTokenTypes.EQEQ || op === JSTokenTypes.EQEQEQ
    }

    /**
     * 判断这个字符串字面量是否是 enum entry 的初始化值
     * 如 enum X { A = "中文" } 中的 "中文"
     */
    // ───────────────────────────────────────────────
    // 跳过：成员变量/数组下标/index 访问中的中文 key（用户需求）
    //   例：P['中文']、obj['姓名']、arr['第1个']、P['姓' + '名']、P[`中文键${suffix}`]
    //   只要元素在 JSIndexedPropertyAccessExpression 的 indexExpr 子树里（即 [...] 方括号内）
    //   就跳过 —— 并且严格只跳过"index 表达式内部"，不要误把 qualifier 里的中文也砍掉。
    // ───────────────────────────────────────────────
    fun isInIndexKeyPosition(ele: PsiElement): Boolean {
        // PsiTreeUtil.isAncestor(ancestor, descendant, strict=false)：允许
        //   ancestor == descendant（非严格祖先）。因为 indexExpr 经常就是
        //   ele 自己（P['中文'] 里 indexExpr 直接就是 '中文' 字面量）。
        val indexed = PsiTreeUtil.getParentOfType(ele, JSIndexedPropertyAccessExpression::class.java)
            ?: return false
        val ie = indexed.indexExpression ?: return false
        return PsiTreeUtil.isAncestor(ie, ele, false)
    }

    /**
     * 判断 ele 是否是一个「指令属性值整体」的字符串字面量，即 `:title="'中文'"` 里的 `'中文'`。
     * 此时内层字符串字面量是属性值的唯一内容，应交给 collectXmlAttributeValueChange 统一处理，
     * 避免 collectJSStringChange 重复提取。
     */
    fun isDirectiveSoleStringLiteral(ele: JSLiteralExpression): Boolean {
        val attrValue = PsiTreeUtil.getParentOfType(ele, XmlAttributeValue::class.java, false) ?: return false
        val attr = attrValue.parent as? XmlAttribute ?: return false
        if (!isVueDirective(attr.name)) return false
        // 整个属性值就等于这个字符串字面量（含引号），说明它不是表达式里的一部分
        return ele.text == attrValue.value.trim()
    }

    /**
     * 【Bug A1】判断 ele 是否位于「纯字符串拼接」内：自 ele 向上找到最顶层的 `+` 表达式，
     * 并递归检查整条拼接链的所有叶子操作数是否都是字符串字面量（无变量/引用/数字/嵌套调用）。
     */
    fun isWithinPureStringConcat(ele: PsiElement): Boolean {
        // 向上找到最顶层的 PLUS 表达式
        var top: PsiElement? = ele
        while (true) {
            val parent = top?.parent as? JSBinaryExpression
            if (parent == null || parent.operationSign != JSTokenTypes.PLUS) break
            top = parent
        }
        val topBin = top as? JSBinaryExpression ?: return false
        // 递归检查整条链的所有操作数是否都是纯字符串
        return isPureStringOperand(topBin.lOperand) && isPureStringOperand(topBin.rOperand)
    }

    /** 判断某操作数是否为可被整体合并的纯字符串（字面量、纯模板，或嵌套的纯字符串拼接）。 */
    fun isPureStringOperand(e: PsiElement?): Boolean {
        return when (e) {
            null -> false
            is JSLiteralExpression -> true
            is JSStringTemplateExpression -> {
                // 纯字符串模板（无 ${} 插值）可整体合并；有插值则不是纯字符串
                !e.text.contains("${'$'}{")
            }
            is JSBinaryExpression -> {
                // 嵌套的 + 拼接：仅当左右操作数也都是纯字符串时才视为纯字符串
                e.operationSign == JSTokenTypes.PLUS &&
                        isPureStringOperand(e.lOperand) && isPureStringOperand(e.rOperand)
            }
            else -> false
        }
    }

    fun convertConcatTextToTemplate(binaryExpr: JSBinaryExpression): String {
        val sb = StringBuilder("`")

        // 递归处理「左右操作数」，而非所有子节点（避免空白/多余节点）
        fun processOperand(operand: PsiElement) {
            when (operand) {
                // 递归处理嵌套的 + 拼接表达式
                is JSBinaryExpression -> {
                    val nestedTemplate = convertConcatTextToTemplate(operand)
                    sb.append(nestedTemplate.substring(1, nestedTemplate.length - 1))
                }
                // 字符串字面量：去掉引号
                is JSStringTemplateExpression -> {
                    sb.append(operand.text.substring(1, operand.text.length - 1))
                }

                else -> {
                    val text = operand.text.trim() // 去除节点文本的首尾空格
                    when {
                        // 过滤空文本/空格/+号
                        text.isBlank() || text == "+" -> return
                        // 普通字符串字面量（单/双引号）
                        text.startsWith("'") || text.startsWith("\"") -> {
                            sb.append(text.substring(1, text.length - 1))
                        }
                        // 变量/数字/表达式：用 ${} 包裹
                        else -> sb.append("\${$text}")
                    }
                }
            }
        }

        // 只处理「左操作数」和「右操作数」（+ 表达式的核心片段）
        binaryExpr.lOperand?.let { processOperand(it) }
        binaryExpr.rOperand?.let { processOperand(it) }

        sb.append("`")
        return sb.toString()
    }

    /**
     * BUG #37：生成 key 前对 vue-i18n / i18next 视为「路径/语法分隔符」的保留字符消毒。
     *   - `@` → vue-i18n 指令 / i18next 命名空间语法
     *   - `|` → vue-i18n 复数分隔符
     *   - 句首/句末的 `.` → 造成空路径分段，去掉（但**内部**的点是合法内容：编号列表
     *     "1. 隔离库存"、小数 "CD≥0.8" 必须保留，不能像处理路径分隔符那样整体替换，
     *     否则会损坏真实用户文案生成的新 key）
     * 一律缩成单个空格并 trim。generateKey 是所有 site key 的唯一入口，调用侧 $t(key)
     * 与写回资源文件的 key 天然同源，故此处消毒保证两侧一致、运行时可查。
     */
    internal fun generateKey(value: String, element: PsiElement): String {
        return value.trim()
            .replace("@", " ")
            .replace("|", " ")
            .trim()
            .trimStart('.')
            .trimEnd('.')
            .trim()
    }

    fun isInComment(element: PsiElement): Boolean {
        var parent = element.parent
        while (parent != null) {
            if (parent is PsiComment) return true
            parent = parent.parent
        }
        return false
    }

    fun isComment(element: PsiElement): Boolean {
        val content = element.text.trim();

        return content.startsWith("<!--") && content.endsWith("-->")
    }

    fun isInStyleOrComment(element: PsiElement): Boolean {
        var parent = element.parent
        while (parent != null) {
            if (parent is PsiComment) return true
            if (parent is XmlTag && parent.name == "style") return true
            parent = parent.parent
        }
        return false
    }

    /**
     * 纯文本构建 t() 调用，不依赖当前 processor 已探测到的 tFunctionName 注入上下文，
     * 直接按 isVue/isReact 生成最稳妥形式（和现有探测一致：都用 \$t，减少复杂度）。
     * - 若 skeletonKeyOverride 非空：key 用这个覆盖而不是 message.trim()（供合并骨架时使用）
     */
    fun buildTExprForRawText(
        message: String,
        paramsObject: String,
        isVue: Boolean,
        isReact: Boolean,
        skeletonKeyOverride: String? = null,
    ): String {
        val trimmedMsg = message.trim()
        val escapedMsg = if (trimmedMsg.contains("\n")) {
            trimmedMsg.replace("`", "\\`")
        } else {
            trimmedMsg.replace("'", "\\'")
        }
        val quote = if (trimmedMsg.contains("\n")) "`" else "'"
        val key = skeletonKeyOverride?.trim()?.ifBlank { null } ?: trimmedMsg
        // Vue 用 $t，React 用 t；避免长形式 i18n.global.t / i18n.t
        val fn = if (isReact) "t" else "\$t"
        val keyEscaped = if (key.contains("\n")) key.replace("`", "\\`") else key.replace("'", "\\'")
        return if (paramsObject.replace(" ", "") == "{}") {
            "$fn($quote$keyEscaped$quote)"
        } else {
            "$fn($quote$keyEscaped$quote, $paramsObject)"
        }
    }
}