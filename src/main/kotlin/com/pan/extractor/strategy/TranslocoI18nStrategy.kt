package com.pan.extractor.strategy

import com.pan.extractor.project.Util
import com.pan.extractor.core.ImportManager
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.pan.extractor.planner.ImportPlan

/**
 * Transloco 策略：面向 `@jsverse/transloco`（更名后，原 @ngneat/transloco）。
 *
 * 与 [AngularI18nStrategy]（ngx-translate）形态完全一致——模板用**管道**而非函数：
 *   - 模板文本 / 属性插值：`{{ '你好' | transloco }}`
 *   - 带参数：`{{ 'Hello {{0}}' | transloco: { "0": name } }}`
 *
 * 差异仅在依赖名（`@jsverse/transloco`）与管道名（`transloco` 而非 `translate`），
 * 及服务类（[TranslocoService] 而非 TranslateService，但均靠构造注入）。插值占位符
 * `{{0}}` 与参数 key 复用 [ReactI18nextStrategy] 实现。
 *
 * §架构（ScanStrategy / ImportBuildStrategy / CallExpressionStrategy 收敛）：与 Angular 一致，
 * 只需声明自身 scanner / 占位符 / import 计划 / 调用形态（管道），其余统一遍历、识别、注入执行
 * 由核心层复用。
 */
object TranslocoI18nStrategy : I18nFramework {
    override val id = "transloco"
    override val tFunctionName = "transloco"
    override val hookImport = "import { TranslocoService } from '@jsverse/transloco';"
    override val bootstrapDeps = listOf("@jsverse/transloco")
    override val paramKeyNeedsQuote = true
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.AngularScanner
    override fun matches(element: PsiElement): Boolean = Util.isTransloco(element)

    /** 与 Angular 一致：还需处理 .html 模板文件（管道插值）与 .ts/.js（组件/服务）。 */
    override val supportedFileSuffixes: Set<String> get() = BASE_JS_EXTENSIONS + ".html"

    /** Transloco 插值占位符 `{{0}}` / `{{1}}`（与 react-i18next / ngx-translate 一致）。 */
    override fun placeholderFor(index: Int): String = ReactI18nextStrategy.placeholderFor(index)
    override fun paramKey(index: Int): String = ReactI18nextStrategy.paramKey(index)
    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    /**
     * Transloco 的"调用"是模板管道 `{{ 'key' | transloco[ : params] }}`，忽略 [fn]，直接拼管道形态。
     */
    override fun buildCallExpression(fn: String, keyLiteral: String, paramsLiteral: String): String {
        return if (paramsLiteral.trim().replace(Util.WS_COMPACT_RE, "") == "{}") {
            "$keyLiteral | transloco"
        } else {
            "$keyLiteral | transloco: $paramsLiteral"
        }
    }

    /** Transloco 站点形态：与 Angular 同为插值 `{{ }}` 双花括号包裹。 */
    override fun getSiteForm(element: PsiElement): SiteForm = SiteForm.ANGULAR_BINDING

    /** Transloco 需在组件构造函数注入 TranslocoService，故不注入全局 \$t 别名。 */
    override fun detectGlobalDollarTNeeded(file: PsiFile): Boolean = false

    /**
     * §11 收敛点 — Transloco 注入计划。与 Angular 一致：模板侧提取后只需注入
     * `import { TranslocoService } from '@jsverse/transloco';`（供组件注入服务使用）。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan {
        val imports = mutableListOf<String>()
        if (d.hasExtractedStrings && !file.name.endsWith(".html", ignoreCase = true)) {
            val alreadyImported = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
                .any { it.text.contains("@jsverse/transloco") || it.text.contains("@ngneat/transloco") }
            if (!alreadyImported) {
                imports += "import { TranslocoService } from '@jsverse/transloco';\n"
            }
        }
        return ImportPlan(
            fileName = file.name,
            imports = imports.distinct(),
            aliases = emptyList(),
            hooks = emptyList(),
            frameworkId = id,
            injectIntoSfcScript = false,
            rewriteI18nTCallsToT = false,
        )
    }

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './$entryImport';\n"
        } else ""
        // Transloco 同样需注入 TranslocoService 并注册语言包（transloco 的 setTranslation 注册默认作用域）。
        val setTranslationLine = if (!entryImport.isNullOrBlank()) {
            "    this.translocoService.setTranslation(zh, '', '$defaultLocale');\n"
        } else ""
        return """
            import { NgModule } from '@angular/core';
            import { TranslocoModule, TranslocoService } from '@jsverse/transloco';
            $importLine
            export function defaultLocale(): string {
              return '$defaultLocale';
            }

            @NgModule({
              imports: [TranslocoModule],
            })
            export class AppI18nModule {
              constructor(private translocoService: TranslocoService) {
                this.translocoService.setActiveLang(defaultLocale());
                $setTranslationLine}
            }
        """.trimIndent() + "\n"
    }
}