package com.pan.extractor.orchestrator

import com.pan.extractor.ui.ExtractedStringsDialog.MergePlan
import com.pan.extractor.ui.OutputDestination
import com.intellij.openapi.vfs.VirtualFile

/**
 * 应用阶段的**纯数据输入** —— 取代把 Swing 对话框 [com.pan.extractor.ui.ExtractedStringsDialog]
 * 直接传进编排器（P2：编排器/本层不再认识 Swing 组件，只消费本 DTO 携带的、由对话框 UI 勾选的决策）。
 *
 * 字段全部是值类型：
 *  - [mergePlan]：用户确认的合并执行计划（勾选项，纯数据）；
 *  - [outputMode]：输出方式（写入口文件 / 剪贴板）；
 *  - [entryFile]：[outputMode] == FILE 时选中的中文入口文件；
 *  - [clipboardJson]：对话框已生成的 JSON 文本（回退剪贴板时使用；可为 null）。
 */
data class ApplyOptions(
    val mergePlan: MergePlan,
    val outputMode: OutputDestination,
    val entryFile: VirtualFile?,
    val clipboardJson: String? = null,
)