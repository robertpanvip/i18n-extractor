package com.pan.extractor

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.pan.extractor.planner.ImportPlan

/**
 * Svelte 策略：面向 `svelte-i18n`。
 *
 * svelte-i18n 的调用形态：`t` 是一个 readable store，模板里以
 * `{$t('key')}` 引用（`$` 表示 store 自动订阅 + 取当前 formatter 函数）。因此
 * `tFunctionName` 定为 `$t`；占位符用 messageformat 的位置参数 `{0}`（与 Generic 一致），
 * 参数 key `"0"`（需引号）。
 *
 * 与 Vue 的差异仅在 **模板包装**：Svelte 用单花括号 `{$t('key')}`（而非 Vue 的 `{{ }}`
 * 双花括号），这点由 [SiteForm.SVELTE_BINDING] 驱动 analyzer 的重写分支处理。
 *
 * §架构（ScanStrategy / ImportBuildStrategy / CallExpressionStrategy）：本策略只需声明自身
 * 的 scanner / 占位符 / import 计划，其余统一遍历、表达式成型、注入执行都由核心层复用——
 * 符合"新增一种框架 = 新增一个策略文件 + 注册一行"的收敛目标。
 */
object SvelteI18nStrategy : I18nFramework {
    override val id = "svelte-i18n"
    override val tFunctionName = "\$t"
    override val hookImport = "import { t } from 'svelte-i18n';"
    override val bootstrapDeps = listOf("svelte-i18n")
    override val paramKeyNeedsQuote = true
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.SvelteScanner
    override fun matches(element: PsiElement): Boolean = Util.isSvelte(element)

    /** messageformat 位置占位符 `{0}` / `{1}`。 */
    override fun placeholderFor(index: Int): String = "{$index}"
    override fun paramKey(index: Int): String = index.toString()

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    /**
     * P7：Svelte 站点形态。返回 [SiteForm.SVELTE_BINDING]。
     *
     * analyzer 的 recordChange 映射里 SVELTE_BINDING 不映射到 isVue/isReact（isVue=false，
     * isReact=false），与 Vue/React/Solid 的互斥判定一致；但模板/属性重写分支据此产出
     * 单花括号形态 `{$t('key')}`（见 analyzer 的 SVELTE_BINDING 判断）。
     */
    override fun getSiteForm(element: PsiElement): SiteForm = SiteForm.SVELTE_BINDING

    /**
     * Svelte 纯工具 .ts/.js 文件：svelte-i18n 没有像 Vue `i18n.global.t` 那样的全局别名，
     * 也不在普通函数里用 store，因此不注入全局 \$t 别名 → 默认 false。
     * `.svelte` SFC 模板本身即天然上下文，同样无需全局别名。
     */
    override fun detectGlobalDollarTNeeded(file: PsiFile): Boolean = false

    /**
     * §11 收敛点 — Svelte 注入计划。
     * 只需注入 `import { t } from 'svelte-i18n';`（供模板 `$t` store 订阅引用），
     * 无全局别名 / 无组件 Hook。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan {
        val imports = mutableListOf<String>()
        if (d.hasExtractedStrings) {
            val alreadyImported = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
                .any { it.text.contains("svelte-i18n") }
            if (!alreadyImported) {
                imports += "import { t } from 'svelte-i18n';\n"
            }
        }
        return ImportPlan(
            fileName = file.name,
            imports = imports.distinct(),
            aliases = emptyList(),
            hooks = emptyList(),
            frameworkId = id,
            injectIntoSfcScript = file.name.endsWith(".svelte", ignoreCase = true),
            rewriteI18nTCallsToT = false,
        )
    }

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './locales/$entryImport';\n"
        } else ""
        val messagesBlock = if (!entryImport.isNullOrBlank()) {
            "addMessages('$defaultLocale', zh);\n"
        } else ""
        return """
            import { addMessages, init, getLocaleFromNavigator } from 'svelte-i18n';
            $importLine
            $messagesBlock
            init({
              fallbackLocale: '$defaultLocale',
              initialLocale: getLocaleFromNavigator() ?? '$defaultLocale',
            });
        """.trimIndent() + "\n"
    }
}