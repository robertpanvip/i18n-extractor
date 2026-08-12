package com.pan.extractor

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
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
        // 关键：告诉系统 update() 要后台执行
        return ActionUpdateThread.BGT  // Background Thread
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
        val allFiles = getIncludesFile(project)
        // Bug 2: 翻译资源文件不进入 Processor，避免提取/注入到语言包中
        val files = allFiles.filterNot { Util.isTranslationResourceFile(it) }
        val extracted = mutableMapOf<String, String>()

        val psiFiles: List<I18nProcessor?> = files.map { file ->
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

        // 弹出模态框显示 JSON
        val dialog = ExtractedStringsDialog(project, extracted);
        if (dialog.showAndGet()) {
            CommandProcessor.getInstance().executeCommand(
                project,
                {
                    WriteCommandAction.runWriteCommandAction(project) {
                        psiFiles.forEach { processor ->
                            processor?.run();
                        }
                    }
                },
                "Vue i18n Extract",
                null
            )

            if (dialog.json !== null) {
                val content = getJsonContent(dialog.json!!)
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            }
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
