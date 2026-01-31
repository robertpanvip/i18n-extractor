package com.pan.extractor

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.XmlElementFactory
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.psi.PsiComment
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlToken
import com.intellij.psi.xml.XmlTokenType

class VueI18nProcessor(private val project: Project) {

    /** 收集的 key -> 原文本 */
    val extractedStrings = mutableMapOf<String, String>()

    /** 正则匹配连续中文字符 */
    private val chineseRegex = Regex("[\\u4e00-\\u9fff]+")

    /** 处理整个 Vue 文件，支持 undo */
    fun processFile(psiFile: PsiElement) {
        val changes = mutableListOf<() -> Unit>()

        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is XmlText -> if (!isInStyleOrComment(element)) {
                        collectTemplateTextChange(element, changes)
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

    private fun ensureVueI18nImported(
        psiFile: PsiElement
    ) {
        val scriptTag = PsiTreeUtil.findChildrenOfType(psiFile, XmlTag::class.java)
            .firstOrNull { it.name == "script" } ?: return
        val project = psiFile.project


        val endToken = PsiTreeUtil.findChildrenOfType(scriptTag, XmlToken::class.java)
            .firstOrNull { it.tokenType == XmlTokenType.XML_TAG_END } ?: return

        val setup = endToken.nextSibling

        val factory = XmlElementFactory.getInstance(project)

        val oldText = setup?.text
        val newText = "\nimport { useI18n } from 'vue-i18n'\n const { t: \$t } = useI18n()${oldText ?: ""}"
        if (oldText === null) {
            // 没有文本节点，直接添加一个新的
            val newText = factory.createTagFromText("<script>$newText</script>")
            val newXmlText = PsiTreeUtil.findChildOfType(newText, XmlText::class.java)
            if (newXmlText != null) {
                setup.add(newXmlText)
            }
            return
        }

        if (!oldText.contains("vue-i18n")) {

            val dummy = factory.createTagFromText("<script>$newText</script>")
            val newXmlText = PsiTreeUtil.findChildOfType(dummy, XmlText::class.java)

            if (newXmlText != null) {
                setup.replace(newXmlText)
            }
        }
    }

    // ───────────────────────────────────────────────
    // Template 文本节点
    // ───────────────────────────────────────────────
    private fun collectTemplateTextChange(textNode: XmlText, changes: MutableList<() -> Unit>) {
        val text = textNode.text.trim()
        if (text === "") {
            return;
        }
        if (!hasChinese(text)) {
            return
        }
        if (text.contains("\$t(")) return

        val key = generateKey(text, textNode)
        extractedStrings.putIfAbsent(key, text)

        val newText = "{{ \$t('$key') }}"

        changes.add {
            val factory = XmlElementFactory.getInstance(project)
            val dummyTag = factory.createTagFromText("<div>$newText</div>")
            val newPsiText = PsiTreeUtil.findChildOfType(dummyTag, XmlText::class.java)
            if (newPsiText != null) {
                textNode.replace(newPsiText)
            }
        }
    }

    fun hasChinese(text: String): Boolean {
        return text.any { it in '\u4e00'..'\u9fff' }
    }

    // ───────────────────────────────────────────────
    // 属性值（重点处理 <slot name="中文"> → :name）
    // ───────────────────────────────────────────────
    private fun collectXmlAttributeValueChange(attrValue: XmlAttributeValue, changes: MutableList<() -> Unit>) {
        val originalText = attrValue.value.trim()
        if (originalText.isEmpty()) return
        if (!hasChinese(originalText)) {
            return
        }
        if(originalText.contains("\$t(")){
            return
        }

        val key = generateKey(originalText, attrValue)
        extractedStrings.putIfAbsent(key, originalText)


        val newText = "\$t('$key')"

        if (newText == originalText) return

        val attr = attrValue.parent as? XmlAttribute ?: return
        val tag = attr.parent ?: return

        changes.add {
            val factory = XmlElementFactory.getInstance(project)
            val quote = if (attrValue.text.startsWith('"')) "\"" else "'"
            val attrPart = ":${attr.name}=$quote$newText$quote"
            val tempTagText = "<div $attrPart></div>"
            val tempTag = factory.createTagFromText(tempTagText)
            val newAttr = tempTag.attributes.firstOrNull() ?: return@add
            attr.replace(newAttr)
        }
    }

    private val templateVarRegex = Regex("""\$\{\s*([^}]+?)\s*}""")

    fun collectJSStringTemplate(stringExpr: JSLiteralExpression, changes: MutableList<() -> Unit>) {
        val raw = stringExpr.text
        if (raw.isEmpty()) return
        if (isCalled(stringExpr)) {
            return
        }
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
        val key = generateKey(message, stringExpr)
        extractedStrings.putIfAbsent(key, key);


        //print("text${stringExpr.text},${message}-${paramsObject}\n")
        changes.add {
            val newExprText = "\$t(\"$message\",$paramsObject)"
            val newExpr = JSChangeUtil.tryCreateExpressionFromText(project, newExprText, null, false)
            if (newExpr != null) {
                val newElement = newExpr.psi  // 或者 newAstNode.psi
                stringExpr.replace(newElement)
            }
        }
    }

    fun isCalled(stringExpr: JSLiteralExpression): Boolean {
        val parent = stringExpr.parent.parent
        //print("parent,${parent.text}${parent is JSCallExpression}")
        if (parent is JSCallExpression) {
            val callee = parent.methodExpression?.text
            if (callee == "\$t") return true
        }
        return false;
    }

    // ───────────────────────────────────────────────
    // JS 字符串字面量
    // ───────────────────────────────────────────────
    private fun collectJSStringChange(stringExpr: JSLiteralExpression, changes: MutableList<() -> Unit>) {

        val raw = stringExpr.text
        if (raw.isEmpty()) {
            return
        }

        if (raw.startsWith("`") && raw.contains("\${")) {
            return collectJSStringTemplate(stringExpr, changes);
        }
        val text = stringExpr.stringValue ?: return

        //print("$text,contains${raw.contains("\$t(")}\n")
        if (text.isEmpty()) return
        if (!hasChinese(text)) {
            return
        }
        if (isCalled(stringExpr)) {
            return
        }

        val key = generateKey(text.trim(), stringExpr)
        extractedStrings.putIfAbsent(key, text)


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

        binaryExpr.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (element is JSLiteralExpression) {
                    collectJSStringChange(element, changes)
                }
                super.visitElement(element)
            }
        })
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