package com.pan.extractor.orchestrator

import com.pan.extractor.project.Util
import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.merge.AffixGroupCandidate
import com.pan.extractor.merge.DigitGroupCandidate
import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.*
import com.pan.extractor.resource.ResourceApplier
import com.pan.extractor.ui.*

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.pan.extractor.project.Util.getJsonContent
import java.awt.datatransfer.StringSelection

/**
 * Orchestrator 层 —— 编排 Scanner → Analyzer → Planner → Rewriter → Validator → Applicator 全流程。
 *
 * 目标架构（PROJECT_ANALYSIS §19）：
 * ```text
 * Scanner ─▶ Analyzer ─▶ Planner ─▶ Validate ─▶ Rewriter / ResourceWriter ─▶ Single Command ─▶ Undo
 * ```
 * 入口（AllI18nExtractorAction / I18nExtractorAction）只做「收集触发参数 + 弹模态框 + 起进度任务」，
 * 流水线各层职责收敛到此处，消除两个 Action 之间的重复编排逻辑：
 *
 *  - [collect]：**Scanner/Analyzer 阶段（只读）** —— 把 VirtualFile 列表转成 [I18nProcessor] 并 collect()，
 *    产出候选合并分组（[MergeApplier.factorizeSites]，Analyzer 层）与提取结果；
 *  - [apply]：**Planner/Rewriter/Validator 阶段** —— 校验（[ChangeValidator]）→ 执行 import 注入 + $t 替换
 *    + 骨架合并（[MergeApplier.apply]）→ 资源写回 / 剪贴板（[ResourceApplier]，Rewriter 层），
 *    全部收敛进【单个 WriteCommandAction】，失败整体回滚（多文件原子性）。
 *
 * 核心原则（PROJECT_ANALYSIS §4）：
 * > 分析阶段不修改项目；Plan 阶段只描述修改；只有最终 Apply 阶段进入 Write Action。
 * 因此 [collect] 在 Write Action 之外执行；[apply] 由调用方包在 progress 任务里，内部用
 * `invokeAndWait + runWriteCommandAction` 单 command 原子提交。
 */
object I18nExtractionOrchestrator {

    private val LOG = Logger.getInstance(I18nExtractionOrchestrator::class.java)

    // ─────────────────────────────────────────────────────────────
    // 采集阶段产物（Scanner + Analyzer 输出）
    // ─────────────────────────────────────────────────────────────

    /** 一次提取的「收集 + 分析」结果，供入口弹 Dialog 以及后续 [apply] 使用。 */
    data class Collection(
        /** 已 collect() 的处理器（每个命中的源文件一个）。 */
        val processors: List<I18nProcessor>,
        /** 提取结果（含已有翻译调用），后续作为写入 / 资源回写的源。 */
        val extracted: MutableMap<String, String>,
        /** Analyzer 层：公共前后缀合并候选（填充 Dialog Tab2）。 */
        val affixGroups: List<AffixGroupCandidate>,
        /** Analyzer 层：汉字+数字抽取候选（填充 Dialog Tab2）。 */
        val digitGroups: List<DigitGroupCandidate>,
        /** 上下文 PSI 文件（给 Dialog 推断中文入口位置用）；null 时回退到第一个 processor 的文件。 */
        val contextPsiFile: PsiFile?,
        /** 参与提取的文件个数（供进度提示 / 通知文案）。 */
        val fileCount: Int,
    )

    /** Rewriter 最终输出结果：写回入口文件，或回退到剪贴板。 */
    data class OutputResult(
        /** 是否已回退并复制到剪贴板。 */
        val copiedToClipboard: Boolean,
        /** 是否成功写回入口文件。 */
        val overwroteEntryFile: Boolean,
        /** 成功写回时的入口文件名（供通知文案）。 */
        val entryFileName: String? = null,
        /** 写回失败、回退剪贴板时的原因（供通知文案）。 */
        val fallbackReason: String? = null,
    )

