package com.pan.extractor.model

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.pan.extractor.analyzer.StringContext
import com.pan.extractor.analyzer.TranslationCallStatus

/**
 * 领域模型层（目标架构 Phase 1，PROJECT_ANALYSIS §5）。
 *
 * 原则：
 * > Model 只定义模块之间传递的领域模型，不保存不必要的可变 PSI 状态。
 * > 分析阶段尽量纯、计划阶段不修改。
 *
 * 本文件定义两个最基础的领域对象：
 *  - [ExtractionSite]：一个「候选/待改写」站点（由 Scanner 发现、Analyzer 判定、Planner 决策）。
 *  - [TranslationCall]：一个「翻译调用」的语义判定结果（由 Analyzer 层产出，供上层做提取/跳过决策）。
 *
 * 它们是后续 ExtractionPlan / RewritePlan / ImportPlan / ResourcePlan（见
 * [com.pan.extractor.planner.ExtractionPlan]）之上的原子单元。
 */

/**
 * 站点位置在收集期的 PSI 元信息（只用于定位，不持可变逻辑）。
 */
data class ExtractionSiteLocation(
    /** 站点所在虚拟文件；可能为 null（纯内存 PSI）。 */
    val containingFile: com.intellij.openapi.vfs.VirtualFile?,
    /** 站点内容在文件内的起始行（1 基），用于 UI 定位。 */
    val startLine: Int,
)

/**
 * 一个提取/改写站点。
 *
 * [id] 是站点在所属处理器内的稳定标识（与
 * [com.pan.extractor.I18nProcessor.CollectedSite.id] 同源）；[replaceRoot] 是可选的
 * 替换目标指针——仅用于定位与实际生效前的校验，不在模型内做任何 PSI 改写。
 */
data class ExtractionSite(
    val id: String,
    val originalMessage: String,
    val replaceRoot: SmartPsiElementPointer<PsiElement>?,
    val location: ExtractionSiteLocation? = null,
    val isVue: Boolean = false,
    val isReact: Boolean = false,
    val form: com.pan.extractor.SiteForm? = null,
) {
    /** 兼容访问：替换目标（旧 CollectedSite.replaceRootPointer 的同义名）。 */
    val replaceRootPointer: SmartPsiElementPointer<PsiElement>?
        get() = replaceRoot

    /** 站点所在虚拟文件（等效旧 CollectedSite.containingFile）。 */
    val containingFile: com.intellij.openapi.vfs.VirtualFile?
        get() = location?.containingFile

    /** 站点内容起始行（1 基），用于 UI/摘要展示（等效旧 CollectedSite.startLine）。 */
    val startLine: Int
        get() = location?.startLine ?: 1
}

/**
 * 一个翻译调用的语义判定结果（Analyzer 层产物）。
 *
 * 把 [com.pan.extractor.analyzer.TranslationAnalyzer] 的三态判定与来源证明收敛为一个
 * 可传递的领域对象，供上层（JsStringCollector / I18nProcessor / 折叠）直接消费，
 * 而无需各自重新解析 callee + resolve。
 */
data class TranslationCall(
    val call: JSCallExpression,
    /** 三态语义判定。 */
    val status: TranslationCallStatus,
    /** 字符串实参在调用内的上下文（用于提取/替换策略）。 */
    val stringContext: StringContext,
)