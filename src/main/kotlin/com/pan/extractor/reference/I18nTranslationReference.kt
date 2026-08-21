package com.pan.extractor.reference

import com.google.gson.JsonParser
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.impl.PsiElementBase
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.pan.extractor.locate.EntryFileLocator
import com.pan.extractor.messages.LocaleMessages
import com.pan.extractor.strategy.I18nFrameworkRegistry

/**
 * 注册 PsiReference 贡献点，使 `t("key")` / `t('key')` / `` t(`key`) `` 中的字符串参数
 * 成为可导航的引用（Ctrl+点击跳转到翻译文件对应条目）。
 *
 * 支持嵌套 JSON key（如 `button.text`）逐级下钻定位到对应 value 所在行。
 */
class I18nTranslationReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // 常规字符串字面量：t("key") / t('key')
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSLiteralExpression::class.java),
            I18nTranslationReferenceProvider()
        )
        // 模板字符串（反引号）：t(`key`)
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(JSStringTemplateExpression::class.java),
            I18nTranslationReferenceProvider()
        )
    }
}

/**
 * 为翻译调用 `t("key")` 中的字符串参数创建 [I18nTranslationReference]。
 * 非翻译调用或非首参的元素返回空数组。
 */
class I18nTranslationReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        // 快速放行：必须是 JSCallExpression 的首参
        val call = PsiTreeUtil.getParentOfType(element, JSCallExpression::class.java)
            ?: return PsiReference.EMPTY_ARRAY
        if (call.arguments.firstOrNull() !== element) return PsiReference.EMPTY_ARRAY

        // 检测是否为翻译调用并提取 key
        val fw = try {
            I18nFrameworkRegistry.detect(call)
        } catch (_: Exception) {
            return PsiReference.EMPTY_ARRAY
        }
        if (!fw.isTranslationCall(call)) return PsiReference.EMPTY_ARRAY
        val key = fw.extractKey(call) ?: return PsiReference.EMPTY_ARRAY

        return arrayOf(I18nTranslationReference(element, key))
    }
}

/**
 * PsiReference 实现：将 `t("key")` 中的字符串字面量映射到翻译文件中的对应条目。
 *
 * - [resolve] 返回一个轻量 [PsiElement]，导航到翻译文件中 key 对应的 value 所在位置。
 * - 支持嵌套 JSON key（如 `button.text`），按 `.` 分割逐级下钻定位。
 */
class I18nTranslationReference(
    private val element: PsiElement,
    private val key: String,
) : PsiReference {

    override fun getElement(): PsiElement = element

    /** 引用范围覆盖字符串值部分（不含引号/反引号）。 */
    override fun getRangeInElement(): TextRange {
        val text = element.text
        return if (text.length >= 2 && (text[0] == '\'' || text[0] == '"' || text[0] == '`')) {
            TextRange(1, text.length - 1)
        } else {
            TextRange(0, text.length)
        }
    }

    /**
     * 解析到翻译文件中的目标条目。
     * 返回一个轻量 [PsiElement]，其 [PsiElement.navigate] 会打开翻译文件并定位到对应行。
     */
    override fun resolve(): PsiElement? {
        val project = element.project
        val file = element.containingFile
        if (project.isDisposed || file == null) return null

        // 复用 LocaleMessages 的缓存快速判断 key 是否存在，避免对不存在的 key 做文件定位和解析
        if (key !in LocaleMessages.loadCached(project, file)) return null

        val entryFile = EntryFileLocator.findChineseLocaleEntryFile(project, file) ?: return null
        val psiFile = PsiManager.getInstance(project).findFile(entryFile) ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return null

        val offset = if (entryFile.extension?.lowercase() == "json") {
            // JSON 文件：先尝试按嵌套 key 逐级下钻定位 value，若失败则回退到文本搜索
            findJsonKeyOffset(doc.text, key) ?: findTextKeyOffset(doc, key) ?: return null
        } else {
            // TS/JS 文件：文本搜索
            findTextKeyOffset(doc, key) ?: return null
        }

        return I18nTranslationTargetElement(psiFile, offset)
    }

    override fun isReferenceTo(element: PsiElement): Boolean = resolve() == element
    override fun getCanonicalText(): String = key
    override fun isSoft(): Boolean = true

    override fun handleElementRename(newElementName: String): PsiElement {
        // 不处理重命名
        return element
    }

    override fun bindToElement(element: PsiElement): PsiElement = element
}

/**
 * 轻量目标 [PsiElement]，封装翻译文件及其偏移量。
 * 当用户 Ctrl+点击时，[navigate] 通过 [OpenFileDescriptor] 打开翻译文件并定位到目标行。
 */
