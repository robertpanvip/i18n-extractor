package com.pan.extractor

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVarStatement
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
    var effects = mutableListOf<() -> Unit>()

    /** 收集的 key -> 原文本 */
    val extractedStrings = mutableMapOf<String, String>()

    val factory: XmlElementFactory = XmlElementFactory.getInstance(project)

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
        val sb = StringBuilder()
        element.children.forEachIndexed { index, e ->
            val text = rm(e)
            when (index) {
                // 第一个节点：拼接`开头
                0 -> sb.append(quote).append(text)
                // 最后一个节点：拼接`结尾
                element.children.lastIndex -> sb.append(text).append(quote)
                // 中间节点：过滤空白，直接拼接
                else -> if (e !is PsiWhiteSpace) sb.append(text)
            }
        }
        if (element.children.size == 1) {
            sb.append(quote)
        }
        val raw = sb.toString().trim()

        if (raw.startsWith("`\${\$t(")) {
            return
        }
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
                            visitMustache(element, { item ->
                                if (item is JSBinaryExpression) {
                                    collectJSBinaryExpressionChange(item, changes)
                                }
                                if (item is JSLiteralExpression) {
                                    collectJSStringChange(item, changes)
                                }
                            })
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
        val changes = pureCollect(psiFile)
        effects = changes;
        return changes;
    }

    fun run() {
        this.effects.forEach { it() }
        if (extractedStrings.isNotEmpty() && isVueFile(psiFile.containingFile)) {
            ensureVueI18nImported(psiFile);
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
        // 1. 创建 import 语句
        val importUseI18n = createStringExpressionNode("import { useI18n } from 'vue-i18n';", psiFile)

        // 2. 创建 const 语句
        val constUseI18n = createStringExpressionNode("const { t: \$t } = useI18n();", psiFile)


        if (importStatements.isEmpty()) {
            // 没有 import，直接加到内容最前面（或合适位置）
            val importUseI18n = scriptContent.addAfter(importUseI18n, scriptContent.firstChild)
            val whiteSpace = scriptContent.addAfter(createStringExpressionNode("\n", psiFile), importUseI18n)
            scriptContent.addAfter(constUseI18n, whiteSpace)
        } else {
            val alreadyExists = importStatements.find({ s ->
                s.text == importUseI18n.text
            })

            if (alreadyExists === null) {
                // 有 import → 新 import 加到第一个 import 前面
                val firstImport = importStatements.first()
                firstImport.parent.addBefore(importUseI18n, firstImport)
            }

            val jsVars = PsiTreeUtil.findChildrenOfType(scriptContent, JSVarStatement::class.java)
            val alreadyUseI18Exists = jsVars.find({ s ->
                s.text == constUseI18n.text
            })

            if (alreadyUseI18Exists === null) {
                // const 加到最后一个 import 后面
                val lastImport = importStatements.last()
                lastImport.parent.addAfter(constUseI18n, lastImport)
            }

        }
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
        val message = templateVarRegex.replace(content) { match ->
            // 提取${}内的原始内容（groupValues[1] 是正则括号内的匹配结果）
            val innerContent = match.groupValues[1].trim()
            // 按顺序分配索引
            val key = index.toString()
            params[key] = innerContent
            index++
            // 替换为${数字索引}
            "{$key}"
        }


        // 步骤4：保存提取的message（按trim后的value去重）
        val key = generateKey(message, ele)
        extractedStrings.putIfAbsent(key, message)
        // 步骤5：添加替换逻辑（保持你的原有逻辑）
        changes.add {
            // 步骤3：生成paramsObject（{ 0: 原始内容, 1: 原始内容... }）
            val paramsObject = params.entries.joinToString(
                prefix = "{ ",
                postfix = " }"
            ) { (k, v) ->
                val expr = JSChangeUtil.createExpressionFromText(
                    project,
                    v,
                    null,  // context
                    false  // 不抛异常
                )
                if (expr?.psi !== null) {
                    val changes = pureCollect(expr.psi);
                    changes.forEach { it() }
                    "\"$k\": ${expr.text}"
                } else {
                    // 数字索引加引号，内容保留原始格式
                    "\"$k\": $v"
                }
            }
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
        collectJSStringTemplate(raw, changes, stringExpr) { value -> value }
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