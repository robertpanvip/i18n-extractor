package com.pan.extractor.ui

import com.intellij.lang.ASTNode
import com.intellij.lang.HTMLLanguage
import com.intellij.lang.folding.FoldingBuilder
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.folding.LanguageFolding
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/**
 * Vue 宿主文件（.vue 顶层，语言 Vue）的复合折叠构建器。
 *
 * ## 为什么需要它
 * IntelliJ 平台按语言分发折叠时（[LanguageFolding.allForLanguage]）沿语言链
 * （Vue → HTML → XML）**只取首个注册了 foldingBuilder 的语言**：
 * 若直接把 [I18nFoldingBuilder] 注册到 Vue，会遮蔽 XML 链上的原生 XmlFoldingBuilder，
 * 导致 template 内 div / 标签等结构折叠全部失效（JS 语言不受影响，因为它原生已有
 * JSFoldingBuilder，多个 builder 会被平台合成 CompositeFoldingBuilder）。
 *
 * ## 合并策略
 *  - 结构折叠（div 等）委托原生 builder：查询 [HTMLLanguage]（Vue 的 base 链），
 *    等价于未注册本扩展时 `forLanguage(Vue)` 沿链命中的结果（XML 的 XmlFoldingBuilder）；
 *  - `$t()/t()` 翻译折叠复用 [I18nFoldingBuilder]，其 descriptor 在构造时硬编码
 *    `collapsedByDefault=true`，不经过本类的 [isCollapsedByDefault] 委托；
 *  - 相同 range 去重，原生结构折叠优先。
 *
 * [isCollapsedByDefault] / [getPlaceholderText] 只会作用于未硬编码状态的 descriptor
 * （即原生结构折叠），因此全部委托原生 builder，保证 div 折叠的默认展开状态与
 * 占位文本（如 "<div> …"）与原生行为完全一致。
 */
class VueHostFoldingBuilder : FoldingBuilderEx() {

    private val i18n = I18nFoldingBuilder()

    /**
     * 原生结构折叠 builder：等价于未注册本扩展时 forLanguage(Vue) 沿
     * HTML → XML 链命中的首个 builder（XmlFoldingBuilder）。
     * [LanguageExtension.forLanguage] 自带按语言实例的缓存，重复查询开销可忽略。
     */
    private fun nativeStructureBuilder(): FoldingBuilder? =
        LanguageFolding.INSTANCE.forLanguage(HTMLLanguage.INSTANCE)

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val byRange = LinkedHashMap<TextRange, FoldingDescriptor>()
        val native = nativeStructureBuilder()
        if (native != null) {
            for (descriptor in buildNativeRegions(native, root, document, quick)) {
                byRange.putIfAbsent(descriptor.range, descriptor)
            }
        }
        for (descriptor in i18n.buildFoldRegions(root, document, quick)) {
            byRange.putIfAbsent(descriptor.range, descriptor)
        }
        return byRange.values.toTypedArray()
    }

    private fun buildNativeRegions(
        builder: FoldingBuilder,
        root: PsiElement,
        document: Document,
        quick: Boolean,
    ): Array<FoldingDescriptor> = when (builder) {
        is FoldingBuilderEx -> builder.buildFoldRegions(root, document, quick)
        else -> root.node?.let { builder.buildFoldRegions(it, document) } ?: FoldingDescriptor.EMPTY_ARRAY
    }

    /** 原生结构折叠的默认状态（div 等默认展开），交由原生 builder 判断。 */
    override fun isCollapsedByDefault(node: ASTNode): Boolean =
        nativeStructureBuilder()?.isCollapsedByDefault(node) ?: false

    override fun getPlaceholderText(node: ASTNode): String? =
        nativeStructureBuilder()?.getPlaceholderText(node)

    override fun getPlaceholderText(node: ASTNode, range: TextRange): String? {
        val native = nativeStructureBuilder() ?: return null
        return if (native is FoldingBuilderEx) native.getPlaceholderText(node, range)
        else native.getPlaceholderText(node)
    }
}
