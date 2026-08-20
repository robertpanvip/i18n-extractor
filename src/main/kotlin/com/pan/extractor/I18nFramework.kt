package com.pan.extractor

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.pan.extractor.planner.ImportPlan

/**
 * BUG_ANALYSIS 5.1 — I18nFramework 能力拆分。
 *
 * 原 [I18nFramework] 是「单个上帝接口」，方法多且职责混在一起。为避免其继续膨胀，
 * 按 6 类能力切分为子接口：
 *
 *  - [DetectionStrategy]：框架识别 / 兜底 / 站点形态
 *  - [PlaceholderStrategy]：占位符语法 / 参数 key / 插值
 *  - [TranslationCallStrategy]：翻译调用匹配与 key 提取、注入函数名
 *  - [TemplateStrategy]：模板（mustache）中已有 key 扫描
 *  - [ImportStrategy]：hook import / 全局 $t 别名注入判定
 *  - [BootstrapStrategy]：缺依赖引导
 *
 * [I18nFramework] 只做「聚合」：把这些能力接口全部 extends，因此对所有调用点与
 * 各策略实现（仍实现 [I18nFramework]）保持字节级行为不变——调用方看到的仍是一个
 * 策略对象，但能力边界被显式类型化，后续可按单一能力扩展或局部实现。
 */
interface I18nFramework :
    DetectionStrategy,
    PlaceholderStrategy,
    TranslationCallStrategy,
    TemplateStrategy,
    ImportStrategy,
    BootstrapStrategy,
    ImportBuildStrategy,
    ScanStrategy,
    CallExpressionStrategy

/**
 * 能力 1 — [DetectionStrategy]：框架检测。
 * 对应原「耦合点 8（matches）+ 兜底（isFallback）+ 站点形态（getSiteForm）」。
 */
interface DetectionStrategy {
    /** 策略唯一标识，如 "vue-i18n" / "react-i18next" / "generic"。 */
    val id: String

    /**
     * 判断 [element] 是否命中本框架（决定检测顺序、参与注册表遍历）。
     *
     * 历史行为由 [I18nFrameworkRegistry.detect] 的固定 if-else 链（`Util.isVue` /
     * `Util.isSolid` / `Util.isReact`）固化。为了将"注册表"与"检测"真正连接
     * （BUG_ANALYSIS 3.1），把判定下沉为策略自身的 `matches`，`detect` 按注册顺序
     * 首个命中即返回。各内置策略的 `matches` 直接委托给原 `Util.isXxx`，保证行为完全不变；
     * 注册顺序即优先级顺序（Vue > Solid > React），最后一个 [GenericStrategy] 恒为 true 兜底。
     */
    fun matches(element: PsiElement): Boolean

    /**
     * 是否为兜底策略（fallback）。兜底策略不参与 [I18nFrameworkRegistry.detect] 的常规
     * 匹配扫描，仅在所有非兜底策略都未命中后被返回。[I18nFrameworkRegistry] 保证至少存在
     * 一个兜底策略（当前为 [GenericStrategy]），且第三方框架注册后不会被兜底遮蔽。
     */
    val isFallback: Boolean get() = false

    /**
     * 判定 [element] 所在站点的「形态」（用于 [I18nProcessor.recordChange] 推导 isVue/isReact）。
     *
     * P7 收敛点：原 [I18nProcessor.recordChange] 用 `Util.isVue(anchor) || isVueFile(f)` /
     * `Util.isReact(anchor)` 二分判定。由于 [I18nFrameworkRegistry.detect] 已基于
     * `Util.isVue` / `Util.isReact` / `Util.isSolid` 选定策略，策略本身即代表框架归属，
     * 此方法只需返回框架级常量（O(1)，无 PSI 遍历），即可 1:1 还原原 isVue/isReact 结果。
     *
     * 默认 [SiteForm.GENERIC]：[GenericStrategy] 与 [SolidI18nStrategy] 均走此路径
     * （Solid 在原 Util.isReact 中因 `!hasSolid` 被排除 → isReact=false，故 Solid 返回
     * [SiteForm.SOLID_BINDING] 但不映射到 isReact，保持与原行为一致）。
     *
     * 形态→isVue/isReact 映射（在 [I18nProcessor.recordChange] 内）：
     *  - VUE_BINDING / VUE_MUSTACHE → isVue=true
     *  - JSX_ATTRIBUTE / TEMPLATE_LITERAL → isReact=true（仅当 !isVue）
     *  - SOLID_BINDING / GENERIC → isVue=false, isReact=false（与原 Solid/Generic 一致）
     *
     * 注意：VUE_MUSTACHE / TEMPLATE_LITERAL 当前未由策略产出（避免热路径 PSI 遍历），
     * 保留枚举值供未来按元素形态细分时使用；当前 isVue/isReact 派生已覆盖这两种形态。
     */
    fun getSiteForm(element: PsiElement): SiteForm = SiteForm.GENERIC
}

