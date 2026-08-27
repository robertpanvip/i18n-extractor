package com.pan.extractor.orchestrator

import com.pan.extractor.log.PluginLogBuffer
import com.pan.extractor.messages.I18nExtractorBundle
import com.pan.extractor.project.Util
import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.merge.AffixGroupCandidate
import com.pan.extractor.merge.DigitGroupCandidate
import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.*
import com.pan.extractor.resource.ResourceApplier
import com.pan.extractor.locate.EntryFileLocator
import com.pan.extractor.ui.*

import com.intellij.notification.NotificationAction
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.max

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
 * 因此 [collect] 在 Write Action 之外执行；[apply] 由调用方包在 progress 任务里，内部向
 * EDT `invokeLater` 投递【单个】`runWriteCommandAction`，以 CompletableFuture 等待结果，
 * 等待期间每 150ms 仍可推进 ProgressIndicator（不会 0% 卡死）。
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
    fun collect(project: Project, files: List<VirtualFile>, contextPsi: PsiFile?, indicator: ProgressIndicator? = null): Collection {
        val extracted = mutableMapOf<String, String>()
        val total = files.size
        val processors: List<I18nProcessor> = files.mapIndexedNotNull { idx, file ->
            // 【进度卡在 20/477 修复】进度更新放在文件处理**之前**。
            // 旧实现放之后导致：用户看到的是「已处理 20/477」，但文件 21 正在处理中（可能耗时较长），
            // 用户就误以为进度条「卡在 20 不动了」。换成 before 后，用户看到的是「正在处理 21/477」，
            // 知道哪个文件正在被分析，不会再误以为卡住。
            // 单文件分析容错：某个文件（如 PSI 未就绪 / IndexNotReady / 偶发解析异常）失败时
            // 记录日志并跳过，而不是中断整批 —— 否则一个大项目里个别坏文件会导致 collect 整体
            // 抛异常，进而被动作层误报成「未发现中文」并让进度条停在失败点。
            if (indicator != null) {
                indicator.checkCanceled()
                indicator.text = I18nExtractorBundle.message("action.progress.analyzing.file", file.name, idx + 1, total)
                indicator.text2 = I18nExtractorBundle.message("action.progress.extracted.keys", extracted.size)
                indicator.fraction = 0.2 + 0.8 * (idx).toDouble() / total.toDouble()
            }
            val result = try {
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
            } catch (t: Throwable) {
                PluginLogBuffer.error(
                    LOG,
                    "I18nExtractionOrchestrator: 分析文件失败，已跳过（不影响后续）—— ${file.path}",
                    t
                )
                null
            }
            result
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
     * 前置条件：调用方需处于 ProgressManager 后台任务内（本方法会写 ProgressIndicator，并
     * 通过 `invokeLater + CompletableFuture` 向 EDT 投递单次 WriteCommandAction）。
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
            PluginLogBuffer.warn(LOG, "I18nExtractionOrchestrator: Apply 前统一 preflight 失败，已中止（零写入）。${t.message?.take(120)}", t)
            throw t
        }

        indicator.text = I18nExtractorBundle.message("orchestrator.waiting.edt")
        indicator.text2 = I18nExtractorBundle.message("orchestrator.waiting.edt.detail")
        indicator.fraction = 0.05

        // ══════════════════════════════════════════════════════════════════════════════
        // 【Progress 卡死的核心修复 v2】
        // 旧实现（v1）：invokeLater + CompletableFuture + 后台线程每隔 150ms 推"假进度"。
        // 问题：EDT 被整个 WCA 占住数秒，后台线程推的 indicator 更新全积压在 EDT 队列，
        // 积压的 repaint 也要等 WCA 完成后才处理，用户看到的就是"进度条一直 0% 不动"，
        // 然后 WebStorm 弹出"卡死"警告。
        //
        // 修复（v2）：不用 invokeLater，改用 invokeAndWait + edtRunner。
        //   · MergeApplier.apply 在后台线程上直接调用（不 dispatch 到 EDT）；
        //   · edtRunner 把每个 processor.run() 独立包装为 invokeAndWait + WCA，让
        //     后台线程在两次 invokeAndWait 之间「有机会」更新 indicator；
        //   · 后台线程的 indicator.fraction/text 更新在后台线程上执行，被 ProgressManager
        //     自动 dispatch 到 EDT 队列，在两次 invokeAndWait 之间的 EDT 空闲期被处理，
        //     进度条因此能实时推进。
        //   · 保留原子性：每个 processor.run() 和 applyFinalOutput 各自在 WCA 内执行，
        //     但整体不再是单 command 原子——多文件写入任一步失败，已完成的文件已生效。
        //     这比「进度条卡死+用户强制退出IDE」更可接受。
        // ══════════════════════════════════════════════════════════════════════════════
        val edtRunner: ((() -> Unit) -> Unit) = { r ->
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project) { r() }
            }
        }

        val merged = MergeApplier.apply(
            processors = collection.processors,
            extracted = collection.extracted,
            mergePlan = mergePlan,
            indicator = indicator,
            edtRunner = edtRunner,
            dropExistingKeysOut = dropExistingKeys,
        )
        collection.extracted.clear()
        collection.extracted.putAll(merged)

        // 最终输出（资源写回）：同样走 invokeAndWait + WCA，确保 EDT 处理进度
        // 注意：不再包 EmptyProgressIndicator()—— 我们已在后台 Task 内，ProgressManager
        // 已有 indicator 实例；runProcess 会创建新的可见进度上下文，导致弹出额外进度条。
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                output = applyFinalOutput(
                    project, options, LinkedHashMap(merged), dropExistingKeys
                )
            }
        }
        indicator.fraction = 1.0
        indicator.text = I18nExtractorBundle.message("orchestrator.complete")
        indicator.text2 = ""
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
            var writes: List<Pair<VirtualFile, String>>? = ResourceApplier.apply(project, plan)
            // 写回目标解析失败时：很可能是 i18n 初始化文件（顶层非 export 对象）。此时透过
            // 其 config 重定位到真实语言包文件（如 zh.ts）再写回，避免把结果直接丢进剪贴板。
            if (writes == null && ext in setOf("ts", "tsx", "js", "jsx")) {
                val localeVf = EntryFileLocator.relocateToLocaleEntryFile(project, entryVf)
                if (localeVf != null) {
                    writes = ResourceApplier.apply(
                        project,
                        ResourceApplier.buildPlan(localeVf, finalFlatJson, dropExistingKeys)
                    )
                }
            }
            if (writes != null) {
                try {
                    for ((vf, newText) in writes) {
                        // 【写入 2 遍修复】spread 路由的返回值总是包含入口文件——即使入口对象
                        // 本次没有任何新增/修改 key，也会整体 setText + saveDocument 重写一遍，
                        // 用户表现为"最终写入写了 2 遍"（入口一遍 + spread 目标一遍）。
                        // 内容与磁盘一致的文件直接跳过，只写真正发生变化的文件。
                        val current = try {
                            String(vf.contentsToByteArray(), Charsets.UTF_8)
                        } catch (_: Throwable) {
                            null
                        }
                        if (current != null && current == newText) continue
                        TsFileEditor.writeVirtualFileText(vf, newText)
                    }
                    return OutputResult(
                        copiedToClipboard = false,
                        overwroteEntryFile = true,
                        entryFileName = entryVf.name,
                    )
                } catch (t: Throwable) {
                    PluginLogBuffer.warn(LOG, "I18nExtractionOrchestrator: 写回入口文件失败，回退到剪贴板。${t.message?.take(60)}", t)
                    val content = Util.getJsonContent(jsonPretty)
                    CopyPasteManager.getInstance().setContents(StringSelection(content))
                    return OutputResult(
                        copiedToClipboard = true,
                        overwroteEntryFile = false,
                        fallbackReason = t.message?.take(40) ?: I18nExtractorBundle.message("orchestrator.fallback.reason.write")
                    )
                }
            } else {
                val reason = when (ext) {
                    "ts", "tsx", "js", "jsx" -> I18nExtractorBundle.message("orchestrator.fallback.tsjs")
                    "json" -> I18nExtractorBundle.message("orchestrator.fallback.json")
                    else -> I18nExtractorBundle.message("orchestrator.fallback.unsupported")
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
            processedFiles > 1 -> I18nExtractorBundle.message("orchestrator.notify.scanned", processedFiles)
            else -> ""
        }
        val outputPart = when {
            output.overwroteEntryFile && output.entryFileName != null ->
                I18nExtractorBundle.message("orchestrator.notify.merged", output.entryFileName)
            output.copiedToClipboard && output.fallbackReason != null ->
                I18nExtractorBundle.message("orchestrator.notify.fallback", output.fallbackReason)
            output.copiedToClipboard -> I18nExtractorBundle.message("orchestrator.notify.clipboard")
            else -> ""
        }
        val subtitle = I18nExtractorBundle.message("orchestrator.notify.subtitle", extractedCount, filesPart, outputPart)
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("I18nExtractorNotification")
        Notifications.Bus.notify(
            notificationGroup.createNotification(title, subtitle, NotificationType.INFORMATION),
            project
        )
    }

    /** 当没有中文可提取时，通知用户「取消」的原因，避免以为插件没反应。 */
    fun notifyNothingExtracted(project: Project, scope: String) {
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("I18nExtractorNotification")
        Notifications.Bus.notify(
            notificationGroup.createNotification(
                I18nExtractorBundle.message("orchestrator.notify.nothing.title"),
                I18nExtractorBundle.message("orchestrator.notify.nothing.body", scope),
                NotificationType.WARNING
            ),
            project
        )
    }

    /**
     * 内部异常兜底通知：任何「本应显示进度/完成气泡，但提前抛异常」的路径都用它提示用户，
     * 避免出现「点了确定完全没反应」。同时把堆栈写入 LOG 便于后续定位。
     */
    fun notifyInternalError(project: Project, title: String, throwable: Throwable) {
        PluginLogBuffer.warn(LOG, "I18n Extractor 内部异常 —— $title", throwable)
        val notificationGroup = NotificationGroupManager.getInstance()
            .getNotificationGroup("I18nExtractorNotification")
        val msg = buildString {
            append(throwable.javaClass.simpleName)
            val m = throwable.message
            if (!m.isNullOrBlank()) append(": ").append(m.take(140))
            append(I18nExtractorBundle.message("orchestrator.notify.internal.detail"))
        }
        val notification = notificationGroup.createNotification(
            I18nExtractorBundle.message("orchestrator.notify.internal.title"),
            msg,
            NotificationType.ERROR
        )
        notification.addAction(NotificationAction.create(
            I18nExtractorBundle.message("notification.copy.full.log")
        ) { _, _ ->
            CopyPasteManager.getInstance().setContents(
                StringSelection(PluginLogBuffer.dump())
            )
        })
        Notifications.Bus.notify(notification, project)
    }
}