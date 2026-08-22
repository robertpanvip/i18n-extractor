package com.pan.extractor.strategy

import com.pan.extractor.project.Util
import com.pan.extractor.core.ImportManager
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.pan.extractor.planner.ImportPlan

/**
 * Angular 策略：面向 `@ngx-translate/core`（ngx-translate）。
 *
 * 与 Vue/React/Solid/Svelte 的 `t(...)` 函数式调用不同，ngx-translate 在模板里用 **管道**：
 *   - 模板文本 / 属性插值：`{{ '你好' | translate }}`
 *   - 带参数：`{{ 'Hello {{0}}' | translate: { "0": name } }}`
 *
 * ngx-translate 的插值占位符是 `{{0}}` / `{{name}}`（与 react-i18next 的 `{{0}}` 一致），
 * 参数对象 key 需引号，因此 [interpolatePlaceholders] / placeholder / paramKey 复用
 * [ReactI18nextStrategy] 的实现。差异仅在「调用形态」：管道而非函数——由
 * [buildCallExpression] 覆盖为 `keyLiteral | translate`（[CallExpressionStrategy] 能力）。
 *
 * §架构（ScanStrategy / ImportBuildStrategy / CallExpressionStrategy 收敛）：本策略只需声明
 * 自身 scanner / 占位符 / import 计划 / 调用形态（管道），其余统一遍历、识别、注入执行由核心层
 * 复用——符合"新增一种框架 = 新增一个策略文件 + 注册一行"的收敛目标。
 *
 * 说明：模板（.html 或组件内联模板）文本/属性的提取产物是正确且自洽的管道表达式。
 * TS/JS 组件方法内裸字符串若要翻译，ngx-translate 需注入 [TranslateService]（构造函数 DI），
 * 这超出当前"函数体 Hook 注入"模型，故本策略不把 .ts 内裸字符串改写成管道（会生成位运算语义），
 * 而聚焦模板侧这一 ngx-translate 的主流用法。
 */
object AngularI18nStrategy : I18nFramework {
    override val id = "ngx-translate"
    override val tFunctionName = "translate"
    override val hookImport = "import { TranslateService } from '@ngx-translate/core';"
    override val bootstrapDeps = listOf("@ngx-translate/core")
    override val paramKeyNeedsQuote = true
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.AngularScanner
    override fun matches(element: PsiElement): Boolean = Util.isAngular(element)

    /** Angular 还需处理 .html 模板文件（管道插值）与 .ts/.js（组件/服务）。 */
    override val supportedFileSuffixes: Set<String> get() = BASE_JS_EXTENSIONS + ".html"

    /** ngx-translate 插值占位符 `{{0}}` / `{{1}}`（与 react-i18next 一致）。 */
    override fun placeholderFor(index: Int): String = ReactI18nextStrategy.placeholderFor(index)
    override fun paramKey(index: Int): String = ReactI18nextStrategy.paramKey(index)
    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    /**
     * ngx-translate 的"调用"是模板管道 `{{ 'key' | translate[ : params] }}`，
     * 而非函数调用，故忽略 [fn]，直接拼管道形态。
     */
    override fun buildCallExpression(fn: String, keyLiteral: String, paramsLiteral: String): String {
        return if (paramsLiteral.trim().replace(Util.WS_COMPACT_RE, "") == "{}") {
            "$keyLiteral | translate"
        } else {
            "$keyLiteral | translate: $paramsLiteral"
        }
    }

    /**
     * P8：Angular 站点形态。返回 [SiteForm.ANGULAR_BINDING]。
     *
     * recordChange 的形态→isVue/isReact 映射里 ANGULAR_BINDING 不映射到 isVue 也不映射到
     * isReact（与 Svelte/Solid 的互斥判定一致），从而 Merge/骨架路径用 `{{ }}` 双花括号包裹
     * （Angular 插值正是 `{{ ... }}`），且不会误走 React 的 `{ }` 单花括号 / Vue 的 `:` 前缀。
     */
    override fun getSiteForm(element: PsiElement): SiteForm = SiteForm.ANGULAR_BINDING

    /** Angular 需在组件构造函数注入 TranslateService，故不注入全局 \$t 别名。 */
    override fun detectGlobalDollarTNeeded(file: PsiFile): Boolean = false

    /**
     * §11 收敛点 — Angular 注入计划。模板侧提取后只需注入 `import { TranslateService }
     * from '@ngx-translate/core';`（供组件注入服务使用），无全局别名 / 无组件 Hook。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan {
        val imports = mutableListOf<String>()
        // TranslateService 注入面向组件 .ts/.js；.html 模板文件无脚本块，不能注入 import。
        if (d.hasExtractedStrings && !file.name.endsWith(".html", ignoreCase = true)) {
            val alreadyImported = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
                .any { it.text.contains("@ngx-translate/core") }
            if (!alreadyImported) {
                imports += "import { TranslateService } from '@ngx-translate/core';\n"
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
        // ngx-translate 的翻译资源须显式注册到 TranslateService 才会生效：
        // 注入 TranslateService，把语言包对象注册进 setTranslation，并设默认/当前语言。
        // （此前只 import zh 却从未使用 → 未使用导入 + 资源从未挂载，翻译不生效。）
        return """
            import { NgModule } from '@angular/core';
            import { TranslateModule, TranslateService } from '@ngx-translate/core';
            $importLine
            export function defaultLocale(): string {
              return '$defaultLocale';
            }

            @NgModule({
              imports: [TranslateModule.forRoot()],
            })
            export class AppI18nModule {
              constructor(translate: TranslateService) {
                translate.setDefaultLang(defaultLocale());
                translate.use(defaultLocale());
                ${if (!entryImport.isNullOrBlank()) "translate.setTranslation(defaultLocale(), zh);" else ""}
              }
            }
        """.trimIndent() + "\n"
    }
}