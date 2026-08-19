package com.pan.extractor.planner

import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.pan.extractor.I18nFramework
import com.pan.extractor.ImportManager
import com.pan.extractor.I18nProcessor
import com.pan.extractor.ProjectStructure
import com.intellij.psi.PsiFile

/**
 * Planner 层 —— 把 collect 阶段锁定的注入决策转换为 [ImportPlan] 列表（目标架构 Phase 4）。
 *
 * 核心原则（PROJECT_ANALYSIS §4）：
 * > 分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。
 *
 * 本对象把所有注入相关决策收敛为纯数据 [ImportPlan]：import 语句 / 全局别名语句 /
 * 函数体注入描述 / 框架注入类型。它只读取 collect 已锁定的注入标记与文件 PSI（用于去重、
 * 解析 i18n 实例路径、定位组件/hook 是否存在），**不执行任何写操作**；实际注入由
 * Rewriter 层（[com.pan.extractor.rewriter.ImportRewriter]）在 Apply 阶段消费 plan 完成。
 *
 * 决策与 ImportManager 的旧命令式分支（injectVueBranch / injectReactBranch /
 * injectSolidBranch）行为 1:1，仅把「要不要注入 / 注入哪几条」从中提取为数据。
 */
object ImportPlanner {

    /**
     * 把 [decision]（对应 collect 阶段锁定的 needInject* 标记）转换为一个 [ImportPlan]。
     *
     * @param injector 注入工具：复用其只读方法（buildVueI18nInstanceImport /
     *                 buildReactI18nInstanceImport / hasI18nInstanceImported /
     *                 hasReactGetI18nImported 等）来完成 import / 别名文本与去重判定，
     *                 但**不调用任何写 PSI 的方法**。缺省传 [processor] 的 [I18nProcessor.injector]。
     * @return 该文件所需的注入计划；无需注入时返回一个仅描述文件信息的空计划。
     */
    fun buildImportPlan(
        processor: I18nProcessor,
        psiFile: PsiElement,
        framework: I18nFramework,
        decision: ImportManager.InjectionDecision,
        injector: ImportManager = processor.injector,
    ): ImportPlan {
        val file = psiFile.containingFile
            ?: return ImportPlan(fileName = psiFile.javaClass.simpleName, frameworkId = framework.id)
        val imports = mutableListOf<String>()
        val aliases = mutableListOf<String>()
        val hooks = mutableListOf<HookInjectPlan>()
        val d = decision

        val isVue = framework is com.pan.extractor.VueI18nStrategy
        val isReact = framework is com.pan.extractor.ReactI18nextStrategy
        val isSolid = framework is com.pan.extractor.SolidI18nStrategy
        val isSfc = file.name.endsWith(".vue", ignoreCase = true)
        val tName = processor.analyzer.tFunctionName

        // ── Vue：全局 i18n 实例导入 + 函数体 useI18n 注入 ─────────────────────────
        if (isVue) buildVuePlan(file, tName, injector, d, imports, aliases, hooks)
        else if (isReact) buildReactPlan(file, tName, injector, d, imports, aliases, hooks)
        else if (isSolid) buildSolidPlan(file, d, imports, aliases, hooks)

        return ImportPlan(
            fileName = file.name,
            imports = imports.distinct(),
            aliases = aliases.distinct(),
            hooks = hooks.distinct(),
            frameworkId = framework.id,
            injectIntoSfcScript = isVue && isSfc,
            rewriteI18nTCallsToT = isReact && d.reactI18nTFallbackToDollarT,
        )
    }