class I18nTranslationTargetElement(
    private val psiFile: PsiFile,
    private val offset: Int,
) : PsiElementBase() {

    override fun getProject(): Project = psiFile.project
    override fun getParent(): PsiElement? = psiFile
    override fun getContainingFile(): PsiFile? = psiFile
    override fun getTextRange(): TextRange = TextRange.from(offset, 0)
    override fun getTextOffset(): Int = offset
    override fun getText(): String = ""
    override fun getTextLength(): Int = 0
    override fun isValid(): Boolean = psiFile.isValid && !project.isDisposed
    override fun getIcon(flags: Int): javax.swing.Icon? = psiFile.getIcon(flags)
    override fun getLanguage(): com.intellij.lang.Language = psiFile.language
    override fun getChildren(): Array<PsiElement> = PsiElement.EMPTY_ARRAY
    override fun getStartOffsetInParent(): Int = offset
    override fun findElementAt(offset: Int): PsiElement? = null
    override fun textToCharArray(): CharArray = charArrayOf()
    override fun getNode(): com.intellij.lang.ASTNode? = null

    override fun navigate(requestFocus: Boolean) {
        val vf = psiFile.virtualFile ?: return
        OpenFileDescriptor(project, vf, offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = psiFile.isValid && psiFile.virtualFile != null
    override fun canNavigateToSource(): Boolean = true
}

// ---------------------------------------------------------------------------
// 辅助函数：在翻译文件文本中定位 key 的 value 所在偏移
// ---------------------------------------------------------------------------

private val LOG = Logger.getInstance("I18nTranslationReference")

/**
 * 在 JSON 文本中按嵌套 key 路径（如 `button.text`）逐级下钻定位 value，
 * 返回该 value 在文本中的起始偏移。若未找到则返回 null。
 */
private fun findJsonKeyOffset(text: String, key: String): Int? {
    val parts = key.split(".")
    if (parts.isEmpty()) return null

    // 解析 JSON 树，沿路径下钻获取 value 字符串
    val value = try {
        val root = JsonParser.parseString(text)
        if (!root.isJsonObject) return null
        navigateJsonValue(root.asJsonObject, parts, 0) ?: return null
    } catch (e: Exception) {
        LOG.debug("findJsonKeyOffset: 解析 JSON 失败", e)
        return null
    }

    if (value.isBlank()) return null

    // 在原始文本中定位该 value 所在位置
    val escapedValue = Regex.escape(value)
    // 匹配 "value" 或 'value'（JSON 中 value 通常用双引号）
    val valuePattern = Regex(""""${escapedValue}"""")
    val matches = valuePattern.findAll(text).toList()

    if (matches.isEmpty()) return null
    if (matches.size == 1) return matches.first().range.first

    // 多个匹配时：选择前面最近有对应 key 的匹配
    val lastKey = parts.last()
    for (match in matches) {
        val before = text.substring(0, match.range.first)
        val contextStart = maxOf(0, before.length - 300)
        val recentContext = before.substring(contextStart)
        // 检查该 value 前是否有 "lastKey":
        if (Regex(""""${Regex.escape(lastKey)}"\s*:""").containsMatchIn(recentContext)) {
            return match.range.first
        }
    }

    return matches.first().range.first
}

/** 沿 JSON 对象路径逐级下钻，返回最终 value 的字符串值。 */
private fun navigateJsonValue(
    obj: com.google.gson.JsonObject,
    parts: List<String>,
    index: Int,
): String? {
    if (index >= parts.size) return null
    val child = obj.get(parts[index]) ?: return null
    return if (index == parts.size - 1) {
        // 末级：返回原始字符串值
        if (child.isJsonPrimitive) child.asString else null
    } else {
        // 中间级：必须是对象，继续下钻
        if (child.isJsonObject) navigateJsonValue(child.asJsonObject, parts, index + 1) else null
    }
}

/**
 * 文本搜索兜底：在翻译文件文档中查找 key 值所在行。
 * 支持 `'key'` / `"key"` / `` `key` `` 三种引号风格。
 * 返回该行的起始偏移，未找到时返回 null。
 */
private fun findTextKeyOffset(doc: com.intellij.openapi.editor.Document, key: String): Int? {
    val escapedKey = key.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
    val patterns = listOf("'$escapedKey'", "\"$escapedKey\"", "`$escapedKey`")
    val text = doc.text
    val lines = text.lines()
    val lineIdx = lines.indexOfFirst { line -> patterns.any { it in line } }
    return if (lineIdx >= 0) doc.getLineStartOffset(lineIdx) else null
}