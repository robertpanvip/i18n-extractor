package com.pan.extractor

import com.pan.extractor.ui.*

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText
import com.pan.extractor.planner.HookInjectPlan
import com.pan.extractor.planner.HookTarget
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
    ImportBuildStrategy

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
     * 返回 true 时，collect() 会打 needInjectXxxGlobalDollarT=true 标记，run() 时顶部注入全局别名。
     */
    fun detectGlobalDollarTNeeded(file: PsiFile): Boolean = false
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

// ════════════════════════════════════════════════════════════════════════
// 策略实现：行为均从现有代码原样搬入
// ════════════════════════════════════════════════════════════════════════

/** vue-i18n 策略：占位符 `{N0}`、参数 key `N0`（无引号）、折叠时 `{{`/`}}` 为字面转义。 */
object VueI18nStrategy : I18nFramework {
    override val id = "vue-i18n"
    override val tFunctionName = "\$t"
    override val hookImport = "import { useI18n } from 'vue-i18n';"
    override val bootstrapDeps = listOf("vue-i18n")
    override val paramKeyNeedsQuote = false
    override fun matches(element: PsiElement): Boolean = Util.isVue(element)

    /** Vue 占位符前缀（默认 `N`）来自 [I18nSettings]，运行时读取以反映用户配置。 */
    private fun prefix(): String = I18nSettings.getInstance().vuePlaceholderPrefix()

    override fun placeholderFor(index: Int): String = "{${prefix()}$index}"
    override fun paramKey(index: Int): String = "${prefix()}$index"

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String {
        if (params.isEmpty()) return value
        var result = value
        // {{ }} 是字面花括号转义，先用占位符保护，只对单层 {N0}/{0} 做插值替换，最后还原
        result = result.replace("{{", "\u0000").replace("}}", "\u0001")
        val re = Regex("""\{N?(\d+)\}""")
        re.findAll(result).forEach { match ->
            val index = match.groupValues[1]
            val replacement = params[index] ?: return@forEach
            result = result.replace(match.value, replacement)
        }
        result = result.replace("\u0000", "{").replace("\u0001", "}")
        return result
    }

    override fun detectGlobalDollarTNeeded(file: PsiFile): Boolean {
        if (file.name.endsWith(".vue", ignoreCase = true)) return false
        val ext = file.name.substringAfterLast('.', "")
        val known = ext.equals("ts", ignoreCase = true) || ext.equals("tsx", ignoreCase = true) ||
            ext.equals("js", ignoreCase = true) || ext.equals("jsx", ignoreCase = true)
        if (!known) return false
        return ProjectStructure.findHookFunctions(file).isEmpty() &&
            ProjectStructure.findVueComponentFunctions(file).isEmpty()
    }

    override fun detectExistingTFunctionName(call: JSCallExpression): String? {
        val method = call.methodExpression as? JSReferenceExpression ?: return null
        val text = method.text
        return if (text == "i18n.global.t" || text == "i18n.global.tc") "i18n.global.t" else null
    }

