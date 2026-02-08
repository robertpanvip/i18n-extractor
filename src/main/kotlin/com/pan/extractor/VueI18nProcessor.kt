package com.pan.extractor

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
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

class VueI18nProcessor(private val project: Project, private var psiFile: PsiElement) {

    /** 收集的 key -> 原文本 */
    val extractedStrings = mutableMapOf<String, String>()

    //val psiFactory = PsiFileFactory.getInstance(project)
    val factory: XmlElementFactory = XmlElementFactory.getInstance(project)

    fun isMustache(text: String): Boolean {
        return text.contains("{{") && text.contains("}}")
    }

    fun isFullMustache(text: String): Boolean {
        val content = text.trim();
        return content.startsWith("{{") && content.endsWith("}}")
    }

    // 處理帶 Mustache 的 XmlText：獲取注入的 JS
    fun visitMustache(element: PsiElement, visitElement: (JSLiteralExpression) -> Unit) {

        val injected = InjectedLanguageManager.getInstance(project)
            .getInjectedPsiFiles(element)  // 或 getInjectedFragments
        injected?.forEach { pair ->
            val injectedRoot: PsiElement = pair.first     // 這才是注入的 PSI 根元素
            injectedRoot.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(e: PsiElement) {
                    if (e is JSLiteralExpression) {
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

    /** 处理整个 Vue 文件，支持 undo */
    fun processFile() {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                WriteCommandAction.runWriteCommandAction(project) {
                    val changes = mutableListOf<() -> Unit>();
                    psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: PsiElement) {
                            when (element) {
                                is XmlText -> if (!isInStyleOrComment(element)) {
                                    if (isMustache(element.text)) {
                                        visitMustache(element, { item ->
                                            collectJSStringChange(item, changes)
                                        })
                                        val list = getNotMustacheElement(element)
                                        list.forEach { ele ->
                                            collectTemplateTextChange(ele, changes)
                                        }
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
                    changes.forEach { it() }
                    if (extractedStrings.isNotEmpty() && isVueFile(psiFile.containingFile)) {
                        ensureVueI18nImported(psiFile).forEach { it() }
                    }
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

    private fun ensureVueI18nImported(
        psiFile: PsiElement
    ): MutableList<() -> Unit> {
        val action = mutableListOf<() -> Unit>();
        var scriptTag = this.getScriptTag();
        if (scriptTag === null) {
            psiFile.add(factory.createTagFromText("<script setup lang=\"ts\">\n</script>"));
            scriptTag = this.getScriptTag()
        }

        val endToken = PsiTreeUtil.findChildrenOfType(scriptTag, XmlToken::class.java)
            .firstOrNull { it.tokenType == XmlTokenType.XML_TAG_END } ?: return action

        val setup = endToken.nextSibling

        val content = setup?.text ?: "";

        val insetText = "import { useI18n } from 'vue-i18n'"
        val lines = content.split("\\n".toRegex()).toMutableList()
        if (!content.contains("useI18n")) {
            if (lines[0] === "") {
                lines.removeAt(0)
            }
            lines.add(0, "")
            lines.add(1, insetText)
        }
        val lastImportIndex = lines.indexOfLast { item -> item.startsWith("import") }
        val toAdd = "const { t: \$t } = useI18n();"

        if (lastImportIndex == -1) {
            lines.add(toAdd);
        } else {
            lines[lastImportIndex] = "${lines[lastImportIndex]}\n${toAdd}"
        }
        if (!content.contains("useI18n")) {
            val newContent = lines.joinToString("\n")
            action.add {
                scriptTag?.value?.text = newContent  // 直接设置 value.text
            }
        }
        return action
    }

    fun getCharactersText(textNode: XmlElement): List<XmlToken> {
        val textChild = textNode.children.filterIsInstance<XmlToken>()
            .filter { it.tokenType == XmlTokenType.XML_DATA_CHARACTERS }
        return textChild
    }

    fun getNotMustacheElement(element: XmlText): MutableList<XmlToken> {
        val result = mutableListOf<XmlToken>()

        var inMustache = false

        getCharactersText(element).forEach { token ->
            when (token.text) {
                "{{" -> inMustache = true
                "}}" -> inMustache = false
                else -> {
                    if (!inMustache) {
                        result.add(token)
                    }
                }
            }
        }
        return result
    }

    // Template 文本节点
    // ───────────────────────────────────────────────
    private fun collectTemplateTextChange(textNode: XmlElement, changes: MutableList<() -> Unit>) {
        val original = textNode.text
        val trimmed = original.trim()
        if (trimmed === "") {
            return;
        }

        if (trimmed.startsWith("<!--") && trimmed.endsWith("-->")) {
            val startTagCount = trimmed.split("<!--").size - 1 // 得到 Int（次数）
            val endTagCount = trimmed.split("-->").size - 1     // 得到 Int（次数）

            if (startTagCount == 1 && endTagCount == 1) { // 同上，布尔条件
                return
            }
        }
        if (!hasChinese(trimmed)) {
            return
        }

        val isJSX = Util.isJSX(textNode);

        if (trimmed.contains("\$t(")) return

        val key = collectExtractedStrings(textNode)

        changes.add {
            // 只找“同一个父节点”下的 XmlText（非常关键）
            val textChild = getCharactersText(textNode)
            val textNodes = textChild.ifEmpty { listOf(textNode) }
            val newContent =
                if (!isJSX) "{{ \$t(`$key`) }}" else "{ \$t(`$key`) }"

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
        if (originalText.contains("\$t(")) {
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
            newText = "\$t('$key')"
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

    private val templateVarRegex = Regex("""\$\{\s*([^}]+?)\s*}""")


    fun collectJSStringTemplate(raw: String, changes: MutableList<() -> Unit>, ele: PsiElement) {
        val content = raw.substring(1, raw.length - 1)
        val params = LinkedHashMap<String, String>()

        val message = templateVarRegex.replace(content) { match ->
            val expr = match.groupValues[1].trim()
            val key = expr.replace(".", "_")
            params[key] = expr
            "{$key}"
        }

        val paramsObject = params.entries.joinToString(
            prefix = "{ ",
            postfix = " }"
        ) { (k, v) ->
            if (k.all { it.isDigit() }) "\"$k\": $v" else "$k: $v"
        }

        val key = generateKey(message, ele)
        extractedStrings.putIfAbsent(key, message);

        changes.add {
            val newExprText = buildTFunctionExpr(message.trim(), paramsObject)
            val newElement = createStringExpressionNode(newExprText, ele)
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

        // 步骤4：拼接最终的 $t 函数调用表达式
        return "\$t($quote$escapedMsg$quote, $paramsObject)"
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

    fun collectJSStringTemplateFromExpression(stringExpr: JSLiteralExpression, changes: MutableList<() -> Unit>) {
        val raw = stringExpr.text
        if (raw.isEmpty()) return
        if (isTransformedCalled(stringExpr)) {
            return
        }
        collectJSStringTemplate(raw, changes, stringExpr)
    }

    fun isTransformedCalled(stringExpr: JSLiteralExpression): Boolean {
        val parent = stringExpr.parent.parent
        //print("parent,${parent.text}${parent is JSCallExpression}")
        if (parent is JSCallExpression) {
            val callee = parent.methodExpression?.text
            if (callee == "\$t") return true
        }
        return false;
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

        if (!hasChinese(raw)) {
            return
        }

        if (isJSTemplateLiteral(raw)) {
            return collectJSStringTemplateFromExpression(ele, changes);
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

        val key = collectExtractedStrings(ele)

        if (isTransformedCalled(ele)) {
            return
        }

        val quote = if (ele.text.startsWith('"')) "\"" else "'"
        val newText = "\$t($quote$key$quote)"
        if (newText == text) return

        changes.add {
            val newExprText = newText
            val newExpr = JSChangeUtil.tryCreateExpressionFromText(project, newExprText, null, false)
            if (newExpr != null) {
                val newElement = newExpr.psi  // 或者 newAstNode.psi
                ele.replace(newElement)
            }
        }
    }

    // ───────────────────────────────────────────────
// JS 字符串拼接 (+)
// ───────────────────────────────────────────────
    private fun collectJSBinaryExpressionChange(binaryExpr: JSBinaryExpression, changes: MutableList<() -> Unit>) {

        if (binaryExpr.operationSign != JSTokenTypes.PLUS) return
        if (!hasChinese(binaryExpr.text)) {
            return
        }
        val template = convertConcatTextToTemplate(binaryExpr.text)
        //println("template${template}${isJSTemplateLiteral(template)}")
        collectJSStringTemplate(template, changes, binaryExpr)
    }

    fun convertMustacheToTemplate(text: String): String {
        // 匹配 {{ ... }} （允许中间有空格，非贪婪）
        val regex = Regex("""\{\{\s*(.+?)\s*\}\}""")

        val result = StringBuilder()
        var lastEnd = 0

        regex.findAll(text).forEach { match ->
            // 添加插值前面的普通文本
            if (match.range.first > lastEnd) {
                result.append(text.substring(lastEnd, match.range.first))
            }

            // 添加 ${表达式}
            val expr = match.groupValues[1].trim()
            if (expr.isNotEmpty()) {
                result.append("\${$expr}")
            }

            lastEnd = match.range.last + 1
        }

        // 添加最后的普通文本
        if (lastEnd < text.length) {
            result.append(text.substring(lastEnd))
        }

        return result.toString()
    }

    private fun convertConcatTextToTemplate(concatText: String): String {
        // 步骤1：按 + 分割（处理空格，如 "a" + b → ["a", "b"]）
        val parts = concatText.split(Regex("\\s*\\+\\s*")).map { it.trim() }

        // 步骤2：拼接模板字符串
        val sb = StringBuilder("`")
        parts.forEach { part ->
            when {
                // 字符串字面量：去掉引号（处理 "" 或 '' 包裹的情况）
                part.startsWith("\"") && part.endsWith("\"") ->
                    sb.append(part.substring(1, part.length - 1))

                part.startsWith("'") && part.endsWith("'") ->
                    sb.append(part.substring(1, part.length - 1))
                // 变量/表达式：用 ${} 包裹
                else -> sb.append("\${$part}")
            }
        }
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