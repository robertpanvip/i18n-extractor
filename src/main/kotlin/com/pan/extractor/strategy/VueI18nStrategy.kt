package com.pan.extractor.strategy

import com.pan.extractor.project.Util
import com.pan.extractor.project.ProjectStructure
import com.pan.extractor.project.I18nPsiTools
import com.pan.extractor.core.CollectionState
import com.pan.extractor.core.ImportManager
import com.pan.extractor.planner.HookInjectPlan
import com.pan.extractor.planner.HookTarget
import com.pan.extractor.planner.ImportPlan
import com.pan.extractor.ui.I18nSettings
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlText

/** vue-i18n 策略：占位符 `{N0}`、参数 key `N0`（无引号）、折叠时 `{{`/`}}` 为字面转义。 */
object VueI18nStrategy : I18nFramework {
    override val id = "vue-i18n"
    override val tFunctionName = "\$t"
    override val hookImport = "import { useI18n } from 'vue-i18n';"
    override val bootstrapDeps = listOf("vue-i18n")
    override val paramKeyNeedsQuote = false
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.VueScanner
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

    override fun onGlobalDollarTNeeded(state: CollectionState) {
        state.needInjectGlobalDollarT = true
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
        return vars.any { it.text.replace(Util.WS_COMPACT_RE, "").contains(Util.SIGNATURE_VUE_GLOBAL_T) }
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
        // 用 trimMargin("|") 而非 trimIndent：messagesBlock 内部带 2/4 空格的相对缩进，
        // 若用 trimIndent 会被插值块的最小缩进（2sp）拉低基准，导致所有顶层行多出不该有的前导缩进。
        return """
            |import { createI18n } from 'vue-i18n';
            |$importLine
            |const i18n = createI18n({
            |  legacy: false,
            |  locale: '$defaultLocale',
            |  fallbackLocale: '$defaultLocale',
            |$messagesBlock});

            |export default i18n;
        """.trimMargin() + "\n"
    }
}