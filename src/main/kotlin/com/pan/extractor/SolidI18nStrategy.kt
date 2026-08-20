package com.pan.extractor

import com.pan.extractor.planner.HookInjectPlan
import com.pan.extractor.planner.HookTarget
import com.pan.extractor.planner.ImportPlan
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil

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
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.SolidScanner
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

    override fun onGlobalDollarTNeeded(state: CollectionState) {
        state.needInjectGlobalDollarT = true
        state.tFunctionName = "\$t" // Solid 默认是 t，纯工具 TS 统一改 $t（与 Vue 一致）
    }

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
                val globalMode = d.needInjectGlobalDollarT ||
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
                it.text.replace(Util.WS_COMPACT_RE, "").contains(Util.SIGNATURE_SOLID_GLOBAL_T)
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