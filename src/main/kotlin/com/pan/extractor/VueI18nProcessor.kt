package com.pan.extractor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

class VueI18nProcessor(private val project: Project, private var psiFile: PsiElement) {

    /** 收集的 key -> 原文本 */
    val extractedStrings = mutableMapOf<String, String>()
    //val psiFactory = PsiFileFactory.getInstance(project)
    val factory: XmlElementFactory = XmlElementFactory.getInstance(project)

    fun isMustache(text: String): Boolean {
        return text.contains("{{") && text.contains("}}")
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

    /** 处理整个 Vue 文件，支持 undo */
    fun processFile() {
        val changes = mutableListOf<() -> Unit>()
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is XmlText -> if (!isInStyleOrComment(element)) {
                        if (isMustache(element.text)) {
                            visitMustache(element, { item ->
                                collectJSStringChange(item, changes)
                            })
                        } else {
                            collectTemplateTextChange(element, changes)
                        }
                    }

                    is XmlAttributeValue -> if (!isInStyleOrComment(element)) {
                        collectXmlAttributeValueChange(element, changes)
                    }

                    is JSLiteralExpression -> if (!isInComment(element)) {
                        collectJSStringChange(element, changes)
                    }

                    is JSBinaryExpression -> if (!isInComment(element)) {
                        collectJSBinaryExpressionChange(element, changes)
                    }
                }
                super.visitElement(element)
            }
        })

        changes.forEach { it() }
        if (extractedStrings.isNotEmpty()) {
            ensureVueI18nImported(psiFile)
        }
    }

    fun getScriptTag(): XmlTag? {
        return PsiTreeUtil.findChildrenOfType(psiFile, XmlTag::class.java)
            .firstOrNull { it.name == "script" }
    }

    private fun ensureVueI18nImported(
        psiFile: PsiElement
    ) {
        var scriptTag = this.getScriptTag();
        if (scriptTag === null) {
            psiFile.add(factory.createTagFromText("<script setup lang=\"ts\">\n</script>"));
            scriptTag = this.getScriptTag()
        }

        val endToken = PsiTreeUtil.findChildrenOfType(scriptTag, XmlToken::class.java)
            .firstOrNull { it.tokenType == XmlTokenType.XML_TAG_END } ?: return

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
            scriptTag?.value?.text = newContent  // 直接设置 value.text
        }
    }

    // ───────────────────────────────────────────────
    // Template 文本节点
    // ───────────────────────────────────────────────
    private fun collectTemplateTextChange(textNode: XmlText, changes: MutableList<() -> Unit>) {
        val original = textNode.text
        val trimmed = original.trim()
        if (trimmed === "") {
            return;
        }
        if (!hasChinese(trimmed)) {
            return
        }

        val inScript = isInScript(textNode);

        if (trimmed.contains("\$t(")) return
        //println("TemplateText-${textNode.text}")

        val key = collectExtractedStrings(textNode)
        changes.add {
            val factory = XmlElementFactory.getInstance(project)
            // 計算前導空白（leading whitespace）
            val leading = original.substringBefore(trimmed)

            // 計算尾隨空白（trailing whitespace）
            val trailing = original.substringAfterLast(trimmed)

            val newContent = if(!inScript) "$leading{{ \$t('$key') }}$trailing" else "$leading{ \$t('$key') }$trailing"

            val dummyTag = factory.createTagFromText("<div>$newContent</div>")
            val newPsiText = PsiTreeUtil.findChildOfType(dummyTag, XmlText::class.java)
            if (newPsiText != null) {
                textNode.replace(newPsiText)
            }
        }
    }

    fun hasChinese(text: String): Boolean {
        return text.any { it in '\u4e00'..'\u9fff' }
    }


    fun isJSTemplateLiteral(text: String): Boolean {
        return text.startsWith("`") && text.contains("\${")
    }

    fun isBlock(originalText:String): Boolean{
        return originalText.startsWith('{') && originalText.endsWith('}')
    }


    // 属性值（重点处理 <slot name="中文"> → :name）
    // ───────────────────────────────────────────────
    private fun collectXmlAttributeValueChange(attrValue: XmlAttributeValue, changes: MutableList<() -> Unit>) {
        val originalText = attrValue.value.trim();
        //println("XmlAttributeValue-${originalText}-${attrValue.text}")
        val inScript = isInScript(attrValue);
        if (inScript && isBlock(originalText)) {
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

        val key = collectExtractedStrings(attrValue)

        val newText = "\$t('$key')"

        if (newText == originalText) return

        val attr = attrValue.parent as? XmlAttribute ?: return
        //val tag = attr.parent ?: return

        changes.add {
            var quote = if (attrValue.text.startsWith('"')) "\"" else "'"
            val prefix = if (inScript) "" else ":";
            var endQuote = quote;
            if (inScript) {
                quote = "{"
                endQuote = "}"
            }
            attr.name = "${prefix}${attr.name}"
            attr.setValue("$quote$newText$endQuote")
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
        extractedStrings.putIfAbsent(key, key);
        //println("text${content},${message}-${paramsObject}")
        changes.add {
            val newExprText = "\$t(\'$message\',$paramsObject)"
            val newExpr = JSChangeUtil.tryCreateExpressionFromText(project, newExprText, null, false)
            if (newExpr != null) {
                val newElement = newExpr.psi  // 或者 newAstNode.psi
                ele.replace(newElement)
            }
        }
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

    fun collectExtractedStrings(ele: PsiElement): String {
        var text = ele.text;
        if (ele is JSLiteralExpression) {
            text = ele.stringValue;
        }
        val key = generateKey(text.trim(), ele)
        extractedStrings.putIfAbsent(key, text)
        return key;
    }

    // ───────────────────────────────────────────────
    // JS 字符串字面量
    // ───────────────────────────────────────────────
    private fun collectJSStringChange(stringExpr: JSLiteralExpression, changes: MutableList<() -> Unit>) {

        val raw = stringExpr.text
        if (raw.isEmpty()) {
            return
        }
        if (stringExpr is XmlTag) {
            return
        }
        //println("JSString${raw}")

        if (isJSTemplateLiteral(raw)) {
            return collectJSStringTemplateFromExpression(stringExpr, changes);
        }
        val text = stringExpr.stringValue ?: return

        //print("$text,contains${raw.contains("\$t(")}\n")
        if (text.isEmpty()) return
        if (!hasChinese(text)) {
            return
        }
        val key = collectExtractedStrings(stringExpr)

        if (isTransformedCalled(stringExpr)) {
            return
        }

        val quote = if (stringExpr.text.startsWith('"')) "\"" else "'"
        val newText = "\$t($quote$key$quote)"
        if (newText == text) return

        changes.add {
            val newExprText = newText
            val newExpr = JSChangeUtil.tryCreateExpressionFromText(project, newExprText, null, false)
            if (newExpr != null) {
                val newElement = newExpr.psi  // 或者 newAstNode.psi
                stringExpr.replace(newElement)
            }
        }
    }

    // ───────────────────────────────────────────────
    // JS 字符串拼接 (+)
    // ───────────────────────────────────────────────
    private fun collectJSBinaryExpressionChange(binaryExpr: JSBinaryExpression, changes: MutableList<() -> Unit>) {

        if (binaryExpr.operationSign != JSTokenTypes.PLUS) return
        val template = convertConcatTextToTemplate(binaryExpr.text)
        //println("template${template}${isJSTemplateLiteral(template)}")
        collectJSStringTemplate(template, changes, binaryExpr)
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
        return value;
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

    private fun isInScript(element: PsiElement): Boolean {
        var parent = element.parent
        while (parent != null) {
            if (parent is XmlTag && parent.name == "script") return true
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