    // ─────────────────────────────────────────────────────────────
    // Phase 1 · Scanner / Analyzer（只读，Write Action 之外）
    // ─────────────────────────────────────────────────────────────
    /**
     * 采集阶段：把 [files]（已过滤语言包）逐个转成 [I18nProcessor] 并 collect()。
     * 纯 PSI 读，统一包 runReadAction 保证线程合规。
     *
     * @param contextPsi 入口 Action 的上下文文件（用于 Dialog 推断入口）；可为 null。
     */
    fun collect(project: Project, files: List<VirtualFile>, contextPsi: PsiFile?): Collection {
        val extracted = mutableMapOf<String, String>()
        val processors: List<I18nProcessor> = files.mapNotNull { file ->
            ApplicationManager.getApplication().runReadAction<I18nProcessor?> {
                val psiFile: PsiFile? = PsiManager.getInstance(project).findFile(file)
                if (psiFile == null) {
                    null
                } else {
                    val processor = I18nProcessor(project, psiFile)
                    processor.collect()
                    // 已翻译的 t()/i18n.t() 调用（existingStrings）也要并入输出 JSON，
                    // 与单文件/目录提取保持一致，否则已翻译文案会丢失导致 JSON 为空。
                    extracted.putAll(processor.analyzer.existingStrings)
                    extracted.putAll(processor.analyzer.extractedStrings)
                    processor
                }
            }
        }
        return finalizeCollection(
            project, processors, extracted,
            contextPsi ?: firstProcessorFile(processors),
            fileCount = files.size,
        )
    }

    /** 单文件采集：直接用已定位的 [psiFile]（不经过 VirtualFile 反查，兼容未保存文档）。 */
    fun collectSingle(project: Project, psiFile: PsiFile): Collection {
        var processor: I18nProcessor? = null
        ApplicationManager.getApplication().runReadAction {
            processor = I18nProcessor(project, psiFile).also { it.collect() }
        }
        val p = processor!!
        val extracted = mutableMapOf<String, String>()
        extracted.putAll(p.analyzer.existingStrings)
        extracted.putAll(p.analyzer.extractedStrings)
        return finalizeCollection(project, listOf(p), extracted, psiFile, fileCount = 1)
    }

    /** 收敛出 [Collection]：跑 Analyzer（factorizeSites）填充合并候选。 */
    private fun finalizeCollection(
        project: Project,
        processors: List<I18nProcessor>,
        extracted: MutableMap<String, String>,
        contextPsi: PsiFile?,
        fileCount: Int,
    ): Collection {
        // Analyzer 层：公共前后缀 + 汉字/数字抽取候选（填充 Dialog Tab2）。
        val (affixGroups, digitGroups) =
            ApplicationManager.getApplication().runReadAction<Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>>> {
                MergeApplier.factorizeSites(processors)
            }
        return Collection(
            processors = processors,
            extracted = extracted,
            affixGroups = affixGroups,
            digitGroups = digitGroups,
            contextPsiFile = contextPsi,
            fileCount = fileCount,
        )
    }

    private fun firstProcessorFile(processors: List<I18nProcessor>): PsiFile? =
        processors.firstOrNull()?.let { p ->
            (p.rootElement as? PsiFile) ?: p.rootElement.containingFile
        }

