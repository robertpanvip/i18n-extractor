package com.pan.extractor

import com.pan.extractor.orchestrator.ApplyOptions
import com.pan.extractor.orchestrator.I18nExtractionOrchestrator as Orchestrator
import com.pan.extractor.ui.*

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import java.nio.charset.StandardCharsets

/**
 * 全项目 i18n 提取入口。
 *
 * 本类只承担「触发参数解析 + 文件发现（Scanner 层）」，真正的流水线编排统一委托给
 * [Orchestrator]（Scanner → Analyzer → Planner → Validator → Rewriter → 原子 Apply）。
 */
class AllI18nExtractorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    // ═════════════════════════════════════════════════════════════
    // 入口文件发现（Scanner 层：tsconfig include 解析 / 全量回退）
    // 保留为 public，供 AllI18nExtractorActionTest 测试。
    // ═════════════════════════════════════════════════════════════

    /**
     * 查找项目中的 tsconfig.json 文件。
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
     * 从 ts-config.json 文件中解析出 include 数组。
     * 返回：List<String> 或空列表（失败时）。
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

    fun resolveScanFiles(project: Project): List<VirtualFile> {
        val tsConfigFile = findTsConfigFile(project)
        if (tsConfigFile == null) {
            return getAllRelevantFiles(project)
        }

        // 2. 解析 ts.config 中的 include
        val includePatterns = parseTsConfigInclude(tsConfigFile)

        // 3. 根据 include 模式查找匹配的文件
        return Util.findFilesByIncludePatterns(project, includePatterns)
    }

    // ═════════════════════════════════════════════════════════════
    // 入口
    // ═════════════════════════════════════════════════════════════

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.PSI_FILE)

        e.presentation.isEnabledAndVisible =
            file?.let {
                // Bug 2: 翻译资源文件禁用菜单
                if (EntryFileLocator.isTranslationResourceFile(it)) return@let false
                val name = it.name.lowercase()
                name.endsWith(".js") ||
                        name.endsWith(".jsx") ||
                        name.endsWith(".ts") ||
                        name.endsWith(".tsx") ||
                        name.endsWith(".vue")
            } ?: false
    }

    /**
     * 全项目 transform 编排：
     * 文件发现（Scanner）→ Orchestrator.collect（Analyzer，只读）→ Dialog → Orchestrator.apply（原子写入）。
     *
     * 注意：不要在 actionPerformed 里把 transform() 整体包进 WriteCommandAction；
     * 全项目 transform 会弹出模态对话框 + 跑后台写入任务，若外层抢占了 EDT 写锁，
     * 后台进度条无法重绘（问题 1）。真正需要写锁的工作都在 Backgroundable 内部
     * 通过 invokeAndWait + WriteCommandAction 由 Orchestrator 自行拿取。
     */
    fun transform(e: AnActionEvent) {
        val project = e.project ?: return
        // 上下文 PsiFile：给 Dialog 推断入口文件位置用
        val contextPsi: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)
        val allFiles = resolveScanFiles(project)
        // Bug 2: 翻译资源文件不进入 Processor，避免提取/注入到语言包中
        val files = allFiles.filterNot { EntryFileLocator.isTranslationResourceFile(it) }

        // Scanner + Analyzer 阶段（只读，Write Action 之外）
        val collection = Orchestrator.collect(project, files, contextPsi)
        if (collection.processors.isEmpty()) {
            if (collection.extracted.isEmpty()) Orchestrator.notifyNothingExtracted(project, "整个项目")
            return
        }

        val dialog = ExtractedStringsDialog(
            project, collection.extracted, collection.affixGroups, collection.digitGroups,
            contextPsiFile = collection.contextPsiFile
        )
        if (dialog.showAndGet()) {
            // 【问题 1 / 用户反馈修复：写 $t 阶段没有进度条 → UI 冻结没反馈】
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project,
                "i18n 全项目写入中…",
                true   // canBeCancelled：用户点进度条 X 可以中断（break 循环）
            ) {
                private var output: Orchestrator.OutputResult =
                    Orchestrator.OutputResult(copiedToClipboard = false, overwroteEntryFile = false)

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "准备写入：预计算合并分组..."
                    indicator.text2 = ""
                    // 单 command 原子写入（import + $t + 骨架 + 资源写回），失败整体回滚。
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
                        title = "全项目国际化提取完成",
                        extractedCount = collection.extracted.size,
                        processedFiles = collection.fileCount,
                        output = output,
                    )
                }
            })
        } else if (collection.extracted.isEmpty()) {
            Orchestrator.notifyNothingExtracted(project, "整个项目")
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        if (e.project == null || e.getData(CommonDataKeys.PSI_FILE) == null) return
        transform(e)
    }
}