/**
 * 能力 2 — [PlaceholderStrategy]：占位符语法。
 * 对应原「耦合点 3」。
 */
interface PlaceholderStrategy {
    /** 资源文件中的占位符写法，如 Vue `{N0}`、React `{{0}}`、Generic `{0}`。 */
    fun placeholderFor(index: Int): String

    /** 调用侧参数对象的 key，如 Vue `N0`（标识符）、React `0`（数字字符串）。 */
    fun paramKey(index: Int): String

    /** 参数对象 key 是否需要加引号：Vue=false（标识符）、React/Generic=true。 */
    val paramKeyNeedsQuote: Boolean

    /**
     * 将翻译值中的占位符替换为实际参数值。
     * - Vue：`{N0}`/`{0}` 单层花括号；`{{`/`}}` 是字面花括号转义，需先保护再还原。
     * - React/Generic：`{{0}}`/`{0}` 均为占位符，整体替换。
     */
    fun interpolatePlaceholders(value: String, params: Map<String, String>): String
}

/**
 * 能力 3 — [TranslationCallStrategy]：翻译调用匹配与 key 提取 + 注入函数名。
 * 对应原「耦合点 2（isTranslationCall / extractKey）+ 耦合点 4（tFunctionName / hookImport）」。
 */
interface TranslationCallStrategy {
    /**
     * 判断 [call] 是否为翻译调用（`$t` / `t` / `$tc` / `tc`，含链式 `xxx.t`）。
     *
     * 【新判定模型】「t 是弱特征，不是语义证明」：默认实现委托
     * [com.pan.extractor.analyzer.TranslationAnalyzer]——CallExpression → callee →
     * Reference Resolution → 来源证明（i18n 框架 import / hook 或工厂产物 / 插件 \$t）。
     * 只有「已证明」是翻译调用才返回 true；本地 shadow / 非 i18n import / 无法解析（UNKNOWN）
     * 一律返回 false（折叠 / 图标等展示层不再把「名字像 t」的普通调用当翻译调用）。
     *
     * React Intl 等参数形态不同的框架可重写以扩展。
     */
    fun isTranslationCall(call: JSCallExpression): Boolean =
        com.pan.extractor.analyzer.TranslationAnalyzer.isTranslationCall(call)

    /**
     * 从翻译调用中提取 key；非翻译调用或 key 不可确定时返回 null。
     *
     * 默认实现取第一个字符串字面量参数，同时支持：
     *  - 普通 string literal（`'x'` / `"x"`）→ `JSLiteralExpression.stringValue`
     *  - 模板字符串（`` `x` ``，无 `${}` 插值）→ 文本级解析（与原 inlay provider 的
     *    `extractStringValue` 一致，因为 `JSStringTemplateExpression` 不是 `JSLiteralExpression`）
     */
    fun extractKey(call: JSCallExpression): String? {
        if (!isTranslationCall(call)) return null
        val firstArg = call.arguments.firstOrNull() ?: return null
        // 优先用 PSI stringValue（普通字符串字面量）
        (firstArg as? JSLiteralExpression)?.let { lit ->
            return lit.stringValue?.takeIf { it.isNotBlank() }
        }
        // BUG_ANALYSIS 3.5：使用 PSI JSStringTemplateExpression 判断模板字符串，
        // 替代文本级 value.contains("${") 检查。
        // JSStringTemplateExpression 的 text 含完整反引号；通过检查是否含 ${ 子串
        // 判断有无插值（与原文本级判断等价，但由 PSI 保证只作用于真正的模板字面量）。
        (firstArg as? JSStringTemplateExpression)?.let { tpl ->
            val text = tpl.text
            if (text.length < 2 || text[0] != '`') return@let null
            val value = text.substring(1, text.length - 1)
            if (value.contains("\${")) return@let null
            return value.takeIf { it.isNotBlank() }
        }
        // 回退：非 PSI 模板字面量时的文本级解析
        val text = firstArg.text
        if (text.length < 2 || text[0] != '`') return null
        val value = text.substring(1, text.length - 1)
        if (value.contains("\${")) return null
        return value.takeIf { it.isNotBlank() }
    }

