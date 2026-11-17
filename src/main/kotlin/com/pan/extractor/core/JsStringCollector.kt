package com.pan.extractor.core

import com.pan.extractor.project.I18nPsiTools
import com.pan.extractor.strategy.I18nFramework
import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.lang.SiteKind
import com.pan.extractor.ui.*

import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.pan.extractor.planner.RewriteKind

/**
 * 「JS 字符串收集 与 $t 表达式生成」辅助类。
 *
 * 从 I18nProcessor 拆分出的状态相关方法群：负责收集 JS 字符串字面量 / 模板 / 二元拼接中的
 * 中文并生成 $t(...) 替换表达式。持有 I18nProcessor 引用以访问其状态（changes/pendingChanges、
 * extractedStrings、tFunctionName、isVueFile、templateVarRegex、project 等），保证行为不变。
 */
class JsStringCollector(
    private val processor: I18nProcessorContract,
    /** 能力面（[processor]）之外，结果/状态经本共享 [CollectionState] 读写（与分析器同一 plan 实例）。 */
    internal val state: CollectionState,
) {

    private val templateVarRegex = processor.templateVarRegex

    /** 已处理过的 enum 父节点（避免重复弹通知） */
    private val processedEnums = mutableSetOf<PsiElement>()

    /** 供 I18nProcessor.resetState() 在每次 collect() 前清空，避免跨收集泄漏导致通知不再触发（P0）。 */
    internal fun clearProcessedEnums() {
        processedEnums.clear()
    }

    // ───────────────────────────────────────────────
    // 模板字符串 / $t 表达式生成
    // ───────────────────────────────────────────────
    /**
     * Vue-i18n 不支持数字占位符 `$t('默认模型配置{0}子', { '0': "123" })` 这种
     * 数字 key 对象写法，必须用命名插值。统一把 Vue 侧占位符改成 {<prefix>0} / {<prefix>1} ...，
     * 调用侧参数对象写成 `{ N0: "123" }`（无引号，合法 JS identifier）。
     * 前缀（默认 `N`）可在设置面板配置，但必须是非空变量名。
     * React i18next 的 `{{0}}` + `{ "0": val }` 原生支持，保持不变。
     */
    internal fun vuePlaceholderKey(rawIndex: Int): String = "${I18nSettings.getInstance().vuePlaceholderPrefix()}$rawIndex"

    /**
     * 安全标识符正则：匹配纯标识符 / 属性访问链。
     * - 合法：`price`, `user.name`, `user.profile.nickname`
     * - 非法（fallback 到编号）：`count + 1`, `getName()`, `list[0]`, `user?.name`, `a ? b : c`
     */
    private val SAFE_SEMANTIC_KEY = Regex("""^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$""")

    /**
     * 从 `${}` 内部提取语义化 key，失败返回 null 调用方应 fallback 到编号。
     * 只提取"简单标识符/属性访问"，复杂表达式（算术/三元/函数调用/数组/可选链）不用。
     */
    private fun extractSemanticKey(innerContent: String): String? {
        val trimmed = innerContent.trim()
        if (trimmed.isEmpty() || trimmed.length > 64) return null
        if (!SAFE_SEMANTIC_KEY.matches(trimmed)) return null
        // 排除 JS 保留字（极大概率不会出现在业务变量名里，但保险）
        if (trimmed in JS_RESERVED) return null
        return trimmed
    }

    /**
     * 预计算模板内容中所有「复杂表达式」占位符（非纯字符串字面量）的最终语义参数键。
     *
     * owner 行为需求（2026-11）：属性链插值取最后一段作占位 key（`${item.versionNum}` →
     * `versionNum`）。尾段经 [SAFE_SEMANTIC_KEY] 校验必为纯标识符，可直接作不加引号的
     * 对象键与 vue-i18n 具名插值名；参数值保留完整变量表达式。
     * 同一 message 内多个占位符尾段 key 相同且绑定不同表达式（如 `item.versionNum` 与
     * `user.versionNum` 都取 `versionNum`）时，冲突键全部回退编号（rawIndex → N0/N1）；
     * 同一表达式重复出现共享尾段 key，不算冲突。
     *
     * 返回列表按占位符出现顺序（与 lambda 内 index++ 同步消费）；
     * 复杂表达式（extractSemanticKey 返回 null）项已预置为编号字符串。
     */
    private fun planPlaceholderSemanticKeys(content: String): List<String> {
        class Slot(val inner: String, var key: String, val rawIndex: Int)
        val slots = mutableListOf<Slot>()
        var idx = 0
        for (m in templateVarRegex.findAll(content)) {
            val inner = m.groupValues[1].trim()
            if (processor.extractPureStringContent(inner) != null) continue
            val rawIndex = idx++
            val lastSegment = extractSemanticKey(inner)?.substringAfterLast('.')
            slots += Slot(inner, lastSegment ?: rawIndex.toString(), rawIndex)
        }
        // 冲突 = 同一 key 绑定到不同表达式（参数对象会相互覆盖）。
        // 同一表达式重复出现（如 `你好 {name}，也是 {name}`）共享尾段 key 是预期行为，不算冲突。
        for ((_, group) in slots.groupBy { it.key }) {
            if (group.size >= 2 && group.map { it.inner }.distinct().size >= 2) {
                group.forEach { it.key = it.rawIndex.toString() }
            }
        }
        return slots.map { it.key }
    }
    private val JS_RESERVED = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default",
        "delete", "do", "else", "enum", "export", "extends", "false", "finally", "for",
        "function", "if", "import", "in", "instanceof", "new", "null", "return", "super",
        "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while",
        "with", "yield", "let", "static", "await", "async"
    )

    /**
     * 内层字符串字面量是否会被外层整条 `+` 拼接统一提取（[collectJSBinaryExpressionChange]）。
     *
     * 判定：自 [parentBin] 向上找最顶层的 `+` 链（遇到非 `+` 的父表达式即停止），若该顶层表达式
     * 整体含目标语言，外层就会把整条拼接合成一个带占位符的 message（`'你好，' + name` →
     * `你好，{name}`），内层字面量再单独提取只会产生重复 key（多余的 `你好，`）。
     *
     * 纯字面量拼接（`'你好' + '世界'`）同样落在这里：多个字面量时内层原本就该让位，
     * 现在统一由「整条拼接是否被提取」这一条规则覆盖，避免两套判定互相打架。
     */
    private fun isHandledByWholeConcat(parentBin: JSBinaryExpression): Boolean {
        if (parentBin.operationSign != JSTokenTypes.PLUS) return false
        var top: PsiElement = parentBin
        while (true) {
            val p = top.parent
            if (p is JSBinaryExpression && p.operationSign == JSTokenTypes.PLUS) top = p else break
        }
        val bin = top as JSBinaryExpression
        // 顶层 `+` 链整体不含目标语言 → 外层不会登记 key，内层字面量必须自己处理。
        return processor.containsTargetLanguage(bin.text, SiteKind.JS_CONCAT)
    }

    fun collectJSStringTemplate(
        raw: String,
        ele: PsiElement,
        creator: (String) -> String
    ) {
        // 模板字符串形式的索引键（例：P[`中文键${suffix}`]）→ 也不翻译
        if (isInIndexKeyPosition(ele)) return
        // 步骤1：提取模板字符串纯内容（去掉首尾反引号）
        val content = raw.substring(1, raw.length - 1)
        val params = LinkedHashMap<String, String>()
        var index = 0

        // 步骤2：替换所有${任意内容}为占位符，并收集${}内的原始内容
        // 占位符策略由 I18nFramework 决定（Vue {N0} / React {{0}} / Generic {0}），
        // 框架检测委托 I18nFrameworkRegistry.detect，行为与原 isVue/isReact 判定一致。
        val fw = I18nFrameworkRegistry.detect(ele)
        val plannedKeys = planPlaceholderSemanticKeys(content)
        var keyCursor = 0
        val message = templateVarRegex.replace(content) { match ->
            val innerContent = match.groupValues[1].trim()
            // 如果 ${} 内是纯字符串字面量（如 `测试`、'测试'、"测试"），直接内联到 message 中
            val pureString = processor.extractPureStringContent(innerContent)
            if (pureString != null) {
                return@replace pureString
            }
            val rawIndex = index++
            val semanticKey = plannedKeys.getOrElse(keyCursor++) { rawIndex.toString() }
            val key = fw.paramKey(semanticKey)
            val placeholder = fw.placeholderFor(semanticKey)
            params[key] = innerContent
            placeholder
        }


        // 步骤4：检查是否需要翻译。模板字符串的中文可能出现在三处：
        //   a) 静态部分（反引号里的纯文本）→ 替换 ${} 后 message 里
        //   b) ${} 内联的纯字符串字面量 → extractPureStringContent 内联进 message
        //   c) ${} 复杂表达式 fallback params → message 只剩 {N0}，但 params 里的表达式含中文
        // (a)(b) → message 直接含中文；(c) → message 不含但 params 里有中文表达式
        val hasChineseInMessage = processor.containsTargetLanguage(message, SiteKind.JS_TEMPLATE)
        val hasChineseInParams = params.values.any { v ->
            processor.containsTargetLanguage(v, SiteKind.JS_TEMPLATE)
        }
        if (!hasChineseInMessage && !hasChineseInParams) {
            return // 整体都不含中文，跳过
        }

        // 步骤5：保存提取的message（按trim后的value去重）
        val key = generateKey(message, ele)
        state.extractedStrings.putIfAbsent(key, message)

        // 步骤5：预生成 paramsObject
        // - Vue ：标识符 key，无引号（因为 key 形如 N0/N1）
        // - React / Generic ：字符串 key，加引号（因为 key 形如 "0"/"1"）
        val paramKeyNeedsQuote = fw.paramKeyNeedsQuote
        val paramsObject = params.entries.joinToString(
            prefix = "{ ",
            postfix = " }"
        ) { (k, v) ->
            val paramExpr = if (processor.isJSTemplateLiteral(v)) {
                buildNestedTExprFromText(v, ele)
            } else {
                wrapTranslatableLiteralsInParamExpr(v, fw, ele)
            }
            if (paramKeyNeedsQuote) "\"$k\": $paramExpr" else "$k: $paramExpr"
        }

        // 步骤6：登记为纯数据配方（RewritePlan，JS_TEMPLATE），Apply 阶段由解释器执行。
        //   newExpression 在收集期定案（与旧闭包 apply 时算出的 tFunctionName 一致，见 detect 时序）。
        //   注意用消毒后的 key（而非原始 message.trim()）作为 $t(...) 实参：与 extractedStrings
        //   中的资源键保持一致，否则含 @/|/句末点 的文案会因键失配而在运行时查不到翻译（P0）。
        val newExprText = buildTFunctionExpr(fw, key, paramsObject)
        val text = creator(newExprText)
        processor.recordRewrite(
            message = message,
            replaceRoot = ele,
            anchor = ele,
            kind = RewriteKind.JS_TEMPLATE,
            newExpression = text,
        )
    }

    /**
     * 拼装最终翻译调用表达式。
     *
     * §架构（CallExpressionStrategy）：这里的「如何把 key + 参数对象拼成完整调用」不再是
     * 硬编码的 `fn('key'[params])`，而是下沉给 [fw]（[com.pan.extractor.CallExpressionStrategy]）。
     * 默认策略沿用历史拼法（行为 1:1）；react-intl 覆盖为 `formatMessage({ id: 'key' }[, values])`。
     * 本方法只负责：解析 key 文本 → 按换行/引号转义打包成 key 字面量 → 交给 [fw] 完成成型。
     *
     * @param fw 当前文件命中的框架策略（由调用方用 [com.pan.extractor.strategy.I18nFrameworkRegistry.detect] 得到）。
     */
    fun buildTFunctionExpr(fw: I18nFramework, message: String, paramsObject: String): String {
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
        val keyLiteral = "$quote$escapedMsg$quote"

        // 步骤4：使用检测到的函数名，把「key 字面量 + 参数对象」交给框架策略成型调用表达式
        return fw.buildCallExpression(state.tFunctionName, keyLiteral, paramsObject)
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
    ): String = I18nPsiTools.buildTExprForRawText(
        message, paramsObject, isVue, isReact, skeletonKeyOverride
    )

    /**
     * 从模板字面量文本直接构建嵌套 $t() 表达式（纯文本处理，不操作 PSI）
     * - Vue：资源文件占位 `{N0}`，调用侧 `{ N0: val }` 无引号键
     * - React：资源文件占位 `{{0}}`，调用侧 `{ "0": val }` 保持原样
     */
    fun buildNestedTExprFromText(raw: String, ele: PsiElement): String {
        val content = raw.substring(1, raw.length - 1)
        val params = LinkedHashMap<String, String>()
        var index = 0
        val fw = I18nFrameworkRegistry.detect(ele)

        val plannedKeys = planPlaceholderSemanticKeys(content)
        var keyCursor = 0
        val message = templateVarRegex.replace(content) { match ->
            val innerContent = match.groupValues[1].trim()
            val pureString = processor.extractPureStringContent(innerContent)
            if (pureString != null) return@replace pureString
            val rawIndex = index++
            val semanticKey = plannedKeys.getOrElse(keyCursor++) { rawIndex.toString() }
            val key = fw.paramKey(semanticKey)
            val placeholder = fw.placeholderFor(semanticKey)
            params[key] = innerContent
            placeholder
        }

        val key = generateKey(message, ele)
        state.extractedStrings.putIfAbsent(key, message)

        // 参数对象：Vue 用标识符 key（无引号）；React/Generic 用字符串 key（加引号）
        val paramKeyNeedsQuote = fw.paramKeyNeedsQuote
        val paramsObject = params.entries.joinToString(
            prefix = "{ ",
            postfix = " }"
        ) { (k, v) ->
            if (paramKeyNeedsQuote) "\"$k\": $v" else "$k: $v"
        }

        return buildTFunctionExpr(fw, key, paramsObject)
    }

    /** 参数表达式文本中的单/双引号字符串字面量（不含反引号模板）。 */
    private val quotedLiteralInExprRegex =
        Regex("""'[^'\\\n]*(?:\\.[^'\\\n]*)*'|"[^"\\\n]*(?:\\.[^"\\\n]*)*"""")

    /** 字面量前缀以翻译调用开参结尾（t( / $t( / tc( / $tc( / i18n[.global].t(）→ 已是翻译实参。 */
    private val translationCallArgPrefixRegex =
        Regex("""(?:(?<![\w$])\$?tc?|\.t)\(\s*$""")

    /** 对象字面量键位置的前缀（`{` 或 `,` 结尾）。 */
    private val objectKeyPrefixRegex = Regex("""[{,]\s*$""")

    /** 对象字面量键位置的后缀（紧跟 `:`）。 */
    private val objectKeySuffixRegex = Regex("""^\s*:""")

    /**
     * 模板插值 fallback 参数表达式（三元/拼接/调用等复杂表达式）内部的字符串字面量提取。
     *
     * 背景：`${isZn ? '文档贡献者：' : 'Contributor: '}` 这类插值会整体成为命名参数
     * （`{ N0: isZn ? '文档贡献者：' : ... }`），内层字面量在 collectJSStringChange 里
     * 因「外层模板接管」被跳过，导致中文永远留在参数值里、进不了资源字典。
     * 这里在**外层模板生成参数对象文本时**就地处理：把含目标语言的字面量登记进
     * extractedStrings 并替换为 $t('key')，不产生独立 PSI rewrite，避免与外层替换冲突。
     */
    internal fun wrapTranslatableLiteralsInParamExpr(
        expr: String,
        fw: I18nFramework,
        ele: PsiElement,
    ): String = quotedLiteralInExprRegex.replace(expr) { m ->
        val literal = m.value
        val inner = literal.substring(1, literal.length - 1)
        if (inner.isEmpty() || !processor.containsTargetLanguage(inner, SiteKind.JS_STRING)) {
            return@replace literal
        }
        val before = expr.substring(0, m.range.first)
        val after = expr.substring(m.range.last + 1)
        // 已是翻译调用的直接实参（$t('中文')）→ 保持原样，避免 $t($t(...)) 双重包裹
        if (translationCallArgPrefixRegex.containsMatchIn(before)) return@replace literal
        // 对象字面量键位置（{ '中文': ... }）→ 键不能被替换成调用表达式
        if (objectKeyPrefixRegex.containsMatchIn(before) && objectKeySuffixRegex.containsMatchIn(after)) {
            return@replace literal
        }
        // 含转义符的字面量：raw 文本与运行时值不一致，包裹需先解码，保守保留原样
        if ('\\' in inner) return@replace literal
        // key 消毒有损（句末点被裁 / @ 被替换等）→ 包裹会改变运行时文案，保留原样
        val key = generateKey(inner, ele)
        if (key != inner.trim()) return@replace literal
        state.extractedStrings.putIfAbsent(key, inner.trim())
        buildTFunctionExpr(fw, key, "{}")
    }

    fun createStringExpressionNode(text: String, context: PsiElement): PsiElement =
        I18nPsiTools.createStringExpressionNode(text, context)

    /**
     * 从文本创建 JS 语句（使用 PsiFileFactory 构造完整 PSI 语句节点）。
     * 相比直接操作 AST 节点，这种方式创建的语句结构完整，
     * 不会导致 Document is locked 异常。
     */
    internal fun createJSStatementFromText(text: String, context: PsiElement): PsiElement =
        I18nPsiTools.createJSStatementFromText(text, context)

    fun collectJSStringTemplateFromExpression(stringExpr: JSLiteralExpression) {
        val raw = stringExpr.text
        if (raw.isEmpty()) return
        if (isTransformedCalled(stringExpr)) {
            return
        }
        collectJSStringTemplate(raw, stringExpr) { value -> value }
    }

    /**
     * 检查字符串字面量是否已经处于某一层 i18n 翻译调用的作用域内。
     *
     * 返回分三档，收集/替换阶段走不同策略：
     *   - NONE             : 完全不在任何 t/$t/i18n.global.t 调用里 → 正常：加 key + 替换为 $t('key')
     *   - DIRECT_ARG       : 字符串字面量直接就是某条 t/$t(...) 的第一个参数 → 完全跳过（已有完整 $t('x')，不需再处理）
     *   - OUTER_T_EXPRESSION: 外层祖先有 t/$t/... 调用，但本字符串不是其**直接单字符串参数**，
     *                        而是嵌套在参数表达式里（典型：$t(isPinned ? '取消置顶' : '置顶') 三元分支内
     *                        的两个独立字符串）。
     *                        此时仍要提取到 extractedStrings（国际化字典要有「取消置顶 / 置顶」两条），
     *                        但替换时只替换字符串字面量为 'key'，不再包一层 $t('key')，
     *                        避免出现双重 $t：$t(isPinned ? $t(...) : $t(...))。
     */
    fun detectTSemantic(stringExpr: JSLiteralExpression): I18nPsiTools.TSem =
        I18nPsiTools.detectTSemantic(stringExpr)

    /** 旧名兼容：其他地方只需要「DIRECT_ARG 就跳过」——保留 true/false 语义：
     *  仅 DIRECT_ARG 返回 true（完全跳过）；OUTER_T_EXPRESSION 返回 false（仍然进入收集/替换分支，
     *  但在 collectJSStringChange 内部再走 key-text-only 替换分支）。 */
    fun isTransformedCalled(stringExpr: JSLiteralExpression): Boolean =
        I18nPsiTools.isTransformedCalled(stringExpr)

    /**
     * 【Bug A10】排除「同名本地普通函数」的 t/tc 调用。
     * 若引用名 `t`/`tc` 解析到**本文件内**声明的普通函数（function t / function tc），
     * 说明它不是 i18n 翻译函数，其参数里的中文仍应被提取，而不是被当成「已翻译」跳过。
     * 仅对裸名 t/tc 生效；$t/$tc（插件统一的全局别名）与 i18n.t/tc 链式调用不受影响。
     */
    internal fun isLocalFunctionNamedTCall(call: JSCallExpression): Boolean =
        I18nPsiTools.isLocalFunctionNamedTCall(call)

    /**
     * 核心方法：提取 XmlText 中的纯文本（过滤注释、空白符、换行符）
     * 处理场景：<h1>123<!-- 注释 -->这是我的测试</h1> → 输出 "123这是我的测试"
     */
    internal fun getPureXmlText(xmlText: XmlText): String =
        I18nPsiTools.getPureXmlText(xmlText)

    fun collectExtractedStrings(ele: PsiElement): String? {
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
        // 最小提取长度：过短的文案不提取
        if (trimmed.codePointCount(0, trimmed.length) < I18nSettings.getInstance().minStringLength()) return null
        val key = generateKey(trimmed, ele)
        state.extractedStrings.putIfAbsent(key, trimmed)
        return key;
    }

    /** 用已合并好的 [pureText] 生成 key 并登记（供跨节点合并的文本段使用）。 */
    fun collectExtractedStrings(pureText: String, element: PsiElement): String? {
        val trimmed = pureText.trim()
        // 最小提取长度：过短的文案不提取
        if (trimmed.codePointCount(0, trimmed.length) < I18nSettings.getInstance().minStringLength()) return null
        val key = generateKey(trimmed, element)
        state.extractedStrings.putIfAbsent(key, trimmed)
        return key
    }

    // ───────────────────────────────────────────────
    // 跳过：成员变量/数组下标/index 访问中的中文 key（用户需求）
    //   例：P['中文']、obj['姓名']、arr['第1个']、P['姓' + '名']、P[`中文键${suffix}`]
    //   只要元素在 JSIndexedPropertyAccessExpression 的 indexExpr 子树里（即 [...] 方括号内）
    //   就跳过 —— 并且严格只跳过"index 表达式内部"，不要误把 qualifier 里的中文也砍掉。
    //
    //   NOTE: JSIndexedPropertyAccessExpression 在 Vue SFC 指令表达式（如 v-if="obj['已启用']"）
    //   的原生 PSI 中也会被正确构造（见 VueJSEmbeddedExpressionContentImpl 内的 JS…Impl 子树），
    //   因此"标准路径"在 Vue SFC 场景下同样适用。
    // ───────────────────────────────────────────────
    internal fun isInIndexKeyPosition(ele: PsiElement): Boolean =
        I18nPsiTools.isInIndexKeyPosition(ele)

    /**
     * 判断 ele 是否是一个「指令属性值整体」的字符串字面量，即 `:title="'中文'"` 里的 `'中文'`。
     * 此时内层字符串字面量是属性值的唯一内容，应交给 collectXmlAttributeValueChange 统一处理，
     * 避免 collectJSStringChange 重复提取。
     */
    internal fun isDirectiveSoleStringLiteral(ele: JSLiteralExpression): Boolean =
        I18nPsiTools.isDirectiveSoleStringLiteral(ele)

    /**
     * 【Bug A1】判断 ele 是否位于「纯字符串拼接」内：自 ele 向上找到最顶层的 `+` 表达式，
     * 并递归检查整条拼接链的所有叶子操作数是否都是字符串字面量（无变量/引用/数字/嵌套调用）。
     * 若为 true，此时 collectJSBinaryExpressionChange 会把整条拼接合并成一个 key，
     * ele 应交给它而不再单独提取。
     */
    internal fun isWithinPureStringConcat(ele: PsiElement): Boolean =
        I18nPsiTools.isWithinPureStringConcat(ele)

    /** 判断某操作数是否为可被整体合并的纯字符串（字面量、纯模板，或嵌套的纯字符串拼接）。 */
    internal fun isPureStringOperand(e: PsiElement?): Boolean =
        I18nPsiTools.isPureStringOperand(e)

    // ───────────────────────────────────────────────
// JS 字符串字面量
// ───────────────────────────────────────────────
    internal fun collectJSStringChange(ele: JSLiteralExpression) {
        // 如果字面量是 `+` 拼接的直接操作数，先判断整条拼接是否由外层
        // collectJSBinaryExpressionChange 统一提取：只要是，内层字面量就必须让位，
        // 否则同一个字符串会被提两次（例如 '你好，' + name 同时产出 `你好，{name}` 和多余的
        // `你好，`；`"你好" + name` 同时产出 `你好{name}` 和 `你好`）。
        val parentBin = ele.parent as? JSBinaryExpression
        if (parentBin != null && isHandledByWholeConcat(parentBin)) return

        // 索引键位置的字符串字面量 → 不翻译（与 collectJSBinaryExpressionChange /
        // collectJSStringTemplate 的入口检查保持一致）。例如 P['中文'] 里的 '中文'、
        // Vue SFC 指令表达式 v-if="P['中文']" 中注入的 JS 字符串字面量都要被跳过。
        if (isInIndexKeyPosition(ele)) return

        // 指令属性值整体就是一个字符串字面量（如 :title="'中文'"）→ 交给
        // collectXmlAttributeValueChange 统一处理，这里跳过以免重复提取。
        if (isDirectiveSoleStringLiteral(ele)) return

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

        if (!processor.containsTargetLanguage(raw, SiteKind.JS_STRING)) {
            return
        }

        // 跳过模板字面量内部的字符串字面量（如 `${'中文'}` 中的 '中文'），
        // 因为外层模板字面量的处理逻辑会统一处理。
        //
        // 跳过条件（任一满足即跳过）：
        //   1) 模板的「静态部分」（去掉 ${...} 后的残留）含中文 —— collectJSStringTemplate
        //      会把所有 ${} 替换成占位符后整体产出 message（含静态中文），登记 JS_TEMPLATE rewrite；
        //   2) 模板里有「fallback 的 ${}」（复杂表达式：三元/拼接/函数调用等，extractPureStringContent
        //      返回 null）—— 即使 message 只剩 {N0}，外层也会把整个 ${} 包成占位符，
        //      内层字面量单独跑出来会导致「内层被包 $t()、外层模板丢失」的拆穿问题；
        //   3) 静态部分无中文但所有 ${} 都是内联的纯字符串字面量 —— 整体 message 仍然含中文
        //      （如 `${"他说'你好'"}` → message = 他说'你好'），外层模板会登记 JS_TEMPLATE rewrite，
        //      内层不应再单独登记 JS_LITERAL。
        val parentTpl = PsiTreeUtil.getParentOfType(ele, JSStringTemplateExpression::class.java)
        if (parentTpl != null) {
            val tplContent = parentTpl.text.substring(1, parentTpl.text.length - 1)
            // 条件 1：静态部分含中文
            val staticPart = templateVarRegex.replace(tplContent, "")
            if (processor.containsTargetLanguage(staticPart, SiteKind.JS_TEMPLATE)) {
                return // 外层模板有静态中文，交给它统一处理
            }
            // 条件 2 + 3：跑一次 message 预计算，看是否整体含中文 / 有 fallback
            var anyFallback = false
            val hasChinese = templateVarRegex.replace(tplContent) { m ->
                val inner = m.groupValues[1].trim()
                val pure = processor.extractPureStringContent(inner)
                if (pure != null) pure else { anyFallback = true; "{SKIP_PLACEHOLDER}" }
            }.let { processor.containsTargetLanguage(it, SiteKind.JS_TEMPLATE) }
            if (anyFallback || hasChinese) {
                return // 外层模板会整体接管，内层字面量跳过
            }
        }

        if (processor.isJSTemplateLiteral(raw)) {
            return collectJSStringTemplateFromExpression(ele);
        }
        //跳过Enum['中文']
        if (ele.parent is JSIndexedPropertyAccessExpression && ele.prevSibling.prevSibling is JSReferenceExpression && ele.prevSibling.prevSibling.reference?.resolve() is TypeScriptEnum) {
            return
        }

        // 通用：索引/键访问里的中文 key 一律不翻译
        //   例：P['中文']、obj['姓名']、P[("中文括号")]、嵌套链式 arr[0]['第1个']
        //   （拼接、模板字符串形式的索引在下面的对应入口也做了同样防御）
        if (isInIndexKeyPosition(ele)) return

        if (ele.parent is TypeScriptEnumField) {
            if (processedEnums.add(ele.parent.parent)) {
                val notificationGroup = NotificationGroupManager.getInstance()
                    .getNotificationGroup("I18nExtractorNotification")

                if (notificationGroup != null) {
                    val notification = notificationGroup.createNotification(
                        "跳过枚举成员 i18n 提取",
                        "枚举成员初始化值（如 ${ele.parent.parent.parent.text}）不支持运行时 \$t()，会报 TS18033 错误。\n" +
                                "建议改为 const 对象",
                        NotificationType.WARNING
                    )
                    Notifications.Bus.notify(notification, processor.project)
                }
            }
            return
        }
        val text = ele.stringValue ?: return

        if (text.isEmpty()) return
        //print("$text,contains${raw.contains("\$t(")}\n")

        // ── 先检查是否处于 i18n 翻译调用作用域（三态判定）──
        val tSem = detectTSemantic(ele)
        if (tSem == I18nPsiTools.TSem.DIRECT_ARG || tSem == I18nPsiTools.TSem.INSIDE_UNKNOWN) {
            // DIRECT_ARG：字符串直接是 $t('x') 的参数 → 已完成过 i18n，跳过
            // INSIDE_UNKNOWN：位于无法证明来源的调用参数内部 → 保守跳过，零误改（名字不是语义证明）
            return
        }

        val key = collectExtractedStrings(ele) ?: return

        // Bug4 修复：外层祖先有 $t(...)，但参数是表达式不是字符串字面量，
        //  内层字符串不能再包一层 $t(...)，否则出现 $t(isPinned ? $t(...) : $t(...))。
        //  正确：直接把字符串字面量替换为 'key' 文本 → $t(isPinned ? 'key1' : 'key2')
        val newExprText: String = if (tSem == I18nPsiTools.TSem.OUTER_T_EXPRESSION) {
            val quote = if (raw.startsWith("'")) "'" else "\""
            "$quote$key$quote"
        } else {
            // 使用 buildTFunctionExpr：含换行符时自动切换为反引号模板字符串，避免普通字符串跨行导致的解析截断
            // 框架由 ele 检测，保证 react-intl（formatMessage({ id: ... })）等策略的调用形态生效。
            buildTFunctionExpr(I18nFrameworkRegistry.detect(ele), key, "{}")
        }
        if (ele.text == newExprText) return

        // 登记为纯数据配方（RewritePlan，JS_LITERAL），Apply 阶段由解释器执行替换。
        //   target=ele，解释器据此调 JsRewriter.rewriteLiteral（行为与原闭包 1:1）。
        processor.recordRewrite(
            message = key,
            replaceRoot = ele,
            anchor = ele,
            kind = RewriteKind.JS_LITERAL,
            newExpression = newExprText,
        )
    }

    // ───────────────────────────────────────────────
// JS 字符串拼接 (+)
// ───────────────────────────────────────────────
    internal fun collectJSBinaryExpressionChange(binaryExpr: JSBinaryExpression) {
        // 拼接形式的索引键（例：P['姓' + '名']）→ 也不翻译
        if (isInIndexKeyPosition(binaryExpr)) return
        if (binaryExpr.parent is JSBinaryExpression) {
            return
        }
        if (binaryExpr.operationSign != JSTokenTypes.PLUS) return
        if (!processor.containsTargetLanguage(binaryExpr.text, SiteKind.JS_CONCAT)) {
            return
        }
        val template = convertConcatTextToTemplate(binaryExpr)
        collectJSStringTemplate(template, binaryExpr) { value -> value }
    }

    internal fun convertConcatTextToTemplate(binaryExpr: JSBinaryExpression): String =
        I18nPsiTools.convertConcatTextToTemplate(binaryExpr)


    // ───────────────────────────────────────────────
// 生成 key：直接用中文（简单清理）
// ───────────────────────────────────────────────
    internal fun generateKey(value: String, element: PsiElement): String =
        I18nPsiTools.generateKey(value, element)
}