    // ── Vue 决策（镜像旧 injectVueBranch）────────────────────────────────
    private fun buildVuePlan(
        file: PsiFile,
        tName: String,
        injector: ImportManager,
        d: ImportManager.InjectionDecision,
        imports: MutableList<String>,
        aliases: MutableList<String>,
        hooks: MutableList<HookInjectPlan>,
    ) {
        val isSfc = file.name.endsWith(".vue", ignoreCase = true)
        val hasAnyTCallsNeedingGlobalInstance = d.hasExtractedStrings ||
            (d.hasExistingStrings && (d.tFunctionName == "i18n.global.t" || d.tFunctionName == "i18n.t"))
        val vueModeNeedsImport = d.needInjectGlobalDollarT && (d.hasExtractedStrings || d.hasExistingStrings)
        val needGlobalI18nImport = hasAnyTCallsNeedingGlobalInstance || vueModeNeedsImport

        if (needGlobalI18nImport) {
            val m1 = d.tFunctionName == "i18n.global.t" ||
                (d.hasExtractedStrings && d.needInjectGlobalDollarT) || vueModeNeedsImport
            val m2 = vueModeNeedsImport ||
                (d.tFunctionName == "i18n.global.t" && d.hasExtractedStrings)
            // 镜像 ensureI18nInstanceImported：仅当 m1 或 m2 命中才注入全局 i18n 实例 import；
            // 否则（如 TSX defineComponent 走 setup useI18n）不应额外塞 `import { i18n } from ...`。
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
    }

    // ── React 决策（镜像旧 injectReactBranch + ensureI18nInstanceImported 严格判定）───────────
    private fun buildReactPlan(
        file: PsiFile,
        tName: String,
        injector: ImportManager,
        d: ImportManager.InjectionDecision,
        imports: MutableList<String>,
        aliases: MutableList<String>,
        hooks: MutableList<HookInjectPlan>,
    ) {
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
                // $t 模式：locale 可用 → 宽松(hasI18nInstanceImported)；locale 不可用 → 严格需 getI18n 命名导入
                // （老 `import i18n from 'i18next'` 顶不上，否则 const t = getI18n().t 会悬空引用）。
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
                val importsInFile = PsiTreeUtil.findChildrenOfType(
                    file, com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration::class.java
                )
                if (importsInFile.none { it.text.contains("useTranslation") }) {
                    imports += "import { useTranslation } from 'react-i18next';\n"
                }
                hooks += HookInjectPlan(HookTarget.REACT, "const { t } = useTranslation();")
            }
        }
    }

    // ── Solid 决策（镜像旧 injectSolidBranch）────────────────────────────
    private fun buildSolidPlan(
        file: PsiFile,
        d: ImportManager.InjectionDecision,
        imports: MutableList<String>,
        aliases: MutableList<String>,
        hooks: MutableList<HookInjectPlan>,
    ) {
        if (!d.hasExtractedStrings && !d.hasExistingStrings) return
        if (file.name.endsWith(".vue", ignoreCase = true)) return

        val componentFuncs = ProjectStructure.findReactComponentFunctions(file)
        val hookFuncs = ProjectStructure.findHookFunctions(file)
        val globalMode = d.needInjectSolidGlobalDollarT || (componentFuncs.isEmpty() && hookFuncs.isEmpty())

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

    // ── 纯只读辅助（复用 Injector 已有能力 / 文本级匹配，避免写 PSI）────────────

    /** vue-i18n 的 `useI18n` 是否已导入。 */
    private fun hasImportedSpecifierUseI18n(injector: ImportManager, file: PsiElement): Boolean {
        val imports = PsiTreeUtil.findChildrenOfType(
            file, com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration::class.java
        )
        return imports.any { injector.hasImportedSpecifier(it, "vue-i18n", "useI18n") }
    }

    /** Vue 全局 `const \$t = i18n.global.t` 是否已存在（宽松匹配）。 */
    private fun hasVueGlobalDollarTAliased(root: PsiElement): Boolean {
        val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
        return vars.any { it.text.replace("\\s+".toRegex(), "").contains("const\$t=i18n.global.t") }
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

    /** React `const i18n = getI18n()` 别名是否已存在（镜像 injector 的 hasReactI18nGlobalAliased）。 */
    private fun hasReactI18nGlobalAliased(root: PsiElement): Boolean {
        val vars = PsiTreeUtil.findChildrenOfType(root, JSVarStatement::class.java)
        return vars.any {
            it.text.replace("\\s+".toRegex(), "").contains("consti18n=getI18n()")
        }
    }

    /** React 是否已在用 getI18n（import 或 const 别名）—— 用于决定是否切到 locale 导入。 */
    private fun hasReactGetI18nAlias(root: PsiElement): Boolean =
        hasReactGlobalAllowedAliased(root) || hasReactI18nGlobalAliased(root)

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
        val imports = PsiTreeUtil.findChildrenOfType(
            file, com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration::class.java
        )
        return imports.any { Regex("""from\s*['"]@solid-primitives/i18n['"]""").containsMatchIn(it.text) }
    }

    /** Solid i18n 工厂 / useI18n 导入文本（复用 Locator 只读逻辑）。 */
    private fun solidImportText(file: PsiElement): String? {
        val containingFile = file.containingFile ?: return null
        val projectRoot = ProjectStructure.findProjectRoot(containingFile) ?: return null
        val initFile = com.pan.extractor.I18nInstanceLocator.findSolidI18nInstanceFileInRoot(
            projectRoot, containingFile.project
        ) ?: return null
        val importPath = com.pan.extractor.I18nInstanceLocator.resolveVueI18nImportPath(containingFile, initFile)
            ?: return null
        val initText = try { String(initFile.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { "" }
        return when {
            initText.contains("createAppI18n") -> "import { createAppI18n } from '$importPath';\n"
            Regex("""export\s+default\s+\w*[Ii]18n\w*""").containsMatchIn(initText) ->
                "import i18n from '$importPath';\n"
            else -> "import { useI18n } from '$importPath';\n"
        }
    }
}