    /** 默认翻译函数名，如 Vue `$t`、React `t`。 */
    val tFunctionName: String

    /**
     * 探测文件中已有翻译调用使用的"长调用名"（如 Vue 的 i18n.global.t / React 的 i18n.t）。
     * 返回 null 表示无长调用，tFunctionName 保持默认（$t / t）。
     * 返回非 null 时，collect() 会把 tFunctionName 切到该长调用名（兼容历史代码）。
     *
     * 注意：纯工具文件模式（detectGlobalDollarTNeeded=true）下不调用此方法，
     * 老长调用只作兼容保留，新提取一律写短 $t/t。
     */
    fun detectExistingTFunctionName(call: JSCallExpression): String? = null

    /**
     * 判断 [element] 是否为翻译元素（非函数调用形态，如 JSX `<Trans>` / Vue `<i18n-t>` 组件）。
     * 默认 false：现有逻辑只识别 JSCallExpression 形态的翻译调用，
     * 各策略若需支持非函数调用形态可重写。
     */
    fun isTranslationElement(element: PsiElement): Boolean = false

    /**
     * 从翻译元素中提取 key；非翻译元素或 key 不可确定时返回 null。
     * 与 [extractKey] 对称，但作用于 [isTranslationElement] 命中的非函数调用元素。
     * 默认 null：与 [isTranslationElement] 默认 false 配套，保持现有行为不变。
     */
    fun extractKeyFromElement(element: PsiElement): String? = null
}

/**
 * 能力 4 — [ImportStrategy]：注入 import 判定。
 * 对应原「耦合点 4 的 hookImport + detectGlobalDollarTNeeded」。
 */
interface ImportStrategy {
    /** hook import 语句（如 `import { useI18n } from 'vue-i18n'`），无 hook 注入时为 null。 */
    val hookImport: String?

    /**
     * 判定当前文件是否需要"全局 $t 别名"注入（纯工具文件场景，无组件无 Hook）。
     * - Vue: 非 .vue SFC + 既无 Vue 组件也无自定义 Hook
     * - React: 既无 React 组件也无自定义 Hook（用 useTranslation 不能在普通函数里调）
     * - Solid/Generic: 默认 false
     * 返回 true 时，collect() 会回调 [onGlobalDollarTNeeded]，由策略写入全局别名标记。
     */
    fun detectGlobalDollarTNeeded(file: PsiFile): Boolean = false

    /**
     * P1 收敛三岔：当 [detectGlobalDollarTNeeded] 命中（纯工具文件需要全局别名注入）时，
     * 编排器把决策交回本策略自内向，由策略写入本次的 [CollectionState]（如
     * `needInjectGlobalDollarT` / `tFunctionName`）。默认空实现——消除原来编排器里
     * `is React / is Solid / is Vue` 的三岔分支与其三个并行 needInject* 布尔，
     * 新增框架只需实现本方法而无需触碰编排器。
     */
    fun onGlobalDollarTNeeded(state: CollectionState) {}
}

/**
 * 能力 5 — [TemplateStrategy]：模板（mustache）已有 key 扫描。
 * 对应原「耦合点 6」。
 */
