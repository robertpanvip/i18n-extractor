package com.pan.extractor

import com.google.gson.Gson
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
import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.pan.extractor.Util.getJsonContent
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets

class AllI18nExtractorAction : AnAction() {

    // ─────────────────────────────────────────────────────────────
    // 骨架替换 helper：把某个 site 的 rootPsi 换成 `$t('骨架{N0}', { N0: <diff> })`
    //   - 根据 site.isVue/isReact 生成分枝占位符
    //   - 同时把 skeleton key/value 注册到 finalExtracted（翻译资源 JSON）
    // ─────────────────────────────────────────────────────────────
    private fun rewriteSiteToSkeleton(
        rootPsi: PsiElement,
        site: I18nProcessor.CollectedSite,
        skeletonValue: String,
        skeletonKey: String,
        paramPairs: List<Pair<String, String>>,
        proc: I18nProcessor,
        finalExtracted: MutableMap<String, String>,
    ) {
        // 1) 根据站点框架重写骨架占位（Vue → {N0}，React → {{0}}，Generic → {0}）并
        //    构造对应的参数对象字符串
        val placeholderMap: Map<String, Pair<String, String>> = buildPlaceholderRewrite(site.isVue, site.isReact, paramPairs)
        val rewrittenSkeleton = paramPairs.fold(skeletonValue) { acc, (k, _) ->
            // 骨架里原本按 Vue 形式写的 {Nk}，在 React/Generic 下重写成对应占位
            val (ph, _) = placeholderMap[k] ?: error("placeholder missing for $k")
            acc.replace("{$k}", ph)
        }
        val paramsObjStr = buildParamsObjectString(site.isVue, paramPairs.map { (k, vExpr) ->
            val (_, keyInObject) = placeholderMap[k] ?: error("object key missing for $k")
            keyInObject to vExpr
        })
        val callExprText = proc.buildTExprForRawText(rewrittenSkeleton.trim(), paramsObjStr, site.isVue, site.isReact, skeletonKeyOverride = skeletonKey.trim())

        // 2) PSI 替换
        val replacement = when {
            rootPsi is com.intellij.psi.xml.XmlText -> proc.createStringExpressionNode(
                if (site.isReact || com.pan.extractor.Util.isJSX(rootPsi)) "{ $callExprText }" else "{{ $callExprText }}",
                rootPsi
            )
            rootPsi is com.intellij.psi.xml.XmlAttributeValue -> {
                // 对属性值：用 creator 生成一个表达式，然后通过赋值
                val attr = rootPsi.parent as? com.intellij.psi.xml.XmlAttribute
                val jsx = com.pan.extractor.Util.isJSX(rootPsi)
                val vDir = proc.isVueDirective(attr?.name ?: "")
                val prefix = if (jsx || vDir) "" else ":"
                if (attr != null) {
                    attr.setValue(if (jsx) "{$callExprText}" else "\"$callExprText\"")
                    attr.name = "$prefix${attr.name}"
                }
                return
            }
            else -> JSChangeUtil.tryCreateExpressionFromText(
                rootPsi.project,
                callExprText,
                null,
                false
            )?.psi
        }
        if (replacement != null) {
            try { rootPsi.replace(replacement) } catch (_: Throwable) { }
        }

        // 3) 翻译资源补骨架（key 用用户在 Tab2 编辑过的 skeletonKey）
        finalExtracted[skeletonKey] = rewrittenSkeleton.trim()
    }

