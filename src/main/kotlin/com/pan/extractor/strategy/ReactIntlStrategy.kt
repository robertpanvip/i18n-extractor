package com.pan.extractor.strategy

import com.pan.extractor.project.Util
import com.pan.extractor.core.ImportManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.pan.extractor.planner.HookInjectPlan
import com.pan.extractor.planner.HookTarget
import com.pan.extractor.planner.ImportPlan

/**
 * react-intl 策略 —— 由用户设置「React 多语言库 → react-intl」激活。
 *
 * §架构验证：react-intl 与 react-i18next 的**调用形态差异**主要体现在「首参是对象描述符」
 * （`formatMessage({ id: 'key' }, values)`）而非函数名本身。靠 [CallExpressionStrategy]，
 * 本策略**只需自身文件**即可切换调用形态，无需改 `JsStringCollector` / `ImportPlanner`。
 *
 * 激活条件（[matches]）：当前文件命中 React（复用 [Util.isReact]）且设置选了 react-intl，
 * 从而使 [I18nFrameworkRegistry] 在「选择 react-intl」时优先命中本策略（注册顺序在
 * [ReactI18nextStrategy] 之前），默认（i18next）时 [matches] 恒 false → 回到 react-i18next。
 */
object ReactIntlStrategy : I18nFramework {
    override val id = "react-intl"
    override val tFunctionName = "formatMessage"
    override val hookImport = "import { useIntl } from 'react-intl';"
    override val bootstrapDeps = listOf("react-intl")
    override val paramKeyNeedsQuote = true
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.ReactScanner

    override fun matches(element: PsiElement): Boolean =
        Util.isReact(element) &&
            com.pan.extractor.ui.I18nSettings.getInstance().reactLibrary() ==
                com.pan.extractor.ui.ReactLibrary.REACT_INTL

    /** react-intl 使用 ICU 消息格式，位置参数占位符为单花括号 `{0}`。 */
    override fun placeholderFor(index: Int): String = "{$index}"
    override fun paramKey(index: Int): String = index.toString()

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String {
        if (params.isEmpty()) return value
        var result = value
        val re = Regex("""\{(\d+)\}""")
        re.findAll(result).forEach { match ->
            val replacement = params[match.groupValues[1]] ?: return@forEach
            result = result.replace(match.value, replacement)
        }
        return result
    }

    override fun getSiteForm(element: PsiElement): SiteForm = SiteForm.JSX_ATTRIBUTE

    /**
     * react-intl 调用形态：`formatMessage({ id: 'key' }[, values])`。
     * 覆盖 [CallExpressionStrategy] 的默认裸字符串拼法（`fn('key')`）。
     */
    override fun buildCallExpression(fn: String, keyLiteral: String, paramsLiteral: String): String {
        val idObject = "{ id: $keyLiteral }"
        return if (paramsLiteral.trim().replace(Util.WS_COMPACT_RE, "") == "{}") {
            "$fn($idObject)"
        } else {
            "$fn($idObject, $paramsLiteral)"
        }
    }

    override fun detectExistingTFunctionName(call: JSCallExpression): String? {
        val method = call.methodExpression as? JSReferenceExpression ?: return null
        val text = method.text
        return if (text == "intl.formatMessage" || text == "props.intl.formatMessage") text else null
    }

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './$entryImport';\n"
        } else ""
        val messagesLine = if (!entryImport.isNullOrBlank()) "  messages: zh,\n" else ""
        // react-intl 官方命令式 API：createIntl 返回 intl 实例（含 formatMessage 等方法），
        // 应用根节点用 <RawIntlProvider value={intl}> 包裹后，组件内 useIntl() 即可取到同一实例。
        // 文件为 .ts 不含 JSX，故不生成 <IntlProvider> 组件而是导出 intl 实例。
        return """
            |import { createIntl, createIntlCache } from 'react-intl';
            |$importLine
            |const cache = createIntlCache();
            |
            |const intl = createIntl({
            |  locale: '$defaultLocale',
            |$messagesLine}, cache);
            |
            |export default intl;
        """.trimMargin() + "\n"
    }

    /**
     * react-intl 注入计划：为组件注入 `useIntl()` 并解构 `formatMessage`。
     * 无需全局 i18n 实例 / getI18n 别名（与 react-i18next 的全局回退不同）。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        decision: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan {
        if (!decision.hasExtractedStrings) {
            return ImportPlan(fileName = file.name, frameworkId = id)
        }
        val imports = mutableListOf<String>()
        val hooks = mutableListOf<HookInjectPlan>()

        val importsInFile = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        if (importsInFile.none { it.text.contains("useIntl") }) {
            imports += "import { useIntl } from 'react-intl';\n"
        }
        hooks += HookInjectPlan(HookTarget.REACT, "const { formatMessage } = useIntl();")

        return ImportPlan(
            fileName = file.name,
            imports = imports.distinct(),
            aliases = emptyList(),
            hooks = hooks.distinct(),
            frameworkId = id,
            injectIntoSfcScript = false,
            rewriteI18nTCallsToT = false,
        )
    }

    override fun collectExistingTKeysFromTemplate(
        root: PsiElement,
        onCall: (JSCallExpression) -> Unit,
        onRawText: (String) -> Unit,
    ) {
    }

    fun isMaybeTranslationLiteral(lit: JSLiteralExpression): Boolean = true
}