interface TemplateStrategy {
    /**
     * 扫描框架特有的模板/注入 JS 中的已有翻译 key（如 Vue mustache `{{ }}`）。
     * 默认空实现：纯 JS/TS 文件无模板，[ReactI18nextStrategy] / [SolidI18nStrategy] /
     * [GenericStrategy] 均走主 PSI 树的 JSCallExpression 顶层遍历（由 [I18nProcessor] 负责）。
     *
     * Vue 重写以扫描 mustache 注入 JS：
     *  - [onCall] 对每个注入 JS 中的 [JSCallExpression] 调用一次（调用方据此
     *    执行 collectTKeyFromCall + detectTFunctionName，与原 collectTKeysRecursive 一致）；
     *  - [onRawText] 对每个 mustache XmlText 的原始文本调用一次（调用方据此
     *    执行 collectTKeysFromRawText，覆盖 backtick 模板字符串场景）。
     *
     * 必须保持与原 [I18nProcessor.collectExistingTKeys] 中 Vue mustache 遍历完全等价的行为：
     * 仅 Vue 策略重写，其他框架默认空实现。
     *
     * 参数类型为 [PsiElement]（而非 [PsiFile]）：[I18nProcessor.psiFile] 字段即为 PsiElement，
     * 原实现直接对其做 PsiTreeUtil.findChildrenOfType，保持一致以避免额外解析。
     */
    fun collectExistingTKeysFromTemplate(
        root: PsiElement,
        onCall: (JSCallExpression) -> Unit,
        onRawText: (String) -> Unit,
    ) {
        // 默认空实现：纯 JS/TS 文件无 mustache 模板
    }
}

/**
 * 能力 6 — [BootstrapStrategy]：缺依赖引导。
 * 对应原「耦合点 5」。
 */
interface BootstrapStrategy {
    /** 缺 i18n 依赖时需要安装的包名列表。 */
    val bootstrapDeps: List<String>

    /** 生成 i18n 初始化文件内容。 */
    fun buildInitFile(defaultLocale: String, entryImport: String?): String
}

/**
 * 能力 7 — [ImportBuildStrategy]：框架自身产出「import / hook / 全局别名注入计划」。
 *
 * §11 收敛点：原 [com.pan.extractor.planner.ImportPlanner] 用 `isVue/isReact/isSolid`
 * 三岔把注入决策写到同一个方法里，每加一个框架就多一层 if。这里把「这个框架要注入
 * 哪些 import / alias / hook」下沉为策略自身的实现；Planner 层只做「把决策交给框架，
 * 再把返回的 [ImportPlan] 数据原样透传」，从而单一策略扩展即可支持新框架，无需改 Planner。
 *
 * 具体 [ImportPlan] 内各字段（imports / aliases / hooks / injectIntoSfcScript /
 * rewriteI18nTCallsToT）由策略自行填写；[fileName] 与 [frameworkId] 由策略沿用框架 id。
 *
 * @param file 目标源文件（.vue / .ts / .tsx / .js / .jsx）。
 * @param tName 注入前已确定的翻译函数名（来自收集期 [CollectionState.tFunctionName]，
 *              与旧 import 分支 `processor.analyzer.tFunctionName` 完全一致，行为 1:1）。
 * @param decision 收集期锁定的注入决策（needInject* 标记 + 是否已有提取/已有调用）。
 * @param injector 只读复用注入工具的去重 / import 文本 / i18n 实例路径解析能力，
 *                 不调用其写 PSI 方法（本方法是 Planner 层纯决策）。
 */
