package com.pan.extractor.strategy

import com.pan.extractor.project.Util
import com.pan.extractor.project.ProjectStructure
import com.pan.extractor.core.CollectionState
import com.pan.extractor.core.ImportManager
import com.pan.extractor.planner.HookInjectPlan
import com.pan.extractor.planner.HookTarget
import com.pan.extractor.planner.ImportPlan
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

/** react-i18next 策略：占位符 `{{0}}`、参数 key `0`（加引号）、折叠时 `{{0}}`/`{0}` 均整体替换。 */
object ReactI18nextStrategy : I18nFramework {
    override val id = "react-i18next"
    override val tFunctionName = "t"
    override val hookImport = "import { useTranslation } from 'react-i18next';"
    override val bootstrapDeps = listOf("i18next", "react-i18next")
    override val paramKeyNeedsQuote = true
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.ReactScanner
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

    override fun onGlobalDollarTNeeded(state: CollectionState) {
        state.needInjectGlobalDollarT = true
    }

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
        // 用 trimMargin("|") 而非 trimIndent：resourcesBlock 内部带 2/4 空格的相对缩进，
        // 若用 trimIndent 会被插值块的最小缩进（2sp）拉低基准，导致所有顶层行多出不该有的前导缩进。
        return """
            |import i18n from 'i18next';
            |import { initReactI18next } from 'react-i18next';
            |$importLine
            |i18n.use(initReactI18next).init({
            |  lng: '$defaultLocale',
            |  fallbackLng: '$defaultLocale',
            |$resourcesBlock});

            |export default i18n;
        """.trimMargin() + "\n"
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
        val reactModeNeedsImport = d.needInjectGlobalDollarT && (d.hasExtractedStrings || d.hasExistingStrings)
        val needGlobalI18nImport = hasAnyTCallsNeedingGlobalInstance ||
            reactModeNeedsImport || d.reactI18nTFallbackToDollarT

        if (needGlobalI18nImport) {
            if (d.tFunctionName == "i18n.t" || d.reactI18nTFallbackToDollarT ||
                (d.hasExtractedStrings && d.needInjectGlobalDollarT) || reactModeNeedsImport
            ) {
                val i18nAlreadyImported = injector.hasI18nInstanceImported(file)
                val alreadyUsesGetI18n = injector.hasReactGetI18nImported(file) || hasReactGetI18nAlias(file)
                val reactLocaleImport =
                    if (alreadyUsesGetI18n) null else injector.buildReactI18nInstanceImport(file)
                val injectReactGlobalDollarT = d.needInjectGlobalDollarT || d.reactI18nTFallbackToDollarT
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
            if (d.tFunctionName != "i18n.t" && !d.needInjectGlobalDollarT) {
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
            val compact = it.text.replace(Util.WS_COMPACT_RE, "")
            compact.contains(Util.SIGNATURE_REACT_GET_I18N_T) ||
                compact.contains(Util.SIGNATURE_REACT_GET_I18N_DOLLAR_T) ||
                compact.contains(Util.SIGNATURE_REACT_I18N_T)
        }
    }

    /** React `const i18n = getI18n()` 别名是否已存在。 */
    private fun hasReactI18nGlobalAliased(root: PsiElement): Boolean {
        val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
        return vars.any {
            it.text.replace(Util.WS_COMPACT_RE, "").contains(Util.SIGNATURE_REACT_GET_I18N_ALIAS)
        }
    }

    /** React 是否已在用 getI18n（import 或 const 别名）。 */
    private fun hasReactGetI18nAlias(root: PsiElement): Boolean =
        hasReactGlobalAllowedAliased(root) || hasReactI18nGlobalAliased(root)
}