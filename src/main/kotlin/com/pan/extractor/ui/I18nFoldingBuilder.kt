package com.pan.extractor.ui

import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.messages.LocaleMessages
import com.pan.extractor.*
import com.pan.extractor.analyzer.*

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
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
        val params = extractInterpolationParams(call, messages)
        return I18nFrameworkRegistry.detect(call).interpolatePlaceholders(rawValue, params) + TOGGLE_HINT
    }

    /** 为单个调用创建折叠描述符（若 key 在翻译资源中存在）。 */
    private fun addFoldingDescriptor(
        call: JSCallExpression,
        messages: Map<String, String>,
        descriptors: MutableList<FoldingDescriptor>,
    ) {
        val key = extractKey(call) ?: return
        val rawValue = messages[key] ?: return
        val params = extractInterpolationParams(call, messages)
        val value = I18nFrameworkRegistry.detect(call).interpolatePlaceholders(rawValue, params) + TOGGLE_HINT
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
    private fun extractKey(call: JSCallExpression): String? =
        I18nFrameworkRegistry.detect(call).extractKey(call)

    private fun isTranslationCall(call: JSCallExpression): Boolean =
        I18nFrameworkRegistry.detect(call).isTranslationCall(call)

    /**
     * 从 t() 调用的第二个参数（对象字面量）中提取插值参数映射，如 `{"0": "xxx"}` → `{"0": "xxx"}`。
     *
     * 支持两种参数值（因子化产物常见第二种）：
     *  - 字面量：`N0: '搜索关键词'` / `"0": 2`
     *  - 内层翻译调用：`N0: $t('搜索关键词')` —— 用该内层 key 的译文作为参数值，使
     *    骨架折叠出「请输入搜索关键词」这类完整文案。
     *
     * 参数键统一去前缀 `N`（Vue 生成的 `{N0}` 占位匹配数字索引 `0`）。
     */
    private fun extractInterpolationParams(call: JSCallExpression, messages: Map<String, String>): Map<String, String> {
        val secondArg = call.arguments.getOrNull(1) ?: return emptyMap()
        val text = secondArg.text
        if (text.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        // 字面量： "key": 'value' / "key": "value" / "key": 数字
        val re = Regex("""["']?(\w+)["']?\s*:\s*("[^"]*"|'[^']*'|-?\d+)""")
        re.findAll(text).forEach { match ->
            val key = match.groupValues[1].removePrefix("N")
            val rawValue = match.groupValues[2]
            val value = if (rawValue.startsWith("\"") || rawValue.startsWith("'"))
                rawValue.substring(1, rawValue.length - 1)
            else rawValue
            result[key] = value
        }
        // 内层翻译调用：N0: $t('innerKey') / N0: xxx.t('innerKey')
        val tRe = Regex("""["']?(\w+)["']?\s*:\s*(?:[\w.]+\s*\.\s*\$?t|\$?t)\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        tRe.findAll(text).forEach { match ->
            val key = match.groupValues[1].removePrefix("N")
            val innerKey = match.groupValues[2]
            // 用内层 key 的译文替换，使骨架折叠显示完整文案
            result[key] = messages[innerKey] ?: innerKey
        }
        return result
    }
}

// 注：占位符插值逻辑已迁移至 I18nFramework 策略
// （VueI18nStrategy / ReactI18nextStrategy / GenericStrategy.interpolatePlaceholders），
// 原 interpolatePlaceholders(value, params, isVue) 方法已删除。