interface ImportBuildStrategy {
    fun buildImportPlan(
        file: PsiFile,
        tName: String,
        decision: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan
}

/**
 * 能力 8 — [ScanStrategy]：框架声明自身所需的候选站点扫描器。
 *
 * §11 收敛点：原 [com.pan.extractor.analyzer.I18nAnalyzer] 用 `isVue/isReact/isSolid`
 * 三岔去匹配 [com.pan.extractor.scanner] 下的各扫描器。这里把「这个框架用哪个 Scanner」
 * 收敛为策略自身的常量 [scanner]；Analyzer 只需 `framework.scanner` 一行，即可消除
 * 扫描分发层面的又一处 `is Xxx` 三岔（与 [ImportBuildStrategy] 对应注入决策三岔互补）。
 */
interface ScanStrategy {
    /**
     * 本框架扫描候选站点使用的 [SourceScanner]。
     * 各策略声明各自单例扫描器；[VueI18nStrategy]/[ReactI18nextStrategy]/
     * [SolidI18nStrategy] 分别返回 VueScanner/ReactScanner/SolidScanner，
     * [GenericStrategy] 返回 JsScanner —— 与原 I18nAnalyzer 三岔映射完全一致。
     */
    val scanner: com.pan.extractor.scanner.SourceScanner
}

/**
 * 能力 9 — [CallExpressionStrategy]：翻译调用「表达式」的组装。
 *
 * §react-intl 收敛点：原 [com.pan.extractor.JsStringCollector.buildTFunctionExpr] 把翻译调用
 * **硬编码**成 `fn('key'[, params])` 形态——函数名 + 裸字符串首参。这无法承载 react-intl 的
 * `formatMessage({ id: 'key' }, values)`（首参是对象描述符而非裸字符串），属于「表达式生成」
 * 未被策略化。本能力把"如何把 key + 参数对象拼成调用表达式"下沉为策略自身实现：
 *
 *  - 默认（Vue / react-i18next / Solid）沿用历史拼法 `fn('key'[params])`，行为 1:1；
 *  - react-intl 覆盖为 `formatMessage({ id: 'key' }, values)`，从而**只用策略文件**就能切换调用形态。
 *
 * 该接口紧邻 [TranslationCallStrategy] 的 `tFunctionName` 语义：默认形态即用它做函数名，
 * 但首参结构差异由本能力承载——这正是"函数名 vs 调用形态"的拆分。
 */
interface CallExpressionStrategy {
    /**
     * 把翻译 key 与参数对象组装成完整调用表达式文本（不含调用方的引号/转义，key 已按引号类型包装）。
     *
     * @param fn 已确定的翻译函数名（如 `t` / `$t` / `formatMessage`）。
     * @param keyLiteral 已按引号类型包装好的 key 字面量（如 `'你好'` 或 `` `你好` ``）。
     * @param paramsLiteral 参数对象字面量（可能为 `{}`）。
     * @return 完整调用表达式，如 `t('你好')`、`t('你好', { "0": x })`、
     *         `formatMessage({ id: '你好' })`、`formatMessage({ id: '你好' }, { "0": x })`。
     */
    fun buildCallExpression(fn: String, keyLiteral: String, paramsLiteral: String): String {
        return if (paramsLiteral.trim().replace(Util.WS_COMPACT_RE, "") == "{}") {
            "$fn($keyLiteral)"
        } else {
            "$fn($keyLiteral, $paramsLiteral)"
        }
    }
}

/**
 * 框架策略注册表。按优先级匹配首个命中的策略，无命中回退到 [GenericStrategy]。
 *
 * [detect] 委托给现有 [Util.isVue] / [Util.isReact] / [Util.isSolid]，
 * 保证 Vue / React 行为与历史完全一致，新增 Solid 识别。
 */
object I18nFrameworkRegistry {

    private val strategies = mutableListOf<I18nFramework>()

    init {
        // 注册顺序即优先级顺序；detect 按此顺序首个 matches 命中即返回。
        register(VueI18nStrategy)
        register(SolidI18nStrategy)
        // Svelte 靠 .svelte 扩展名或 svelte 依赖识别；置于 React 之前使 svelte 项目优先命中。
        register(SvelteI18nStrategy)
        // Angular（ngx-translate）靠 @angular/core / @ngx-translate/core 依赖识别；置于 React 之前。
        register(AngularI18nStrategy)
        // react-intl 与 react-i18next 共存：两者都靠 Util.isReact 识别，靠用户设置
        // （React 多语言库 → react-intl）区分。注册顺序放 react-i18next 之前，
        // 使「选了 react-intl」时 detect 优先命中 ReactIntlStrategy；默认 i18next 时
        // ReactIntlStrategy.matches 恒 false → 落到 ReactI18nextStrategy。
        register(ReactIntlStrategy)
        register(ReactI18nextStrategy)
        register(GenericStrategy)
    }

