package com.pan.extractor.resource

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.pan.extractor.planner.ResourcePlan

/**
 * Resource 层统一写回执行器（目标架构 Phase 4 —— 让 [ResourcePlan] 成为资源写回的数据契约）。
 *
 * 职责：
 *  > 动作 / MergeApplier 只**描述**资源写回答意图（组装 [ResourcePlan]），
 *  > 具体的"往哪个入口文件、用什么格式、合并/删除哪些 key"统一落在本执行器，避免
 *  > 三个动作各自复制一份 `when(ext)` 分发。
 *
 * 本执行器**只做写回计算与 VirtualFile 落盘**，不做 UI（progress/notification/clipboard
 * fallback 仍在动作层处理）—— 与旧行为逐分支 1:1。
 */
object ResourceApplier {

    /**
     * 把写回意图组装为 [ResourcePlan]。
     *
     * @param format 目标格式：`json` / `ts` / `tsx` / `js` / `jsx`。
     */
    fun buildPlan(
        entryVf: VirtualFile,
        entries: Map<String, String>,
        dropKeys: Set<String>,
    ): ResourcePlan = ResourcePlan(
        targetPath = entryVf.path,
        entries = entries,
        dropKeys = dropKeys,
        format = entryVf.extension?.lowercase() ?: "",
    )

    /**
     * 按计划执行入口资源文件写回，返回「待落盘文件 → 新内容」列表；失败/不支持返回 null。
     *
     *  - json：直接再生 JSON 文件；
     *  - ts/tsx/js/jsx：优先 spread 路由（把新 key 写进 `...common` 指向的文件），否则再生入口文件。
     */
    fun apply(project: Project, plan: ResourcePlan): List<Pair<VirtualFile, String>>? {
        val entryVf = LocalFileSystem.getInstance().findFileByPath(plan.targetPath) ?: return null
        return try {
            when (plan.format) {
                "json" -> com.pan.extractor.TsFileEditor
                    .regenerateJsonFileWithNewJson(entryVf, plan.entries, plan.dropKeys)
                    ?.let { listOf(entryVf to it) }
                "ts", "tsx", "js", "jsx" -> {
                    val spread = com.pan.extractor.TsFileEditor
                        .regenerateTsFileWithSpreadRouting(project, entryVf, plan.entries, plan.dropKeys)
                    if (spread != null) spread
                    else com.pan.extractor.TsFileEditor
                        .regenerateTsFileWithNewJson(project, entryVf, plan.entries, plan.dropKeys)
                        ?.let { listOf(entryVf to it) }
                }
                else -> null
            }
        } catch (t: Throwable) {
            null
        }
    }
}