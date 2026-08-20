package com.pan.extractor.planner

import com.pan.extractor.AffixGroupCandidate
import com.pan.extractor.DigitGroupCandidate
import com.pan.extractor.GenericStrategy
import com.pan.extractor.I18nFramework
import com.pan.extractor.model.ExtractionSite
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlText

/**
 * Planner 层 —— 把分析结果转换为「计划」（目标架构，迁移自 MergeApplier / ExtractedStringsDialog.MergePlan）。
 *
 * 核心原则（PROJECT_ANALYSIS §4）：
 * > **分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。**
 *
 * 计划是纯数据描述，不持有可变 PSI 状态；所有改写动作在 Rewriter 阶段统一执行。
 */

/**
 * 一次提取计划：Apply 阶段「要改什么」的唯一事实来源。
 *
 * 中央化（单一数据流）：
 *  - [rewrites] 承载**所有**站点改写动作（普通 `$t` 替换 + 骨架合并重写），全部数据化为
 *    [RewritePlan]，由 Rewriter 阶段统一执行 —— 取代旧的 `pendingChanges/CollectedChange`
 *    闭包并行流；
 *  - [selectedAffix] / [selectedDigit] / [blockedSiteIds] 承载用户勾选的合并决策。
 *
 * P2（类型安全）：selectedAffix / selectedDigit 由曾经的 `List<Any>` 收紧为具体的
 * [AffixGroupCandidate] / [DigitGroupCandidate] 候选类型，与因式分解层模型直接对齐，
 * 避免下游用 `as?` 强转 / 类型擦除。
 */
data class ExtractionPlan(
    /** 用户勾选的公共前后缀合并组（原文承载：骨架 + 差异段）。 */
    val selectedAffix: List<AffixGroupCandidate> = emptyList(),
    /** 用户勾选的数字抽取组（差异段为数字字面量）。 */
    val selectedDigit: List<DigitGroupCandidate> = emptyList(),
    /** 被合并承载、应跳过普通单句替换的 siteId 集合。 */
    val blockedSiteIds: Set<String> = emptySet(),
    /** 全部待执行站点改写（普通替换 + 骨架合并），按收集/规划顺序。 */
    val rewrites: List<RewritePlan> = emptyList(),
    /** 计划附带说明（供 UI / 日志展示）。 */
    val description: String = "",
)

/** 单站点改写类型：决定 Rewriter 阶段采用哪种 PSI 写入原语。 */
enum class RewriteKind {
    /** 模板纯文本（一组仅空白分隔的 XmlText）→ `{{ $t('key') }}` / `{ $t('key') }`。 */
    XML_TEXT,
    /** XML 属性值 → 翻译调用（Vue `:` 绑定 / JSX `{ }` / Angular 插值）。 */
    XML_ATTRIBUTE,
    /** JS 模板字符串 / 字符串拼接 → 纯文本替换（内联 / 拼接场景）。 */
    JS_TEMPLATE,
    /** JS 字符串字面量 → 表达式替换（`'key'` 或 `$t('key')`）。 */
    JS_LITERAL,
    /** 骨架合并重写：被合并承载 site → `$t('骨架{N0}', { N0: diff })`，回填骨架资源 key。 */
    SKELETON,
}

/**
 * 单站点重写计划 —— Apply 阶段「改写动作」的唯一描述（纯数据，不含可执行闭包）。
 *
 * 取代旧的 `I18nProcessor.CollectedChange`（siteId + 闭包）双数据流：所有站点改写（含骨架
 * 合并）统一以本类型登记进 [ExtractionPlan.rewrites]，由 Rewriter 按 [kind] 派发执行，
 * 使 Apply 阶段只消费一个计划对象，不再有「计划 + 闭包」两条并行输入。
 */
data class RewritePlan(
    /** 目标 site 标识（用于经处理器定位节点 / 判断是否被合并阻塞）。 */
    val siteId: String,
    /** 改写类型。 */
    val kind: RewriteKind,
    /** 处理器下标（processors 列表中的位置）。仅 SKELETON 需要，用于定位目标 processor。 */
    val processorIndex: Int,
    /** 替换表达式/新文本（收集期或规划期已按 framework/tFunctionName 定案）。 */
    val newExpression: String,
    /** 单节点目标（XML_ATTRIBUTE / JS_TEMPLATE / JS_LITERAL 的替换根指针）。 */
    val target: SmartPsiElementPointer<PsiElement>? = null,
    /** XML_TEXT：命中该句的一组 XmlText 节点指针（仅空白分隔，需整体重写/删除）。 */
    val xmlTextPointers: List<SmartPsiElementPointer<XmlText>> = emptyList(),
    /** XML_ATTRIBUTE / SKELETON：JSX 大括号形态旗标。 */
    val isJSX: Boolean = false,
    /** XML_ATTRIBUTE：Vue 指令属性（不加 `:` 前缀）。 */
    val isDirective: Boolean = false,
    /** XML_ATTRIBUTE：Angular 属性插值形态。 */
    val isAngular: Boolean = false,
    /** SKELETON：重写后回填资源用的骨架 key（`{{0}}`/`{N0}` 形态已变换）。 */
    val skeletonKey: String? = null,
    /** SKELETON：原骨架文本（含 {N0} 占位，如 `请输入{N0}关键词`）。 */
    val skeleton: String? = null,
    /** SKELETON：差异段占位表达式列表（`N0` → diff 表达式文本）。 */
    val params: List<Pair<String, String>> = emptyList(),
)

