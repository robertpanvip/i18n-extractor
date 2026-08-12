package com.pan.extractor

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.lang.javascript.psi.impl.JSPsiElementFactory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.*
import kotlin.collections.forEach
import kotlin.text.replace

class I18nProcessor(
    private val project: Project,
    private var psiFile: PsiElement,
) {
    /** 公开只读访问器，供进度条 UI 显示文件名（progress 线程里调用） */
    val targetPsiFile: PsiElement get() = psiFile

    var effects = mutableListOf<() -> Unit>()

    /** 新提取的 key -> 原文本 */
    val extractedStrings = mutableMapOf<String, String>()

    /** 已存在的 $t() 调用 key -> 原文本（仅展示，不替换） */
    val existingStrings = mutableMapOf<String, String>()

    val factory: XmlElementFactory = XmlElementFactory.getInstance(project)

    /** 检测到的翻译函数名（例如 $t / t / i18n.t），默认 $t */
    private var tFunctionName: String = "\$t"

    /**
     * 全局 $t 别名注入标记（用户要求：全部统一用 $t，减少复杂度）。
     *
     * =============== Vue 版本 needInjectGlobalDollarT ===============
     * - true：run() 调 ensureI18nInstanceImported(isVue=true) 时，会在
     *   `import { i18n } from '...'` 之后追加 `const \$t = i18n.global.t;`
     *   （并去重保证只出现一次）。
     *
     * 预判："Vue 项目 + 非 .vue SFC（纯 .ts/.js 文件）+ 无自定义 hook"
     * → 这种场景没法用 useI18n 解构 $t，但只要在文件顶部把 i18n.global.t
     *   赋给全局 const $t，全文件仍然可以用 $t('xxx') 短写法。
     *
     * =============== React 版本 needInjectReactGlobalDollarT ===============
     * - true：run() 调 ensureI18nInstanceImported(isVue=false) 时，会在
     *   `import { getI18n } from 'react-i18next'` 之后追加
     *   `const \$t = getI18n().t;`（并去重保证只出现一次）。
     *
     * 预判："React 项目 + 既没有 React 组件也没有自定义 hook（纯工具函数）"
     * → 旧实现是切 `tFunctionName="i18n.t"` + 注入 `import i18n from 'i18next'`，
     *   用户觉得长调用 i18n.t('key') 麻烦，要求统一写 $t('key')：
     *   仍然保持 tFunctionName 为默认 \$t，只需要在顶部写 2 行代码：
     *       import { getI18n } from 'react-i18next';
     *       const $t = getI18n().t;
     *   之后文件里所有替换仍然是短写法 \$t('xxx')，与 Vue/组件/Hook 内部一致。
     */
    private var needInjectGlobalDollarT: Boolean = false
    private var needInjectReactGlobalDollarT: Boolean = false

    fun isMustache(text: String): Boolean {
        return text.contains("{{") && text.contains("}}")
    }

    // 處理帶 Mustache 的 XmlText：獲取注入的 JS
    fun visitMustache(element: PsiElement, visitElement: (JSExpression) -> Unit) {

        val injected = InjectedLanguageManager.getInstance(project)
            .getInjectedPsiFiles(element)  // 或 getInjectedFragments
        injected?.forEach { pair ->
            val injectedRoot: PsiElement = pair.first     // 這才是注入的 PSI 根元素
            injectedRoot.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(e: PsiElement) {
                    if (e is JSLiteralExpression) {
                        visitElement(e)
                    }
                    if (e is JSBinaryExpression) {
                        visitElement(e)
                    }
                    super.visitElement(e)
                }
            })
        }
    }


    private fun isVueFile(psiFile: PsiFile): Boolean {
        return psiFile.name.endsWith(".vue", ignoreCase = true)
    }

    fun rm(element: PsiElement): String {
        return element.text.replace("{{", "\${")  // 替换左符号（注意$需要转义）
            .replace("}}", "}")
    }

    fun collectXmlText(element: PsiElement, changes: MutableList<() -> Unit>) {
        if (isComment(element)) {
            return
        }

        val quote = "`"
        // 过滤掉 PsiWhiteSpace 和注释子节点，避免首尾空白和注释内容干扰 raw 字符串构建
        val children = element.children.filter { it !is PsiWhiteSpace && !isComment(it) && it !is PsiComment }
        if (children.isEmpty()) return

        val sb = StringBuilder()
        children.forEachIndexed { index, e ->
            val text = rm(e)
            when (index) {
                0 -> sb.append(quote).append(text)
                children.lastIndex -> sb.append(text).append(quote)
                else -> sb.append(text)
            }
        }
        if (children.size == 1) {
            sb.append(quote)
        }
        val raw = sb.toString().trim()

        // 已是 $t() 调用的跳过（兼容 ${ $t( 和 ${$t( 两种写法）
        // 同时去除所有空白字符后检查，避免换行/空格干扰
        val compactRaw = raw.replace(Regex("\\s"), "")
        if (compactRaw.startsWith("`\${\$t(")) {
            return
        }

        // 1. 先尝试通过注入的 JS 提取字符串字面量
        // 对于复杂表达式（三目、函数调用等），注入的 JS PSI 能准确找到内部的字符串
        val injected = InjectedLanguageManager.getInstance(project)
            .getInjectedPsiFiles(element)
        if (injected != null && injected.isNotEmpty()) {
            var foundStrings = false
            injected.forEach { pair ->
                pair.first.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(e: PsiElement) {
                        if (e is JSLiteralExpression && !isInComment(e)) {
                            // 跳过模板字面量内部的字符串（避免重复提取）
                            if (PsiTreeUtil.getParentOfType(e, JSStringTemplateExpression::class.java) == null) {
                                // 跳过已在 $t() 调用中的字符串
                                if (!isTransformedCalled(e)) {
                                    collectJSStringChange(e, changes)
                                    foundStrings = true
                                }
                            }
                        }
                        if (e is JSBinaryExpression && !isInComment(e)) {
                            // 只有实际产生变更时才标记 foundStrings，避免无条件跳过 $t() 检查
                            val sizeBefore = changes.size
                            collectJSBinaryExpressionChange(e, changes)
                            if (changes.size > sizeBefore) {
                                foundStrings = true
                            }
                        }
                        super.visitElement(e)
                    }
                })
            }
            // 如果注入 JS 中找到了字符串，就不再用模板字符串方式处理
            if (foundStrings) {
                return
            }
            // 如果 raw 中已包含 $t() 调用，说明所有字符串已在 $t() 中，跳过回退方案
            if (raw.contains("\$t(") || raw.contains("i18n.global.t(") || raw.contains("i18n.t(")) {
                return
            }
            // 如果 raw 去除 ${} 后只剩 JS 注释（如 {{ //新增按钮 }}），跳过
            val contentOnly = raw.substring(1, raw.length - 1).trim()
            val strippedContent = contentOnly.replace(Regex("\\$\\{[^}]*\\}"), "").trim()
            if (strippedContent.startsWith("//") || (strippedContent.startsWith("/*") && strippedContent.endsWith("*/"))) {
                return
            }
        }

        // 2. 回退方案：用模板字符串方式处理（适用于简单模板字面量场景）
        collectJSStringTemplate(raw, changes, element) { value -> "{{${value}}}" }
    }

    fun pureCollect(psiFile: PsiElement): MutableList<() -> Unit> {
        val changes = mutableListOf<() -> Unit>();
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                //println("${element.text},${element.javaClass.simpleName}")
                when (element) {
                    is XmlText -> if (!isInStyleOrComment(element)) {
                        if (isMustache(element.text)) {
                            // 只用 collectXmlText 统一处理，避免 visitMustache 重复提取单个表达式
                            collectXmlText(element, changes)
                        } else {
                            collectTemplateTextChange(element, changes)
                        }
                    }

                    is XmlAttributeValue -> if (!isInStyleOrComment(element)) {
                        //println("XmlAttributeValue${element.text}")
                        collectXmlAttributeValueChange(element, changes)
                    }

                    is JSLiteralExpression -> if (!isInComment(element)) {
                        //println("JSString${element.text}")
                        collectJSStringChange(element, changes)
                    }

                    is JSBinaryExpression -> if (!isInComment(element)) {
                        //println("JSBinaryExpression${element.text}")
                        collectJSBinaryExpressionChange(element, changes)
                    }
                }
                super.visitElement(element)
            }
        })
        return changes
    }

    fun collect(): MutableList<() -> Unit> {
        // Bug 2: 语言包/翻译资源文件（en-US.ts、i18n/zh-CN.js、messages.ja.ts、locales/xxx）
        // 本身存储的就是翻译后的 key/value，应当跳过整个提取与注入流程。
        val containingFile = psiFile.containingFile
        if (containingFile != null && Util.isTranslationResourceFile(containingFile)) {
            effects = mutableListOf()
            return effects
        }

        // —— 旧：React 普通函数预判切换 tFunctionName=i18n.t，然后注入 import i18n from 'i18next'
        // —— 新（用户要求：统一用 $t 减少复杂度）：React 普通函数预判也不切 tFunctionName，
        //    保持默认 $t；改为打布尔标记 needInjectReactGlobalDollarT=true，由 ensureI18nInstanceImported
        //    在文件顶部注入：
        //        import { getI18n } from 'react-i18next';
        //        const $t = getI18n().t;
        //    翻译函数仍是 $t('key')，与 Vue/React hook 内部一致。
        //
        //    预判规则：React 项目 + （当前默认翻译函数仍是 $t）+ （既没有组件也没有自定义 hook）
        if (tFunctionName == "\$t") {
            val f = containingFile ?: (psiFile as? PsiFile)
            if (f != null && Util.isReact(f)) {
                val components = Util.findReactComponentFunctions(f)
                val hooks = Util.findHookFunctions(f)
                if (components.isEmpty() && hooks.isEmpty()) {
                    needInjectReactGlobalDollarT = true
                }
            } else if (f != null &&
                !f.name.endsWith(".vue", ignoreCase = true) &&
                Util.isVue(f)) {   // ★ 用户新要求：Vue 项目判定必须看 package.json 依赖
                // 「用户要求：全部都用 \$t 减少复杂度」
                //
                // 之前实现是切 tFunctionName = i18n.global.t，替换结果变成
                //   const label = i18n.global.t('日期')   长串
                // 现在保持默认 \$t，改为顶部注入全局别名：
                //   import { i18n } from '@/locales/xxx'
                //   const \$t = i18n.global.t
                // 全局文件仍然用 \$t('xxx') 短写法，与 Vue SFC 内的写法保持一致。
                //
                // 触发条件：已知脚本后缀 + 「既没有 Vue 组件也没有自定义 hook」。
                // 【★ 你刚才反馈的 Bug 修复】：Vue TSX (.tsx) 文件里经常有
                // defineComponent({...}) 或 PascalCase 函数式组件，这些是 Vue 组件，
                // **不能**按"纯工具"注入顶部 const $t = i18n.global.t——
                //   有 Vue 组件 → 走 ensureVueComponentI18nDeclared() 的 useI18n hook。
                //   纯工具（Vue TS/TSX 里没有组件和 Hook）→ 才能打 needInjectGlobalDollarT=true。
                val ext = f.name.substringAfterLast('.', "")
                val known = ext.equals("ts", ignoreCase = true) || ext.equals("tsx", ignoreCase = true) ||
                    ext.equals("js", ignoreCase = true) || ext.equals("jsx", ignoreCase = true)
                if (known) {
                    val hooks = Util.findHookFunctions(f)
                    val vueComponents = Util.findVueComponentFunctions(f)
                    if (hooks.isEmpty() && vueComponents.isEmpty()) {
                        needInjectGlobalDollarT = true
                    }
                }
            }
        }

        collectExistingTKeys()
        // collectExistingTKeys 可能会基于现有调用把 tFunctionName 改成 i18n.t（如文件
        // 已经存在 i18n.t('xxx') 调用）——这是对的，不要覆盖回去。
        val changes = pureCollect(psiFile)
        effects = changes;
        return changes;
    }

    /**
     * 扫描文件中已有的 $t() / t() / i18n.global.t() / i18n.t() 调用，收集其 key 到 existingStrings。
     * 覆盖模板注入 JS 和 script/JS/TS 两种来源。
     * 同时检测文件使用的翻译函数名：
     * - Vue: i18n.global.t（vue-i18n 全局实例）
     * - React: i18n.t（i18next 全局实例）
     * - 默认: $t（useI18n / useTranslation 解构）
     * 注意：i18n.global.t 和 $t 可以在同一文件中共存，两者都识别为已翻译。
     */
    private fun collectExistingTKeys() {
        // 0. 检测翻译函数名：扫描已有 JSCallExpression
        // 优先级：i18n.global.t > i18n.t > $t（默认）
        // 注意：Vue 模板 {{ }} 中的 JS 调用是注入到 XmlText 的注入 PSI，
        // 不在主 PSI 树中，需要单独扫描（见下方 mustache 处理）。
        PsiTreeUtil.findChildrenOfType(psiFile, JSCallExpression::class.java).forEach { call ->
            detectTFunctionName(call)
        }

        // 1. 模板 {{ }} 中的注入 JS
        PsiTreeUtil.findChildrenOfType(psiFile, XmlText::class.java).forEach { xmlText ->
            if (isMustache(xmlText.text)) {
                val injected = InjectedLanguageManager.getInstance(project)
                    .getInjectedPsiFiles(xmlText)
                if (injected != null && injected.isNotEmpty()) {
                    // 有 JS 注入：通过 PSI 遍历查找 $t() 调用
                    injected.forEach { pair ->
                        collectTKeysRecursive(pair.first)
                    }
                }
                // 无论是否有 JS 注入，都从原始文本中补充提取 $t() 调用。
                // backtick 模板字符串 $t(`确定`) 虽然有注入但注入的 PSI 可能不包含
                // JSCallExpression，导致 $t() 调用被遗漏。
                collectTKeysFromRawText(xmlText.text)
            }
        }

        // 2. script / JS / TS 中的 $t() 调用
        PsiTreeUtil.findChildrenOfType(psiFile, JSCallExpression::class.java).forEach { call ->
            collectTKeyFromCall(call)
        }
    }

    /**
     * 从原始文本中提取 $t(`文本`)、$t("文本")、$t('文本')、i18n.global.t(`文本`) 等调用，
     * 用于 Vue 模板中 backtick 等无法被 JS 注入解析的情况。
     */
    private fun collectTKeysFromRawText(text: String) {
        // 匹配 $t / $tc / i18n.global.t / i18n.global.tc / i18n.t / i18n.tc 调用
        // 使用反向引用确保引号配对（如开闭都是反引号）
        val pattern = Regex("(?:\\$(?:t|tc)|i18n\\.global\\.(?:t|tc)|i18n\\.(?:t|tc))\\(\\s*([`\"'])([^`\"'\\n]+)\\1\\s*[,)]")
        pattern.findAll(text).forEach { match ->
            val content = match.groupValues[2]
            val key = generateKey(content.trim(), psiFile)
            existingStrings.putIfAbsent(key, content.trim())
        }
    }

    private fun collectTKeysRecursive(root: PsiElement) {
        root.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is JSCallExpression) {
                    collectTKeyFromCall(element)
                    // 同步检测翻译函数名：mustache 注入的 i18n.global.t / i18n.t
                    // 不在主 PSI 树中，需在此补充检测
                    detectTFunctionName(element)
                }
                super.visitElement(element)
            }
        })
    }

    private fun collectTKeyFromCall(call: JSCallExpression) {
        val method = call.methodExpression
        val firstArg = call.arguments.firstOrNull() ?: return
        val text = extractStringArgText(firstArg) ?: return
        val key = generateKey(text.trim(), call)

        // 分支 A：简单引用名 $t / t / $tc / tc（useI18n 解构得到的局部 t 函数）
        if (method is JSReferenceExpression) {
            val name = method.referenceName
            if (name == "\$t" || name == "t" || name == "\$tc" || name == "tc") {
                existingStrings.putIfAbsent(key, text.trim())
            }
            return
        }
        // 分支 B：链式调用 i18n.global.t / i18n.t / i18n.global.tc / i18n.tc
        //        这里 method 是 JSPropertyReferenceExpression 或其他链表达式，
        //        isTransformedCalled 已经用 endsWith(".t")/".tc" 判定为"已翻译"，
        //        但之前没有把参数字符串录入 existingStrings，导致 JSON 里缺这段。
        val calleeText = method?.text ?: return
        if (calleeText.endsWith(".t") || calleeText.endsWith(".tc") ||
            calleeText.endsWith(".global.t") || calleeText.endsWith(".global.tc")) {
            existingStrings.putIfAbsent(key, text.trim())
        }
    }

    /**
     * 检测翻译函数名并更新 [tFunctionName]。优先级（默认 \$t 不变的前提下）：i18n.global.t > i18n.t。
     * 在主 PSI 树和 mustache 注入 PSI 中均需调用，以覆盖 Vue 模板内的调用。
     *
     * 【Bug 修复：needInject*GlobalDollarT 时绝不切长调用】
     * 用户要求「全部统一用 \$t 减少复杂度」，所以一旦 collect() 预判命中
     * needInjectGlobalDollarT / needInjectReactGlobalDollarT=true（即这个文件被判定为
     * 「Vue 纯 TS 工具」或「React 纯 TS 工具」），即便该文件里有历史遗留的
     * i18n.global.t / i18n.t 长调用，也不允许把 tFunctionName 从 \$t 改写成长调；
     * 老调用只作"兼容保留"，**新提取一律写短 $t('xxx')**。
     */
    private fun detectTFunctionName(call: JSCallExpression) {
        // 预判为「统一 $t 别名模式」时：锁死 tFunctionName=$t，老调用只兼容不影响新提取形式
        if (needInjectGlobalDollarT || needInjectReactGlobalDollarT) {
            tFunctionName = "\$t"
            return
        }
        val method = call.methodExpression
        if (method is JSReferenceExpression) {
            val text = method.text
            // Vue: i18n.global.t / i18n.global.tc
            if (text == "i18n.global.t" || text == "i18n.global.tc") {
                tFunctionName = "i18n.global.t"
            }
            // React: i18n.t / i18n.tc（i18next 全局实例）
            else if (text == "i18n.t" || text == "i18n.tc") {
                if (tFunctionName == "\$t") {
                    tFunctionName = "i18n.t"
                }
            }
        }
    }

    private fun extractStringArgText(expr: PsiElement): String? {
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

    fun run() {
        // Bug 2（双重保险）：翻译资源文件不做任何 import/hook 注入
        val containingFile = psiFile.containingFile
        if (containingFile != null && Util.isTranslationResourceFile(containingFile)) return

        this.effects.forEach { it() }
        val isVue = isVueFile(psiFile.containingFile) || Util.isVue(psiFile)
        val isReact = Util.isReact(psiFile)
        // 需要全局 i18n 实例 import 的场景：
        // 【问题 1 修复（用户报告：没有中文的文件也导入了全局导入）】
        // 老代码把 needInjectGlobalDollarT / needInjectReactGlobalDollarT 作为「优先级最高的
        // 独立 OR 条件」——但这两个标记只是"预判注入形式（Vue/React 的 const $t 别名模板）"
        // 的 switch，不等于"有实际内容需要用 $t"。纯纯工具文件只要"是 React 项目 + 无组件无 Hook"
        // 就会命中，导致哪怕一个中文都没提取也要顶部塞两行导入。
        //
        // 新规则（严谨）：**只有存在"需要全局 i18n 语义的内容"才注入**。
        //   「内容」= extractedStrings（新提取） 或 existingStrings（文件里原本写了 t/i18n 调用但缺导入）
        //   「形式 switch」= needInjectGlobalDollarT / needInjectReactGlobalDollarT /
        //                   tFunctionName == i18n.global.t / tFunctionName == i18n.t
        //
        // 具体 4 个场景：
        //   1) extractedStrings 非空（有新中文替换）→ 必须注入全局语义（只要 tFunctionName 不是
        //      组件/Hook 内部能提供的 \$t，或者 needInject*GlobalDollarT=true 预判了"纯工具文件")
        //   2) existingStrings 非空（原本就有老 t 调用）+ tFunctionName == i18n.global.t / i18n.t
        //      → 维持旧全局长调用，但缺 import 时补 import（这是 section 9 的老测试）
        //   3) needInjectGlobalDollarT=true 且（extractedStrings 非空 或 existingStrings 非空
        //      但文件里还没有 Vue 的 const $t = i18n.global.t 别名）→ Vue 纯工具文件要补齐
        //   4) needInjectReactGlobalDollarT=true 且（extractedStrings 非空 或 existingStrings 非空
        //      但文件里还没有 React 的 const $t = getI18n().t 别名）→ React 纯工具文件要补齐
        val hasAnyTCallsNeedingGlobalInstance = extractedStrings.isNotEmpty() ||
            (existingStrings.isNotEmpty() &&
                (tFunctionName == "i18n.global.t" || tFunctionName == "i18n.t"))
        val vueModeNeedsImport = needInjectGlobalDollarT &&
            (extractedStrings.isNotEmpty() || existingStrings.isNotEmpty())
        val reactModeNeedsImport = needInjectReactGlobalDollarT &&
            (extractedStrings.isNotEmpty() || existingStrings.isNotEmpty())
        val needGlobalI18nImport = (
            hasAnyTCallsNeedingGlobalInstance ||
                vueModeNeedsImport || reactModeNeedsImport
            )
        if (needGlobalI18nImport) {
            if (isVue && (
                    tFunctionName == "i18n.global.t" ||
                        (extractedStrings.isNotEmpty() && needInjectGlobalDollarT) ||
                        vueModeNeedsImport
                    )
            ) {
                ensureI18nInstanceImported(psiFile, isVue = true, injectGlobalDollarT = needInjectGlobalDollarT)
            } else if (isReact && (
                    tFunctionName == "i18n.t" ||
                        (extractedStrings.isNotEmpty() && needInjectReactGlobalDollarT) ||
                        reactModeNeedsImport
                    )
            ) {
                ensureI18nInstanceImported(
                    psiFile,
                    isVue = false,
                    injectGlobalDollarT = false,
                    injectReactGlobalDollarT = needInjectReactGlobalDollarT
                )
            } else if (
                vueModeNeedsImport ||
                reactModeNeedsImport ||
                (tFunctionName == "i18n.global.t" && extractedStrings.isNotEmpty())
            ) {
                // 兜底分支：.vue SFC 之外的 Vue 项目脚本，或 needInjectReactGlobalDollarT=true 但 isReact
                // 判定暂时 false 的场景（兼容老文件）。
                if (reactModeNeedsImport) {
                    ensureI18nInstanceImported(
                        psiFile,
                        isVue = false,
                        injectGlobalDollarT = false,
                        injectReactGlobalDollarT = true
                    )
                } else {
                    ensureI18nInstanceImported(
                        psiFile,
                        isVue = true,
                        injectGlobalDollarT = needInjectGlobalDollarT || tFunctionName != "\$t"
                    )
                }
            }
        }
        if (extractedStrings.isNotEmpty()) {
            if (isVue) {
                val f = containingFile ?: (psiFile as PsiFile)
                // 区分：
                //   .vue SFC → 走 ensureVueI18nImported（在 <script> 顶部加 import / 解构）
                //   .ts/.tsx 纯脚本：
                //     · 有 Vue 组件 → 调 ensureVueComponentI18nInjected，
                //                     在每个组件 setup() 体首行注入 const { t: $t } = useI18n()
                //                     不要全局 const $t
                //     · 只有自定义 hook → ensureVueHookI18nImported
                //     · 纯工具（无组件无 hook）→ 需要"全局别名"的，needInjectGlobalDollarT=true
                //                              在 needGlobalI18nImport 分支已调
                //                              ensureI18nInstanceImported(injectGlobalDollarT=true)
                //                              注入了 i18n.global.t 别名，这里不用再调
                val isSfc = f.name.endsWith(".vue", ignoreCase = true)
                val components = if (isSfc) emptyList() else Util.findVueComponentFunctions(f)
                val hooks = if (isSfc) emptyList() else Util.findHookFunctions(f)
                when {
                    !isSfc && components.isNotEmpty() -> ensureVueComponentI18nInjected(psiFile)
                    !isSfc && hooks.isNotEmpty() -> ensureVueHookI18nImported(psiFile)
                    isSfc -> {
                        // .vue SFC：只有 $t 时才注入
                        if (tFunctionName != "i18n.global.t") {
                            ensureVueI18nImported(psiFile)
                        }
                    }
                    // else: 非 SFC、无组件无 hook 的纯工具文件 → 什么都不做。
                    //       因为 needInjectGlobalDollarT=true 时，needGlobalI18nImport 分支
                    //       已经调过 ensureI18nInstanceImported(injectGlobalDollarT=true)
                    //       注入了 import { i18n } + const $t = i18n.global.t。
                }
            } else if (isReact) {
                // 新规则：React 纯工具 TS 场景用 react-i18next getI18n + const $t=getI18n().t，
                // 仍然不需要 useTranslation（不能在普通函数中调 hook）。
                // 只有 tFunctionName!="i18n.t" 且 **没开启 needInjectReactGlobalDollarT** 的场景才注入
                // useTranslation（典型：React 组件内部 / 自定义 hook）。
                if (tFunctionName != "i18n.t" && !needInjectReactGlobalDollarT) {
                    ensureReactI18nImported(psiFile)
                }
            } else {
                // 兜底：isVue=false 且 isReact=false 的普通 .ts 文件（极少见，兼容旧逻辑）。
                // Vue TSX / Vue 项目纯脚本现在都在上面的 isVue 分支处理了。
            }
        }
    }

    /** 处理整个 Vue 文件，支持 undo */
    fun execute() {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                WriteCommandAction.runWriteCommandAction(project) {
                    this.run();
                }
            },
            "Vue i18n Extract",
            null
        )
    }

    fun getScriptTag(): XmlTag? {
        return PsiTreeUtil.findChildrenOfType(psiFile, XmlTag::class.java)
            .firstOrNull { it.name == "script" }
    }

    // ───────────────────────────────────────────────
    // Vue import 去重 / 解构去重 工具函数（问题 4 修复）
    // ───────────────────────────────────────────────
    /**
     * 判断 [decl]（一个 ES6ImportDeclaration）是否已经从 [moduleName] 导入了 [wantedName]。
     *
     * 改用"文本级宽松匹配"而不是 IntelliJ PSI 内部 API：
     * 不同版本的 IntelliJ（2024/2025 EAP）对 ES6ImportDeclaration 的内部属性
     * 名字变化很大（importedModule / importedNamespaceBinding / importedName 等都不存在），
     * 但 `decl.text` 即源代码字符串是稳定的。
     *
     * 匹配规则：
     * - decl.text 必须包含 `from "...moduleName..."` 或 `from '...moduleName...'`
     *   （相对路径还允许 `/index` 尾缀）
     * - 然后看整个 import 里是否包含 wantedName：
     *     1) 命名导入：`{ useI18n }` / `{ useI18n as i18n }` / `{ foo, useI18n }`
     *     2) 命名空间导入：`import * as X from` → 视为"已经处理过"
     *     3) 默认导入：默认变量名 == wantedName 或 wantedName == "default"
     */
    private fun hasImportedSpecifier(decl: ES6ImportDeclaration, moduleName: String, wantedName: String): Boolean {
        val text = decl.text.replace("\\s+".toRegex(), " ")
        // 1. from 路径检查（单双引号 / 分号 / 末尾空白 / index 尾缀 都容忍）
        val want = moduleName.lowercase()
        val fromMatch = Regex("""from\s*['"]([^'"]+)['"]""").find(text)
        val from = fromMatch?.groupValues?.get(1)?.trim()?.lowercase()?.removeSuffix("/index")
        if (from != want) return false

        val cleaned = text

        // 2. namespace import: `import * as X from` 视为"已处理过"
        if (Regex("""import\s+\*\s+as\s+""").containsMatchIn(cleaned)) return true

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
     * 判断 [scope] 范围内是否已经存在"[callee]() 函数调用 + 指定解构"。
     * 用于避免 `const { t: \$t } = useI18n();` 在 ensureVueI18nImported 里重复插入。
     *
     * 文本级宽松匹配：
     * 1) scope 文本里出现 `useI18n(`（即调用过），
     * 2) 并且要么
     *    - const/let/var 解构文本中包含 `{ $destructureNameFrom: $destructureAlias }`
     *    - 或者 `useI18n(` 附近存在 `{$destructureNameFrom`（比如用户自己写 const { t, n } = useI18n()）
     *       就认为"已经处理过"，不重复塞。
     */
    private fun scopeHasDestructuredCall(
        scope: PsiElement,
        callee: String,
        destructureNameFrom: String,
        destructureAlias: String,
    ): Boolean {
        val text = scope.text.replace("\\s+".toRegex(), " ")
        if (!text.contains("$callee(")) return false

        // 精确形式（我们注入的代码）
        val canonical = "{$destructureNameFrom: $destructureAlias}"
        if (text.contains(canonical)) return true

        // 近似形式（用户自己手写了 const { t } = useI18n() 或 const { t, n } = useI18n()
        // 或 const { t: $t, n: $n } = useI18n()）
        // 正则：`{ <任意> destructureNameFrom <任意> } <任意> = <任意> callee(`
        val re = Regex("""\{\s*[^\}]*\b\Q$destructureNameFrom\E\b[^\}]*\}\s*=\s*[A-Za-z_][\w\$]*\s*\.\s*\Q$callee\E\(""")
        if (re.containsMatchIn(text)) return true
        val re2 = Regex("""\{\s*[^\}]*\b\Q$destructureNameFrom\E\b[^\}]*\}\s*=\s*\Q$callee\E\(""")
        if (re2.containsMatchIn(text)) return true

        return false
    }

    private fun ensureVueI18nImported(psiFile: PsiElement) {
        val scriptTag = getScriptTag() ?: run {
            val script = factory.createHTMLTagFromText("<script setup lang=\"ts\">\n\n</script>")
            psiFile.add(script);
            getScriptTag()
        } ?: return;
        // 關鍵：找到 <script> 內部的文本節點（注入宿主通常在這裡）
        val scriptContent = PsiTreeUtil.findChildOfType(scriptTag, JSEmbeddedContent::class.java)
        if (scriptContent === null) {
            return
        }
        val importStatements = PsiTreeUtil.findChildrenOfType(scriptContent, ES6ImportDeclaration::class.java)

        // ── 修复问题 4（重复注入）：语义级判定 import 是否存在 ──
        val importUseI18nExists = importStatements.any {
            hasImportedSpecifier(it, moduleName = "vue-i18n", wantedName = "useI18n")
        }
        val constUseI18nExists = scopeHasDestructuredCall(
            scriptContent,
            callee = "useI18n",
            destructureNameFrom = "t",
            destructureAlias = "\$t"
        )

        // 1. 创建 import 语句
        val importUseI18nNode = createStringExpressionNode("import { useI18n } from 'vue-i18n';", psiFile)
        // 2. 创建 const 语句
        val constUseI18nNode = createStringExpressionNode("const { t: \$t } = useI18n();", psiFile)

        if (importStatements.isEmpty()) {
            // 没有 import，直接加到内容最前面（或合适位置）
            val addedImport = scriptContent.addAfter(importUseI18nNode, scriptContent.firstChild)
            val whiteSpace = scriptContent.addAfter(createStringExpressionNode("\n", psiFile), addedImport)
            if (!constUseI18nExists) scriptContent.addAfter(constUseI18nNode, whiteSpace)
        } else {
            if (!importUseI18nExists) {
                // 有 import → 新 import 加到第一个 import 前面
                val firstImport = importStatements.first()
                firstImport.parent.addBefore(importUseI18nNode, firstImport)
            }
            if (!constUseI18nExists) {
                // const 加到最后一个 import 后面
                val lastImport = importStatements.last()
                lastImport.parent.addAfter(constUseI18nNode, lastImport)
            }
        }
    }

    /** React i18n 导入 + useTranslation hook 注入 */
    private fun ensureReactI18nImported(psiFile: PsiElement) {
        val containingFile = psiFile.containingFile ?: return

        // Bug 1 修复：先确认文件中真正存在"能合法调用 useTranslation 的地方"，
        // 否则直接 return，避免给纯工具/纯常量/纯配置文件注入 useTranslation import。
        // 合法注入位置：
        //   1) React 函数组件/类组件的 render 函数体（findReactComponentFunctions）
        //   2) React 项目中 .ts/.tsx 文件里的自定义 hook（use 开头的顶级函数）
        val componentFuncs = Util.findReactComponentFunctions(containingFile)
        val hookFuncs = Util.findHookFunctions(containingFile)
        val allTargets = (componentFuncs.asSequence() + hookFuncs.asSequence())
            .distinct()
            .toList()
        if (allTargets.isEmpty()) return

        // 1. 确保 react-i18next 导入存在（仅当有合法调用目标时才注入 import）
        val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
        if (imports.none { it.text.contains("react-i18next") }) {
            val importText = "import { useTranslation } from 'react-i18next';\n"
            val importStmt = createJSStatementFromText(importText, containingFile)
            if (imports.isNotEmpty()) {
                val firstImport = imports.first()
                firstImport.parent.addBefore(importStmt, firstImport)
            } else {
                // 没有 import 时，加到文件最开头（第一个有效语句之前）
                val firstStatement = findFirstNonWhitespaceChild(containingFile)
                if (firstStatement != null) {
                    containingFile.addBefore(importStmt, firstStatement)
                } else {
                    containingFile.add(importStmt)
                }
            }
        }

        // 2. 逐个注入（从后往前插入，避免 offset 偏移）
        // 使用 PSI 操作创建语句并插入，全部使用纯 PSI 操作避免 Document locked 异常
        for (func in allTargets.asReversed()) {
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            // 检查是否已存在 useTranslation 调用
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.none { it.text.contains("useTranslation") }) {
                val hookStmt = createJSStatementFromText(
                    "\n    const { t: \$t } = useTranslation();",
                    func
                )
                // 插入到 body 的 '{' 之后（即第一个 LeafElement 之后）
                val openingBrace = body.firstChild
                if (openingBrace != null) {
                    body.addAfter(hookStmt, openingBrace)
                }
            }
        }
    }

    /**
     * Vue 项目纯 .ts 文件中 use 开头自定义 hook 的 useI18n 注入。
     *
     * 场景：Vue 项目里独立的 .ts 文件（非 .vue SFC）写了 useXxx 自定义 hook，
     * 内部有硬编码中文。这类文件既不是 Vue SFC（无 <script> 标签）也不是 React，
     * 无法走 [ensureVueI18nImported]（依赖 <script>）或 [ensureReactI18nImported]。
     *
     * 处理：
     * 1. 缺少 vue-i18n 导入时，在文件顶部注入 `import { useI18n } from 'vue-i18n'`
     * 2. 给每个 use 开头的顶级 hook 函数体首行注入 `const { t: $t } = useI18n();`
     */
    private fun ensureVueHookI18nImported(psiFile: PsiElement) {
        val containingFile = psiFile.containingFile ?: return

        // 0. 先找到所有 use 开头的 hook 函数，没有则直接返回
        //    （避免普通 TS 文件被注入 vue-i18n import）
        val hookFuncs = Util.findHookFunctions(containingFile)
        if (hookFuncs.isEmpty()) return

        // 1. 确保 vue-i18n 导入存在
        val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
        if (imports.none { it.text.contains("vue-i18n") }) {
            val importText = "import { useI18n } from 'vue-i18n';\n"
            val importStmt = createJSStatementFromText(importText, containingFile)
            if (imports.isNotEmpty()) {
                val firstImport = imports.first()
                firstImport.parent.addBefore(importStmt, firstImport)
            } else {
                val firstStatement = findFirstNonWhitespaceChild(containingFile)
                if (firstStatement != null) {
                    containingFile.addBefore(importStmt, firstStatement)
                } else {
                    containingFile.add(importStmt)
                }
            }
        }

        // 3. 逐个注入（从后往前插入，避免 offset 偏移）
        for (func in hookFuncs.asReversed()) {
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            // 检查是否已存在 useI18n 调用
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.none { it.text.contains("useI18n") }) {
                val hookStmt = createJSStatementFromText(
                    "\n    const { t: \$t } = useI18n();",
                    func
                )
                val openingBrace = body.firstChild
                if (openingBrace != null) {
                    body.addAfter(hookStmt, openingBrace)
                }
            }
        }
    }

    /**
     * 【Vue TSX 组件的 useI18n 注入】（对应 ensureVueHookI18nImported 的"组件版"）
     *
     * 场景：Vue 项目中的 .tsx / .jsx 文件写了 defineComponent({...}) 或 PascalCase
     *       函数式组件，内部有硬编码中文需要替换为 $t('key')，此时需要：
     *       1) 顶部存在 `import { useI18n } from 'vue-i18n'`；
     *       2) defineComponent 的 setup() 函数体开头，或函数式组件函数体开头，
     *          注入 `const { t: $t } = useI18n()`；
     *       这样 $t 在组件作用域可用，不需要"全局 i18n 实例 + const $t = i18n.global.t"。
     *
     * 注入目标集合（allTargets，每一个都要在函数体开头插 const 解构）：
     *   A. defineComponent({ setup() { ... } }) 中找到的 setup() 函数体
     *   B. findVueComponentFunctions 返回的函数式组件（PascalCase + return JSX）
     *   C. findVueComponentFunctions 返回的 defineComponent 调用 → 定位 setup 属性
     *
     * 注意：和 ensureVueHookI18nImported 对称——如果文件里找不到任何组件函数，直接 return。
     */
    private fun ensureVueComponentI18nInjected(psiFile: PsiElement) {
        val containingFile = psiFile.containingFile ?: return
        val vueComponents = Util.findVueComponentFunctions(containingFile)
        if (vueComponents.isEmpty()) return

        // —— 阶段 1：先确认要注入 const 解构的"函数体列表"
        val targetBodies = mutableListOf<JSBlockStatement>()

        for (cand in vueComponents) {
            when {
                // 类型 1：cand 就是一个函数式组件（defineComponent 场景 2 返回的 JSFunction）
                cand is JSFunction -> {
                    val body = PsiTreeUtil.findChildOfType(cand, JSBlockStatement::class.java)
                    if (body != null) targetBodies.add(body)
                }
                // 类型 2：cand 是 defineComponent({ setup(){...} }) 的调用（JSCallExpression）
                cand is JSCallExpression -> {
                    // 在 cand 的整个子树里找 setup: 属性
                    // （defineComponent 的第一个参数就是 { setup: ... } 对象字面量）
                    // 直接用 findChildrenOfType(JSProperty) 搜索，避免 SDK 版本差异
                    // （arguments 类型在不同 SDK 版本不一致，但属性一定是 JSProperty 的后代）。
                    val setupProp = PsiTreeUtil.findChildrenOfType(cand, JSProperty::class.java)
                        .firstOrNull { it.name == "setup" } ?: continue
                    // setup 可能是三种形态，且不同 IntelliJ 版本解析结构不一致：
                    //   · 简写方法 setup() {}        → 直接在 setupProp 下展开 PARAMETER_LIST + JSBlockStatement
                    //     （JSFunction 也能 find 到，但其下的 block 可能是空 → 需要在 setupProp 直接找）
                    //   · setup: function(){}        → JSFunction + block
                    //   · setup: () => {}            → JSFunction(箭头类型，SDK 内 JSFunction 可识别) + block
                    // 兼容所有结构：**先在 setupFunc（JSFunction）内找 block，找不到就退到 setupProp 内找**
                    val setupFunc = PsiTreeUtil.findChildOfType(setupProp, JSFunction::class.java)
                    val body: JSBlockStatement =
                        ((if (setupFunc != null) PsiTreeUtil.findChildOfType(setupFunc, JSBlockStatement::class.java) else null)
                            ?: PsiTreeUtil.findChildOfType(setupProp, JSBlockStatement::class.java))
                            ?: continue
                    targetBodies.add(body)
                }
            }
        }
        // 没找到任何组件函数体 → 不注入 import（避免无意义的 vue-i18n 注入）
        if (targetBodies.isEmpty()) return

        // —— 阶段 2：保证 vue-i18n import 存在（复用 ensureVueHookI18nImported 的同一段逻辑）
        val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
        if (imports.none { it.text.contains("vue-i18n") }) {
            val importText = "import { useI18n } from 'vue-i18n';\n"
            val importStmt = createJSStatementFromText(importText, containingFile)
            if (imports.isNotEmpty()) {
                imports.first().parent.addBefore(importStmt, imports.first())
            } else {
                val firstStatement = findFirstNonWhitespaceChild(containingFile)
                if (firstStatement != null) {
                    containingFile.addBefore(importStmt, firstStatement)
                } else {
                    containingFile.add(importStmt)
                }
            }
        }

        // —— 阶段 3：从后往前，在每个组件函数体首行插 `const { t: $t } = useI18n();`（去重）
        for (body in targetBodies.asReversed()) {
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.any { it.text.contains("useI18n") }) continue
            val hookStmt = createJSStatementFromText(
                "\n    const { t: \$t } = useI18n();",
                body
            )
            val openingBrace = body.firstChild ?: continue
            body.addAfter(hookStmt, openingBrace)
        }
    }

    /**
     * 当文件使用 i18n.global.t / i18n.t / getI18n().t 但缺少 i18n 实例导入时，注入默认导入。
     *
     * - Vue:   查找项目中调用 createI18n 的文件（通常位于 @/locales 目录），
     *          根据该文件的实际路径与导出方式生成导入：
     *            `import { i18n } from '@/locales'`   （命名导出，别名路径）
     *            `import i18n from './locales/index'` （默认导出，相对路径）
     *          找不到 createI18n 文件时回退到从 vue-i18n 包导入。
     *          （可选 injectGlobalDollarT=true：追加 const \$t = i18n.global.t;）
     *
     * - React 旧模式：`import i18n from 'i18next'`（i18next 全局实例，兼容已存在导入场景）
     *
     * - React 新模式（用户要求）：统一用 \$t 减少复杂度，顶部写两行：
     *       import { getI18n } from 'react-i18next';
     *       const $t = getI18n().t;
     *   由 injectReactGlobalDollarT=true 开启。
     *
     * 注意：已有任意形式的 i18n / getI18n 导入时不重复注入。
     */
    /**
     * 确保文件顶部已经导入了 i18n 实例。
     *
     * @param injectGlobalDollarT —— 仅在 isVue=true 且"非 SFC 的纯 TS 文件"时为 true：
     *   在已经 `import { i18n } from '@/locales/xxx'` 之后，再追加一行
     *   `const $t = i18n.global.t;`（去重，用户要求"全部统一用 $t 减少复杂度"）。
     *
     * @param injectReactGlobalDollarT —— 仅在 isVue=false 且"React 纯工具 TS（无组件无 Hook）"时为 true：
     *   顶部注入 `import { getI18n } from 'react-i18next';`，并在其后追加
     *   `const $t = getI18n().t;`（去重）。
     */
    private fun ensureI18nInstanceImported(
        psiFile: PsiElement,
        isVue: Boolean,
        injectGlobalDollarT: Boolean = false,
        injectReactGlobalDollarT: Boolean = false
    ) {
        // ── 局部 helper：先声明，后面才能用 ─────────────────────────────
        // Vue 版：检查是否已经存在 `const $t = i18n.global.t`（宽松空格/分号容忍）
        fun hasVueGlobalDollarTAliased(root: PsiElement): Boolean {
            val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
            val re = Regex("""const\s*\{\s*[\s\S]*\}\s*=\s*i18n\s*\.\s*global\s*\.\s*t""")
            val reSimple = Regex("""const\s+\${'$'}t\s*=\s*i18n\s*\.\s*global\s*\.\s*t""")
            return vars.any {
                re.containsMatchIn(it.text.replace("\\s+", "")) ||
                    reSimple.containsMatchIn(it.text) ||
                    it.text.replace("\\s+", "").let { t ->
                        t.contains("const\$t=i18n.global.t")
                    }
            }
        }
        // React 版：检查是否已经存在 `const $t = getI18n().t`
        fun hasReactGlobalDollarTAliased(root: PsiElement): Boolean {
            val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
            val reSimple = Regex("""const\s+\${'$'}t\s*=\s*getI18n\s*\(\s*\)\s*\.\s*t""")
            return vars.any {
                reSimple.containsMatchIn(it.text) ||
                    it.text.replace("\\s+", "").let { t ->
                        t.contains("const\$t=getI18n().t")
                    }
            }
        }
        // ───────────────────────────────────────────────────────────────

        val i18nAlreadyImported = hasI18nInstanceImported(psiFile)
        // React getI18n 导入是否已经存在？
        val reactGetI18nAlreadyImported = !isVue && hasReactGetI18nImported(psiFile)

        // 【问题 2 修复（用户报告：只出现 const $t=getI18n().t 却没 import { getI18n }）】
        val requiredImportAlreadyPresent = when {
            // React 新模板（getI18n）→ 严格要求 getI18n 导入，老 i18n 不算
            !isVue && injectReactGlobalDollarT -> reactGetI18nAlreadyImported
            // 其他所有情况：Vue + React 旧 i18n.t 模式 → 只要"存在一个指向 i18n 实例的导入"即可
            else -> i18nAlreadyImported
        }

        // $t 全局别名是否已经存在：对应各自 helper 才叫存在
        val dollarTAliasAlreadyPresent = when {
            isVue && injectGlobalDollarT -> hasVueGlobalDollarTAliased(psiFile)
            !isVue && injectReactGlobalDollarT -> hasReactGlobalDollarTAliased(psiFile)
            else -> true // 不需要别名 → 当然算"已存在"
        }

        // 提前 return：(所需的 import 已存在) 且 (不需要追加别名 OR 别名也已经追加过)
        val stillNeedConstAlias = (isVue && injectGlobalDollarT) || (!isVue && injectReactGlobalDollarT)
        if (requiredImportAlreadyPresent && !(stillNeedConstAlias && !dollarTAliasAlreadyPresent)) {
            return
        }

        // —— 计算 import 文本 & const $t 别名文本 ——
        val importText: String? = when {
            requiredImportAlreadyPresent -> null
            isVue -> buildVueI18nInstanceImport(psiFile.containingFile ?: psiFile)
            injectReactGlobalDollarT -> "import { getI18n } from 'react-i18next';\n"
            else -> "import i18n from 'i18next';\n"
        }
        val dollarTText: String? = when {
            isVue && injectGlobalDollarT && !dollarTAliasAlreadyPresent -> "const \$t = i18n.global.t;\n"
            !isVue && injectReactGlobalDollarT && !dollarTAliasAlreadyPresent -> "const \$t = getI18n().t;\n"
            else -> null
        }

        if (isVue) {
            // 分两种情况：
            // A) .vue SFC：需要注入到文件内部的 <script> 内（getScriptTag() 能找到）
            // B) 纯 TS/JS 文件（用户新场景：needInjectGlobalDollarT=true 的典型宿主）：
            //    就跟 React 一样直接写到 PsiFile 顶部
            val sfcScript = getScriptTag()
            if (sfcScript != null) {
                val scriptContent = PsiTreeUtil.findChildOfType(sfcScript, JSEmbeddedContent::class.java)
                    ?: return
                val importStatements = PsiTreeUtil.findChildrenOfType(scriptContent, ES6ImportDeclaration::class.java)
                val dollarTAlreadyAliased = hasVueGlobalDollarTAliased(scriptContent)

                // 1) Import 注入（如果需要）
                if (importText != null) {
                    val importStmt = createStringExpressionNode(importText, scriptContent)
                    if (importStatements.isNotEmpty()) {
                        val firstImport = importStatements.first()
                        firstImport.parent.addBefore(importStmt, firstImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(scriptContent)
                        if (firstStatement != null) {
                            scriptContent.addBefore(importStmt, firstStatement)
                        } else {
                            scriptContent.add(importStmt)
                        }
                    }
                }
                // 2) $t 全局别名注入（如果需要且没有）
                if (injectGlobalDollarT && dollarTText != null && !dollarTAlreadyAliased) {
                    val stmt = createStringExpressionNode(dollarTText, scriptContent)
                    val lastImport = importStatements.lastOrNull()
                    if (lastImport != null) {
                        lastImport.parent.addAfter(stmt, lastImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(scriptContent)
                        if (firstStatement != null) {
                            scriptContent.addBefore(stmt, firstStatement)
                        } else {
                            scriptContent.add(stmt)
                        }
                    }
                }
            } else {
                // —— Case B: 纯脚本文件，直接写到 PsiFile 顶部（React 相同的位置注入逻辑，
                //            但 $t 别名用字符串 LeafPSI，避免 createJSStatementFromText
                //            在缺少 JS language 上下文时失败）
                val containingFile = psiFile.containingFile ?: return
                val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
                val dollarTAlreadyAliased = hasVueGlobalDollarTAliased(containingFile)

                // 1) Import 注入（如果需要）
                if (importText != null) {
                    val importStmt = createJSStatementFromText(importText, containingFile)
                    if (imports.isNotEmpty()) {
                        val firstImport = imports.first()
                        firstImport.parent.addBefore(importStmt, firstImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(containingFile)
                        if (firstStatement != null) {
                            containingFile.addBefore(importStmt, firstStatement)
                        } else {
                            containingFile.add(importStmt)
                        }
                    }
                }
                // 2) $t 全局别名：位置 = import 语句（含刚注入的）之后的首行；若仍无 import
                //    就放在文件首部。为了去重，先在 ES6ImportDeclaration 的 PSI 树上操作。
                if (injectGlobalDollarT && dollarTText != null && !dollarTAlreadyAliased) {
                    val stmt = createStringExpressionNode(dollarTText, containingFile)
                    val latestImports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
                    if (latestImports.isNotEmpty()) {
                        val lastImport = latestImports.last()
                        lastImport.parent.addAfter(stmt, lastImport)
                    } else {
                        val firstStatement = findFirstNonWhitespaceChild(containingFile)
                        if (firstStatement != null) {
                            containingFile.addBefore(stmt, firstStatement)
                        } else {
                            containingFile.add(stmt)
                        }
                    }
                }
            }
        } else {
            // —— React: 注入到文件顶部
            // 旧模式：只有 import i18n from 'i18next'（dollarTText=null，因为 tFunctionName 是 i18n.t）
            // 新模式：import { getI18n } from 'react-i18next' + const $t = getI18n().t;
            val containingFile = psiFile.containingFile ?: return
            val imports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
            val dollarTAlreadyAliased = hasReactGlobalDollarTAliased(containingFile)

            // 1) Import 注入（如果需要）
            if (importText != null) {
                val importStmt = createJSStatementFromText(importText, containingFile)
                if (imports.isNotEmpty()) {
                    val firstImport = imports.first()
                    firstImport.parent.addBefore(importStmt, firstImport)
                } else {
                    val firstStatement = findFirstNonWhitespaceChild(containingFile)
                    if (firstStatement != null) {
                        containingFile.addBefore(importStmt, firstStatement)
                    } else {
                        containingFile.add(importStmt)
                    }
                }
            }
            // 2) React $t 别名注入（新模式 injectReactGlobalDollarT=true 时）
            if (injectReactGlobalDollarT && dollarTText != null && !dollarTAlreadyAliased) {
                val stmt = createStringExpressionNode(dollarTText, containingFile)
                val latestImports = PsiTreeUtil.findChildrenOfType(containingFile, ES6ImportDeclaration::class.java)
                if (latestImports.isNotEmpty()) {
                    val lastImport = latestImports.last()
                    lastImport.parent.addAfter(stmt, lastImport)
                } else {
                    val firstStatement = findFirstNonWhitespaceChild(containingFile)
                    if (firstStatement != null) {
                        containingFile.addBefore(stmt, firstStatement)
                    } else {
                        containingFile.add(stmt)
                    }
                }
            }
        }
    }

    /**
     * 为 Vue 全局 i18n 实例构造 import 语句。
     *
     * 流程：
     * 1. 通过 Util.findVueI18nInstanceFile 查找 createI18n 调用的文件
     * 2. 通过 resolveVueI18nImportPath 推断别名/相对路径（自动去掉扩展名和 /index 后缀）
     * 3. 通过 isVueI18nDefaultExport 判断命名 or 默认导入语法
     * 4. 任何一步失败都回退到 `import { i18n } from 'vue-i18n'`
     */
    private fun buildVueI18nInstanceImport(psiFile: PsiElement): String {
        val containingFile = psiFile.containingFile ?: return FALLBACK_VUE_I18N_IMPORT
        val i18nVFile = Util.findVueI18nInstanceFile(containingFile)
            ?: return FALLBACK_VUE_I18N_IMPORT
        val importPath = Util.resolveVueI18nImportPath(containingFile, i18nVFile)
            ?: return FALLBACK_VUE_I18N_IMPORT
        val isDefault = Util.isVueI18nDefaultExport(i18nVFile)
        return if (isDefault) {
            "import i18n from '$importPath';\n"
        } else {
            "import { i18n } from '$importPath';\n"
        }
    }

    companion object {
        /** 找不到 createI18n 文件时的回退：直接从 vue-i18n 包导入命名导出 i18n */
        private const val FALLBACK_VUE_I18N_IMPORT = "import { i18n } from 'vue-i18n';\n"
    }

    /**
     * 检查文件是否已导入 i18n 实例（命名导入、默认导入、namespace 导入均可）。
     * 匹配形式：
     * - `import { i18n } from '...'`            （命名导入）
     * - `import i18n from '...'`                （默认导入）
     * - `import * as i18n from '...'`           （namespace 导入）
     * - `import i18n, { other } from '...'`     （混合导入）
     *
     * —— 新规则（React 兼容）：若已存在 `import { getI18n } from 'react-i18next'` 也算
     * "已有全局 i18n 能力"，因为 getI18n() 就是 react-i18next 返回 i18next i18n 实例的
     * 官方 API，不应该重复再注入 `import i18n from 'i18next'`。
     */
    private fun hasI18nInstanceImported(root: PsiElement): Boolean {
        if (hasReactGetI18nImported(root)) return true
        val imports = PsiTreeUtil.findChildrenOfType(root, ES6ImportDeclaration::class.java)
        val namedImport = Regex("""import\s*\{[^}]*\bi18n\b[^}]*\}""")
        val defaultImport = Regex("""import\s+i18n\s+(?:,|from)""")
        val namespaceImport = Regex("""import\s+\*\s+as\s+i18n\s+from""")
        return imports.any { imp ->
            namedImport.containsMatchIn(imp.text) ||
                defaultImport.containsMatchIn(imp.text) ||
                namespaceImport.containsMatchIn(imp.text)
        }
    }

    /**
     * 检查文件是否已导入 react-i18next 的 getI18n（React 新模板）。
     * 匹配形式：
     *   - `import { getI18n } from 'react-i18next'`           （独立命名导入）
     *   - `import { useTranslation, getI18n } from ...'`      （和 useTranslation 混合）
     *   - 路径中含 `react-i18next`（容忍引号/反引号差异）
     */
    private fun hasReactGetI18nImported(root: PsiElement): Boolean {
        val imports = PsiTreeUtil.findChildrenOfType(root, ES6ImportDeclaration::class.java)
        val namedSpec = Regex("""import\s*\{[^}]*\bgetI18n\b[^}]*\}""")
        return imports.any { imp ->
            val t = imp.text
            namedSpec.containsMatchIn(t) && t.contains("react-i18next")
        }
    }

    /** 找到第一个非空白符、非注释的子元素 */
    private fun findFirstNonWhitespaceChild(element: PsiElement): PsiElement? {
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

    // Template 文本节点
    // ───────────────────────────────────────────────
    private fun collectTemplateTextChange(textNode: XmlElement, changes: MutableList<() -> Unit>) {
        val original = textNode.text
        val trimmed = original.trim()
        if (trimmed === "") {
            return;
        }

        // 去除所有 HTML 注释后检查是否有实际内容
        val withoutComments = trimmed.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "").trim()
        if (withoutComments.isEmpty()) {
            return
        }

        // 使用纯文本（过滤注释）检查是否包含中文，避免注释中的中文被误提取
        val pureText = if (textNode is XmlText) getPureXmlText(textNode) else withoutComments
        if (!hasChinese(pureText)) {
            return
        }

        val isJSX = Util.isJSX(textNode);

        if (trimmed.contains("\$t(") || trimmed.contains("i18n.global.t(") || trimmed.contains("i18n.t(")) return

        val key = collectExtractedStrings(textNode)

        changes.add {
            // 只找“同一个父节点”下的 XmlText（非常关键）
            val textChild = getCharactersText(textNode)
            val textNodes = textChild.ifEmpty { listOf(textNode) }
            val newContent =
                if (!isJSX) "{{ ${tFunctionName}(`$key`) }}" else "{ ${tFunctionName}(`$key`) }"

            textNodes.forEachIndexed { index, node ->
                if (!node.isValid) return@forEachIndexed

                if (index == 0) {
                    val newElement = createStringExpressionNode(newContent, node)
                    // 第一个：替换
                    node.replace(newElement)
                } else {
                    // 其他：删除
                    node.delete()
                }
            }
        }
    }

    fun hasChinese(text: String): Boolean {
        return text.any { it in '\u4e00'..'\u9fff' }
    }


    fun isJSTemplateLiteral(text: String): Boolean {
        return text.startsWith("`") && text.contains("\${")
    }

    /**
     * 如果内容是纯字符串字面量（无插值的反引号、单引号、双引号字符串），
     * 返回去掉外层引号后的内容；否则返回 null。
     * 例如：`测试` -> "测试"，'hello' -> "hello"，"world" -> "world"
     */
    fun extractPureStringContent(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < 2) return null
        // 双引号字符串
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        // 单引号字符串
        if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        // 反引号字符串（必须不含 ${} 插值才算纯字符串）
        if (trimmed.startsWith("`") && trimmed.endsWith("`") && !trimmed.contains("\${")) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return null
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


    // 属性值（重点处理 <slot name="中文"> → :name）
    // ───────────────────────────────────────────────
    private fun collectXmlAttributeValueChange(attrValue: XmlAttributeValue, changes: MutableList<() -> Unit>) {
        val originalText = attrValue.value.trim();
        //println("jsx${Util.isJSX(attrValue)}")
        //println("XmlAttributeValue-${originalText}-${attrValue.text}")
        val isJSX = Util.isJSX(attrValue);
        if (isJSX && isBlock(originalText)) {
            return
        }
        if (originalText.isEmpty()) return
        if (!hasChinese(originalText)) {
            return
        }
        if (originalText.contains("\$t(") || originalText.contains("i18n.global.t(") || originalText.contains("i18n.t(")) {
            return
        }
        if (isJSTemplateLiteral(originalText)) {
            return;
        }

        val attr = attrValue.parent as? XmlAttribute ?: return

        val isDirective = isVueDirective(attr.name);

        var newText = originalText;

        if (!(isDirective && !attr.text.startsWith("\"")
                    && !attr.text.startsWith("'")
                    && !attr.text.startsWith("`"))
        ) {
            val key = collectExtractedStrings(attrValue);
            newText = "${tFunctionName}('$key')"
        }

        if (newText == originalText) return

        changes.add {
            var quote = if (attrValue.text.startsWith('"')) "" else "'"
            val prefix = if (isJSX || isVueDirective(attr.name)) "" else ":";
            var endQuote = quote;
            if (isJSX) {
                quote = "{"
                endQuote = "}"
            }
            attr.setValue("${quote}${newText}${endQuote}")
            attr.name = "${prefix}${attr.name}"
        }
    }

    private val templateVarRegex = """\$\{((?:[^{}]|\{(?:[^{}]|\{[^}]*\})*\})*)\}""".toRegex()

    fun collectJSStringTemplate(
        raw: String,
        changes: MutableList<() -> Unit>,
        ele: PsiElement,
        creator: (String) -> String
    ) {
        // 步骤1：提取模板字符串纯内容（去掉首尾反引号）
        val content = raw.substring(1, raw.length - 1)
        val params = LinkedHashMap<String, String>() // 索引 -> ${}内的原始内容
        var index = 0 // 按出现顺序分配数字索引

        // 步骤2：替换所有${任意内容}为${数字索引}，并收集${}内的原始内容
        // react-i18next 使用 {{key}} 双括号插值，vue-i18n 使用 {key} 单括号插值
        // 通过 JSX 上下文 / react 导入 / package.json 依赖综合判断
        val isReact = Util.isReact(ele)
        val message = templateVarRegex.replace(content) { match ->
            // 提取${}内的原始内容（groupValues[1] 是正则括号内的匹配结果）
            val innerContent = match.groupValues[1].trim()
            // 如果 ${} 内是纯字符串字面量（如 `测试`、'测试'、"测试"），直接内联到 message 中
            val pureString = extractPureStringContent(innerContent)
            if (pureString != null) {
                return@replace pureString
            }
            // 按顺序分配索引
            val key = index.toString()
            params[key] = innerContent
            index++
            // 替换为${数字索引}，React 使用双括号，Vue 使用单括号
            if (isReact) "{{$key}}" else "{$key}"
        }


        // 步骤4：检查 message 是否包含中文，不含中文则跳过
        if (!hasChinese(message)) {
            return
        }

        // 步骤5：保存提取的message（按trim后的value去重）
        val key = generateKey(message, ele)
        extractedStrings.putIfAbsent(key, message)

        // 步骤5：预生成 paramsObject（在 lambda 外执行，确保 extractedStrings 在 collect 阶段就完整）
        val paramsObject = params.entries.joinToString(
            prefix = "{ ",
            postfix = " }"
        ) { (k, v) ->
            // 模板字面量带插值：递归构建嵌套 $t() 调用（同时注册到 extractedStrings）
            if (isJSTemplateLiteral(v)) {
                "\"$k\": ${buildNestedTExprFromText(v, ele)}"
            } else {
                "\"$k\": $v"
            }
        }

        // 步骤6：添加替换逻辑
        changes.add {
            val newExprText = buildTFunctionExpr(message.trim(), paramsObject)
            val text = creator(newExprText)
            val newElement = createStringExpressionNode(text, ele)
            ele.replace(newElement)
        }
    }

    fun buildTFunctionExpr(message: String, paramsObject: String): String {
        // 步骤1：处理 message（trim 并转义特殊字符）
        val trimmedMsg = message.trim()

        // 步骤2：转义特殊字符（避免引号闭合、语法错误）
        val escapedMsg = if (trimmedMsg.contains("\n")) {
            // 模板字符串：转义反引号
            trimmedMsg.replace("`", "\\`")
        } else {
            // 单引号字符串：转义单引号
            trimmedMsg.replace("'", "\\'")
        }

        // 步骤3：判断是否包含换行符，选择引号类型
        val quote = if (trimmedMsg.contains("\n")) "`" else "'"

        // 步骤4：拼接最终的翻译函数调用表达式（使用检测到的函数名，空参数对象时省略第二个参数）
        val fn = tFunctionName
        return if (paramsObject.replace(" ", "") == "{}") {
            "$fn($quote$escapedMsg$quote)"
        } else {
            "$fn($quote$escapedMsg$quote, $paramsObject)"
        }
    }

    /**
     * 从模板字面量文本直接构建嵌套 $t() 表达式（纯文本处理，不操作 PSI）
     * 例如: `中国${1}` -> $t('中国{0}', { "0": 1 })
     */
    fun buildNestedTExprFromText(raw: String, ele: PsiElement): String {
        val content = raw.substring(1, raw.length - 1)
        val params = LinkedHashMap<String, String>()
        var index = 0
        val isReact = Util.isReact(ele)

        val message = templateVarRegex.replace(content) { match ->
            val innerContent = match.groupValues[1].trim()
            val pureString = extractPureStringContent(innerContent)
            if (pureString != null) return@replace pureString
            val key = index.toString()
            params[key] = innerContent
            index++
            if (isReact) "{{$key}}" else "{$key}"
        }

        val key = generateKey(message, ele)
        extractedStrings.putIfAbsent(key, message)

        val paramsObject = params.entries.joinToString(
            prefix = "{ ",
            postfix = " }"
        ) { (k, v) -> "\"$k\": $v" }

        return buildTFunctionExpr(message.trim(), paramsObject)
    }

    fun createStringExpressionNode(text: String, context: PsiElement): PsiElement {
        val dummyLiteral = JSPsiElementFactory.createJSExpression("''", context)
        val elementType: IElementType = JSTokenTypes.STRING_LITERAL
        // 步骤：创建纯文本 LeafPsiElement（无语法解析，保留原始文本）
        val textNode = LeafPsiElement(elementType, text)

        dummyLiteral.node.addChild(textNode.node)

        // 步骤：返回挂载后的完整节点（此时文本节点已关联 CharTable）
        return dummyLiteral.lastChild
    }

    /**
     * 从文本创建 JS 语句（使用 PsiFileFactory 构造完整 PSI 语句节点）。
     * 相比直接操作 AST 节点，这种方式创建的语句结构完整，
     * 不会导致 Document is locked 异常。
     */
    private fun createJSStatementFromText(text: String, context: PsiElement): PsiElement {
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

    fun collectJSStringTemplateFromExpression(stringExpr: JSLiteralExpression, changes: MutableList<() -> Unit>) {
        val raw = stringExpr.text
        if (raw.isEmpty()) return
        if (isTransformedCalled(stringExpr)) {
            return
        }
        collectJSStringTemplate(raw, changes, stringExpr) { value -> value }
    }

    fun isTransformedCalled(stringExpr: JSLiteralExpression): Boolean {
        // 兼容两种 PSI 结构：
        // 1. JSCallExpression -> JSLiteralExpression（新版 IntelliJ，无 JSArgumentList）
        // 2. JSCallExpression -> JSArgumentList -> JSLiteralExpression（旧版结构）
        val parent = stringExpr.parent
        val callExpr = when {
            parent is JSCallExpression -> parent
            parent.parent is JSCallExpression -> parent.parent as JSCallExpression
            else -> null
        }
        if (callExpr != null) {
            val method = callExpr.methodExpression
            if (method is JSReferenceExpression) {
                val name = method.referenceName
                // $t / t / $tc / tc 调用
                if (name == "\$t" || name == "t" || name == "\$tc" || name == "tc") return true
            }
            // 支持 i18n.global.t / i18n.t 等链式调用
            val calleeText = method?.text
            if (calleeText != null && (calleeText.endsWith(".t") || calleeText.endsWith(".tc"))) return true
        }
        return false
    }

    /**
     * 核心方法：提取 XmlText 中的纯文本（过滤注释、空白符、换行符）
     * 处理场景：<h1>123<!-- 注释 -->这是我的测试</h1> → 输出 "123这是我的测试"
     */
    private fun getPureXmlText(xmlText: XmlText): String {
        val stringBuilder = StringBuilder()

        // 遍历 XmlText 的所有子节点
        xmlText.children.forEach { child ->
            // 跳过注释节点
            when (child) {
                is XmlComment -> return@forEach
                // 跳过纯空白符（换行、空格、制表符）
                is PsiWhiteSpace -> {
                    // 可选：保留单个空格（避免文本拼接在一起），根据需求调整
                    /*val whitespaceText = child.text ?: ""
                    if (whitespaceText.contains("\n") || whitespaceText.contains("\t")) {
                        return@forEach // 跳过换行/制表符
                    } else if (whitespaceText.isBlank()) {
                        return@forEach // 跳过空空白符
                    } else {
                        stringBuilder.append(" ") // 保留单个空格
                    }*/
                    stringBuilder.append(child.text) // 保留单个空格
                }
                // 有效文本节点：拼接内容
                else -> stringBuilder.append(child.text ?: "")
            }
        }

        // 最终处理：去掉多余空格，合并连续空格为一个
        return stringBuilder.toString()
            .trim() // 去掉首尾空格
    }

    fun collectExtractedStrings(ele: PsiElement): String {
        val text = when (ele) {
            // JS 字面量：取纯字符串值（去掉引号）
            is JSLiteralExpression -> ele.stringValue ?: ""
            // XML 属性值：取纯值
            is XmlAttributeValue -> ele.value
            // XML 文本：过滤注释+空白符，只保留有效文本
            is XmlText -> getPureXmlText(ele)
            // 其他类型：直接取文本
            else -> ele.text ?: ""
        }
        val trimmed = text.trim()
        val key = generateKey(trimmed, ele)
        extractedStrings.putIfAbsent(key, trimmed)
        return key;
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
    private val processedEnums = mutableSetOf<PsiElement>()

    // ───────────────────────────────────────────────
// JS 字符串字面量
// ───────────────────────────────────────────────
    private fun collectJSStringChange(ele: JSLiteralExpression, changes: MutableList<() -> Unit>) {

        val raw = ele.text

        if (raw.isEmpty()) {
            return
        }
        if (ele is XmlTag) {
            return
        }
        // parent 不是赋值=表达式和 没有==
        /*if (ele.parent is JSBinaryExpression && ele.parent !is JSAssignmentExpression && !hasEqInExpression(ele.parent)) {
            return
        }*/

        if (!hasChinese(raw)) {
            return
        }

        // 跳过模板字面量内部的字符串字面量（如 `${'中文'}` 中的 '中文'），
        // 因为外层模板字面量的处理逻辑会统一处理
        if (PsiTreeUtil.getParentOfType(ele, JSStringTemplateExpression::class.java) != null) {
            return
        }

        if (isJSTemplateLiteral(raw)) {
            return collectJSStringTemplateFromExpression(ele, changes);
        }
        //跳过Enum['中文']
        if (ele.parent is JSIndexedPropertyAccessExpression && ele.prevSibling.prevSibling is JSReferenceExpression && ele.prevSibling.prevSibling.reference?.resolve() is TypeScriptEnum) {
            return
        }

        if (ele.parent is TypeScriptEnumField) {
            if (processedEnums.add(ele.parent.parent)) {
                val notificationGroup = NotificationGroupManager.getInstance()
                    .getNotificationGroup("Vue i18n 提取提示")  // 自定义组名

                val notification = notificationGroup.createNotification(
                    "跳过枚举成员 i18n 提取",
                    "枚举成员初始化值（如 ${ele.parent.parent.parent.text}）不支持运行时 \$t()，会报 TS18033 错误。\n" +
                            "建议改为 const 对象",
                    NotificationType.WARNING
                )

                Notifications.Bus.notify(notification, project)
            }
            return
        }
        val text = ele.stringValue ?: return

        if (text.isEmpty()) return
        //print("$text,contains${raw.contains("\$t(")}\n")

        // 先检查是否已在 $t() 调用中，避免误添加到 extractedStrings
        if (isTransformedCalled(ele)) {
            return
        }

        val key = collectExtractedStrings(ele)

        // 使用 buildTFunctionExpr：含换行符时自动切换为反引号模板字符串，避免普通字符串跨行导致的解析截断
        val newExprText = buildTFunctionExpr(key, "{}")
        if (ele.text == newExprText) return

        changes.add {
            val newExpr = JSChangeUtil.tryCreateExpressionFromText(project, newExprText, null, false)
            if (newExpr != null) {
                val newElement = newExpr.psi
                ele.replace(newElement)
            }
        }
    }

    // ───────────────────────────────────────────────
// JS 字符串拼接 (+)
// ───────────────────────────────────────────────
    private fun collectJSBinaryExpressionChange(binaryExpr: JSBinaryExpression, changes: MutableList<() -> Unit>) {
        if (binaryExpr.parent is JSBinaryExpression) {
            return
        }
        if (binaryExpr.operationSign != JSTokenTypes.PLUS) return
        if (!hasChinese(binaryExpr.text)) {
            return
        }
        val template = convertConcatTextToTemplate(binaryExpr)
        //println("template${template}${binaryExpr.text}")
        collectJSStringTemplate(template, changes, binaryExpr) { value -> value }
    }

    private fun convertConcatTextToTemplate(binaryExpr: JSBinaryExpression): String {
        val sb = StringBuilder("`")

        // 核心：递归处理「左右操作数」，而非所有子节点（避免空白/多余节点）
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


    // ───────────────────────────────────────────────
// 生成 key：直接用中文（简单清理）
// ───────────────────────────────────────────────
    private fun generateKey(value: String, element: PsiElement): String {
        return value.trim();
        /* val cleaned = value.trim()
             .replace(Regex("\\s+"), " ")           // 多个空格 → 一个
             .replace(Regex("[\\p{Punct}&&[^，。！？]]"), "")  // 去除大部分标点，保留常见中文标点
             .replace(Regex("\\s+"), "_")           // 空格转下划线

         if (cleaned.isEmpty()) {
             return "文本_${System.nanoTime() % 100000}"
         }

         return cleaned*/
    }

    private fun isInComment(element: PsiElement): Boolean {
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

    private fun isInStyleOrComment(element: PsiElement): Boolean {
        var parent = element.parent
        while (parent != null) {
            if (parent is PsiComment) return true
            if (parent is XmlTag && parent.name == "style") return true
            parent = parent.parent
        }
        return false
    }
}