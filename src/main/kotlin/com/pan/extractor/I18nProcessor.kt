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

    /** 新提取的 key -> 原文本 */
    val extractedStrings = mutableMapOf<String, String>()

    /** 已存在的 $t() 调用 key -> 原文本（仅展示，不替换） */
    val existingStrings = mutableMapOf<String, String>()

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
        // 过滤掉 PsiWhiteSpace 子节点，避免首尾空白干扰 raw 字符串构建
        val children = element.children.filter { it !is PsiWhiteSpace }
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
                        // 处理纯模板字面量（反引号字符串，无插值）
                        // 例如三目表达式中 `点击展开` : "点击收起" 的反引号部分
                        if (e is JSStringTemplateExpression && !isInComment(e)) {
                            val text = e.text
                            // 纯文本模板字面量（不含 ${} 插值）才提取
                            if (text.startsWith("`") && text.endsWith("`") && !text.contains("\${")) {
                                val content = text.substring(1, text.length - 1)
                                if (hasChinese(content) && !isTransformedCalledTemplate(e)) {
                                    collectTemplateLiteralChange(e, content, changes)
                                    foundStrings = true
                                }
                            }
                        }
                        if (e is JSBinaryExpression && !isInComment(e)) {
                            collectJSBinaryExpressionChange(e, changes)
                            foundStrings = true
                        }
                        super.visitElement(e)
                    }
                })
            }

            // 2. 同时处理 {{ }} 表达式之间的普通文本（含中文的部分）
            // 例如：{{ "a" }}-测试 {{ "b" }} 中的 "-测试"
            processPlainTextBetweenMustaches(element, changes)

            // 如果注入 JS 中找到了字符串，就不再用模板字符串方式处理
            if (foundStrings) {
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
        collectExistingTKeys()
        val changes = pureCollect(psiFile)
        effects = changes;
        return changes;
    }

    /**
     * 扫描文件中已有的 $t() / t() 调用，收集其 key 到 existingStrings。
     * 覆盖模板注入 JS 和 script/JS/TS 两种来源。
     */
    private fun collectExistingTKeys() {
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
     * 从原始文本中提取 $t(`文本`)、$t("文本")、$t('文本') 调用，
     * 用于 Vue 模板中 backtick 等无法被 JS 注入解析的情况。
     */
    private fun collectTKeysFromRawText(text: String) {
        // 匹配 $t(`文本`)、$t("文本")、$t('文本')，支持可选的第二个参数
        // 使用反向引用确保引号配对（如开闭都是反引号）
        // 使用 char 拼接构建正则字符串，完全避免 Kotlin 转义序列问题
        val bs = '\\'.toString()       // 单个反斜杠字符
        val dollar = '$'.toString()     // $ 字符
        val bt = '`'.toString()         // 反引号
        val dq = '"'.toString()         // 双引号
        val sq = "'"                  // 单引号
        val quotes = bt + dq + sq       // 三种引号字符组
        
        // 正则: \$(?:t|tc)\(\s*([`"'])([^`"'\n]+)\1\s*[,)]
        val patternStr = buildString {
            append(bs).append(dollar)   // \$
            append("(?:t|tc)")          // t 或 tc
            append(bs).append("(")      // \(
            append(bs).append("s")      // \s
            append("*([")               // *(
            append(quotes)              // [`"']
            append("])([^").             // "])([^
            append(quotes)              // [`"']
            append(bs).append("n")      // \n
            append("]+)")               // ]+
            append(bs).append("1")      // \1
            append(bs).append("s")      // \s
            append("*[,)]")             // *[,)]
        }
        val pattern = Regex(patternStr)
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
                }
                super.visitElement(element)
            }
        })
    }

    private fun collectTKeyFromCall(call: JSCallExpression) {
        val method = call.methodExpression
        if (method is JSReferenceExpression) {
            val name = method.referenceName
            if (name == "\$t" || name == "t") {
                val firstArg = call.arguments.firstOrNull() ?: return
                val text = extractStringArgText(firstArg) ?: return
                val key = generateKey(text.trim(), call)
                existingStrings.putIfAbsent(key, text.trim())
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
        this.effects.forEach { it() }
        if (extractedStrings.isNotEmpty()) {
            if (isVueFile(psiFile.containingFile)) {
                ensureVueI18nImported(psiFile)
            } else if (Util.isReact(psiFile)) {
                ensureReactI18nImported(psiFile)
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
            // 用模块名包含判断，兼容单引号/双引号差异
            val alreadyHasI18nImport = importStatements.any { s ->
                s.text.contains("vue-i18n") && s.text.contains("useI18n")
            }

            if (!alreadyHasI18nImport) {
                // 有 import → 新 import 加到第一个 import 前面
                val firstImport = importStatements.first()
                firstImport.parent.addBefore(importUseI18n, firstImport)
            }

            val jsVars = PsiTreeUtil.findChildrenOfType(scriptContent, JSVarStatement::class.java)
            // 用内容包含判断，避免格式差异导致重复
            val alreadyUseI18Exists = jsVars.any { s ->
                s.text.contains("useI18n()") && s.text.contains("\$t")
            }

            if (!alreadyUseI18Exists) {
                // const 加到最后一个 import 后面
                val lastImport = importStatements.last()
                lastImport.parent.addAfter(constUseI18n, lastImport)
            }

        }
    }

    /** React i18n 导入 + useTranslation hook 注入 */
    private fun ensureReactI18nImported(psiFile: PsiElement) {
        val containingFile = psiFile.containingFile ?: return

        // 1. 确保 react-i18next 导入存在
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

        // 2. 找到所有 React 组件函数并注入 useTranslation hook
        val componentFuncs = Util.findReactComponentFunctions(containingFile)
        if (componentFuncs.isEmpty()) return

        // 3. 逐个注入（从后往前插入，避免 offset 偏移）
        // 使用 PSI 操作创建语句并插入，全部使用纯 PSI 操作避免 Document locked 异常
        for (func in componentFuncs.asReversed()) {
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
            // 只找"同一个父节点"下的 XmlText（非常关键）
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

    private val templateVarRegex = """\$\{((?:[^{}]|\{(?:[^{}]|\{[^}]*\})*\})*)}""".toRegex()

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

        // 步骤4：拼接最终的 $t 函数调用表达式（空参数对象时省略第二个参数）
        return if (paramsObject.replace(" ", "") == "{}") {
            "\$t($quote$escapedMsg$quote)"
        } else {
            "\$t($quote$escapedMsg$quote, $paramsObject)"
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
            val callee = callExpr.methodExpression?.text
            if (callee == "\$t") return true
        }
        return false
    }

    /**
     * 判断模板字面量（反引号字符串）是否已在 $t() 调用中。
     * 对应 $t(`文本`) 这种用法。
     */
    private fun isTransformedCalledTemplate(templateExpr: JSStringTemplateExpression): Boolean {
        val parent = templateExpr.parent
        val callExpr = when {
            parent is JSCallExpression -> parent
            parent.parent is JSCallExpression -> parent.parent as JSCallExpression
            else -> null
        }
        if (callExpr != null) {
            val callee = callExpr.methodExpression?.text
            if (callee == "\$t" || callee == "t") return true
        }
        return false
    }

    /**
     * 处理纯模板字面量（反引号字符串，无插值）的提取和替换。
     * 将 `中文` 替换为 $t('中文')
     */
    private fun collectTemplateLiteralChange(
        templateExpr: JSStringTemplateExpression,
        content: String,
        changes: MutableList<() -> Unit>
    ) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return

        val key = generateKey(trimmed, templateExpr)
        extractedStrings.putIfAbsent(key, trimmed)

        changes.add {
            val newExprText = "\$t('$key')"
            val newExpr = JSChangeUtil.tryCreateExpressionFromText(project, newExprText, null, false)
            if (newExpr != null) {
                templateExpr.replace(newExpr.psi)
            }
        }
    }

    /**
     * 处理同一个 XmlText 中 {{ }} 表达式之间的普通文本。
     * 例如：{{ "a" }}-测试 {{ "b" }} 中的 "-测试" 部分。
     * 通过遍历子节点中的 XmlToken(XML_DATA_CHARACTERS) 逐个处理。
     */
    private fun processPlainTextBetweenMustaches(element: PsiElement, changes: MutableList<() -> Unit>) {
        val children = element.children
        for (child in children) {
            if (child is XmlToken && child.tokenType == XmlTokenType.XML_DATA_CHARACTERS) {
                val text = child.text
                if (text.isNotBlank() && hasChinese(text)) {
                    // 排除 mustache 语法本身（理论上 XML_DATA_CHARACTERS 不包含 {{ }}，但保险起见）
                    if (!isMustache(text)) {
                        val key = collectExtractedStrings(child)
                        changes.add {
                            if (!child.isValid) return@add
                            val newContent = "{{ \$t(`$key`) }}"
                            val newElement = createStringExpressionNode(newContent, child)
                            child.replace(newElement)
                        }
                    }
                }
            }
        }
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