    /**
     * §11 收敛点 — Vue 注入计划。镜像旧 [ImportPlanner] 的 [isVue 分支]，
     * 把「是否注入全局 i18n 实例 import / $t 别名 / useI18n（SFC / 组件 / Hook）」下沉到本策略。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan {
        val isSfc = file.name.endsWith(".vue", ignoreCase = true)
        val imports = mutableListOf<String>()
        val aliases = mutableListOf<String>()
        val hooks = mutableListOf<HookInjectPlan>()

        val hasAnyTCallsNeedingGlobalInstance = d.hasExtractedStrings ||
            (d.hasExistingStrings && (d.tFunctionName == "i18n.global.t" || d.tFunctionName == "i18n.t"))
        val vueModeNeedsImport = d.needInjectGlobalDollarT && (d.hasExtractedStrings || d.hasExistingStrings)
        val needGlobalI18nImport = hasAnyTCallsNeedingGlobalInstance || vueModeNeedsImport

        if (needGlobalI18nImport) {
            val m1 = d.tFunctionName == "i18n.global.t" ||
                (d.hasExtractedStrings && d.needInjectGlobalDollarT) || vueModeNeedsImport
            val m2 = vueModeNeedsImport ||
                (d.tFunctionName == "i18n.global.t" && d.hasExtractedStrings)
            if (m1 || m2) {
                val injectGlobalDollarT =
                    if (m1) d.needInjectGlobalDollarT
                    else (d.needInjectGlobalDollarT || d.tFunctionName != "\$t")
                if (!injector.hasI18nInstanceImported(file)) {
                    imports += injector.buildVueI18nInstanceImport(file)
                }
                if (injectGlobalDollarT && !hasVueGlobalDollarTAliased(file)) {
                    aliases += "const \$t = i18n.global.t;\n"
                }
            }
        }

        if (d.hasExtractedStrings) {
            val components = if (isSfc) emptyList() else ProjectStructure.findVueComponentFunctions(file)
            val hooksInFile = if (isSfc) emptyList() else ProjectStructure.findHookFunctions(file)
            when {
                !isSfc && components.isNotEmpty() -> {
                    if (!hasImportedSpecifierUseI18n(injector, file)) {
                        imports += "import { useI18n } from 'vue-i18n';\n"
                    }
                    hooks += HookInjectPlan(HookTarget.VUE_COMPONENT, "const { t: \$t } = useI18n();")
                }
                !isSfc && hooksInFile.isNotEmpty() -> {
                    if (!hasImportedSpecifierUseI18n(injector, file)) {
                        imports += "import { useI18n } from 'vue-i18n';\n"
                    }
                    hooks += HookInjectPlan(HookTarget.VUE_HOOK, "const { t: \$t } = useI18n();")
                }
                isSfc -> {
                    if (d.tFunctionName != "i18n.global.t") {
                        if (!hasImportedSpecifierUseI18n(injector, file)) {
                            imports += "import { useI18n } from 'vue-i18n';\n"
                        }
                        hooks += HookInjectPlan(HookTarget.VUE_SFC_SCRIPT, "const { t: \$t } = useI18n();")
                    }
                }
            }
        }

        return ImportPlan(
            fileName = file.name,
            imports = imports.distinct(),
            aliases = aliases.distinct(),
            hooks = hooks.distinct(),
            frameworkId = id,
            injectIntoSfcScript = isSfc,
            rewriteI18nTCallsToT = false,
        )
    }

    /** vue-i18n 的 `useI18n` 是否已导入。 */
    private fun hasImportedSpecifierUseI18n(injector: ImportManager, file: PsiElement): Boolean {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        return imports.any { injector.hasImportedSpecifier(it, "vue-i18n", "useI18n") }
    }

    /** Vue 全局 `const \$t = i18n.global.t` 是否已存在（宽松匹配）。 */
    private fun hasVueGlobalDollarTAliased(root: PsiElement): Boolean {
        val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
        return vars.any { it.text.replace("\\s+".toRegex(), "").contains("const\$t=i18n.global.t") }
    }

    /**
     * P7：Vue 站点形态。返回 [SiteForm.VUE_BINDING]（O(1)，无 PSI 遍历）。
     *
     * 等价于原 `recordChange` 中 `isVue = isVueFile(f) || Util.isVue(anchor)`：
     *  - [I18nFrameworkRegistry.detect] 已基于 `Util.isVue`（含 .vue 扩展名 + vue 依赖 +
     *    无 package.json 时 `!isReact` 兜底）选定本策略，故策略命中即 isVue=true。
     *  - recordChange 中 VUE_BINDING → isVue=true、isReact=false，1:1 还原原行为。
     */
    override fun getSiteForm(element: PsiElement): SiteForm = SiteForm.VUE_BINDING