    // ─────────────────────────────────────────────────────────────
    // Phase 2 · Validate + Planner + Rewriter（单 command 原子写入）
    // ─────────────────────────────────────────────────────────────
    /**
     * 应用阶段：把 [options] 的合并计划 + 输出方式落地。
     *
     * 【P0 多文件修改原子性】import 注入 + $t 替换 + 骨架合并重写 + 资源写回全部放进
     * 【单个】WriteCommandAction：任一步抛异常，IntelliJ 撤销整个 command，不做留半完成状态。
     * 写入前由 [ChangeValidator]（经 [MergeApplier.validateAllModifiableSites]）做完整校验。
     *
     * P2：不再接收 Swing 对话框，只消费纯数据 [ApplyOptions]（编排器不感知 UI 组件）。
     *
     * 前置条件：调用方必须处于 ProgressManager 后台任务内（本方法用 invokeAndWait 上 EDT 拿写锁）。
     *
     * @return [applyFinalOutput] 的输出结果；执行后 [Collection.extracted] 已同步为最终合并结果。
     */
    fun apply(
        project: Project,
        collection: Collection,
        options: ApplyOptions,
        indicator: ProgressIndicator,
    ): OutputResult {
        val mergePlan = options.mergePlan
        val dropExistingKeys = LinkedHashSet<String>()
        var output: OutputResult = OutputResult(copiedToClipboard = false, overwroteEntryFile = false)

        // 【P0 A 组 A4】写入前统一 preflight：Code + Import + Resource 作为整体校验，
        // 任一类失效立即抛异常 ⇒ 尚未进入 WriteCommandAction，零写入。
        // 收集阶段已完成，此处只需做只读的 target / import / resource 解析校验；
        // apply 处于后台任务，读 PSI（buildImportPlan）需显式包 read action（线程合规）。
        try {
            ApplicationManager.getApplication().runReadAction {
                val preflightRewrites = collection.processors.flatMap { it.analyzer.rewrites }
                val preflightSites = collection.processors.flatMap { it.analyzer.collectedSites }
                val preflightPlan = com.pan.extractor.planner.ProjectChangePlanner.plan(
                    processors = collection.processors,
                    sites = preflightSites,
                    rewrites = preflightRewrites,
                    processorCount = collection.processors.size,
                    entryVf = options.entryFile,
                    finalExtracted = collection.extracted,
                    dropKeys = emptySet(),
                )
                preflightPlan.preflightOrThrow()
            }
        } catch (t: Throwable) {
            LOG.warn("I18nExtractionOrchestrator: Apply 前统一 preflight 失败，已中止（零写入）。${t.message?.take(120)}", t)
            throw t
        }

        indicator.text = "原子写入 ${collection.processors.size} 个文件（import + \$t 替换 + 骨架 + 资源写回）"
        indicator.text2 = "单 command 统一提交，失败将整体回滚"

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                // Planner/Rewriter 层：MergeApplier.apply 内部按 ExtractionPlan 描述统一执行；
                // 空合并计划等价于逐文件 processor.run()（行为与原分支 1:1）。
                val merged = MergeApplier.apply(
                    processors = collection.processors,
                    extracted = collection.extracted,
                    mergePlan = mergePlan,
                    indicator = indicator,
                    // edtRunner = null → 所有写入都在当前 command 内同步执行（单 command 原子）。
                    edtRunner = null,
                    dropExistingKeysOut = dropExistingKeys,
                )
                collection.extracted.clear()
                collection.extracted.putAll(merged)
                output = applyFinalOutput(project, options, LinkedHashMap(merged), dropExistingKeys)
            }
        }
        indicator.text = "已完成单 command 原子写入（入口 / 剪贴板）"
        return output
    }

    // ─────────────────────────────────────────────────────────────
    // 最终输出（Rewriter 层：资源写回 / 剪贴板）
    // ─────────────────────────────────────────────────────────────
    /**
     * 最终输出：根据 [options.outputMode] + [options.entryFile]，
     * 要么写回入口文件（TS/JSON），要么拷贝到剪贴板。
     * 必须在 EDT + WriteCommandAction 内部调用（调用方负责包裹）。
     */
    fun applyFinalOutput(
        project: Project,
        options: ApplyOptions,
        finalFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): OutputResult {
        val prettyGson = com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        val mode = options.outputMode
        val entryVf = options.entryFile
        val jsonPretty = prettyGson.toJson(finalFlatJson)

        if (mode == OutputDestination.FILE && entryVf != null) {
            val ext = entryVf.extension?.lowercase()
            // Resource 层统一写回：组装 ResourcePlan，由 ResourceApplier 按格式分发（json / ts spread / ts）。
            val plan = ResourceApplier.buildPlan(entryVf, finalFlatJson, dropExistingKeys)
            val writes: List<Pair<VirtualFile, String>>? = ResourceApplier.apply(project, plan)
            if (writes != null) {
                try {
                    for ((vf, newText) in writes) {
                        TsFileEditor.writeVirtualFileText(vf, newText)
                    }
                    return OutputResult(
                        copiedToClipboard = false,
                        overwroteEntryFile = true,
                        entryFileName = entryVf.name,
                    )
                } catch (t: Throwable) {
                    LOG.warn("I18nExtractionOrchestrator: 写回入口文件失败，回退到剪贴板。${t.message?.take(60)}", t)
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

        if (options.clipboardJson != null) {
            val content = Util.getJsonContent(options.clipboardJson!!)
            CopyPasteManager.getInstance().setContents(StringSelection(content))
        }
        return OutputResult(copiedToClipboard = true, overwroteEntryFile = false)
    }

    // ─────────────────────────────────────────────────────────────
    // 用户回馈（通知）
    // ─────────────────────────────────────────────────────────────
    /** 成功提取后的用户回馈（Notification balloon）。 */
    fun notifyExtractSuccess(
        project: Project,
        title: String,
        extractedCount: Int,
        processedFiles: Int,
        output: OutputResult,
    ) {
        val filesPart = when {
            processedFiles > 1 -> "（扫描 $processedFiles 个文件）"
            else -> ""
        }
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

    /** 当没有中文可提取时，通知用户「取消」的原因，避免以为插件没反应。 */
    fun notifyNothingExtracted(project: Project, scope: String) {
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
}