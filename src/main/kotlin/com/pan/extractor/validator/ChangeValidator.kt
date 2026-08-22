package com.pan.extractor.validator

import com.pan.extractor.messages.I18nExtractorBundle
import com.pan.extractor.ui.*

import com.pan.extractor.ui.ExtractedStringsDialog
import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.SiteRef

/**
 * ChangeValidator —— 应用前完整性校验（目标架构 Validator 层）。
 *
 * 职责：在**写入任何文件之前**确认所有将被改写 / 将作为骨架重写目标的 site 所指向的
 * [com.pan.extractor.model.ExtractionSite.replaceRoot] 仍然有效。任何被选中的合并计划引用的
 * site 失效（文件已被外部修改 / PSI 失效）时，抛出 [IllegalStateException]，
 * 调用方可在写入任何文件之前中止整批操作，避免留下"部分文件已改、其余未改"的半完成状态。
 *
 * 迁移自 [com.pan.extractor.merge.MergeApplier.validateAllModifiableSites]（原实现保留为委托，
 * 行为 1:1 不变）。
 */
object ChangeValidator {

    /**
     * 校验全部待改写站点。
     *
     * @throws IllegalStateException 存在失效站点时抛出，message 列出 FQN 描述。
     */
    @JvmStatic
    fun validateAllModifiableSites(
        processors: List<I18nProcessor>,
        mergePlan: ExtractedStringsDialog.MergePlan,
    ) {
        val invalid = mutableListOf<String>()
        fun checkSite(ref: SiteRef) {
            val proc = processors.getOrNull(ref.processorIndex)
            if (proc == null) {
                invalid += "${ref.originalMessage}@${ref.containingFile?.name}（processor 索引 ${ref.processorIndex} 缺失）"
                return
            }
            val site = proc.analyzer.collectedSites.firstOrNull { it.id == ref.siteId }
            if (site == null) {
                invalid += "${ref.originalMessage}@${ref.containingFile?.name}（site ${ref.siteId} 缺失）"
                return
            }
            val el = site.replaceRootPointer?.element
            if (el == null || !el.isValid) {
                invalid += "${ref.originalMessage}@${ref.containingFile?.name}（替换目标已失效）"
            }
        }
        for (g in mergePlan.selectedAffix) {
            if (g.isExactDuplicate) continue
            for (v in g.variants) for (ref in v.sites) checkSite(ref)
        }
        for (g in mergePlan.selectedDigit) {
            for (ps in g.perSites) checkSite(ps.site)
        }
        if (invalid.isNotEmpty()) {
            throw IllegalStateException(
                I18nExtractorBundle.message("change.validator.invalid.sites", invalid.size, invalid.joinToString("\n"))
            )
        }
    }
}
