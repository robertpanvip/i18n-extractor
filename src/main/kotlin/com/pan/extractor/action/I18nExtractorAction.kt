package com.pan.extractor.action

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
        if (dialog.showAndGet()) {
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
                        indicator.text = "写入中：替换硬编码中文 + 注入 i18n 导入/别名"
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
            "i18n 提取：分析当前文件…",
            true
        ) {
            private var collection: Orchestrator.Collection? = null
            private var err: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "解析 PSI 并提取候选字符串（含已有 \$t 调用）"
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
                        "当前文件（内部异常: ${t.javaClass.simpleName}）"
                    )
                    return
                }
                val c = collection ?: return
                if (c.processors.isEmpty()) {
                    if (c.extracted.isEmpty()) Orchestrator.notifyNothingExtracted(project, "当前文件")
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
            "i18n 目录提取：递归扫描并分析 ${files.size} 个文件…",
            true
        ) {
            private var collection: Orchestrator.Collection? = null
            private var err: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = "递归扫描目录：$dir"
                indicator.fraction = 0.05
                indicator.checkCanceled()
                try {
                    indicator.text = "分析 ${files.size} 个文件中的硬编码中文"
                    indicator.fraction = 0.2
                    collection = Orchestrator.collect(project, files, null)
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
                        "目录 ${dir.presentableUrl}（内部异常: ${t.javaClass.simpleName}）"
                    )
                    return
                }
                val c = collection ?: return
                if (c.processors.isEmpty()) {
                    Orchestrator.notifyNothingExtracted(project, "目录 ${dir.presentableUrl}")
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
        if (dialog.showAndGet()) {
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