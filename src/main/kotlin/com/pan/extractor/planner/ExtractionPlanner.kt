package com.pan.extractor.planner

import com.pan.extractor.ui.*
import com.pan.extractor.ui.ExtractedStringsDialog.MergePlan

/**
 * Planner 层 —— 把收集结果 + 用户勾选转换为「计划」（目标架构 Phase 4）。
 *
 * 职责（PROJECT_ANALYSIS §4）：
 * > 分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。
 *
 * 本对象为上层的[一次"被合并承载 → 阻塞单句替换 + 清理冗余 key"决策]提供纯函数，
 * 全部输入输出皆值类型，不接触 PSI、不触发任何写操作 —— 便于在 WriteCommandAction 之外
 * 独立测试，也是后续把所有 apply 决策统一收敛到 [ExtractionPlan] 的第一步。
 */
object ExtractionPlanner {

    /**
     * 计算"被合并承载"的 siteId 集合（对应 [ExtractionPlan.blockedSiteIds]）。
     *
     * 被合并承载的句子不走普通 `$t('整句')` 单句替换，而由骨架重写承载：
     *  - 公共前后缀组：每组每个差异段的每个 site（跨文件）都进集合；
     *  - 数字抽取组：每个 site 都进集合；
     *  - 「完全相同文本」的提示组（[AffixGroupCandidate.isExactDuplicate]）骨架里没有 `{N0}`
     *    占位，勾选也不做骨架重写，其站点本就走普通 `$t('全选')` 替换，因此**不进**集合，
     *    以免自引用。
     *
     * 该集合同时驱动两件事：apply 阶段跳过这些 site 的普通单句替换（填 blockedSiteIds），
     * 以及写回资源时判定某原句是否被完全承载从而删除其整句 key。
     */
    fun computeBlockedSiteIds(mergePlan: MergePlan): Set<String> {
        val blocked = LinkedHashSet<String>()
        for (g in mergePlan.selectedAffix) {
            if (g.isExactDuplicate) continue
            for (v in g.variants) for (ref in v.sites) blocked.add(ref.siteId)
        }
        for (g in mergePlan.selectedDigit) {
            for (ps in g.perSites) blocked.add(ps.site.siteId)
        }
        return blocked
    }

    /**
     * 计算「被完全合并承载」的**整句原句 trim 文本**集合（对应资源清理：写回入口文件 / 整理
     * finalExtracted 时删除这些整句 key，Bug：zh.ts 整句 key 与骨架 key 重复）。
     *
     * 以「站点」粒度判定，而不是按文本值：只有某个原句的**所有**命中站点都进了
     * [blockedSiteIds]（被合并承载）时，该句才是真正冗余的；若仍存在未被合并的独立站点
     * （同名文本），其 key 必须保留（MergeApplier ⑤ 语义 1:1）。
     *
     * @param messageToSiteIds 原句 trim → 命中该句的所有 siteId（由调用方从 collectedSites 聚拢）。
     */
    fun computeFullyConsumedMessages(
        messageToSiteIds: Map<String, Set<String>>,
        blockedSiteIds: Set<String>,
    ): Set<String> =
        messageToSiteIds
            .filterValues { ids -> ids.isNotEmpty() && ids.all { it in blockedSiteIds } }
            .keys
}