    /**
     * 扫描 Vue 模板 mustache `{{ }}` 中的已有翻译 key。
     *
     * 行为从 [I18nProcessor.collectExistingTKeys] + [I18nProcessor.collectTKeysRecursive] 原样搬入：
     *  1. 遍历文件中所有 [XmlText]，仅处理含 mustache 的节点；
     *  2. 取其注入的 JS PSI，递归遍历查找 [JSCallExpression]，对每个调用回调 [onCall]
     *     （由调用方执行 collectTKeyFromCall + detectTFunctionName）；
     *  3. 无论是否有 JS 注入，都对 XmlText 原始文本回调 [onRawText]
     *     （由调用方执行 collectTKeysFromRawText，覆盖 backtick 模板字符串场景）。
     *
     * 注意：project 取自 [root] 本身（PSI 元素所属项目，与 I18nProcessor.project 一致）。
     */
    override fun collectExistingTKeysFromTemplate(
        root: PsiElement,
        onCall: (JSCallExpression) -> Unit,
        onRawText: (String) -> Unit,
    ) {
        // 1. 模板 {{ }} 中的注入 JS
        PsiTreeUtil.findChildrenOfType(root, XmlText::class.java).forEach { xmlText ->
            if (!I18nPsiTools.isMustache(xmlText.text)) return@forEach
            val injected = InjectedLanguageManager.getInstance(root.project)
                .getInjectedPsiFiles(xmlText)
            if (injected != null && injected.isNotEmpty()) {
                // 有 JS 注入：通过 PSI 遍历查找 $t() 调用
                injected.forEach { pair ->
                    pair.first.accept(object : PsiRecursiveElementWalkingVisitor() {
                        override fun visitElement(element: PsiElement) {
                            if (element is JSCallExpression) {
                                onCall(element)
                            }
                            super.visitElement(element)
                        }
                    })
                }
            }
            // 无论是否有 JS 注入，都从原始文本中补充提取 $t() 调用。
            // backtick 模板字符串 $t(`确定`) 虽然有注入但注入的 PSI 可能不包含
            // JSCallExpression，导致 $t() 调用被遗漏。
            onRawText(xmlText.text)
        }
    }

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './locales/$entryImport';\n"
        } else ""
        val messagesBlock = if (!entryImport.isNullOrBlank()) {
            "  messages: {\n    $defaultLocale: zh,\n  },\n"
        } else ""
        return """
            import { createI18n } from 'vue-i18n';
            $importLine
            const i18n = createI18n({
              legacy: false,
              locale: '$defaultLocale',
              fallbackLocale: '$defaultLocale',
            $messagesBlock});

            export default i18n;
        """.trimIndent() + "\n"
    }
}

/** react-i18next 策略：占位符 `{{0}}`、参数 key `0`（加引号）、折叠时 `{{0}}`/`{0}` 均整体替换。 */
object ReactI18nextStrategy : I18nFramework {
    override val id = "react-i18next"
    override val tFunctionName = "t"
    override val hookImport = "import { useTranslation } from 'react-i18next';"
    override val bootstrapDeps = listOf("i18next", "react-i18next")
    override val paramKeyNeedsQuote = true
    override fun matches(element: PsiElement): Boolean = Util.isReact(element)

    override fun placeholderFor(index: Int): String = "{{$index}}"
    override fun paramKey(index: Int): String = index.toString()

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String {
        if (params.isEmpty()) return value
        var result = value
        val re = Regex("""\{\{N?(\d+)\}\}|\{N?(\d+)\}""")
        re.findAll(result).forEach { match ->
            val index = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val replacement = params[index] ?: return@forEach
            result = result.replace(match.value, replacement)
        }
        return result
    }

    override fun detectGlobalDollarTNeeded(file: PsiFile): Boolean =
        ProjectStructure.findReactComponentFunctions(file).isEmpty() &&
            ProjectStructure.findHookFunctions(file).isEmpty()

    override fun detectExistingTFunctionName(call: JSCallExpression): String? {
        val method = call.methodExpression as? JSReferenceExpression ?: return null
        val text = method.text
        return if (text == "i18n.t" || text == "i18n.tc") "i18n.t" else null
    }

