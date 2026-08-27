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
import com.intellij.openapi.application.ApplicationManager
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
                    // 分析异常应上报「内部错误」（含堆栈文案），不能伪装成「未发现中文」
                    Orchestrator.notifyInternalError(
                        project,
                        I18nExtractorBundle.message("orchestrator.notify.internal.title"),
                        t
                    )
                    return
                }
                val c = collection ?: return
                if (c.processors.isEmpty() || c.extracted.isEmpty()) {
                    Orchestrator.notifyNothingExtracted(project, I18nExtractorBundle.message("action.progress.single.scope"))
                    return
                }
                // 【进度条显示 2 次修复】onSuccess 时本 Task 的进度窗口（ProgressWindow）
                // 的关闭事件还排在 EDT 队列里；若在这里同步 showAndGet() 模态对话框，
                // 事件泵切到 dialog 级别，进度窗口的关闭被延迟 → 扫描进度条挂住，
                // 之后写入 Task 又弹一个进度条 → 用户看到 2 个进度条。
                // 用 invokeLater 把弹窗推迟一拍，让进度窗口先正常关闭。
                ApplicationManager.getApplication().invokeLater {
                    launchSingleWrite(project, c)
                }
            }
        })
    }

    /**
     * 递归收集文件夹内所有受支持的文件。
     *
     * 目录递归时应用 [I18nSettings.excludeDirs]（默认含 node_modules/.git/dist 等构建产物），
     * 否则会递归进 node_modules 等目录并把海量 .js 依赖源码纳入提取范围。
     */
    private fun collectSupportedFiles(dir: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        for (child in dir.children) {
            if (child.isDirectory) {
                if (child.name in excludeDirs) continue
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
     * Bug 修复（目录无中文仍卡住 + EDT 卡顿）：
     *  1. `collectSupportedFiles(dir)` 旧实现在 actionPerformed（EDT）里**同步递归目录**，
     *     目录稍大时 EDT 被占住，进度条根本出不来 —— 现移入后台 run() 内执行；
     *  2. onSuccess 旧实现只检查 `processors.isEmpty()`，没检查 `extracted.isEmpty()`：
     *     目录下文件都没有中文时仍会弹空内容的模态对话框（表现为"卡住"）——
     *     现与单文件入口一致：无任何可提取文案时直接通知并返回，不弹对话框。
     */
    private fun processDirectory(project: Project, dir: VirtualFile) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            I18nExtractorBundle.message("action.progress.dir.scan.pending"),
            true
        ) {
            private var files: List<VirtualFile> = emptyList()
            private var collection: Orchestrator.Collection? = null
            private var err: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = I18nExtractorBundle.message("action.progress.dir.scanning", dir)
                indicator.fraction = 0.05
                indicator.checkCanceled()
                try {
                    // 目录递归（含 excludeDirs 过滤）放后台线程，避免 EDT 同步递归大目录卡 UI；
                    // VFS children 访问需在 read action 内执行。
                    files = ApplicationManager.getApplication().runReadAction<List<VirtualFile>> {
                        collectSupportedFiles(dir)
                    }
                } catch (t: Throwable) {
                    err = t; return
                }
                if (files.isEmpty()) return
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
                    // 扫描/分析异常应上报「内部错误」（含堆栈文案），不能伪装成「未发现中文」
                    Orchestrator.notifyInternalError(
                        project,
                        I18nExtractorBundle.message("orchestrator.notify.internal.title"),
                        t
                    )
                    return
                }
                if (files.isEmpty()) {
                    Orchestrator.notifyNothingExtracted(project, "${dir.presentableUrl}")
                    return
                }
                val c = collection ?: return
                if (c.processors.isEmpty() || c.extracted.isEmpty()) {
                    Orchestrator.notifyNothingExtracted(project, "${dir.presentableUrl}")
                    return
                }
                // 同单文件入口：推迟一拍再弹模态对话框，让本 Task 的进度窗口先关闭
                //（否则扫描进度条挂住 + 写入 Task 进度条 → 同屏 2 个进度条）
                ApplicationManager.getApplication().invokeLater {
                    launchDirWrite(project, c, dir)
                }
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