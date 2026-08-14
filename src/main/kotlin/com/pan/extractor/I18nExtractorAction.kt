package com.pan.extractor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.pan.extractor.Util.getJsonContent
import java.awt.datatransfer.StringSelection

class I18nExtractorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    // ── 问题 6：成功提取后的用户回馈（Notification balloon） ──
    data class OutputResult(
        val copiedToClipboard: Boolean,
        val overwroteEntryFile: Boolean,
        val entryFileName: String? = null,
        val fallbackReason: String? = null,
    )

    private fun notifyExtractSuccess(
        project: com.intellij.openapi.project.Project,
        title: String,
        extractedCount: Int,
        processedFiles: Int,
        output: OutputResult,
    ) {
        val filesPart = if (processedFiles <= 1) "" else "（扫描 $processedFiles 个文件）"
        val outputPart = when {
            output.overwroteEntryFile && output.entryFileName != null ->
                "，已合并写回入口文件「${output.entryFileName}」"
            output.copiedToClipboard && output.fallbackReason != null ->
                "，JSON 已复制到剪贴板（写回入口失败：${output.fallbackReason}）"
            output.copiedToClipboard -> "，JSON 已复制到剪贴板"
            else -> ""
        }
        val subtitle = "提取 $extractedCount 条 key$filesPart$outputPart"

        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("Vue i18n 提取提示")
        Notifications.Bus.notify(
            notificationGroup.createNotification(title, subtitle, NotificationType.INFORMATION),
            project
        )
    }

    /**
     * 最终输出：根据 dialog.outputMode + selectedEntryFile，
     * 要么写回入口文件（TS/JSON），要么拷贝到剪贴板。
     * 都在调用线程执行；写 VirtualFile 的操作必须在 EDT + WriteCommandAction 内部
     * （调用方负责包裹 invokeAndWait + WCA 或直接当前线程已拿写锁）。
     */
    private fun applyFinalOutput(
        project: com.intellij.openapi.project.Project,
        dialog: ExtractedStringsDialog,
        finalFlatJson: Map<String, String>,
    ): OutputResult {
        val prettyGson = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val mode = dialog.outputMode
        val entryVf = dialog.selectedEntryFile
        val jsonPretty = prettyGson.toJson(finalFlatJson)

        if (mode == Util.OutputMode.OVERWRITE_ENTRY_FILE && entryVf != null) {
            val ext = entryVf.extension?.lowercase()
            val newText: String? = try {
                when (ext) {
                    "json" -> Util.regenerateJsonFileWithNewJson(entryVf, finalFlatJson)
                    "ts", "tsx", "js", "jsx" -> Util.regenerateTsFileWithNewJson(project, entryVf, finalFlatJson)
                    else -> null
                }
            } catch (t: Throwable) {
                null
            }
            if (newText != null) {
                try {
                    Util.writeVirtualFileText(entryVf, newText)
                    return OutputResult(
                        copiedToClipboard = false,
                        overwroteEntryFile = true,
                        entryFileName = entryVf.name,
                    )
                } catch (t: Throwable) {
                    // 写文件失败：fallback 到剪贴板，并在通知里提示原因
                    val content = getJsonContent(jsonPretty)
                    CopyPasteManager.getInstance().setContents(StringSelection(content))
                    return OutputResult(
                        copiedToClipboard = true,
                        overwroteEntryFile = false,
                        fallbackReason = t.message?.take(40) ?: "写文件异常"
                    )
                }
            } else {
                // 解析/生成失败：fallback 剪贴板
                val reason = when (ext) {
                    "ts", "tsx", "js", "jsx" -> "TS/JS 入口未找到 export default/export const 对象字面量，或包含无法解析结构"
                    "json" -> "JSON 解析失败"
                    else -> "不支持的入口文件后缀"
                }
                val content = getJsonContent(jsonPretty)
                CopyPasteManager.getInstance().setContents(StringSelection(content))
                return OutputResult(
                    copiedToClipboard = true,
                    overwroteEntryFile = false,
                    fallbackReason = reason
                )
            }
        }

        // 否则：拷贝到剪贴板（默认/用户主动选 clipboard/入口文件为空）
        if (dialog.json != null) {
            val content = getJsonContent(dialog.json!!)
            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
        return OutputResult(copiedToClipboard = true, overwroteEntryFile = false)
    }

    // 当没有中文可提取时，通知用户"取消"的原因，避免以为插件没反应
    private fun notifyNothingExtracted(
        project: com.intellij.openapi.project.Project,
        scope: String,
    ) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("Vue i18n 提取提示")
        Notifications.Bus.notify(
            notificationGroup.createNotification(
                "未找到可提取的中文",
                "$scope 中未发现硬编码中文或 t 调用，无需处理。",
                NotificationType.WARNING
            ),
            project
        )
    }

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
        Util.isTranslationResourceFile(vf)

    /**
     * Bug 2 重载：PsiFile 版本（single-file 流程使用）。
     */
    private fun isTranslationResource(psiFile: PsiFile): Boolean =
        Util.isTranslationResourceFile(psiFile)

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
            processSingleFile(project, psiFile)
        }
    }

    private fun processSingleFile(project: com.intellij.openapi.project.Project, psiFile: PsiFile) {
        // Bug 2（保险）：即便 update() 放过了，到这里仍要拦截语言包文件
        if (isTranslationResource(psiFile)) return

        // 线程规则：PSI 读取（findFile、遍历、collect）必须包在 runReadAction 中，
        // 否则 ActionUpdateThread.BGT 或进度线程（Application pooled thread）会抛：
        //   Read access is allowed from inside read-action only
        val triple = ApplicationManager.getApplication().runReadAction<Triple<Map<String, String>, Map<String, String>, I18nProcessor>> {
            val ins = I18nProcessor(project, psiFile)
            ins.collect()
            Triple(
                HashMap<String, String>(ins.existingStrings),
                HashMap<String, String>(ins.extractedStrings),
                ins
            )
        }
        val existing = triple.first
        val extracted = triple.second
        val processor = triple.third

        val allStrings = mutableMapOf<String, String>()
        allStrings.putAll(existing)
        allStrings.putAll(extracted)

        // 单文件也计算公共前后缀/数字抽取候选，填充 Dialog Tab2
        val (affixGroups, digitGroups) = ApplicationManager.getApplication().runReadAction<Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>>> {
            MergeApplier.factorizeSites(listOf(processor))
        }

        val dialog = ExtractedStringsDialog(
            project, allStrings, affixGroups, digitGroups,
            contextPsiFile = psiFile  // 用于推断中文入口文件
        )
        if (dialog.showAndGet()) {
            // 【问题 1 修复：写入过程加进度提示，避免点确定后"卡住没反馈"】
            //   单文件可能有几十上百处替换 + import/const 注入，之前直接在 EDT 同步调
            //   processor.execute()，表现就是"点了 OK 后对话框消失，但 UI 完全没变化几秒"。
            //   改用 Task.Backgroundable（非模态后台任务）+ 进度条 + 阶段文本：
            //     "阶段 1/2：写入 PSI 替换" → 2/2：覆盖入口文件 / 复制 JSON / 发通知
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(
                    project,
                    "i18n 提取单文件写入中…",
                    true
                ) {
                    private lateinit var output: OutputResult
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        // —— 阶段 1：写入 PSI 替换（含合并计划应用）
                        indicator.text = "写入中：替换硬编码中文 + 注入 i18n 导入/别名"
                        indicator.fraction = 0.1
                        val mergePlan = dialog.mergePlan
                        val hasMerge =
                            mergePlan.selectedAffix.isNotEmpty() || mergePlan.selectedDigit.isNotEmpty()
                        ApplicationManager.getApplication().invokeAndWait {
                            CommandProcessor.getInstance().executeCommand(
                                project,
                                {
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        val finalJson: Map<String, String> =
                                            if (hasMerge) {
                                                // 应用合并计划：常规写入(跳过被合并句) + 骨架重写为带 {N0} 的 $t
                                                MergeApplier.apply(
                                                    processors = listOf(processor),
                                                    extracted = allStrings,
                                                    mergePlan = mergePlan,
                                                    indicator = indicator,
                                                )
                                            } else {
                                                processor.run()
                                                allStrings
                                            }
                                        output = applyFinalOutput(project, dialog, finalJson)
                                    }
                                },
                                "I18n Extract Single",
                                null
                            )
                        }
                        indicator.fraction = 1.0
                    }

                    override fun onSuccess() {
                        // 问题 6：成功提取后的用户回馈（写回到 EDT 发通知）
                        notifyExtractSuccess(
                            project,
                            title = "单文件国际化提取完成",
                            extractedCount = allStrings.size,
                            processedFiles = 1,
                            output = if (::output.isInitialized) output
                                     else OutputResult(copiedToClipboard = false, overwroteEntryFile = false)
                        )
                    }
                }
            )
        } else if (allStrings.isEmpty()) {
            notifyNothingExtracted(project, "当前文件")
        }
    }

    /**
     * 递归收集文件夹内所有受支持的文件
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

    private fun processDirectory(project: com.intellij.openapi.project.Project, dir: VirtualFile) {
        val files = collectSupportedFiles(dir)
        if (files.isEmpty()) return

        val psiManager = PsiManager.getInstance(project)
        val processors = mutableListOf<I18nProcessor>()
        val extracted = mutableMapOf<String, String>()

        // 使用进度对话框，避免文件过多时 UI 冻结
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val indicator = ProgressManager.getInstance().progressIndicator
            indicator.text = "Extracting i18n strings..."
            indicator.isIndeterminate = false
            for ((index, file) in files.withIndex()) {
                if (indicator.isCanceled) break
                indicator.fraction = (index + 1).toDouble() / files.size
                indicator.text2 = file.name

                // 🔴 线程合规：进度线程是 Application pooled thread（非 EDT），
                //    findFile + collect() 做大量 PSI 读遍历，必须包 runReadAction，
                //    否则抛 "Read access is allowed from inside read-action only"
                ApplicationManager.getApplication().runReadAction {
                    val psiFile = psiManager.findFile(file) ?: return@runReadAction
                    val processor = I18nProcessor(project, psiFile)
                    processor.collect()
                    extracted.putAll(processor.existingStrings)
                    extracted.putAll(processor.extractedStrings)
                    processors.add(processor)
                }
            }
        }, "I18n Extraction", true, project)

        if (processors.isEmpty()) return

        // 给 Dialog 传一个"上下文 PsiFile"（第一个 processor 的文件，用于推断项目根找入口）
        val contextFileForDialog: PsiFile? = processors.firstOrNull()?.let { p ->
            (p.targetPsiFile as? PsiFile) ?: p.targetPsiFile.containingFile
        }

        // 目录批量也计算公共前后缀/数字抽取候选，填充 Dialog Tab2
        val (affixGroups, digitGroups) = ApplicationManager.getApplication().runReadAction<Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>>> {
            MergeApplier.factorizeSites(processors)
        }

        val dialog = ExtractedStringsDialog(
            project, extracted, affixGroups, digitGroups,
            contextPsiFile = contextFileForDialog
        )
        if (dialog.showAndGet()) {
            // 【问题 1 修复：目录写入阶段加进度条 + 逐文件反馈】
            // 之前 processDirectory OK 之后是同步串行 processors.forEach{it.run()}，
            // 目录文件多时 UI 冻结没任何进度反馈。
            // 改用 Task.Backgroundable + 进度 0.0~0.85 逐文件写、0.85~1.0 覆盖入口/复制 JSON。
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(
                    project,
                    "i18n 目录批量写入中…",
                    true
                ) {
                    private lateinit var output: OutputResult
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        indicator.text = "批量写入中：处理 $processors.size 个文件"
                        indicator.fraction = 0.0
                        // —— 阶段 1：逐文件写入 + 应用合并计划（写锁必须拿在 EDT 上）
                        val mergePlan = dialog.mergePlan
                        val hasMerge =
                            mergePlan.selectedAffix.isNotEmpty() || mergePlan.selectedDigit.isNotEmpty()
                        ApplicationManager.getApplication().invokeAndWait {
                            CommandProcessor.getInstance().executeCommand(
                                project,
                                {
                                    WriteCommandAction.runWriteCommandAction(project) {
                                        val finalJson: Map<String, String> =
                                            if (hasMerge) {
                                                // 应用合并计划：常规写入(跳过被合并句) + 骨架重写为带 {N0} 的 $t
                                                MergeApplier.apply(
                                                    processors = processors,
                                                    extracted = extracted,
                                                    mergePlan = mergePlan,
                                                    indicator = indicator,
                                                )
                                            } else {
                                                for ((idx, processor) in processors.withIndex()) {
                                                    indicator.fraction = idx.toDouble() / processors.size.coerceAtLeast(1) * 0.85
                                                    val pf = (processor.targetPsiFile as? PsiFile)
                                                    indicator.text2 = pf?.name
                                                        ?: (processor.targetPsiFile.containingFile?.name ?: "文件 ${idx + 1}")
                                                    if (indicator.isCanceled) break
                                                    processor.run()
                                                }
                                                extracted
                                            }
                                        output = applyFinalOutput(project, dialog, finalJson)
                                    }
                                },
                                "I18n Extract Batch",
                                null
                            )
                        }
                        indicator.fraction = 1.0
                    }

                    override fun onSuccess() {
                        notifyExtractSuccess(
                            project,
                            title = "目录批量国际化提取完成",
                            extractedCount = extracted.size,
                            processedFiles = files.size,
                            output = if (::output.isInitialized) output
                                     else OutputResult(copiedToClipboard = false, overwroteEntryFile = false)
                        )
                    }
                }
            )
        } else if (extracted.isEmpty()) {
            notifyNothingExtracted(project, "目录 ${dir.presentableUrl}")
        }
    }
}