    /**
     * P7：React 站点形态。返回 [SiteForm.JSX_ATTRIBUTE]（O(1)，无 PSI 遍历）。
     *
     * 等价于原 `recordChange` 中 `isReact = !isVue && Util.isReact(anchor)`：
     *  - [I18nFrameworkRegistry.detect] 已基于 `Util.isReact`（`hasReact && !hasVue && !hasSolid`）
     *    选定本策略，且 Vue 优先级更高（Vue 命中时不会到 React），故策略命中即 !isVue=true。
     *  - recordChange 中 JSX_ATTRIBUTE → isVue=false、isReact=true，1:1 还原原行为。
     */
    override fun getSiteForm(element: PsiElement): SiteForm = SiteForm.JSX_ATTRIBUTE

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './locales/$entryImport';\n"
        } else ""
        val resourcesBlock = if (!entryImport.isNullOrBlank()) {
            "  resources: {\n    $defaultLocale: { translation: zh },\n  },\n"
        } else ""
        return """
            import i18n from 'i18next';
            import { initReactI18next } from 'react-i18next';
            $importLine
            i18n.use(initReactI18next).init({
              lng: '$defaultLocale',
              fallbackLng: '$defaultLocale',
            $resourcesBlock});

            export default i18n;
        """.trimIndent() + "\n"
    }

    /**
     * §11 收敛点 — React 注入计划。镜像旧 [ImportPlanner] 的 isReact 分支，
     * 把「全局 i18n 实例 import / $t 别名 / i18n 别名 / useTranslation hook」下沉到本策略。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan {
        val imports = mutableListOf<String>()
        val aliases = mutableListOf<String>()
        val hooks = mutableListOf<HookInjectPlan>()

        val hasAnyTCallsNeedingGlobalInstance = d.hasExtractedStrings ||
            (d.hasExistingStrings && (d.tFunctionName == "i18n.global.t" || d.tFunctionName == "i18n.t"))
        val reactModeNeedsImport = d.needInjectReactGlobalDollarT && (d.hasExtractedStrings || d.hasExistingStrings)
        val needGlobalI18nImport = hasAnyTCallsNeedingGlobalInstance ||
            reactModeNeedsImport || d.reactI18nTFallbackToDollarT

        if (needGlobalI18nImport) {
            if (d.tFunctionName == "i18n.t" || d.reactI18nTFallbackToDollarT ||
                (d.hasExtractedStrings && d.needInjectReactGlobalDollarT) || reactModeNeedsImport
            ) {
                val i18nAlreadyImported = injector.hasI18nInstanceImported(file)
                val alreadyUsesGetI18n = injector.hasReactGetI18nImported(file) || hasReactGetI18nAlias(file)
                val reactLocaleImport =
                    if (alreadyUsesGetI18n) null else injector.buildReactI18nInstanceImport(file)
                val injectReactGlobalDollarT = d.needInjectReactGlobalDollarT || d.reactI18nTFallbackToDollarT
                val reactDollarTImportSatisfied =
                    if (reactLocaleImport != null) i18nAlreadyImported else injector.hasReactGetI18nImported(file)
                val requiredImportAlreadyPresent =
                    if (injectReactGlobalDollarT) reactDollarTImportSatisfied else i18nAlreadyImported

                val dollarTAliasAlreadyPresent =
                    if (injectReactGlobalDollarT) hasReactGlobalAllowedAliased(file) else true
                val reactI18nAliasAlreadyPresent = hasReactI18nGlobalAliased(file)
                val reactNeedsI18nAlias = !injectReactGlobalDollarT &&
                    reactLocaleImport == null && !requiredImportAlreadyPresent

                val importText: String? = when {
                    requiredImportAlreadyPresent -> null
                    reactLocaleImport != null -> reactLocaleImport
                    else -> "import { getI18n } from 'react-i18next';\n"
                }
                val dollarTText: String? = when {
                    injectReactGlobalDollarT && !dollarTAliasAlreadyPresent ->
                        if (reactLocaleImport != null) "const $tName = i18n.t;\n"
                        else "const $tName = getI18n().t;\n"
                    reactNeedsI18nAlias && !reactI18nAliasAlreadyPresent -> "const i18n = getI18n();\n"
                    else -> null
                }
                if (importText != null) imports += importText
                if (dollarTText != null) aliases += dollarTText
            }
        }

        if (d.hasExtractedStrings) {
            if (d.tFunctionName != "i18n.t" && !d.needInjectReactGlobalDollarT) {
                val importsInFile = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
                if (importsInFile.none { it.text.contains("useTranslation") }) {
                    imports += "import { useTranslation } from 'react-i18next';\n"
                }
                hooks += HookInjectPlan(HookTarget.REACT, "const { t } = useTranslation();")
            }
        }

        return ImportPlan(
            fileName = file.name,
            imports = imports.distinct(),
            aliases = aliases.distinct(),
            hooks = hooks.distinct(),
            frameworkId = id,
            injectIntoSfcScript = false,
            rewriteI18nTCallsToT = d.reactI18nTFallbackToDollarT,
        )
    }

    /** React 全局 `const t = i18n.t` / `const t = getI18n().t` 是否已存在（宽松匹配）。 */
    private fun hasReactGlobalAllowedAliased(root: PsiElement): Boolean {
        val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
        return vars.any {
            val compact = it.text.replace("\\s+".toRegex(), "")
            compact.contains("constt=getI18n().t") ||
                compact.contains("const\$t=getI18n().t") ||
                compact.contains("constt=i18n.t")
        }
    }

    /** React `const i18n = getI18n()` 别名是否已存在。 */
    private fun hasReactI18nGlobalAliased(root: PsiElement): Boolean {
        val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
        return vars.any {
            it.text.replace("\\s+".toRegex(), "").contains("consti18n=getI18n()")
        }
    }

    /** React 是否已在用 getI18n（import 或 const 别名）。 */
    private fun hasReactGetI18nAlias(root: PsiElement): Boolean =
        hasReactGlobalAllowedAliased(root) || hasReactI18nGlobalAliased(root)
}

