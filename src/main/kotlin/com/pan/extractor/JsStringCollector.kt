package com.pan.extractor

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

/**
 * 「JS 字符串收集 与 $t 表达式生成」辅助类。
 *
 * 从 I18nProcessor 拆分出的状态相关方法群：负责收集 JS 字符串字面量 / 模板 / 二元拼接中的
 * 中文并生成 $t(...) 替换表达式。持有 I18nProcessor 引用以访问其状态（changes/pendingChanges、
 * extractedStrings、tFunctionName、isVueFile、templateVarRegex、project 等），保证行为不变。
 */
class JsStringCollector(private val processor: I18nProcessorContract) {

    /** 能力面（[processor]，[I18nProcessorContract]）之外，结果/状态经 [state] 读写（由分析器注入，见 I18nProcessor）。 */
    internal lateinit var state: CollectionState

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

    fun collectJSStringTemplate(
        raw: String,
        changes: MutableList<I18nProcessor.CollectedChange>,
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
        val message = templateVarRegex.replace(content) { match ->
            val innerContent = match.groupValues[1].trim()
            // 如果 ${} 内是纯字符串字面量（如 `测试`、'测试'、"测试"），直接内联到 message 中
            val pureString = processor.extractPureStringContent(innerContent)
            if (pureString != null) {
                return@replace pureString
            }
            val rawIndex = index++
            val key = fw.paramKey(rawIndex)
            val placeholder = fw.placeholderFor(rawIndex)
            params[key] = innerContent
            placeholder
        }


        // 步骤4：检查 message 是否包含中文，不含中文则跳过
        if (!processor.containsTargetLanguage(message, SiteKind.JS_TEMPLATE)) {
            return
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
                v
            }
            if (paramKeyNeedsQuote) "\"$k\": $paramExpr" else "$k: $paramExpr"
        }

        // 步骤6：添加替换逻辑（包装为 CollectedChange，允许后续因子化阻止旧替换）
        processor.recordChange(
            message = message,
            replaceRoot = ele,
            anchor = ele,
            changes = changes
        ) {
            // 目标架构 Rewriter 层：JsRewriter 纯文本节点替换（行为与原闭包 1:1）
            // 注意用消毒后的 key（而非原始 message.trim()）作为 $t(...) 实参：与 extractedStrings
            // 中的资源键保持一致，否则含 @/|/句末点 的文案会因键失配而在运行时查不到翻译（P0）。
            val newExprText = buildTFunctionExpr(fw, key, paramsObject)
            val text = creator(newExprText)
            com.pan.extractor.rewriter.JsRewriter.rewriteWithStringNode(ele, text)
        }
    }

    /**
     * 拼装最终翻译调用表达式。
     *
     * §架构（CallExpressionStrategy）：这里的「如何把 key + 参数对象拼成完整调用」不再是
     * 硬编码的 `fn('key'[params])`，而是下沉给 [fw]（[com.pan.extractor.CallExpressionStrategy]）。
     * 默认策略沿用历史拼法（行为 1:1）；react-intl 覆盖为 `formatMessage({ id: 'key' }[, values])`。
     * 本方法只负责：解析 key 文本 → 按换行/引号转义打包成 key 字面量 → 交给 [fw] 完成成型。
     *
     * @param fw 当前文件命中的框架策略（由调用方用 [com.pan.extractor.I18nFrameworkRegistry.detect] 得到）。
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

        val message = templateVarRegex.replace(content) { match ->
            val innerContent = match.groupValues[1].trim()
            val pureString = processor.extractPureStringContent(innerContent)
            if (pureString != null) return@replace pureString
            val rawIndex = index++
            val key = fw.paramKey(rawIndex)
            val placeholder = fw.placeholderFor(rawIndex)
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

    fun createStringExpressionNode(text: String, context: PsiElement): PsiElement =
        I18nPsiTools.createStringExpressionNode(text, context)

    /**
     * 从文本创建 JS 语句（使用 PsiFileFactory 构造完整 PSI 语句节点）。
     * 相比直接操作 AST 节点，这种方式创建的语句结构完整，
     * 不会导致 Document is locked 异常。
     */
    internal fun createJSStatementFromText(text: String, context: PsiElement): PsiElement =
        I18nPsiTools.createJSStatementFromText(text, context)

    fun collectJSStringTemplateFromExpression(stringExpr: JSLiteralExpression, changes: MutableList<I18nProcessor.CollectedChange>) {
        val raw = stringExpr.text
        if (raw.isEmpty()) return
        if (isTransformedCalled(stringExpr)) {
            return
        }
        collectJSStringTemplate(raw, changes, stringExpr) { value -> value }
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
    internal fun collectJSStringChange(ele: JSLiteralExpression, changes: MutableList<I18nProcessor.CollectedChange>) {
        // 【Bug A1 修复】仅当字面量位于「纯字符串拼接」(`"a" + "b" + ...`，所有操作数都是字符串字面量)
        // 中时，其提取交由 collectJSBinaryExpressionChange 统一合并成一个 key，这里必须跳过，
        // 否则操作数会被重复提取，且 binary change 先替换整节点后操作数 change 会作用在失效 PSI 上。
        // 注意：若拼接中有变量/引用（如 `prefix.value + "正在执行操作，请稍候"`），则该字面量仍需单独提取，
        // 不能被跳过（collectJSBinaryExpressionChange 会把它包进 \${}，语义不同）。
        if (ele.parent is JSBinaryExpression && isWithinPureStringConcat(ele)) return

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
        // 因为外层模板字面量的处理逻辑会统一处理
        if (PsiTreeUtil.getParentOfType(ele, JSStringTemplateExpression::class.java) != null) {
            return
        }

        if (processor.isJSTemplateLiteral(raw)) {
            return collectJSStringTemplateFromExpression(ele, changes);
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
                    .getNotificationGroup("Vue i18n 提取提示")  // 自定义组名

                val notification = notificationGroup.createNotification(
                    "跳过枚举成员 i18n 提取",
                    "枚举成员初始化值（如 ${ele.parent.parent.parent.text}）不支持运行时 \$t()，会报 TS18033 错误。\n" +
                            "建议改为 const 对象",
                    NotificationType.WARNING
                )

                Notifications.Bus.notify(notification, processor.project)
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

        processor.recordChange(
            message = key,
            replaceRoot = ele,
            anchor = ele,
            changes = changes
        ) {
            // 目标架构 Rewriter 层：JsRewriter 表达式替换（行为与原闭包 1:1）
            com.pan.extractor.rewriter.JsRewriter.rewriteLiteral(ele, newExprText, processor.project)
        }
    }

    // ───────────────────────────────────────────────
// JS 字符串拼接 (+)
// ───────────────────────────────────────────────
    internal fun collectJSBinaryExpressionChange(binaryExpr: JSBinaryExpression, changes: MutableList<I18nProcessor.CollectedChange>) {
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
        //println("template${template}${binaryExpr.text}")
        collectJSStringTemplate(template, changes, binaryExpr) { value -> value }
    }

    internal fun convertConcatTextToTemplate(binaryExpr: JSBinaryExpression): String =
        I18nPsiTools.convertConcatTextToTemplate(binaryExpr)


    // ───────────────────────────────────────────────
// 生成 key：直接用中文（简单清理）
// ───────────────────────────────────────────────
    internal fun generateKey(value: String, element: PsiElement): String =
        I18nPsiTools.generateKey(value, element)
}