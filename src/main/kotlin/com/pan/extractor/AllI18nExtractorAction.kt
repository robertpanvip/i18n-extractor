package com.pan.extractor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
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
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.pan.extractor.Util.getJsonContent
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets

class AllI18nExtractorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    data class OutputResult(
        val copiedToClipboard: Boolean,
        val overwroteEntryFile: Boolean,
        val entryFileName: String? = null,
        val fallbackReason: String? = null,
    )

    // ── 问题 6：全项目提取成功提示 ──
    private fun notifyExtractSuccess(
        project: Project,
        title: String,
        extractedCount: Int,
        processedFiles: Int,
        output: OutputResult,
    ) {
        val filesPart = "（扫描 $processedFiles 个文件）"
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

    private fun applyFinalOutput(
        project: Project,
        dialog: ExtractedStringsDialog,
        finalFlatJson: Map<String, String>,
    ): OutputResult {
        val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
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
                    val content = Util.getJsonContent(jsonPretty)
                    CopyPasteManager.getInstance().setContents(StringSelection(content))
                    return OutputResult(
                        copiedToClipboard = true,
                        overwroteEntryFile = false,
                        fallbackReason = t.message?.take(40) ?: "写文件异常"
                    )
                }
            } else {
                val reason = when (ext) {
                    "ts", "tsx", "js", "jsx" -> "TS/JS 入口未找到 export default/export const 对象字面量，或包含无法解析结构"
                    "json" -> "JSON 解析失败"
                    else -> "不支持的入口文件后缀"
                }
                val content = Util.getJsonContent(jsonPretty)
                CopyPasteManager.getInstance().setContents(StringSelection(content))
                return OutputResult(
                    copiedToClipboard = true,
                    overwroteEntryFile = false,
                    fallbackReason = reason
                )
            }
        }

        if (dialog.json != null) {
            val content = Util.getJsonContent(dialog.json!!)
            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
        return OutputResult(copiedToClipboard = true, overwroteEntryFile = false)
    }

    private fun notifyNothingExtracted(project: Project) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("Vue i18n 提取提示")
        Notifications.Bus.notify(
            notificationGroup.createNotification(
                "未找到可提取的中文",
                "整个项目中未发现硬编码中文或 t 调用，无需处理。",
                NotificationType.WARNING
            ),
            project
        )
    }

   override fun update(e: AnActionEvent) {
    val file = e.getData(CommonDataKeys.PSI_FILE)

    e.presentation.isEnabledAndVisible =
        file?.let {
            // Bug 2: 翻译资源文件禁用菜单
            if (Util.isTranslationResourceFile(it)) return@let false
            val name = it.name.lowercase()
            name.endsWith(".js") ||
                    name.endsWith(".jsx") ||
                    name.endsWith(".ts") ||
                    name.endsWith(".tsx") ||
                    name.endsWith(".vue")
        } ?: false
  }

    /**
     * 查找项目中的 tsconfig.json 文件
     */
    private fun findTsConfigFile(project: Project): VirtualFile? {
        val baseDir = project.getBaseDirectories().first()
        val candidates = listOf("tsconfig.app.json", "tsconfig.json", "tsconfig.base.json")

        candidates.forEach { name ->
            baseDir.findFileByRelativePath(name)?.let { return it }
            // 也可以递归找，但通常在根目录
        }

        // 如果没找到，尝试在 src 等子目录找（可选）
        return null
    }

    /**
     * 从 ts-config.json 文件中解析出 include 数组
     * 返回：List<String> 或空列表（失败时）
     */
    fun parseTsConfigInclude(tsConfigVf: VirtualFile): List<String> {
        try {
            // 读取文件内容
            val content = String(tsConfigVf.contentsToByteArray(), StandardCharsets.UTF_8)

            // 使用 Gson 解析
            val gson = Gson()
            val jsonObject = gson.fromJson(content, JsonObject::class.java)

            // 获取 include 字段（可能是数组，也可能不存在）
            val includeElement: JsonElement? = jsonObject.get("include")

            if (includeElement == null || !includeElement.isJsonArray) {
                return emptyList()
            }

            val includeArray: JsonArray = includeElement.asJsonArray

            // 转换为 List<String>
            return includeArray.mapNotNull { element ->
                if (element.isJsonPrimitive) {
                    element.asString
                } else {
                    null // 忽略非字符串元素
                }
            }

        } catch (e: JsonParseException) {
            println("tsconfig.json 格式错误: ${e.message}")
        } catch (e: Exception) {
            println("读取或解析 tsconfig.json 失败: ${e.message}")
        }

        return emptyList()
    }

    private fun getAllRelevantFiles(project: Project): List<VirtualFile> {
        val scope = GlobalSearchScope.projectScope(project)

        return listOf("ts", "tsx", "vue")
            .flatMap { ext ->
                FilenameIndex.getAllFilesByExt(project, ext, scope)
            }
            .distinct()
    }

    fun getIncludesFile(project: Project): List<VirtualFile> {
        val tsConfigFile = findTsConfigFile(project)
        if (tsConfigFile == null) {
            return getAllRelevantFiles(project);
        }

        // 2. 解析 ts.config 中的 include
        val includePatterns = parseTsConfigInclude(tsConfigFile)

        // 3. 根据 include 模式查找匹配的文件
        val matchedFiles: List<VirtualFile> = Util.findFilesByIncludePatterns(project, includePatterns)
        return matchedFiles
    }

    fun transform(e: AnActionEvent) {
        val project = e.project ?: return
        // 上下文 PsiFile：给 Dialog 推断入口文件位置用
        val contextPsi: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
        val allFiles = getIncludesFile(project)
        // Bug 2: 翻译资源文件不进入 Processor，避免提取/注入到语言包中
        val files = allFiles.filterNot { Util.isTranslationResourceFile(it) }
        val extracted = mutableMapOf<String, String>()

        val processors: List<I18nProcessor> = files.mapNotNull { file ->
            // 🔴 线程合规：transform() 此时可能跑在 WriteCommandAction lambda 里（OK），
            //    但"全项目扫描"也可能是后台触发；PsiManager.findFile + processor.collect()
            //    属于纯 PSI 读，统一加一层 runReadAction 保险。
            ApplicationManager.getApplication().runReadAction<I18nProcessor?> {
                val psiFile: PsiFile? = PsiManager.getInstance(project).findFile(file)
                if (psiFile == null) null else {
                    val processor = I18nProcessor(project, psiFile)
                    processor.collect()
                    extracted.putAll(processor.extractedStrings)
                    processor
                }
            }
        }

        // ─────────────────────────────────────────────────────────
        // 新增：公共前后缀合并 + 汉字+数字抽取（Dialog Tab 2 的候选）
        // ─────────────────────────────────────────────────────────
        val (affixGroups, digitGroups) = ApplicationManager.getApplication().runReadAction<Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>>> {
            MergeApplier.factorizeSites(processors)
        }

        // 弹出模态框：Tab1 JSON / Tab2 合并建议 + Tab 底部输出方式配置
        val dialog = ExtractedStringsDialog(
            project, extracted, affixGroups, digitGroups,
            contextPsiFile = contextPsi
        );
        if (dialog.showAndGet()) {
            val mergePlan = dialog.mergePlan

            // 【问题 1 / 用户反馈修复：写 $t 阶段没有进度条 → UI 冻结没反馈】
            //   Task.Backgroundable（非模态后台任务）+ ProgressIndicator 分 6 段：
            //     ① 填 blockedSiteIds                               0.00 → 0.02
            //     ② processors逐文件 run()（import注入 + $t 替换）    0.02 → 0.60
            //     ③ 预构建 rewriteTasks (Psi取指 + finalExtracted)   0.60
            //     ④ 逐 rewriteTask 执行骨架合并重写                    0.60 → 0.92
            //     ⑤ finalExtracted 清理 + 回填 extracted              0.92
            //     ⑥ 根据用户配置：覆盖入口文件 / 复制 JSON 到剪贴板    0.92 → 1.0
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "i18n 全项目写入中…",
                true   // canBeCancelled：用户点进度条 X 可以中断（break 循环）
            ) {
                private lateinit var output: OutputResult

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.text = "准备写入：预计算合并分组..."
                    indicator.fraction = 0.0
                    indicator.text2 = ""

                    // ── ①~⑤ 所有写 PSI + map 清理合并都要拿在 EDT + 同一个 Command/WCA
                    ApplicationManager.getApplication().invokeAndWait {
                        CommandProcessor.getInstance().executeCommand(
                            project,
                            runnable@{
                                WriteCommandAction.runWriteCommandAction(project) {
                                    // ── ①~⑤ 应用合并计划 ──
                                    //   MergeApplier.apply 内部完成：填 blockedSiteIds →
                                    //   常规写入(跳过被合并句) → 骨架重写为带 {N0} 的 $t →
                                    //   清理被合并承载的冗余 key。
                                    indicator.text = "应用合并计划（公共前后缀/数字抽取）"
                                    val finalExtracted = MergeApplier.apply(
                                        processors = processors,
                                        extracted = extracted,
                                        mergePlan = mergePlan,
                                        indicator = indicator,
                                    )
                                    extracted.clear()
                                    extracted.putAll(finalExtracted)

                                    // ── ⑥ 最终输出：覆盖入口文件 or 复制 JSON（在同一 WCA 中执行，保证撤销一致） ──
                                    indicator.text = when (dialog.outputMode) {
                                        Util.OutputMode.OVERWRITE_ENTRY_FILE -> "合并写回中文多语言入口文件"
                                        else -> "复制翻译 JSON 到剪贴板"
                                    }
                                    indicator.fraction = 0.95
                                    val finalMap = LinkedHashMap(extracted)
                                    output = applyFinalOutput(project, dialog, finalMap)
                                    indicator.fraction = 1.0
                                }
                            },
                            "Vue i18n Extract (含公共前后缀/数字合并)",
                            null
                        )
                    }
                }

                override fun onSuccess() {
                    notifyExtractSuccess(
                        project,
                        title = "全项目国际化提取完成",
                        extractedCount = extracted.size,
                        processedFiles = files.size,
                        output = if (::output.isInitialized) output
                                 else OutputResult(copiedToClipboard = false, overwroteEntryFile = false)
                    )
                }
            })
        } else if (extracted.isEmpty()) {
            notifyNothingExtracted(project)
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        WriteCommandAction.runWriteCommandAction(project, "项目中文国际提取", null, {
            transform(e);
        }, file)
    }
}
