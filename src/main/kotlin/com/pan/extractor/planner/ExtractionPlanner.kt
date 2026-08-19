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

    /**
     * 预构建骨架重写计划列表（纯数据描述：**只读取收集结果，不触碰 PSI、不改写任何文件**）。
     *
     * 覆盖两类合并：公共前后缀组（差分段可能是中文，需落 key）与数字抽取组（差分是数字字面量）。
     * 每个计划记录：目标 site、骨架文本、重新生成资源 key 用的 skeletonKey、以及**最终**差分
     * 占位表达式（已按该 site 的 Vue/React 形态渲染好，可直接喂给重写器）。
     *
     * 与原命令式预构建（MergeApplier ③）语义 1:1：
     *  - 完全相同文本的提示组（[AffixGroupCandidate.isExactDuplicate]）不产生骨架计划；
     *  - 差分段是目标语言（中文）→ 登记进 [diffKeys] 并渲染为 `$t('中文')`（用对应处理器的
     *    buildTExprForRawText 纯文本生成），否则渲染为字样面量（字符串/数字）；
     *  - 数字差分带前导零（0755）时加引号以字符串输出，避免破坏 JS 字面量。
     *
     * @param procs    参与合并的处理器集合（只读，用于解析 site 的 Vue/React 形态与生成差分表达式）。
     * @param diffKeys 差分为 target 语言的 key 登记表（等价 apply 侧 finalExtracted，惰性 putIfAbsent）。
     * @return 生成的骨架重写计划。
     */
    @JvmStatic
    fun buildRewritePlans(
        mergePlan: MergePlan,
        procs: List<com.pan.extractor.I18nProcessor>,
        diffKeys: MutableMap<String, String>,
    ): List<RewritePlan> {
        val plans = mutableListOf<RewritePlan>()
        for (g in mergePlan.selectedAffix) {
            if (g.isExactDuplicate) continue
            for (v in g.variants) for (ref in v.sites) {
                val proc = procs.getOrNull(ref.processorIndex) ?: continue
                val site = proc.collectedSites.firstOrNull { it.id == ref.siteId } ?: continue
                val diffKey = v.diff.trim()
                val diffWillBeKey = com.pan.extractor.Util.containsTargetLanguage(v.diff)
                if (diffWillBeKey) diffKeys.putIfAbsent(diffKey, v.diff)
                // 差分占位表达式：中文→`$t('中文')`（纯文本生成，无 PSI 副作用）；否则字面量
                val paramsExpr = if (diffWillBeKey) {
                    proc.buildTExprForRawText(v.diff, "{}", site.isVue, site.isReact)
                } else {
                    com.pan.extractor.planner.SkeletonPlanner.renderLiteralValue(v.diff)
                }
                plans += RewritePlan(
                    siteId = ref.siteId,
                    processorIndex = ref.processorIndex,
                    skeleton = g.skeleton,
                    skeletonKey = g.skeletonKey.trim().ifBlank { g.skeleton },
                    params = listOf("N0" to paramsExpr),
                )
            }
        }
        for (g in mergePlan.selectedDigit) {
            for (ps in g.perSites) {
                val ref = ps.site
                val proc = procs.getOrNull(ref.processorIndex) ?: continue
                val digitText = com.pan.extractor.planner.SkeletonPlanner.renderDigitLiteral(
                    ps.digitValues.firstOrNull() ?: "0"
                )
                plans += RewritePlan(
                    siteId = ref.siteId,
                    processorIndex = ref.processorIndex,
                    skeleton = g.skeleton,
                    skeletonKey = g.skeletonKey.trim().ifBlank { g.skeleton },
                    params = listOf("N0" to digitText),
                )
            }
        }
        return plans
    }
}