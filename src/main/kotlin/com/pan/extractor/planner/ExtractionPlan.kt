package com.pan.extractor.planner
import com.pan.extractor.ui.*


/**
 * Planner 层 —— 把分析结果转换为「计划」（目标架构，迁移自 MergeApplier / ExtractedStringsDialog.MergePlan）。
 *
 * 核心原则（PROJECT_ANALYSIS §4）：
 * > **分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。**
 *
 * 计划是纯数据描述，不持有可变 PSI 状态；所有改写动作在 Rewriter 阶段统一执行。
 */

/**
 * 一次提取计划：全部待改写站点 + 骨架合并 + 数字抽取的完整描述。
 * 当前由 [com.pan.extractor.MergeApplier] 直接消费（selectedAffix / selectedDigit /
 * blockedSiteIds 语义来自 [com.pan.extractor.ExtractedStringsDialog.MergePlan]）。
 */
data class ExtractionPlan(
    /** 用户勾选的公共前后缀合并组（原文承载：骨架 + 差异段）。 */
    val selectedAffix: List<Any>,
    /** 用户勾选的数字抽取组（差异段为数字字面量）。 */
    val selectedDigit: List<Any>,
    /** 被合并承载、应跳过普通单句替换的 siteId 集合。 */
    val blockedSiteIds: Set<String> = emptySet(),
    /** 计划附带说明（供 UI / 日志展示）。 */
    val description: String = "",
)

/** 单点改写计划：把某 site 替换为翻译调用。 */
data class RewritePlan(
    /** 目标文件内的 site 标识。 */
    val siteId: String,
    /** 替换生成的调用表达式（如 `$t('key')`）。 */
    val newExpression: String,
    /** 若为骨架合并：替换为 `$t('骨架{N0}', { N0: diff })`。 */
    val skeleton: String? = null,
    /** 差异段表达式列表（占位符 N0/N1… → 表达式文本）。 */
    val params: List<Pair<String, String>> = emptyList(),
)

/** import 计划：为文件注入 i18n import / hook / 全局 \$t 别名。 */
data class ImportPlan(
    /** 目标文件。 */
    val fileName: String,
    /** 需要追加的 import 语句（已去重判断过）。 */
    val imports: List<String> = emptyList(),
    /** 需要追加的别名语句（如 `const \$t = i18n.global.t;`）。 */
    val aliases: List<String> = emptyList(),
    /** 框架注入类型（vue / react / solid / generic）。 */
    val frameworkId: String = "generic",
)

/** 资源计划：翻译资源（JSON / TS 对象）的合并写回。 */
data class ResourcePlan(
    /** 入口资源文件路径。 */
    val targetPath: String,
    /** 需要写入 / 合并的 key → value。 */
    val entries: Map<String, String> = emptyMap(),
    /** 因被骨架合并承载而应删除的历史整句 key。 */
    val dropKeys: Set<String> = emptySet(),
    /** 输出格式：json / ts。 */
    val format: String = "json",
)
