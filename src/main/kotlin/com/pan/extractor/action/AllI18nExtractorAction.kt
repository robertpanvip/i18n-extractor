package com.pan.extractor.action

import com.pan.extractor.messages.I18nExtractorBundle
import com.pan.extractor.project.Util
import com.pan.extractor.locate.EntryFileLocator
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
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
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

    private val LOG = Logger.getInstance(AllI18nExtractorAction::class.java)

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
            LOG.warn("tsconfig.json 格式错误: ${e.message}", e)
        } catch (e: Exception) {
            LOG.warn("读取或解析 tsconfig.json 失败: ${e.message}", e)
        }

        return emptyList()
    }

    /**
     * 解析 tsconfig 的 `include` / `extends` / `references` 链，展开为**项目根相对**的 include 模式列表
     * （去重、保持遍历顺序），按 BFS 沿 extends / references 图遍历，避免循环引用与重复解析。
     *
     * - `extends`：相对路径（缺 `.json` 自动补）或 `node_modules` 裸包 specifier（如 `@tsconfig/node18`）；
     * - `references`：`{ "path": "./apps/web" }` 目录（取其下 tsconfig.json）或直接指向 tsconfig.json 文件；
     * - 每个配置的 include 都相对于「该配置所在目录」，这里统一拼回项目根相对模式，供
     *   [com.pan.extractor.project.Util.findFilesByIncludePatterns] 消费。
     *
     * 任一配置解析或读取失败只跳过该节点，不影响其余链。
     */
    fun resolveIncludePatternsExpanded(tsConfigVf: VirtualFile, project: Project): List<String> {
        val rootVf = project.getBaseDirectories().firstOrNull()
            ?: return parseTsConfigInclude(tsConfigVf)
        val rootPath = rootVf.path
        val out = LinkedHashSet<String>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<VirtualFile, String>>() // (config, 其所在目录相对项目根)
        queue.add(tsConfigVf to relativeDirOf(tsConfigVf, rootPath))

        while (queue.isNotEmpty()) {
            val (cfg, relDir) = queue.removeFirst()
            if (!visited.add(cfg.path)) continue

            val json = readTsConfigJson(cfg) ?: continue

            json.get("include")
                ?.takeIf { it.isJsonArray }
                ?.asJsonArray
                ?.forEach { el -> if (el.isJsonPrimitive) out.add(joinRootRel(relDir, el.asString)) }

            json.get("extends")?.let { ext ->
                val paths = when {
                    ext.isJsonPrimitive -> listOf(ext.asString)
                    ext.isJsonArray -> ext.asJsonArray.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                    else -> emptyList()
                }
                for (p in paths) {
                    resolveTsConfigRef(cfg, project, p)?.let { queue.add(it to relativeDirOf(it, rootPath)) }
                }
            }

            json.get("references")?.let { refs ->
                if (refs.isJsonArray) refs.asJsonArray.forEach { el ->
                    val path = el.asJsonObject?.get("path")?.takeIf { it.isJsonPrimitive }?.asString
                        ?: return@forEach
                    resolveTsConfigRef(cfg, project, path)?.let { queue.add(it to relativeDirOf(it, rootPath)) }
                }
            }
        }
        return out.toList()
    }

    /** 读取并解析 tsconfig JSON；失败返回 null（不阻断 extends 链）。 */
    private fun readTsConfigJson(cfg: VirtualFile): JsonObject? {
        val text = try {
            String(cfg.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            LOG.warn("AllI18nExtractorAction: 读取 tsconfig ${cfg.path} 失败，跳过", e)
            return null
        }
        return try {
            Gson().fromJson(text, JsonObject::class.java)
        } catch (e: JsonParseException) {
            LOG.warn("AllI18nExtractorAction: 解析 tsconfig ${cfg.path} 失败，跳过", e)
            null
        } catch (e: Exception) {
            LOG.warn("AllI18nExtractorAction: 解析 tsconfig ${cfg.path} 失败，跳过", e)
            null
        }
    }

    /** tsconfig 文件「所在目录」相对于项目根的路径（空串 = 就在根目录）。 */
    private fun relativeDirOf(cfg: VirtualFile, rootPath: String): String =
        cfg.parent.path.removePrefix(rootPath).trimStart('/').replace("\\", "/")

    /** 把某配置目录下的相对 include 模式拼成项目根相对模式。 */
    private fun joinRootRel(relDir: String, p: String): String {
        val norm = p.trim().replace("\\", "/").removePrefix("./").removePrefix("/")
        if (norm.isEmpty()) return relDir
        return if (relDir.isEmpty()) norm else "$relDir/$norm"
    }

    /**
     * 解析 `extends` / `references` 指向的 tsconfig：
     * 绝对路径 / 相对路径（缺 `.json` 自动补）/ node_modules 裸包 specifier；解析到目录取其下
     * tsconfig.json，解析到文件则直接用。
     */
    private fun resolveTsConfigRef(cfg: VirtualFile, project: Project, raw: String): VirtualFile? {
        val norm = raw.trim().replace("\\", "/")
        if (norm.isEmpty()) return null
        if (norm.startsWith("/")) return normalizeTsConfigRef(LocalFileSystem.getInstance().findFileByPath(norm))

        val isBareSpecifier = !norm.startsWith("./") && !norm.startsWith("../")
        val parent = if (isBareSpecifier) {
            project.getBaseDirectories().firstOrNull()
                ?.let { LocalFileSystem.getInstance().findFileByPath(it.path) }
        } else cfg.parent
        if (parent == null) return null

        val looksLikeDir = isBareSpecifier && !norm.substringAfterLast('/').contains('.')
        val rel = if (isBareSpecifier) {
            if (looksLikeDir) "node_modules/$norm/tsconfig.json" else "node_modules/$norm"
        } else norm
        return normalizeTsConfigRef(findByRelativeNoExt(parent, rel))
    }

    /** 目录 → 取其下 tsconfig.json；否则直接作为 tsconfig 文件返回。 */
    private fun normalizeTsConfigRef(vf: VirtualFile?): VirtualFile? {
        if (vf == null) return null
        return if (vf.isDirectory) vf.findChild("tsconfig.json") else vf
    }

    /** 相对路径查找；缺 `.json` 扩展名时自动补一次。 */
    private fun findByRelativeNoExt(parent: VirtualFile, path: String): VirtualFile? {
        parent.findFileByRelativePath(path)?.let { return it }
        if (!path.endsWith(".json")) return parent.findFileByRelativePath("$path.json")
        return null
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

        // 2. 解析 ts.config 中的 include（含 extends / references 链展开）
        val includePatterns = resolveIncludePatternsExpanded(tsConfigFile, project)

        // 3. 根据 include 模式查找匹配的文件
        return Util.findFilesByIncludePatterns(project, includePatterns)
    }

    // ═════════════════════════════════════════════════════════════
    // 入口
    // ═════════════════════════════════════════════════════════════

    private fun isSupportedFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".vue") ||
                lower.endsWith(".ts") ||
                lower.endsWith(".tsx") ||
                lower.endsWith(".js") ||
                lower.endsWith(".jsx")
    }

    override fun update(e: AnActionEvent) {
        // 启用策略：基于上下文中的 PsiFile / VirtualFile 判断（与单文件入口语义一致），
        // 避免"菜单总是可用、点了看似无反应"的误导：
        //   · 语言包/翻译资源文件 → 禁用；
        //   · 支持后缀（.vue/.ts/.tsx/.js/.jsx）→ 启用；
        //   · PSI_FILE 为 null 但用户在 Project View 选中【目录】→ 允许（全项目提取）；其余 → 禁用。
        val psi = e.getData(CommonDataKeys.PSI_FILE)
        if (psi == null) {
            val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
            if (virtualFile == null) {
                e.presentation.isEnabledAndVisible = false
                return
            }
            if (virtualFile.isDirectory) {
                e.presentation.isEnabledAndVisible = true
                return
            }
            e.presentation.isEnabledAndVisible = isSupportedFile(virtualFile.name)
            return
        }
        // 语言包/翻译资源文件上禁用菜单
        if (EntryFileLocator.isTranslationResourceFile(psi.virtualFile)) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        e.presentation.isEnabledAndVisible = isSupportedFile(psi.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        // 上下文 PsiFile：可空；若在 Project View 目录触发则为 null，后续仍能正常工作
        val contextPsi: PsiFile? = e.getData(CommonDataKeys.PSI_FILE)

        // ── Bug 修复：Scanner + collect（Analyzer）必须在后台任务里跑 ─────────────────
        //   旧实现直接在 actionPerformed（EDT）里做 resolveScanFiles + collect，
        //   这两步都是重工作（FilenameIndex 全局扫描 + 每文件 PSI 分析），EDT 被占住
        //   导致 IntelliJ 的 Progress 重绘完全阻塞 ⇒ 用户看到的就是"进度条一直 0% / 不动"。
        //   这里改成 Task.Backgroundable，onSuccess 里再在 EDT 弹对话框并启动写入任务。
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            I18nExtractorBundle.message("action.progress.batch.scan"),
            true
        ) {
            private var files: List<VirtualFile> = emptyList()
            private var collection: Orchestrator.Collection? = null
            private var err: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.text = I18nExtractorBundle.message("action.progress.batch.found")
                indicator.fraction = 0.05
                indicator.checkCanceled()
                val allFiles = try {
                    resolveScanFiles(project)
                } catch (t: Throwable) {
                    err = t; return
                }
                // Bug 2: 翻译资源文件不进入 Processor
                files = allFiles.filterNot { EntryFileLocator.isTranslationResourceFile(it) }
                if (files.isEmpty()) return
                indicator.text = I18nExtractorBundle.message("action.progress.batch.analyzing", files.size)
                indicator.fraction = 0.2
                try {
                    collection = Orchestrator.collect(project, files, contextPsi, indicator)
                } catch (t: Throwable) {
                    err = t
                }
                indicator.fraction = 1.0
            }

            override fun onSuccess() {
                val t = err
                if (t != null) {
                    LOG.error("i18n 全项目扫描/分析阶段异常：${t.message?.take(120)}", t)
                    Orchestrator.notifyNothingExtracted(
                        project,
                        I18nExtractorBundle.message("action.progress.batch.scope")
                    )
                    return
                }
                if (files.isEmpty()) {
                    Orchestrator.notifyNothingExtracted(project, I18nExtractorBundle.message("action.progress.batch.scope"))
                    return
                }
                val c = collection ?: return
                if (c.processors.isEmpty()) {
                    if (c.extracted.isEmpty()) {
                        Orchestrator.notifyNothingExtracted(project, I18nExtractorBundle.message("action.progress.batch.scope"))
                    }
                    return
                }
                // 收集成功，进入对话框 → 后台写入
                launchWriteAfterDialog(project, c)
            }
        })
    }

    /**
     * 收集完成后：在 EDT 弹对话框；用户确认则启动【后台写入】Task。
     * 对话框本身是模态的（showAndGet），必须运行在 EDT（onSuccess 正好就是 EDT）。
     */
    private fun launchWriteAfterDialog(
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
                ProgressManager.getInstance().run(object : Task.Backgroundable(
                    project,
                    I18nExtractorBundle.message("action.progress.batch.writing"),
                    true
                ) {
                    private var output: Orchestrator.OutputResult =
                        Orchestrator.OutputResult(copiedToClipboard = false, overwroteEntryFile = false)

                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        indicator.text = I18nExtractorBundle.message("action.progress.batch.preparing")
                        indicator.text2 = ""
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
                            title = I18nExtractorBundle.message("action.progress.batch.complete"),
                            extractedCount = collection.extracted.size,
                            processedFiles = collection.fileCount,
                            output = output,
                        )
                    }

                    override fun onThrowable(error: Throwable) {
                        Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), error)
                    }
                })
            } catch (t: Throwable) {
                Orchestrator.notifyInternalError(project, I18nExtractorBundle.message("orchestrator.notify.internal.title"), t)
            }
        } else if (collection.extracted.isEmpty()) {
            Orchestrator.notifyNothingExtracted(project, I18nExtractorBundle.message("action.progress.batch.scope"))
        }
    }
}