/** Generic 策略：无 React/Vue 依赖的项目（如纯 Node 工具）。
 * 占位符 `{0}`、参数 key `0`（加引号）；折叠插值沿用 React 路径
 * （现有代码中 isVue=false 时即走 React 分支，保持一致）。
 */
object GenericStrategy : I18nFramework {
    override val id = "generic"
    override val tFunctionName = "\$t"
    override val hookImport = null
    override val bootstrapDeps = emptyList<String>()
    override val paramKeyNeedsQuote = true

    /** Generic 恒为兜底：匹配语义由 [I18nFrameworkRegistry.detect] 的 fallback 通道兜底。 */
    override fun matches(element: PsiElement): Boolean = true

    override val isFallback: Boolean get() = true

    override fun placeholderFor(index: Int): String = "{$index}"
    override fun paramKey(index: Int): String = index.toString()

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String =
        ReactI18nextStrategy.buildInitFile(defaultLocale, entryImport)

    /**
     * §11 收敛点 — Generic 不注入任何 hook / 全局别名：只描述空计划（fileName / frameworkId）。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan = ImportPlan(fileName = file.name, frameworkId = id)
}

/**
 * SolidJS 策略：面向 `@solid-primitives/i18n` / `@solid-hooks/i18n` 等 SolidJS i18n 库。
 *
 * SolidJS 的翻译调用形态 `t('key')` 与 React-i18next 一致，占位符用 `{{0}}`，
 * 因此占位符 / 插值 / 折叠 / ↩ 图标行为完全复用 [ReactI18nextStrategy]。
 * 差异仅在框架识别（依赖 `solid-js`）和引导文件（使用 `@solid-primitives/i18n`）。
 */
object SolidI18nStrategy : I18nFramework {
    override val id = "solid-i18n"
    override val tFunctionName = "t"
    override val hookImport = "import { useI18n } from '@solid-primitives/i18n';"
    override val bootstrapDeps = listOf("@solid-primitives/i18n")
    override val paramKeyNeedsQuote = true
    override fun matches(element: PsiElement): Boolean = Util.isSolid(element)

