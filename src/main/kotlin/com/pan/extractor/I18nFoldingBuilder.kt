package com.pan.extractor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/**
 * $t() 折叠：
 * 把 `$t('key')` / `t('key')`（含 Vue 的 `{{ $t('key') }}` 脚本表达式、React 的 `t('key')`）
 * 折叠为指定语言（[I18nSettings.foldDisplayLanguage]）的翻译文案。
 *
 * - 折叠占位文本 = 翻译值，编辑器内 Ctrl+F 可直接搜到翻译文案。
 * - 带插值参数的调用会将 {N0}/{0} 等占位符替换为实际参数值，同时支持 Vue（{N0}）和 React（{0}）格式。
 * - 仅在指定语言资源中查得到 key 时才折叠，避免误折叠。
 */
class I18nFoldingBuilder : FoldingBuilderEx() {

    companion object {
        /** 折叠占位文本末尾的折叠切换提示符，提醒用户此处可展开。 */
        const val TOGGLE_HINT = " \u21A9"
    }

    private val logger = Logger.getInstance(I18nFoldingBuilder::class.java)

    init {
        // 类加载时输出，用于确认 FoldingBuilder 是否被 IDE 实例化
        logger.warn("I18nFoldingBuilder 类已加载（实例化时触发）")
    }

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val project = root.project ?: return FoldingDescriptor.EMPTY_ARRAY
        // quick=true 表示 IntelliJ 在快速输入/滚动时请求，期望尽快返回；
        // 此时跳过全量 PSI 遍历与翻译加载，避免输入卡顿（非 quick 时再完整计算）。
        if (quick) return FoldingDescriptor.EMPTY_ARRAY
        val containingFile = root.containingFile ?: return FoldingDescriptor.EMPTY_ARRAY
        // 对注入代码（如 Vue 模板插值 {{ $t('x') }}）折叠时，root 是注入片段；
        // 翻译入口需基于其所属的顶层源文件定位，故映射回宿主文件。
        val contextFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(containingFile) ?: containingFile
        val messages = LocaleMessages.loadCached(project, contextFile)
        if (messages.isEmpty()) {
            logger.warn("I18nFoldingBuilder: 未找到翻译文件，跳过折叠。文件=${contextFile.name} displayLang=${I18nSettings.getInstance().foldDisplayLanguage()}")
            return FoldingDescriptor.EMPTY_ARRAY
        }

        val descriptors = mutableListOf<FoldingDescriptor>()
        PsiTreeUtil.collectElementsOfType(root, JSCallExpression::class.java).forEach { call ->
            addFoldingDescriptor(call, messages, descriptors)
        }
        return descriptors.toTypedArray()
    }

    /** 打开文件时 $t() 调用默认全部折叠，便于直接看到翻译文案。 */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = true

    /** 兜底占位文本：descriptor 已在构造时携带占位文本，此方法通常不会被调用。 */
    override fun getPlaceholderText(node: ASTNode): String? {
        val psi = node.psi ?: return null
        val call = psi as? JSCallExpression ?: return null
        val project = call.project ?: return null
        val messages = LocaleMessages.loadCached(project, call.containingFile)
        val key = extractKey(call) ?: return null
        val rawValue = messages[key] ?: return null
        val params = extractInterpolationParams(call)
        return interpolatePlaceholders(rawValue, params) + TOGGLE_HINT
    }

    /** 为单个调用创建折叠描述符（若 key 在翻译资源中存在）。 */
    private fun addFoldingDescriptor(
        call: JSCallExpression,
        messages: Map<String, String>,
        descriptors: MutableList<FoldingDescriptor>,
    ) {
        val key = extractKey(call) ?: return
        val rawValue = messages[key] ?: return
        val params = extractInterpolationParams(call)
        val value = interpolatePlaceholders(rawValue, params) + TOGGLE_HINT
        val range = call.textRange
        if (!range.isEmpty()) {
            descriptors.add(
                FoldingDescriptor(
                    call.node,
                    TextRange(range.startOffset, range.endOffset),
                    null,
                    value
                )
            )
        }
    }

    /** 从 `$t('key')` / `t('key')` / `xxx.t('key')` 调用中提取 key；非翻译调用返回 null。 */
    private fun extractKey(call: JSCallExpression): String? {
        if (!isTranslationCall(call)) return null
        val firstArg = call.arguments.firstOrNull() as? JSLiteralExpression ?: return null
        return firstArg.stringValue?.takeIf { it.isNotBlank() }
    }

    private fun isTranslationCall(call: JSCallExpression): Boolean {
        val method = call.methodExpression
        if (method is JSReferenceExpression) {
            val name = method.referenceName
            if (name == "\$t" || name == "t" || name == "\$tc" || name == "tc") return true
            return false
        }
        // 链式调用：xxx.t('key') / xxx.$t('key')
        val calleeText = method?.text ?: return false
        val last = calleeText.substringAfterLast('.')
        return last == "t" || last == "\$t" || last == "tc" || last == "\$tc"
    }

    /** 从 t() 调用的第二个参数（对象字面量）中提取插值参数映射，如 `{"0": "xxx"}` → `{"0": "xxx"}`。 */
    private fun extractInterpolationParams(call: JSCallExpression): Map<String, String> {
        val secondArg = call.arguments.getOrNull(1) ?: return emptyMap()
        val text = secondArg.text
        if (text.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        // 匹配 "key": 'value' / "key": "value" / "key": 数字
        val re = Regex("""["']?(\w+)["']?\s*:\s*("[^"]*"|'[^']*'|-?\d+)""")
        re.findAll(text).forEach { match ->
            val key = match.groupValues[1]
            val rawValue = match.groupValues[2]
            val value = if (rawValue.startsWith("\"") || rawValue.startsWith("'"))
                rawValue.substring(1, rawValue.length - 1)
            else rawValue
            result[key] = value
        }
        return result
    }

    /** 将翻译值中的占位符替换为实际参数值。同时支持 {N0}（Vue）和 {0}（React）两种格式。 */
    private fun interpolatePlaceholders(value: String, params: Map<String, String>): String {
        if (params.isEmpty()) return value
        var result = value
        val re = Regex("""\{N?(\d+)\}""")
        re.findAll(result).forEach { match ->
            val index = match.groupValues[1]
            val replacement = params[index] ?: return@forEach
            result = result.replace(match.value, replacement)
        }
        return result
    }
}