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
    override fun matches(element: PsiElement): Boolean =
        Util.isReact(element) &&
            com.pan.extractor.ui.I18nSettings.getInstance().reactLibrary() !=
                com.pan.extractor.ui.ReactLibrary.REACT_INTL

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
            "import zh from './$entryImport';\n"
        } else ""
        val resourcesBlock = if (!entryImport.isNullOrBlank()) {
            "  resources: {\n    '$defaultLocale': { translation: zh },\n  },\n"
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

        // 先计算 shouldInjectUseTranslation，用于下面判断是否跳过全局导入
        val alreadyHasGlobalI18nInstance = injector.hasI18nInstanceImported(file)
        // 组件文件（无全局 $t 需求）注入 useTranslation；纯工具文件走全局导入。
        // 注意：不检查 d.tFunctionName != "i18n.t"——组件文件即使有 i18n.t 调用也应注入
        // useTranslation hook（老 i18n.t 调用保留不走全局导入）。
        val shouldInjectUseTranslation = !alreadyHasGlobalI18nInstance &&
            !d.needInjectGlobalDollarT

        // 全局导入：仅对纯工具文件（非组件）注入 locale import 或 getI18n 回退。
        // 组件文件跳过此块，走下面 useTranslation hook 注入。
        if (needGlobalI18nImport && !shouldInjectUseTranslation) {
            if (d.tFunctionName == "i18n.t" || d.reactI18nTFallbackToDollarT ||
                d.hasExtractedStrings || reactModeNeedsImport
            ) {
                val reactLocaleImport = injector.buildReactI18nInstanceImport(file)
                val injectReactGlobalDollarT = d.needInjectGlobalDollarT || d.reactI18nTFallbackToDollarT

                // 当 injectReactGlobalDollarT 为 true 时，必须检查是否已有 getI18n 命名导入，
                // 不能把 `import i18n from 'i18next'` 视作"已满足"（否则会生成无 import 的
                // getI18n().t 别名，运行时报 ReferenceError: getI18n is not defined）。
                val requiredImportAlreadyPresent = if (injectReactGlobalDollarT) {
                    injector.hasReactGetI18nImported(file)
                } else {
                    alreadyHasGlobalI18nInstance
                }

                val dollarTAliasAlreadyPresent =
                    if (injectReactGlobalDollarT) hasReactGlobalAllowedAliased(file) else true

                // 纯工具文件（needInjectGlobalDollarT = true）且无 locale 实例，
                // 且文件本身没有任何 i18n 导入时，不生成 getI18n 回退，避免无意义的注入。
                // 但文件已有 `import i18n from 'i18next'` 等旧 i18n 导入时，
                // 仍应注入 getI18n（因为旧导入不提供 getI18n）。
                val hasLocaleInstance = reactLocaleImport != null
                val skipGetI18nFallback = d.needInjectGlobalDollarT &&
                    !hasLocaleInstance &&
                    !d.reactI18nTFallbackToDollarT &&
                    !alreadyHasGlobalI18nInstance

                val importText: String? = when {
                    requiredImportAlreadyPresent -> null
                    reactLocaleImport != null -> reactLocaleImport
                    skipGetI18nFallback -> null
                    injectReactGlobalDollarT -> "import { getI18n } from 'react-i18next';\n"
                    else -> null
                }
                val dollarTText: String? = when {
                    injectReactGlobalDollarT && !dollarTAliasAlreadyPresent && !skipGetI18nFallback ->
                        if (reactLocaleImport != null) "const $tName = i18n.t;\n"
                        else "const $tName = getI18n().t;\n"
                    else -> null
                }
                if (importText != null) imports += importText
                if (dollarTText != null) aliases += dollarTText
            }
        }

        // 组件级 useTranslation hook：对非纯工具文件（组件 / hook 文件）注入。
        // 例外：文件已显式导入全局 i18n 实例（`import i18n from 'i18next'` 或
        // `import { getI18n } ...`）时，`i18n.t(...)` 视为有效的全局调用，不再注入组件级
        // useTranslation hook（避免把已走全局实例的老代码误切到组件 hook）。
        if (shouldInjectUseTranslation) {
            val importsInFile = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
            if (importsInFile.none { it.text.contains("useTranslation") }) {
                imports += "import { useTranslation } from 'react-i18next';\n"
            }
            hooks += HookInjectPlan(HookTarget.REACT, "const { t } = useTranslation();")
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
}