    override fun placeholderFor(index: Int): String = ReactI18nextStrategy.placeholderFor(index)
    override fun paramKey(index: Int): String = ReactI18nextStrategy.paramKey(index)
    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './locales/$entryImport';\n"
        } else ""
        val dictLine = if (!entryImport.isNullOrBlank()) {
            "const dict = { $defaultLocale: zh };\n"
        } else ""
        val providerLine = if (!entryImport.isNullOrBlank()) {
            "  const [t, { locale }] = useI18n(dict, () => '$defaultLocale');\n"
        } else {
            "  const [t, { locale }] = useI18n({}, () => '$defaultLocale');\n"
        }
        return """
            import { useI18n } from '@solid-primitives/i18n';
            $importLine$dictLine
            export function createAppI18n() {
            $providerLine  return { t, locale };
            }
        """.trimIndent() + "\n"
    }

    /**
     * Solid 纯工具 TS 判定：与 React 对称——既无 Solid 组件（PascalCase + return JSX）
     * 也无自定义 Hook 时，视为纯工具文件，需要全局 `$t` 别名注入。
     *
     * Solid 组件语法形态与 React 一致（都是 PascalCase 函数返回 JSX），
     * 故复用 [ProjectStructure.findReactComponentFunctions] 与 [ProjectStructure.findHookFunctions]。
     */
    override fun detectGlobalDollarTNeeded(file: PsiFile): Boolean =
        ProjectStructure.findReactComponentFunctions(file).isEmpty() &&
            ProjectStructure.findHookFunctions(file).isEmpty()

    /**
     * P7：Solid 站点形态。
     *
     * 原行为：[ProjectStructure.isReact] 对 Solid 项目返回 false（`!hasSolid` 排除），
     * 故 [I18nProcessor.recordChange] 中 Solid 站点的 isReact=false。
     * 此处返回 [SiteForm.SOLID_BINDING]（而非复用 React 的 JSX_ATTRIBUTE），
     * 在 recordChange 的形态→isReact 映射中 SOLID_BINDING 不映射到 isReact，
     * 从而 1:1 保留原 Solid 行为（与任务说明中「Solid 同 React」的建议相反——
     * 那会改变 isReact 取值，破坏 MergeApplier 的占位符/包装分支）。
     */
    override fun getSiteForm(element: PsiElement): SiteForm = SiteForm.SOLID_BINDING

    /**
     * §11 收敛点 — Solid 注入计划。镜像旧 [ImportPlanner] 的 isSolid 分支，
     * 把「全局 i18n 别名 / useI18n hook」下沉到本策略。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan {
        val imports = mutableListOf<String>()
        val aliases = mutableListOf<String>()
        val hooks = mutableListOf<HookInjectPlan>()

        if (d.hasExtractedStrings || d.hasExistingStrings) {
            if (!file.name.endsWith(".vue", ignoreCase = true)) {
                val componentFuncs = ProjectStructure.findReactComponentFunctions(file)
                val hookFuncs = ProjectStructure.findHookFunctions(file)
                val globalMode = d.needInjectSolidGlobalDollarT ||
                    (componentFuncs.isEmpty() && hookFuncs.isEmpty())

                if (globalMode) {
                    val importText = solidImportText(file) ?: "import { useI18n } from '@solid-primitives/i18n';\n"
                    if (!injectorHasSolidImport(file)) {
                        imports += importText
                    }
                    if (!hasSolidDollarTAliased(file)) {
                        aliases += when {
                            importText.contains("createAppI18n") -> "const { t: \$t } = createAppI18n();\n"
                            importText.startsWith("import i18n ") -> "const \$t = i18n.t;\n"
                            else -> "const [\$t] = useI18n();\n"
                        }
                    }
                } else {
                    if (!injectorHasSolidImport(file)) {
                        imports += "import { useI18n } from '@solid-primitives/i18n';\n"
                    }
                    hooks += HookInjectPlan(HookTarget.SOLID, "const [t, { locale }] = useI18n();")
                }
            }
        }

        return ImportPlan(
            fileName = file.name,
            imports = imports.distinct(),
            aliases = aliases.distinct(),
            hooks = hooks.distinct(),
            frameworkId = id,
            injectIntoSfcScript = false,
            rewriteI18nTCallsToT = false,
        )
    }

    /** Solid 全局 `\$t` 别名是否已存在。 */
    private fun hasSolidDollarTAliased(root: PsiElement): Boolean {
        val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
        return vars.any {
            it.text.contains("= useI18n(") || it.text.contains("= createAppI18n(") ||
                it.text.replace("\\s+".toRegex(), "").contains("const\$t=")
        }
    }

    /** Solid 是否已导入 @solid-primitives/i18n 或 i18n 工厂。 */
    private fun injectorHasSolidImport(file: PsiElement): Boolean {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        return imports.any { Regex("""from\s*['"]@solid-primitives/i18n['"]""").containsMatchIn(it.text) }
    }

    /** Solid i18n 工厂 / useI18n 导入文本（复用 Locator 只读逻辑）。 */
    private fun solidImportText(file: PsiElement): String? {
        val containingFile = file.containingFile ?: return null
        val projectRoot = ProjectStructure.findProjectRoot(containingFile) ?: return null
        val initFile = I18nInstanceLocator.findSolidI18nInstanceFileInRoot(projectRoot, containingFile.project)
            ?: return null
        val importPath = I18nInstanceLocator.resolveVueI18nImportPath(containingFile, initFile) ?: return null
        val initText = try {
            String(initFile.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) { "" }
        return when {
            initText.contains("createAppI18n") -> "import { createAppI18n } from '$importPath';\n"
            Regex("""export\s+default\s+\w*[Ii]18n\w*""").containsMatchIn(initText) ->
                "import i18n from '$importPath';\n"
            else -> "import { useI18n } from '$importPath';\n"
        }
    }
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

    /** 其他（[GenericStrategy] 默认）。 */
    GENERIC,
}