    /**
     * 构建参数表达式里的占位符到 (占位, 参数 key) 映射：
     *   Vue    → "{N0}" + key "N0"
     *   React  → "{{0}}" + key "\"0\""（字符串 key）
     *   Generic → "{0}"  + key "\"0\""
     * 输入顺序按 N0/N1/...（目前只支持 N0，保持参数顺序）
     */
    private fun buildPlaceholderRewrite(
        isVue: Boolean,
        isReact: Boolean,
        pairs: List<Pair<String, String>>,
    ): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        pairs.forEachIndexed { i, (key, _) ->
            require(key.startsWith("N")) { "placeholder keys should be N0/N1 form" }
            val rawIndex = key.substring(1).toIntOrNull() ?: i
            when {
                isVue -> {
                    val k = "N$rawIndex"
                    result[key] = "{$k}" to k
                }
                isReact -> {
                    val k = rawIndex.toString()
                    result[key] = "{{$k}}" to "\"$k\""
                }
                else -> {
                    val k = rawIndex.toString()
                    result[key] = "{$k}" to "\"$k\""
                }
            }
        }
        return result
    }

    private fun buildParamsObjectString(isVue: Boolean, keyVals: List<Pair<String, String>>): String {
        if (keyVals.isEmpty()) return "{}"
        return keyVals.joinToString(prefix = "{ ", postfix = " }") { (k, vExpr) ->
            if (isVue) "$k: $vExpr" else "$k: $vExpr"
        }
    }

    /** 把一个纯字符串渲染成 JS 字面量（差异段是非中文时使用；字符串自动加引号，数字不加） */
    private fun renderLiteralValue(diff: String): String {
        if (diff.matches(Regex("""-?\d+(?:\.\d+)?"""))) return diff
        val quote = if ('\'' !in diff) "'" else "\""
        return "$quote${diff.replace(quote, "\\$quote")}$quote"
    }

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
            val siteRefs = mutableListOf<SiteRef>()
            for ((pIdx, proc) in processors.withIndex()) {
                for (site in proc.collectedSites) {
                    siteRefs += SiteRef(
                        processorIndex = pIdx,
                        siteId = site.id,
                        originalMessage = site.originalMessage,
                        containingFile = site.containingFile,
                        isVue = site.isVue,
                        isReact = site.isReact,
                        line1 = site.startLine,
                    )
                }
            }
            CommonPrefixSuffixFactorizer.factorize(siteRefs)
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
                                    // ── ① 填 blockedSiteIds ──
                                    indicator.text = "标记被合并承载的句子（阻止旧 i18n 单句替换）"
                                    for (g in mergePlan.selectedAffix) {
                                        if (indicator.isCanceled) return@runWriteCommandAction
                                        for (variant in g.variants) {
                                            for (ref in variant.sites) {
                                                processors[ref.processorIndex].blockedSiteIds.add(ref.siteId)
                                            }
                                        }
                                    }
                                    for (g in mergePlan.selectedDigit) {
                                        if (indicator.isCanceled) return@runWriteCommandAction
                                        for (ps in g.perSites) {
                                            processors[ps.site.processorIndex].blockedSiteIds.add(ps.site.siteId)
                                        }
                                    }
                                    indicator.fraction = 0.02

                                    // ── ② 逐文件 i18n 常规写入：import 注入 + 未被阻塞句子替换为 $t ──
                                    indicator.text = "写入 \$t：处理 ${processors.size} 个文件（import 注入 + 硬编码替换）"
                                    for ((idx, processor) in processors.withIndex()) {
                                        if (indicator.isCanceled) break
                                        indicator.fraction = 0.02 + (idx + 1).toDouble() / processors.size.coerceAtLeast(1) * 0.58
                                        val pf = (processor.targetPsiFile as? PsiFile)?.name
                                            ?: (processor.targetPsiFile.containingFile?.name ?: "文件 ${idx + 1}")
                                        indicator.text2 = pf
                                        processor.run()
                                    }
                                    if (indicator.isCanceled) {
                                        indicator.text2 = "（用户取消）"
                                        return@runWriteCommandAction
                                    }

                                    // ── ③ 预构建骨架重写任务列表 ──
                                    indicator.text = "生成骨架重写任务列表（公共前后缀/数字抽取）"
                                    indicator.fraction = 0.6
                                    val finalExtracted: MutableMap<String, String> = LinkedHashMap(extracted)
                                    val rewriteTasks = mutableListOf<Pair<String, () -> Unit>>()

                                    for (g in mergePlan.selectedAffix) {
                                        if (indicator.isCanceled) break
                                        for (variant in g.variants) {
                                            if (indicator.isCanceled) break
                                            for (ref in variant.sites) {
                                                val proc = processors[ref.processorIndex]
                                                val site = proc.collectedSites.firstOrNull { it.id == ref.siteId } ?: continue
                                                val root = site.replaceRootPointer.element ?: continue
                                                if (!root.isValid) continue
                                                val diffExpr = if (Util.hasChinese(variant.diff)) {
                                                    val diffKey = variant.diff.trim()
                                                    finalExtracted.putIfAbsent(diffKey, variant.diff)
                                                    proc.buildTExprForRawText(variant.diff, "{}", site.isVue, site.isReact)
                                                } else {
                                                    renderLiteralValue(variant.diff)
                                                }
                                                val label = (site.containingFile?.name ?: "file") + "@L" + site.startLine
                                                rewriteTasks += label to {
                                                    rewriteSiteToSkeleton(
                                                        rootPsi = root,
                                                        site = site,
                                                        skeletonValue = g.skeleton,
                                                        skeletonKey = g.skeletonKey.trim().ifBlank { g.skeleton },
                                                        paramPairs = listOf("N0" to diffExpr),
                                                        proc = proc,
                                                        finalExtracted = finalExtracted,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    for (g in mergePlan.selectedDigit) {
                                        if (indicator.isCanceled) break
                                        for (ps in g.perSites) {
                                            val ref = ps.site
                                            val proc = processors[ref.processorIndex]
                                            val site = proc.collectedSites.firstOrNull { it.id == ref.siteId } ?: continue
                                            val root = site.replaceRootPointer.element ?: continue
                                            if (!root.isValid) continue
                                            val digitText = ps.digitValues.firstOrNull() ?: "0"
                                            val label = (site.containingFile?.name ?: "file") + "@L" + site.startLine
                                            rewriteTasks += label to {
                                                rewriteSiteToSkeleton(
                                                    rootPsi = root,
                                                    site = site,
                                                    skeletonValue = g.skeleton,
                                                    skeletonKey = g.skeletonKey.trim().ifBlank { g.skeleton },
                                                    paramPairs = listOf("N0" to digitText),
                                                    proc = proc,
                                                    finalExtracted = finalExtracted,
                                                )
                                            }
                                        }
                                    }
                                    if (indicator.isCanceled) {
                                        extracted.clear()
                                        extracted.putAll(finalExtracted)
                                        indicator.text2 = "（用户取消）"
                                        return@runWriteCommandAction
                                    }

                                    // ── ④ 逐任务执行骨架合并重写 ──
                                    indicator.text = "应用骨架合并重写（生成带 {N0} 的 \$t 调用）"
                                    for ((idx, task) in rewriteTasks.withIndex()) {
                                        if (indicator.isCanceled) break
                                        indicator.fraction = 0.6 + (idx + 1).toDouble() / rewriteTasks.size.coerceAtLeast(1) * 0.32
                                        indicator.text2 = task.first
                                        task.second()
                                    }

                                    // ── ⑤ finalExtracted：删除被合并承载的那些原句 key，回填 extracted ──
                                    indicator.text = "整理最终翻译资源（移除被合并承载的冗余句子）"
                                    indicator.fraction = 0.92
                                    val mergedOriginalMessages = HashSet<String>()
                                    for (g in mergePlan.selectedAffix) {
                                        for (variant in g.variants) {
                                            for (ref in variant.sites) mergedOriginalMessages.add(ref.originalMessage.trim())
                                        }
                                    }
                                    for (g in mergePlan.selectedDigit) {
                                        for (ps in g.perSites) mergedOriginalMessages.add(ps.site.originalMessage.trim())
                                    }
                                    val iter = finalExtracted.entries.iterator()
                                    while (iter.hasNext()) {
                                        val (k, v) = iter.next()
                                        if (k.trim() in mergedOriginalMessages || v.trim() in mergedOriginalMessages) {
                                            iter.remove()
                                        }
                                    }
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
