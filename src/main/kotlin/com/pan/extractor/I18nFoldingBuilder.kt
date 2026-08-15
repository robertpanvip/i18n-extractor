package com.pan.extractor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
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
 * - 折叠占位文本 = 翻译值，因此编辑器内 Ctrl+F 可直接搜到翻译文案。
 * - 带插值参数的调用（如 `$t('key', { n: 1 })`）同样折叠，仅展示 key 对应文案。
 * - 仅在指定语言资源中查得到 key 时才折叠，避免误折叠。
 */
class I18nFoldingBuilder : FoldingBuilderEx() {

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
        if (messages.isEmpty()) return FoldingDescriptor.EMPTY_ARRAY

        val descriptors = mutableListOf<FoldingDescriptor>()
        PsiTreeUtil.collectElementsOfType(root, JSCallExpression::class.java).forEach { call ->
            val key = extractKey(call) ?: return@forEach
            val value = messages[key] ?: return@forEach
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
        return messages[key]
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
}