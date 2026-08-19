package com.pan.extractor.orchestrator

import com.pan.extractor.EntryFileLocator
import com.pan.extractor.I18nFrameworkRegistry
import com.pan.extractor.ImportManager
import com.pan.extractor.I18nProcessor
import com.pan.extractor.ReactI18nextStrategy
import com.pan.extractor.SolidI18nStrategy
import com.pan.extractor.VueI18nStrategy
import com.pan.extractor.model.ExtractionContext
import com.intellij.psi.PsiFile

/**
 * 单文件 i18n 编排器 —— 每文件流水线的「调度中枢」。
 *
 * 目标架构（PROJECT_ANALYSIS §结论）：`I18nProcessor` 收敛为纯调度，只负责把
 * Scanner → Analyzer → Rewriter → Injector 各段的**控制流顺序**交给本层执行，
 * 自身不再内联工作流决策。
 *
 *  - [collect]：编排「状态重置 → 语言包守卫 → 框架探测 → tFunctionName 初始化 →
 *    全局 $t 别名预判 → 已有 key 收集 → 候选发现」序列（Scanner/Analyzer 段）；
 *  - [run]：编排「语言包守卫 → 跳过被合并阻塞的站点 → 执行改写 → 按框架注入」序列
 *    （Rewriter/Injector 段）。
 *
 * 行为与原先内联在 [I18nProcessor.collect] / [I18nProcessor.run] 的代码 1:1 等价；
 * [I18nProcessor] 仍作为持有收集期状态（[com.pan.extractor.planner.CollectedPlan]）
 * 与提供原语操作的宿主，公共 API 不变。
 */
open class I18nFileOrchestrator {

    companion object {
        /** 默认单例：生产环境唯一编排器；测试或自定义管道可传子类/新实例覆盖。 */
        val Default: I18nFileOrchestrator = I18nFileOrchestrator()
    }

    /** 编排 [I18nProcessor.collect]：返回待应用改写列表，与旧实现 1:1。 */
    open fun collect(processor: I18nProcessor, context: ExtractionContext): MutableList<I18nProcessor.CollectedChange> {
        processor.analyzer.resetState()
        // Bug 2: 语言包/翻译资源文件本身跳过整个提取与注入流程。
        val containingFile = context.psiFile.containingFile
        if (containingFile != null && EntryFileLocator.isTranslationResourceFile(containingFile)) {
            return processor.analyzer.pendingChanges
        }

        processor.analyzer.framework = I18nFrameworkRegistry.detect(context.psiFile.containingFile ?: context.psiFile)
        processor.analyzer.tFunctionName = processor.analyzer.framework.tFunctionName

        val f = containingFile ?: (context.psiFile as? PsiFile)
        // React 文件统一短 t（与 framework.tFunctionName="t" 一致；P0 之后已默认就是 t，此处冗余但保留）
        if (f != null && processor.analyzer.framework is ReactI18nextStrategy && processor.analyzer.tFunctionName == "\$t") {
            processor.analyzer.tFunctionName = "t"
        }
        if (f != null && processor.analyzer.framework.detectGlobalDollarTNeeded(f)) {
            // React/Vue/Solid 纯工具文件注入全局别名标记的预判（见 I18nProcessor 处详注）。
            when (processor.analyzer.framework) {
                is ReactI18nextStrategy -> processor.analyzer.needInjectReactGlobalDollarT = true
                is SolidI18nStrategy -> {
                    processor.analyzer.needInjectSolidGlobalDollarT = true
                    processor.analyzer.tFunctionName = "\$t" // Solid 默认是 t，纯工具 TS 统一改 $t（与 Vue 一致）
                }
                is VueI18nStrategy -> processor.analyzer.needInjectGlobalDollarT = true
            }
        }

        processor.analyzer.collectExistingTKeys(context.psiFile)
        // collectExistingTKeys 可能基于现有调用把 tFunctionName 改成 i18n.t —— 不要覆盖回去。
        val changes = processor.analyzer.collectFromPsi(context.psiFile)
        processor.analyzer.pendingChanges = changes
        return processor.analyzer.pendingChanges
    }

    /** 编排 [I18nProcessor.run]：执行改写 + 按框架注入，与旧实现 1:1。 */
    open fun run(processor: I18nProcessor, context: ExtractionContext) {
        // Bug 2（双重保险）：翻译资源文件不做任何 import/hook 注入
        val containingFile = context.psiFile.containingFile
        if (containingFile != null && EntryFileLocator.isTranslationResourceFile(containingFile)) return

        val analyzer = processor.analyzer
        analyzer.pendingChanges.forEach { if (it.siteId !in analyzer.blockedSiteIds) it.run() }

        // 注入分支按框架拆到 ImportManager，本层只做派发。
        processor.injector.injectForFramework(
            processor = processor,
            psiFile = context.psiFile,
            framework = analyzer.framework,
            decision = ImportManager.InjectionDecision(
                needInjectGlobalDollarT = analyzer.needInjectGlobalDollarT,
                needInjectReactGlobalDollarT = analyzer.needInjectReactGlobalDollarT,
                needInjectSolidGlobalDollarT = analyzer.needInjectSolidGlobalDollarT,
                reactI18nTFallbackToDollarT = analyzer.reactI18nTFallbackToDollarT,
                tFunctionName = processor.analyzer.tFunctionName,
                hasExtractedStrings = processor.analyzer.extractedStrings.isNotEmpty(),
                hasExistingStrings = processor.analyzer.existingStrings.isNotEmpty(),
            ),
        )
    }
}