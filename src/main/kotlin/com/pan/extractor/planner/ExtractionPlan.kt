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
    /** 目标处理器下标（processors 列表中的位置）。 */
    val processorIndex: Int,
    /** 替换生成的调用表达式（如 `$t('key')`）。重写器执行时填充。 */
    val newExpression: String = "",
    /** 若为骨架合并：替换为 `$t('骨架{N0}', { N0: diff })`。原骨架文本（含 {N0} 占位）。 */
    val skeleton: String? = null,
    /** 重新生成资源 key 时使用的骨架 key（G：与 [skeleton] 同含 {N0} → {{0}} 变换）。 */
    val skeletonKey: String? = null,
    /** 差异段表达式列表（占位符 N0/N1… → 表达式文本）。 */
    val params: List<Pair<String, String>> = emptyList(),
)

/** 函数体注入目标类型（对应各框架的组件 / hook 定位方式）。 */
enum class HookTarget {
    /** Vue `.vue` 的 <script> 顶层注入一次（SFC 级 useI18n）。 */
    VUE_SFC_SCRIPT,
    /** Vue 纯 TS 中 use 开头的自定义 hook 函数体。 */
    VUE_HOOK,
    /** Vue 纯 TS(X) 组件（defineComponent / setup / 函数式组件）函数体。 */
    VUE_COMPONENT,
    /** React 组件函数 + 自定义 hook 函数体。 */
    REACT,
    /** Solid 组件函数 + 自定义 hook 函数体。 */
    SOLID,
}

/** 单个函数体注入描述：去哪找目标 + 注入什么语句。 */
data class HookInjectPlan(
    /** 目标定位方式。 */
    val target: HookTarget,
    /** 注入的函数体首行语句（不含引导缩进，如 `const { t } = useTranslation();`）。 */
    val statement: String,
)

/** import 计划：为文件注入 i18n import / hook / 全局 \$t 别名（纯数据描述，Rewrite 阶段消费）。 */
data class ImportPlan(
    /** 目标文件。 */
    val fileName: String,
    /** 需要追加的 import 语句（已去重判断过）。 */
    val imports: List<String> = emptyList(),
    /** 需要追加的全局别名语句（如 `const \$t = i18n.global.t;`）。 */
    val aliases: List<String> = emptyList(),
    /** 需要追加的函数体注入。 */
    val hooks: List<HookInjectPlan> = emptyList(),
    /** 框架注入类型（vue / react / solid / generic）。 */
    val frameworkId: String = "generic",
    /** true：imports/aliases 注入到 `.vue` 的 <script> 内容；false：注入到文件顶部。 */
    val injectIntoSfcScript: Boolean = false,
    /** true：先把已有 `i18n.t` / `i18n.tc` 调用改写为 `t`（React i18n.t 回落用）。 */
    val rewriteI18nTCallsToT: Boolean = false,
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

/**
 * 收集期产物容器 —— 把 [com.pan.extractor.I18nProcessor] 散落的收集期可变状态收敛为单一对象
 * （目标架构 Phase 1，PROJECT_ANALYSIS §5）。
 *
 * 原则：
 * > 外部（MergeApplier / 对话框 / Validator / 测试）只按「站点 / 提取 / 已有翻译」三组只读意图消费，
 * > 不直接操作处理器内部散落字段；收集过程产生的可变状态集中于此，reset 时整体替换即可清零。
 *
 * 覆盖两类状态：
 *  1. 收集期产物（站点 / 提取 / 已有翻译 / 跳过的站点）：
 *     [collectedSites] / [extractedStrings] / [existingStrings] / [blockedSiteIds] / [siteCounter]；
 *  2. 收集期决策（改写动作 / 框架 / 翻译函数名 / 注入意图）：
 *     [pendingChanges] / [framework] / [tFunctionName] / [needInject*] / react fallback 缓存。
 */
class CollectedPlan {
    // ── 收集期产物 ────────────────────────────────────────────────
    /** 一次提取命中站点列表（领域模型 site，见 [com.pan.extractor.model.ExtractionSite]）。 */
    val collectedSites = mutableListOf<com.pan.extractor.model.ExtractionSite>()

    /** 被骨架合并承载、应跳过普通单句替换的 siteId 集合。 */
    val blockedSiteIds = mutableSetOf<String>()

    /** siteId 自增计数。 */
    var siteCounter = 0

    /** 新提取的 key → 原文本。 */
    val extractedStrings = mutableMapOf<String, String>()

    /** 已存在的 \$t() 调用 key → 原文本（仅展示，不替换）。 */
    val existingStrings = mutableMapOf<String, String>()

    // ── 收集期决策 ────────────────────────────────────────────────
    /** 待应用的重写动作（collect 阶段收集，run 阶段逐个执行）。 */
    val pendingChanges = mutableListOf<com.pan.extractor.I18nProcessor.CollectedChange>()

    /** 当前文件检测到的框架策略。 */
    var framework: com.pan.extractor.I18nFramework? = null

    /** 检测到的翻译函数名（\$t / t / i18n.t / i18n.global.t），默认 \$t。 */
    var tFunctionName: String = "\$t"

    /** 全局 \$t 别名注入标记（Vue 非 SFC 纯 TS）。 */
    var needInjectGlobalDollarT: Boolean = false

    /** React 全局 t 别名注入标记（React 纯工具 TS）。 */
    var needInjectReactGlobalDollarT: Boolean = false

    /** Solid 全局 \$t 别名注入标记（Solid 纯工具 TS）。 */
    var needInjectSolidGlobalDollarT: Boolean = false

    /** React i18n.t 回退 getI18n 的 t 别名标记。 */
    var reactI18nTFallbackToDollarT: Boolean = false

    /** React 是否回退 getI18n 的结果缓存（同一 collect 只算一次）。 */
    var reactFallbackChecked: Boolean = false
    var reactFallbackResult: Boolean = false
}