    fun register(strategy: I18nFramework) {
        strategies.add(strategy)
    }

    /** 反注册一个策略，用于测试清理或热卸载第三方框架。 */
    fun unregister(strategy: I18nFramework) {
        strategies.remove(strategy)
    }

    /**
     * 按 [element] 所在文件检测框架策略，遍历注册表按序首个命中即返回；无命中回退 fallback 策略。
     *
     * 注册顺序即优先级顺序，等价于历史固定 if-else 链：
     *  - `Util.isVue` → [VueI18nStrategy]（.vue 文件 / 依赖 vue）
     *  - `Util.isSolid` → [SolidI18nStrategy]（依赖 solid-js 且不依赖 vue）
     *  - `Util.isReact` → [ReactI18nextStrategy]（依赖 react 且不依赖 vue/solid-js）
     *  - 其余 → fallback（[GenericStrategy]，含"无 package.json 时 isVue 兜底为 true"的 Vue 历史行为）
     *
     * fallback 策略（[I18nFramework.isFallback] = true，当前即 [GenericStrategy]）不会参与
     * 常规匹配扫描，而是在所有非 fallback 策略都未命中后作为兜底返回。这样底层的
     * [GenericStrategy] 就不会遮蔽其后 register() 追加的第三方策略——第三方框架通过
     * `register` 注册 + `matches` 自定义即可真正参与检测（BUG_ANALYSIS 5.2 自定义注册）。
     */
    fun detect(element: PsiElement): I18nFramework {
        var fallback: I18nFramework = GenericStrategy
        for (s in strategies) {
            if (s.isFallback) {
                fallback = s
                continue
            }
            if (s.matches(element)) return s
        }
        return fallback
    }

    /** 当前注册的全部策略（含 fallback），供断言/调试使用。只读快照。 */
    fun registeredStrategies(): List<I18nFramework> = strategies.toList()
}

/**
 * P7：站点形态枚举。用于 [I18nFramework.getSiteForm] → [I18nProcessor.recordChange]
 * 推导 isVue/isReact，替代原 `Util.isVue(anchor) || isVueFile(f)` / `Util.isReact(anchor)` 二分。
 *
 * 当前各策略产出的形态（O(1) 框架级常量，无 PSI 遍历，保证热路径性能）：
 *  - [VueI18nStrategy] → VUE_BINDING（isVue=true）
 *  - [ReactI18nextStrategy] → JSX_ATTRIBUTE（isReact=true）
 *  - [SolidI18nStrategy] → SOLID_BINDING（isVue=false, isReact=false，与原行为一致）
 *  - [GenericStrategy] → GENERIC（isVue=false, isReact=false）
 *
 * VUE_MUSTACHE / TEMPLATE_LITERAL 当前未产出（避免热路径 PSI 遍历区分 mustache/模板字面量），
 * 保留枚举值供未来按元素形态细分；recordChange 的映射已兼容这两种形态（均映射到对应框架）。
 */
enum class SiteForm {
    /** JS 模板字面量 `...`（React/Generic，当前未产出，预留）。 */
    TEMPLATE_LITERAL,

    /** JSX 属性 prop="中文"（React，[ReactI18nextStrategy] 产出）。 */
    JSX_ATTRIBUTE,

    /** Vue 指令绑定 :prop="..." / 普通文本（[VueI18nStrategy] 产出）。 */
    VUE_BINDING,

    /** Vue mustache `{{ }}`（当前未产出，预留）。 */
    VUE_MUSTACHE,

    /** SolidJS 属性（[SolidI18nStrategy] 产出，不映射到 isReact）。 */
    SOLID_BINDING,

    /** Svelte SFC 模板文本/属性（[SvelteI18nStrategy] 产出，单花括号 `{$t(...)}`，不映射到 isReact）。 */
    SVELTE_BINDING,

    /** Angular .html 模板文本/属性（[AngularI18nStrategy] 产出，插值 `{{ 'k' | translate }}`，不映射到 isReact）。 */
    ANGULAR_BINDING,

    /** 其他（[GenericStrategy] 默认）。 */
    GENERIC,
}