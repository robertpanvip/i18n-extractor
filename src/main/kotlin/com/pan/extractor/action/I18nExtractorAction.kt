package com.pan.extractor.action

import com.pan.extractor.messages.I18nExtractorBundle
import com.pan.extractor.locate.EntryFileLocator
import com.pan.extractor.orchestrator.ApplyOptions
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

    /**
     * 单文件写入：在【EDT】接受用户确认后启动后台写入任务。
     * 从 [launchAnalyzeThen] 传入已完成分析的 Collection。
     */
    private fun launchSingleWrite(
        project: Project,
        collection: Orchestrator.Collection,
    ) {
        val dialog = ExtractedStringsDialog(
            project, collection.extracted, collection.affixGroups, collection.digitGroups,
            contextPsiFile = collection.contextPsiFile
        )
        val confirmed = try {
            dialog.showAndGet()
        } catch (t: Throwable) {
            Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), t)
            false
        }
        if (confirmed) {
            try {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(
                        project,
                        I18nExtractorBundle.message("action.progress.single.writing"),
                        true
                    ) {
                        private var output: Orchestrator.OutputResult =
                            Orchestrator.OutputResult(copiedToClipboard = false, overwroteEntryFile = false)

                        override fun run(indicator: ProgressIndicator) {
                            indicator.isIndeterminate = false
                            indicator.text = I18nExtractorBundle.message("action.progress.single.writing.detail")
                            indicator.fraction = 0.1
                            output = Orchestrator.apply(
                                project, collection,
                                ApplyOptions(
                                    mergePlan = dialog.mergePlan,
                                    outputMode = dialog.outputMode,
                                    entryFile = dialog.selectedEntryFile,
                                    clipboardJson = dialog.json,
                                ),
                                indicator,
                            )
                            indicator.fraction = 1.0
                        }

                        override fun onSuccess() {
                            Orchestrator.notifyExtractSuccess(
                                project,
                                title = I18nExtractorBundle.message("action.progress.single.complete"),
                                extractedCount = collection.extracted.size,
                                processedFiles = 1,
                                output = output,
                            )
                        }

                        override fun onThrowable(error: Throwable) {
                            Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), error)
                        }
                    }
                )
            } catch (t: Throwable) {
                Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), t)
            }
        } else if (collection.extracted.isEmpty()) {
            Orchestrator.notifyNothingExtracted(project, I18nExtractorBundle.message("action.progress.single.scope"))
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 单文件 / 目录：先后台扫描分析（Task.Backgroundable）→ onSuccess 里弹 Dialog → 后台写入
    // ─────────────────────────────────────────────────────────────

    /**
     * 单个文件提取：先后台跑 Orchestrator.collectSingle（PSI 重分析不应阻塞 EDT）→
     * 分析结束后在 EDT 弹 Dialog → 用户确认再启动后台写入 Task。
     *
     * 旧实现：collectSingle 在 EDT 同步执行，分析大型 Vue/TSX 文件时 EDT 被占住
     * 整个 IDE 看起来"卡住 0%"（其实还没进入写入阶段）。
     */
    private fun processPsiFile(project: Project, psiFile: PsiFile) {
        if (isTranslationResource(psiFile)) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            I18nExtractorBundle.message("action.progress.single.analyzing"),
            true
        ) {
            private var collection: Orchestrator.Collection? = null
            private var err: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = I18nExtractorBundle.message("action.progress.single.parsing")
                try {
                    collection = Orchestrator.collectSingle(project, psiFile)
                } catch (t: Throwable) {
                    err = t
                }
            }

            override fun onSuccess() {
                val t = err
                if (t != null) {
                    Orchestrator.notifyNothingExtracted(
                        project,
                        I18nExtractorBundle.message("action.progress.single.scope")
                    )
                    return
                }
                val c = collection ?: return
                if (c.processors.isEmpty()) {
                    if (c.extracted.isEmpty()) Orchestrator.notifyNothingExtracted(project, I18nExtractorBundle.message("action.progress.single.scope"))
                    return
                }
                launchSingleWrite(project, c)
            }
        })
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
                result.add(child)
            }
        }
        return result
    }

    /**
     * 目录批量提取：
     *
     * 旧实现用 `runProcessWithProgressSynchronously(..., true, project)` 在 EDT
     * **同步阻塞等待**收集完成，ModalProgressManager 会把事件泵开到特定级别，但 EDT
     * 仍在该调用栈内"等待 future 完成"，一旦 collect 耗时长 + 调用栈被压，就表现为
     * 「进度条一直 0%、点取消无效」。
     *
     * 修复：统一改为 Task.Backgroundable（非模态后台）→ onSuccess 里弹 Dialog → 再后台写入。
     */
    private fun processDirectory(project: Project, dir: VirtualFile) {
        val files = collectSupportedFiles(dir)
        if (files.isEmpty()) return

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            I18nExtractorBundle.message("action.progress.dir.scan", files.size),
            true
        ) {
            private var collection: Orchestrator.Collection? = null
            private var err: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = I18nExtractorBundle.message("action.progress.dir.scanning", dir)
                indicator.fraction = 0.05
                indicator.checkCanceled()
                try {
                    indicator.text = I18nExtractorBundle.message("action.progress.dir.analyzing", files.size)
                    indicator.fraction = 0.2
                    collection = Orchestrator.collect(project, files, null, indicator)
                } catch (t: Throwable) {
                    err = t
                }
                indicator.fraction = 1.0
            }

            override fun onSuccess() {
                val t = err
                if (t != null) {
                    Orchestrator.notifyNothingExtracted(
                        project,
                        "${dir.presentableUrl}"
                    )
                    return
                }
                val c = collection ?: return
                if (c.processors.isEmpty()) {
                    Orchestrator.notifyNothingExtracted(project, "${dir.presentableUrl}")
                    return
                }
                launchDirWrite(project, c, dir)
            }
        })
    }

    /** 目录收集完成后，弹 Dialog → 确认后起写入 Task（与单文件写入完全相同的编排）。 */
    private fun launchDirWrite(
        project: Project,
        collection: Orchestrator.Collection,
        dir: VirtualFile,
    ) {
        val dialog = ExtractedStringsDialog(
            project, collection.extracted, collection.affixGroups, collection.digitGroups,
            contextPsiFile = collection.contextPsiFile
        )
        val confirmed = try {
            dialog.showAndGet()
        } catch (t: Throwable) {
            Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), t)
            false
        }
        if (confirmed) {
            try {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(
                        project,
                        I18nExtractorBundle.message("action.progress.dir.writing"),
                        true
                    ) {
                        private var output: Orchestrator.OutputResult =
                            Orchestrator.OutputResult(copiedToClipboard = false, overwroteEntryFile = false)

                        override fun run(indicator: ProgressIndicator) {
                            indicator.isIndeterminate = false
                            indicator.text = I18nExtractorBundle.message("action.progress.dir.writing.detail", collection.processors.size)
                            indicator.fraction = 0.0
                            output = Orchestrator.apply(
                                project, collection,
                                ApplyOptions(
                                    mergePlan = dialog.mergePlan,
                                    outputMode = dialog.outputMode,
                                    entryFile = dialog.selectedEntryFile,
                                    clipboardJson = dialog.json,
                                ),
                                indicator,
                            )
                            indicator.fraction = 1.0
                        }

                        override fun onSuccess() {
                            Orchestrator.notifyExtractSuccess(
                                project,
                                title = I18nExtractorBundle.message("action.progress.dir.complete"),
                                extractedCount = collection.extracted.size,
                                processedFiles = collection.fileCount,
                                output = output,
                            )
                        }

                        override fun onThrowable(error: Throwable) {
                            Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), error)
                        }
                    }
                )
            } catch (t: Throwable) {
                Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), t)
            }
        } else if (collection.extracted.isEmpty()) {
            Orchestrator.notifyNothingExtracted(project, "${dir.presentableUrl}")
        }
    }
}