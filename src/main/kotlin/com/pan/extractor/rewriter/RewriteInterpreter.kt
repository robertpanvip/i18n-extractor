package com.pan.extractor.rewriter

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttributeValue
import com.pan.extractor.I18nProcessor
import com.pan.extractor.MergeApplier
import com.pan.extractor.planner.ExtractionPlan
import com.pan.extractor.planner.RewriteKind
import com.pan.extractor.planner.RewritePlan

/**
 * Rewriter 层 —— 统一解释器：把 [ExtractionPlan] 里的纯数据 [RewritePlan] 执行落地。
 *
 * 取代旧的 `pendingChanges / CollectedChange`（siteId + 闭包）双数据流：
 *  - 收集期（Analyzer / JsStringCollector）不再包闭包，只产数据配方 [RewritePlan]；
 *  - Apply 阶段唯一的执行入口是 [applyPlan] / [executeProcessor]，按 [RewritePlan.kind]
 *    派发到对应的 PSI 写入原语（[VueRewriter] / [JsRewriter] / 骨架重写）。
 * 这样 Apply 阶段只有一个数据源（计划），不再有「计划 + 闭包」两条并行输入。
 */
object RewriteInterpreter {

    /**
     * 执行一个跨文件统一计划（MergeApplier 用）：普通替换 + 骨架合并全部按数据配方执行。
     *
     *  - 非 SKELETON 站点命中 [ExtractionPlan.blockedSiteIds]（被骨架合并承载）时跳过，
     *    普通 `$t` 单句替换不再触碰该 site；
     *  - SKELETON 站点**总是执行**（它们正是合并承载的消费者，重写为带 `{N0}` 的 `$t` 调用）。
     *
     * @param onRewrite 可选的 EDT 执行器（progress 场景用 `invokeAndWait { … }` 包裹每次改写）；
     *                  null 时同步执行（单 command 原子 / 单元测试）。
     */
    fun applyPlan(
        processors: List<I18nProcessor>,
        plan: ExtractionPlan,
        finalExtracted: MutableMap<String, String>,
        indicator: ProgressIndicator? = null,
        onRewrite: ((() -> Unit) -> Unit)? = null,
    ) {
        val total = plan.rewrites.size
        plan.rewrites.forEachIndexed { idx, rw ->
            if (rw.kind != RewriteKind.SKELETON && rw.siteId in plan.blockedSiteIds) return@forEachIndexed
            indicator?.let {
                it.text = "应用站点改写 ${idx + 1}/$total"
                it.checkCanceled()
            }
            val run: () -> Unit = { dispatch(rw, processors, plan.blockedSiteIds, finalExtracted) }
            if (onRewrite != null) onRewrite(run) else run()
        }
    }

    /**
     * 执行单个 processor 自己的改写配方（单文件 apply / MergeApplier ② 的逐文件常规写入）。
     * 不处理骨架（骨架由 MergeApplier 统一向 [applyPlan] 提供）；命中本 analyzer 的
     * [com.pan.extractor.analyzer.I18nAnalyzer.blockedSiteIds] 时跳过。
     */
    fun executeProcessor(proc: I18nProcessor) {
        val blocked = proc.analyzer.blockedSiteIds
        for (rw in proc.analyzer.rewrites) {
            if (rw.siteId in blocked) continue
            dispatch(rw, listOf(proc), blocked, java.util.HashMap())
        }
    }

    /** 按 [kind] 派发到对应的 PSI 写入原语。 */
    private fun dispatch(
        rw: RewritePlan,
        processors: List<I18nProcessor>,
        blockedSiteIds: Set<String>,
        finalExtracted: MutableMap<String, String>,
    ) {
        when (rw.kind) {
            RewriteKind.XML_TEXT -> {
                val nodes = rw.xmlTextPointers.mapNotNull { it.element }
                if (nodes.isNotEmpty()) VueRewriter.rewriteXmlTextNodes(nodes, rw.newExpression)
            }

            RewriteKind.XML_ATTRIBUTE -> {
                val target = rw.target?.element as? XmlAttributeValue ?: return
                VueRewriter.rewriteAttribute(target, rw.newExpression, rw.isJSX, rw.isDirective, rw.isAngular)
            }

            RewriteKind.JS_TEMPLATE -> {
                val target = rw.target?.element ?: return
                JsRewriter.rewriteWithStringNode(target, rw.newExpression)
            }

            RewriteKind.JS_LITERAL -> {
                val target = rw.target?.element ?: return
                JsRewriter.rewriteLiteral(target, rw.newExpression, target.project)
            }

            RewriteKind.SKELETON -> {
                val proc = processors.getOrNull(rw.processorIndex) ?: return
                val site = proc.analyzer.collectedSites.firstOrNull { it.id == rw.siteId } ?: return
                val root = site.replaceRootPointer?.element ?: return
                if (!root.isValid) return
                MergeApplier.rewriteSiteToSkeleton(
                    rootPsi = root,
                    site = site,
                    skeletonValue = rw.skeleton.orEmpty(),
                    skeletonKey = rw.skeletonKey.orEmpty().ifBlank { rw.skeleton.orEmpty() },
                    paramPairs = rw.params,
                    proc = proc,
                    finalExtracted = finalExtracted,
                )
            }
        }
    }
}