package com.pan.extractor

import com.pan.extractor.orchestrator.I18nExtractionOrchestrator as Orchestrator
import com.pan.extractor.ui.*

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * 单文件 / 目录 i18n 提取入口。
 *
 * 本类只承担「触发参数解析 + 目录递归文件发现（Scanner 层）」，真正的流水线编排统一委托给
 * [Orchestrator]（Scanner → Analyzer → Planner → Validator → Rewriter → 原子 Apply），
 * 与 [AllI18nExtractorAction] 共享同一套编排逻辑，避免重复。
 */
class I18nExtractorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    // ─────────────────────────────────────────────────────────────
    // 触发条件
    // ─────────────────────────────────────────────────────────────
    private fun isSupportedFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".vue") ||
                lower.endsWith(".ts") ||
                lower.endsWith(".tsx") ||
                lower.endsWith(".js") ||
                lower.endsWith(".jsx")
    }

    /**
     * Bug 2：翻译资源文件（语言包）不应被提取或注入。
     * 典型：en-US.ts、locales/zh-CN.js、messages.ja.ts、src/i18n/en.ts 等。
     */
    private fun isTranslationResource(vf: VirtualFile): Boolean =
        EntryFileLocator.isTranslationResourceFile(vf)

    /** Bug 2 重载：PsiFile 版本（single-file 流程使用）。 */
    private fun isTranslationResource(psiFile: PsiFile): Boolean =
        EntryFileLocator.isTranslationResourceFile(psiFile)

    override fun update(e: AnActionEvent) {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        if (virtualFile.isDirectory) {
            e.presentation.isEnabledAndVisible = true
            return
        }

        // Bug 2: 翻译资源文件上禁用菜单
        if (isTranslationResource(virtualFile)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = isSupportedFile(virtualFile.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (virtualFile.isDirectory) {
            processDirectory(project, virtualFile)
        } else {
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
            processPsiFile(project, psiFile)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 单文件 / 目录：统一走 Orchestrator 编排
    // ─────────────────────────────────────────────────────────────

    /**
     * 单个文件提取：Scanner+Analyzer（Orchestrator.collectSingle）→ Dialog → 原子 Apply。
     */
    private fun processPsiFile(project: Project, psiFile: PsiFile) {
        // Bug 2（保险）：即便 update() 放过了，到这里仍要拦截语言包文件
        if (isTranslationResource(psiFile)) return

        val collection = Orchestrator.collectSingle(project, psiFile)
        if (collection.processors.isEmpty()) {
            if (collection.extracted.isEmpty()) Orchestrator.notifyNothingExtracted(project, "当前文件")
            return
        }

        val dialog = ExtractedStringsDialog(
            project, collection.extracted, collection.affixGroups, collection.digitGroups,
            contextPsiFile = collection.contextPsiFile
        )
        if (dialog.showAndGet()) {
            // 【问题 1 修复：写入过程加进度提示，避免点确定后"卡住没反馈"】
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(
                    project,
                    "i18n 提取单文件写入中…",
                    true
                ) {
                    private var output: Orchestrator.OutputResult =
                        Orchestrator.OutputResult(copiedToClipboard = false, overwroteEntryFile = false)

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        // —— 写入 PSI 替换（含合并计划应用），单 command 原子提交 ——
                        indicator.text = "写入中：替换硬编码中文 + 注入 i18n 导入/别名"
                        indicator.fraction = 0.1
                        output = Orchestrator.apply(project, collection, dialog, indicator)
                        indicator.fraction = 1.0
                    }

                    override fun onSuccess() {
                        Orchestrator.notifyExtractSuccess(
                            project,
                            title = "单文件国际化提取完成",
                            extractedCount = collection.extracted.size,
                            processedFiles = 1,
                            output = output,
                        )
                    }
                }
            )
        } else if (collection.extracted.isEmpty()) {
            Orchestrator.notifyNothingExtracted(project, "当前文件")
        }
    }

    /**
     * 递归收集文件夹内所有受支持的文件。
     */
    private fun collectSupportedFiles(dir: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        for (child in dir.children) {
            if (child.isDirectory) {
                result.addAll(collectSupportedFiles(child))
            } else if (isSupportedFile(child.name) && !isTranslationResource(child)) {
                // Bug 2: 目录批量扫描时直接排除翻译资源文件，避免后续被 Processor 处理
                result.add(child)
            }
        }
        return result
    }

    /**
     * 目录批量提取：递归发现文件 → Orchestrator.collect → Dialog → 原子 Apply。
     */
    private fun processDirectory(project: Project, dir: VirtualFile) {
        val files = collectSupportedFiles(dir)
        if (files.isEmpty()) return

        // 用模态进度框包裹收集阶段，避免文件过多时 UI 冻结（收集本身是纯 PSI 读）。
        val holder = arrayOfNulls<Orchestrator.Collection>(1)
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val indicator = ProgressManager.getInstance().progressIndicator
            indicator.isIndeterminate = false
            indicator.text = "Extracting i18n strings..."
            holder[0] = Orchestrator.collect(project, files, null)
        }, "I18n Extraction", true, project)
        val collection = holder[0] ?: return
        if (collection.processors.isEmpty()) {
            Orchestrator.notifyNothingExtracted(project, "目录 ${dir.presentableUrl}")
            return
        }

        val dialog = ExtractedStringsDialog(
            project, collection.extracted, collection.affixGroups, collection.digitGroups,
            contextPsiFile = collection.contextPsiFile
        )
        if (dialog.showAndGet()) {
            // 【问题 1 修复：目录写入阶段加进度条 + 逐文件反馈】单 command 原子写入。
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(
                    project,
                    "i18n 目录批量写入中…",
                    true
                ) {
                    private var output: Orchestrator.OutputResult =
                        Orchestrator.OutputResult(copiedToClipboard = false, overwroteEntryFile = false)

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        indicator.text = "批量写入中：处理 ${collection.processors.size} 个文件"
                        indicator.fraction = 0.0
                        output = Orchestrator.apply(project, collection, dialog, indicator)
                        indicator.fraction = 1.0
                    }

                    override fun onSuccess() {
                        Orchestrator.notifyExtractSuccess(
                            project,
                            title = "目录批量国际化提取完成",
                            extractedCount = collection.extracted.size,
                            processedFiles = collection.fileCount,
                            output = output,
                        )
                    }
                }
            )
        } else if (collection.extracted.isEmpty()) {
            Orchestrator.notifyNothingExtracted(project, "目录 ${dir.presentableUrl}")
        }
    }
}