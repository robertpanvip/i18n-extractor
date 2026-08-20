package com.pan.extractor.validator

import com.pan.extractor.model.ExtractionSite
import com.pan.extractor.planner.ImportPlan
import com.pan.extractor.planner.ResourcePlan
import com.pan.extractor.planner.RewritePlan
import com.intellij.openapi.vfs.VirtualFile

/** 单条 preflight 校验结果：错误码 + 人类可读描述。 */
data class PreflightIssue(
    val code: String,
    val message: String,
)

/**
 * 统一 Apply 前校验结果：全部 issue 为空才允许写入任何文件。
 *
 * `isValid == false` 时必须**零写入**（调用方在写出前 abort），避免留下
 * "部分文件已改、其余未改"的半完成状态（PROJECT_ANALYSIS §16）。
 */
data class PreflightResult(val issues: List<PreflightIssue>) {
    val isValid: Boolean get() = issues.isEmpty()
}

/**
 * ProjectPreflightValidator —— 统一 Apply 前 preflight（P0 A 组，§6 / §16）。
 *
 * 在写入任何文件之前，把 "Code Rewrite + Import + Resource" 三类修改作为一个完整
 * preflight 单元校验，任一类失效即整体失败（零写入）：
 *
 * ```
 * CollectedResult(RewritePlan) + ImportPlan + ResourcePlan
 *              ↓
 *      preflightValidate()
 *              ↓
 *  PSI pointer / Import target / Resource target 全部有效？
 *      ├── 是 ──► Apply
 *      └── 否 ──► 抛出 / 返回［零写入］
 * ```
 *
 * 与 [ChangeValidator]（仅校验合并计划的 site 指针）不同，本对象把普通 [RewritePlan]、
 * [ImportPlan]、[ResourcePlan] 统一纳入校验。文件解析（路径 → [VirtualFile]）通过注入的
 * map 传入，职责单一、可纯单元测试；实际 Apply 路径应先在写入前调用
 * [requireValidWithActualFiles]，收到 [IllegalStateException] 即中止整批写入。
 */
object ProjectPreflightValidator {

    /**
     * 校验三类修改目标，返回 [PreflightResult]（不抛异常，供调用方决定策略）。
     *
     * @param rewrites                全部待执行 [RewritePlan]。
     * @param sites                   收集期全部 [ExtractionSite]（用于按 siteId 定位目标）。
     * @param processorCount          处理器个数（[RewritePlan.processorIndex] 上界）。
     * @param importFiles             [ImportPlan] → 实际目标文件（null 表示无法解析路径）。
     * @param resourceFiles           [ResourcePlan] → 实际目标文件（null 表示无法解析路径）。
     */
    fun preflightValidate(
        rewrites: List<RewritePlan>,
        sites: List<ExtractionSite>,
        processorCount: Int,
        importFiles: Map<ImportPlan, VirtualFile?>,
        resourceFiles: Map<ResourcePlan, VirtualFile?>,
    ): PreflightResult {
        val issues = mutableListOf<PreflightIssue>()

        // ── A1：普通 RewritePlan 校验（processor 索引 / site 存在性 / 目标 pointer 有效性）──
        for (rp in rewrites) {
            if (rp.processorIndex < 0 || rp.processorIndex >= processorCount) {
                issues += PreflightIssue(
                    "REWRITE_PROCESSOR_IDX",
                    "RewritePlan[siteId=${rp.siteId}] 的 processor 索引 ${rp.processorIndex} 越界（共 $processorCount 个）"
                )
            }
            val site = sites.firstOrNull { it.id == rp.siteId }
            if (site == null) {
                issues += PreflightIssue(
                    "REWRITE_SITE_MISSING",
                    "RewritePlan[siteId=${rp.siteId}] 在 collectedSites 中缺失（可能已被外部清空）"
                )
            } else {
                val ptr = rp.target
                if (ptr == null) {
                    // 普通单站点改写一定有 target；无 target 说明计划不完整。
                    issues += PreflightIssue(
                        "REWRITE_TARGET_NULL",
                        "RewritePlan[siteId=${rp.siteId}] 缺少目标 pointer（计划不完整）"
                    )
                } else if (ptr.element == null || !ptr.element!!.isValid) {
                    issues += PreflightIssue(
                        "REWRITE_TARGET_INVALID",
                        "RewritePlan[siteId=${rp.siteId}] 的目标 PSI 已失效（文件可能已被外部修改）"
                    )
                }
            }
        }

        // ── A2：Import 校验（目标可解析 / 可写 / 无重复目标冲突）──
        val seenImportFiles = HashMap<String, String>()
        for ((plan, vf) in importFiles) {
            if (vf == null) {
                issues += PreflightIssue(
                    "IMPORT_TARGET_UNRESOLVED",
                    "ImportPlan[file=${plan.fileName}] 无法解析为目标文件"
                )
                continue
            }
            if (!vf.isWritable) {
                issues += PreflightIssue(
                    "IMPORT_TARGET_NOT_WRITABLE",
                    "ImportPlan[file=${plan.fileName}] 目标文件不可写"
                )
            }
            val prev = seenImportFiles.putIfAbsent(vf.path, plan.fileName)
            if (prev != null) {
                issues += PreflightIssue(
                    "IMPORT_TARGET_DUPLICATE",
                    "ImportPlan[file=${plan.fileName}] 与 ${prev} 同时写入同一文件 ${vf.path}（需合并或确认顺序）"
                )
            }
        }

        // ── A3：Resource 校验（目标可解析 / 可写）──
        for ((plan, vf) in resourceFiles) {
            if (vf == null) {
                issues += PreflightIssue(
                    "RESOURCE_TARGET_UNRESOLVED",
                    "ResourcePlan[target=${plan.targetPath}] 无法解析为目标文件"
                )
                continue
            }
            if (!vf.isWritable) {
                issues += PreflightIssue(
                    "RESOURCE_TARGET_NOT_WRITABLE",
                    "ResourcePlan[target=${plan.targetPath}] 目标文件不可写"
                )
            }
        }

        return PreflightResult(issues)
    }

    /**
     * 便捷入口：preflight 发现任何 issue 即抛出 [IllegalStateException]，调用方应在写入
     * 任何文件之前调用本方法以达成「失败零写入」。
     */
    fun requireValidWithActualFiles(
        rewrites: List<RewritePlan>,
        sites: List<ExtractionSite>,
        processorCount: Int,
        importFiles: Map<ImportPlan, VirtualFile?>,
        resourceFiles: Map<ResourcePlan, VirtualFile?>,
    ) {
        val result = preflightValidate(rewrites, sites, processorCount, importFiles, resourceFiles)
        if (!result.isValid) {
            throw IllegalStateException(
                "Apply 前 preflight 校验失败（未写入任何文件）—— ${result.issues.size} 处问题：\n" +
                    result.issues.joinToString("\n") { "  [${it.code}] ${it.message}" }
            )
        }
    }
}