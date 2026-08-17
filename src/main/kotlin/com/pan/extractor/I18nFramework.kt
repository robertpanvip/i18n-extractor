package com.pan.extractor

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.psi.PsiElement

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
     * 默认实现取第一个字符串字面量参数（与现有 [I18nFoldingBuilder.extractKey] 一致）。
     */
    fun extractKey(call: JSCallExpression): String? {
        if (!isTranslationCall(call)) return null
        val firstArg = call.arguments.firstOrNull() as? JSLiteralExpression ?: return null
        return firstArg.stringValue?.takeIf { it.isNotBlank() }
    }

    // ── 耦合点 4：注入函数（第 2 步接入） ──────────────────────────────

    /** 默认翻译函数名，如 Vue `$t`、React `t`。 */
    val tFunctionName: String

    /** hook import 语句（如 `import { useI18n } from 'vue-i18n'`），无 hook 注入时为 null。 */
    val hookImport: String?

    // ── 耦合点 5：引导（第 2 步接入） ──────────────────────────────────

    /** 缺 i18n 依赖时需要安装的包名列表。 */
    val bootstrapDeps: List<String>

    /** 生成 i18n 初始化文件内容。 */
    fun buildInitFile(defaultLocale: String, entryImport: String?): String
}

/**
 * 框架策略注册表。按优先级匹配首个命中的策略，无命中回退到 [GenericStrategy]。
 *
 * 第 1 步的 [detect] 委托给现有 [Util.isVue] / [Util.isReact]，保证行为与历史完全一致；
 * 第 2 步可改为直接读取 package.json 依赖，移除对 [ProjectStructure] 布尔判定的依赖。
 */
object I18nFrameworkRegistry {

    private val strategies = mutableListOf<I18nFramework>()

    init {
        // 注册顺序即优先级顺序；detect 按此顺序首个 matches 命中即返回。
        // 当前 detect 仍委托 Util.isVue/isReact（保持行为不变），此列表为第 2 步的 matches 预留。
        register(VueI18nStrategy)
        register(ReactI18nextStrategy)
        register(GenericStrategy)
    }

    fun register(strategy: I18nFramework) {
        strategies.add(strategy)
    }

    /**
     * 按 [element] 所在文件检测框架策略。
     * 当前委托 [Util.isVue] / [Util.isReact]，与历史判定完全一致：
     *  - .vue 文件 / 依赖 vue → VueI18nStrategy
     *  - 依赖 react 且不依赖 vue → ReactI18nextStrategy
     *  - 无 package.json 时 isVue 兜底为 true → VueI18nStrategy（历史行为）
     *  - 有 package.json 但无 react/vue 依赖 → GenericStrategy
     */
    fun detect(element: PsiElement): I18nFramework {
        if (Util.isVue(element)) return VueI18nStrategy
        if (Util.isReact(element)) return ReactI18nextStrategy
        return GenericStrategy
    }
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

    override fun placeholderFor(index: Int): String = "{$index}"
    override fun paramKey(index: Int): String = index.toString()

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String =
        ReactI18nextStrategy.buildInitFile(defaultLocale, entryImport)
}
