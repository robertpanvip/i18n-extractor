package com.pan.extractor

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText

/**
 * i18n 框架策略接口：把原先散落在 [I18nFoldingBuilder] / [JsStringCollector] /
 * [I18nBootstrapSupport] / [I18nImportInjector] 中的「Vue vs React vs Generic」
 * 框架差异收敛为一份策略对象。
 *
 * 第 1 步（本文件）只迁移以下耦合点，且行为保持不变：
 *  - 占位符语法（资源占位符 / 参数对象 key / 是否加引号）
 *  - 折叠时的占位符插值（interpolatePlaceholders）
 *
 * 以下方法已按现有行为实现，但尚未接入业务点（留给第 2 步切换）：
 *  - [isTranslationCall] / [extractKey]：翻译调用匹配与 key 提取
 *  - [tFunctionName] / [hookImport]：注入函数名与 import 语句
 *  - [bootstrapDeps] / [buildInitFile]：缺依赖时的引导
 *
 * 新增框架只需实现本接口并在 [I18nFrameworkRegistry] 注册，无需改动各业务点。
 */
interface I18nFramework {
    /** 策略唯一标识，如 "vue-i18n" / "react-i18next" / "generic"。 */
    val id: String

    // ── 耦合点 8：框架检测（[I18nFrameworkRegistry.detect] 遍历注册表用） ──────

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

    // ── 耦合点 3：占位符语法（第 1 步已接入） ──────────────────────────

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

    // ── 耦合点 2：翻译调用匹配（第 2 步接入） ──────────────────────────

    /**
     * 判断 [call] 是否为翻译调用（`$t` / `t` / `$tc` / `tc`，含链式 `xxx.t`）。
     * 默认实现与现有 [I18nFoldingBuilder.isTranslationCall] 完全一致，Vue/React 通用；
     * React Intl 等参数形态不同的框架可重写。
     */
    fun isTranslationCall(call: JSCallExpression): Boolean {
        val method = call.methodExpression
        if (method is JSReferenceExpression) {
            val name = method.referenceName
            if (name == "\$t" || name == "t" || name == "\$tc" || name == "tc") return true
            return false
        }
        val calleeText = method?.text ?: return false
        val last = calleeText.substringAfterLast('.')
        return last == "t" || last == "\$t" || last == "tc" || last == "\$tc"
    }

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
        // 回退：模板字符串（反引号），文本级解析，排除含 ${} 插值的情况
        val text = firstArg.text
        if (text.length < 2 || text[0] != '`') return null
        val value = text.substring(1, text.length - 1)
        if (value.contains("\${")) return null
        return value.takeIf { it.isNotBlank() }
    }

    // ── 耦合点 4：注入函数（第 2 步接入） ──────────────────────────────

    /** 默认翻译函数名，如 Vue `$t`、React `t`。 */
    val tFunctionName: String

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

    // ── 耦合点 6：已有翻译 key 扫描（模板/注入 JS） ────────────────────

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

    // ── 耦合点 7：站点形态判定（P7：收敛 recordChange 的 isVue/isReact 二分） ──

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

    // ── 耦合点 5：引导（第 2 步接入） ──────────────────────────────────

    /** 缺 i18n 依赖时需要安装的包名列表。 */
    val bootstrapDeps: List<String>

    /** 生成 i18n 初始化文件内容。 */
    fun buildInitFile(defaultLocale: String, entryImport: String?): String
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

    /**
     * 按 [element] 所在文件检测框架策略，遍历注册表按序首个命中即返回；无命中回退 [GenericStrategy]。
     *
     * 注册顺序即优先级顺序，等价于历史固定 if-else 链：
     *  - `Util.isVue` → [VueI18nStrategy]（.vue 文件 / 依赖 vue）
     *  - `Util.isSolid` → [SolidI18nStrategy]（依赖 solid-js 且不依赖 vue）
     *  - `Util.isReact` → [ReactI18nextStrategy]（依赖 react 且不依赖 vue/solid-js）
     *  - 其余 → [GenericStrategy]（含"无 package.json 时 isVue 兜底为 true"的 Vue 历史行为）
     *
     * 由于 `register` 在此生效，第三方框架可通过 `register` 注册 + `matches` 自定义参与检测。
     */
    fun detect(element: PsiElement): I18nFramework =
        strategies.firstOrNull { it.matches(element) } ?: GenericStrategy
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
}

/**
 * Generic 策略：无 React/Vue 依赖的项目（如纯 Node 工具）。
 * 占位符 `{0}`、参数 key `0`（加引号）；折叠插值沿用 React 路径
 * （现有代码中 isVue=false 时即走 React 分支，保持一致）。
 */
object GenericStrategy : I18nFramework {
    override val id = "generic"
    override val tFunctionName = "\$t"
    override val hookImport = null
    override val bootstrapDeps = emptyList<String>()
    override val paramKeyNeedsQuote = true

    /** Generic 恒为兜底：排在 [I18nFrameworkRegistry] 末尾，任何元素都命中。 */
    override fun matches(element: PsiElement): Boolean = true

    override fun placeholderFor(index: Int): String = "{$index}"
    override fun paramKey(index: Int): String = index.toString()

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String =
        ReactI18nextStrategy.buildInitFile(defaultLocale, entryImport)
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
