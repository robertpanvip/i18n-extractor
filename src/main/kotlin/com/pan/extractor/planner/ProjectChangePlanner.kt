package com.pan.extractor.planner

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.core.ImportManager
import com.pan.extractor.model.ExtractionSite
import com.pan.extractor.resource.ResourceApplier
import com.pan.extractor.validator.ProjectPreflightValidator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * ProjectChangePlanner —— 把「Code + Import + Resource」三类修改汇成一个统一 [ChangePlan]，
 * 并在**写入任何文件之前**跑一次统一的 preflight（P0 A 组 A4，PROJECT_ANALYSIS §6 / §16）。
 *
 * 目标架构（Planner → Validator）：
 * ```
 * processors(collected) + entryVf + finalExtracted + dropKeys
 *              ↓
 *   ChangePlan(rewrites + importPlans + resourcePlans)
 *              ↓
 *   ProjectChangePlanner.plan()   ← 纯数据组装 + 解析目标文件
 *              ↓
 *   plan.preflightOrThrow()       ← 失败抛异常 ⇒ 调用方在写任何文件前 abort（零写入）
 * ```
 *
 * 这是 A1/A2/A3 的统一入口：不再是「合并计划走 ChangeValidator、普通改写/import/resource
 * 各自散落校验」，而是把全部 [RewritePlan] + [ImportPlan] + [ResourcePlan] 作为一个整体
 * preflight 单元，任一类失效即整体失败。解析文件（计划 → [VirtualFile]）在组装阶段完成，
 * 不持有可变 PSI 状态，可纯单元测试。
 *
 * 与 [com.pan.extractor.validator.ChangeValidator]（仅校验合并计划 site 指针）互补：
 * 本对象覆盖普通改写、import 目标、resource 目标的全量校验。
 */
object ProjectChangePlanner {

    /** 统一变更计划：一次 Apply 要写什么（Code 改写 + Import 注入 + Resource 写回）的聚合。 */
    data class ChangePlan(
        /** 全部站点改写配方（普通替换 + 骨架合并）。 */
        val rewrites: List<RewritePlan>,
        /** 全部 import/注入计划 → 目标文件（null 表示不可解析）。 */
        val importPlans: Map<ImportPlan, VirtualFile?>,
        /** 全部资源写回计划 → 目标文件（null 表示不可解析）。 */
        val resourcePlans: Map<ResourcePlan, VirtualFile?>,
        /** 收集期全部站点（供按 siteId 反查 target）。 */
        val sites: List<ExtractionSite>,
        /** 处理器个数（[RewritePlan.processorIndex] 上界）。 */
        val processorCount: Int,
    ) {
        /**
         * 写入前的统一 preflight：任何 issue 立即抛 [IllegalStateException]（零写入）。
         * Apply 路径在进入 WriteCommandAction / 写任何文件之前调用。
         */
        fun preflightOrThrow() {
            ProjectPreflightValidator.requireValidWithActualFiles(
                rewrites = rewrites,
                sites = sites,
                processorCount = processorCount,
                importFiles = importPlans,
                resourceFiles = resourcePlans,
            )
        }

        /** 只读 preflight：不抛异常，返回校验结果供调用方决定策略。 */
        fun preflight() = ProjectPreflightValidator.preflightValidate(
            rewrites = rewrites,
            sites = sites,
            processorCount = processorCount,
            importFiles = importPlans,
            resourceFiles = resourcePlans,
        )
    }

    /**
     * 组装统一变更计划，并把 import / resource 计划解析为实际 [VirtualFile]。
     *
     * 只读逻辑（不写 PSI）：import 决策经由 [ImportPlanner.buildImportPlan]（Planner 层纯决策）
     * 求出每个处理器的 [ImportPlan]；resource 计划经 [ResourceApplier.buildPlan] 组装；
     * [RewritePlan] 直接取自收集期产物。解析失败的计划以 null 标记（由 preflight 报错）。
     *
     * @param processors      已 collect() 的处理器（每源文件一个）。
     * @param sites           收集期全部站点（重算保证与快照一致）。
     * @param rewrites        全部待执行改写（普通 + 骨架）。
     * @param processorCount  处理器个数。
     * @param entryVf         入口资源文件（null → 无 resource 计划，走剪贴板）。
     * @param finalExtracted  最终翻译资源（key → value）。
     * @param dropKeys        被骨架承载、应删除的历史整句 key。
     */
    fun plan(
        processors: List<I18nProcessor>,
        sites: List<ExtractionSite>,
        rewrites: List<RewritePlan>,
        processorCount: Int,
        entryVf: VirtualFile?,
        finalExtracted: Map<String, String>,
        dropKeys: Set<String>,
    ): ChangePlan {
        // ── Import：逐处理器收集注入计划 → 目标文件 ──
        val importPlans = LinkedHashMap<ImportPlan, VirtualFile?>()
        for (proc in processors) {
            val plan = buildImportPlanFor(proc) ?: continue
            val vf = proc.rootElement.containingFile?.virtualFile
            importPlans[plan] = vf
        }

        // ── Resource：组装写回计划 → 目标文件 ──
        val resourcePlans: Map<ResourcePlan, VirtualFile?> = if (entryVf != null) {
            val rp = ResourceApplier.buildPlan(entryVf, finalExtracted, dropKeys)
            mapOf(rp to (LocalFileSystem.getInstance().findFileByPath(rp.targetPath) ?: entryVf))
        } else {
            emptyMap()
        }

        return ChangePlan(
            rewrites = rewrites,
            importPlans = importPlans,
            resourcePlans = resourcePlans,
            sites = sites,
            processorCount = processorCount,
        )
    }

    /** 便捷入口：组装 + 立即 preflight（失败即抛，零写入）。 */
    fun planAndPreflight(
        processors: List<I18nProcessor>,
        sites: List<ExtractionSite>,
        rewrites: List<RewritePlan>,
        processorCount: Int,
        entryVf: VirtualFile?,
        finalExtracted: Map<String, String>,
        dropKeys: Set<String>,
    ): ChangePlan {
        val cp = plan(processors, sites, rewrites, processorCount, entryVf, finalExtracted, dropKeys)
        cp.preflightOrThrow()
        return cp
    }

    /**
     * 收集处理器快照状态，经 [ImportPlanner.buildImportPlan] 求出其 [ImportPlan]（纯决策，不写 PSI）。
     * 无可注入内容 / 无有效文件时返回 null（本计划无需 preflight import 条目）。
     */
    private fun buildImportPlanFor(proc: I18nProcessor): ImportPlan? {
        val psi = proc.rootElement ?: return null
        val file = psi.containingFile ?: (psi as? com.intellij.psi.PsiFile) ?: return null
        val result = proc.analyzer.snapshot()
        if (!result.needInjectGlobalDollarT && !result.reactI18nTFallbackToDollarT &&
            result.extractedStrings.isEmpty() && result.existingStrings.isEmpty()
        ) {
            return null
        }
        return ImportPlanner.buildImportPlan(
            processor = proc,
            psiFile = file,
            framework = result.framework,
            decision = ImportManager.InjectionDecision(
                needInjectGlobalDollarT = result.needInjectGlobalDollarT,
                reactI18nTFallbackToDollarT = result.reactI18nTFallbackToDollarT,
                tFunctionName = result.tFunctionName,
                hasExtractedStrings = result.extractedStrings.isNotEmpty(),
                hasExistingStrings = result.existingStrings.isNotEmpty(),
            ),
        )
    }
}