/**
 * 收集期产物的**不可变快照** —— collect 结束（[CollectedPlan.freeze]）时生成一次，作为
 * **run/apply 阶段的唯一输入**。
 *
 * 收紧 collect/run 边界（a-mutable）：
 *  - collect 期：写入的是可变 [CollectedPlan]（经 [com.pan.extractor.analyzer.I18nAnalyzer] /
 *    [com.pan.extractor.analyzer.CollectionState]）；此时 run 期还没开始，无并发写。
 *  - run 期：只消费本快照（所有集合字段均为不可变 [List]/[Set]/[Map]），**不再触碰可变
 *    [CollectedPlan]** —— 边界由类型强制（run 拿到的是副本快照，无法反向写回收集容器）而非注释。
 */
data class CollectedResult(
    /** 命中站点列表（快照副本）。
     *  注：站点的 [ExtractionSite.replaceRootPointer] 仍是指向 PSI 的 SmartPointer，
     *  仅实例本身共享，集合快照保证 run 期无法增删站点。 */
    val collectedSites: List<ExtractionSite>,
    /** 被骨架合并承载、应跳过普通替换的 siteId 集合（快照副本）。 */
    val blockedSiteIds: Set<String>,
    /** 新提取 key → 原文本（快照副本）。 */
    val extractedStrings: Map<String, String>,
    /** 已存在 \$t() 调用 key → 原文本（快照副本，仅展示）。 */
    val existingStrings: Map<String, String>,
    /** 全部待执行站点改写配方，按收集/规划顺序（快照副本）。 */
    val rewrites: List<RewritePlan>,
    /** 本次提取锁定的框架策略（null 时回退 [GenericStrategy]）。 */
    val framework: I18nFramework,
    /** collect 期锁定的翻译函数名。 */
    val tFunctionName: String,
    /** 全局 \$t 别名注入标记。 */
    val needInjectGlobalDollarT: Boolean,
    /** React i18n.t 回退 getI18n 的 t 别名标记。 */
    val reactI18nTFallbackToDollarT: Boolean,
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
class CollectedPlan : com.pan.extractor.CollectionState {
    // ── 收集期产物 ────────────────────────────────────────────────
    /** 一次提取命中站点列表（领域模型 site，见 [com.pan.extractor.model.ExtractionSite]）。 */
    val collectedSites = mutableListOf<com.pan.extractor.model.ExtractionSite>()

    /** 被骨架合并承载、应跳过普通单句替换的 siteId 集合。 */
    val blockedSiteIds = mutableSetOf<String>()

    /** siteId 自增计数。 */
    var siteCounter = 0

    /** 新提取的 key → 原文本。 */
    override val extractedStrings = mutableMapOf<String, String>()

    /** 已存在的 \$t() 调用 key → 原文本（仅展示，不替换）。 */
    val existingStrings = mutableMapOf<String, String>()

    // ── 收集期决策 ────────────────────────────────────────────────
    /** 待执行的全部站点改写（collect 阶段收集为数据配方，Apply 阶段经解释器执行）。
     *  取代旧的 `pendingChanges`（闭包流）：站点改写一律数据化为 [RewritePlan]，使
     *  Apply 阶段只消费计划、不再有「计划 + 闭包」两条并行输入。 */
    val rewrites = mutableListOf<RewritePlan>()

    /** 当前文件检测到的框架策略。 */
    var framework: com.pan.extractor.I18nFramework? = null

    /** 检测到的翻译函数名（\$t / t / i18n.t / i18n.global.t），默认 \$t。 */
    override var tFunctionName: String = "\$t"

    /** 全局 \$t 别名注入标记（Vue/React/Solid 纯工具文件，由策略回调写入）。 */
    override var needInjectGlobalDollarT: Boolean = false

    /** React i18n.t 回退 getI18n 的 t 别名标记。 */
    var reactI18nTFallbackToDollarT: Boolean = false

    /** React 是否回退 getI18n 的结果缓存（同一 collect 只算一次）。 */
    var reactFallbackChecked: Boolean = false
    var reactFallbackResult: Boolean = false

    /**
     * 生成 collect 期产物的**不可变快照**（[CollectedResult]）。collect 结束后调用一次，
     * 之后 run/apply 阶段只消费该快照 —— 把「collect 期可变 / run 期只读」的边界由类型强制。
     * 各集合字段均复制为不可变 [List]/[Set]/[Map]，run 期无法反向写回本收集容器。
     */
    fun freeze(): CollectedResult = CollectedResult(
        collectedSites = collectedSites.toList(),
        blockedSiteIds = blockedSiteIds.toSet(),
        extractedStrings = extractedStrings.toMap(),
        existingStrings = existingStrings.toMap(),
        rewrites = rewrites.toList(),
        framework = framework ?: GenericStrategy,
        tFunctionName = tFunctionName,
        needInjectGlobalDollarT = needInjectGlobalDollarT,
        reactI18nTFallbackToDollarT = reactI18nTFallbackToDollarT,
    )

    /**
     * 原位清空所有收集期状态（供每次 collect / reset 复用同一实例，避免共享引用被替换后
     * 收集器/分析器持有的旧引用失效 —— 这正是「共享 plan 打破构造循环」的前提）。
     */
    fun clear() {
        collectedSites.clear()
        blockedSiteIds.clear()
        siteCounter = 0
        extractedStrings.clear()
        existingStrings.clear()
        rewrites.clear()
        framework = null
        tFunctionName = "\$t"
        needInjectGlobalDollarT = false
        reactI18nTFallbackToDollarT = false
        reactFallbackChecked = false
        reactFallbackResult = false
    }
}
