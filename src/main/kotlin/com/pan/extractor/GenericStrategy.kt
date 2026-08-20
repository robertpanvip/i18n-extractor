package com.pan.extractor

import com.pan.extractor.planner.ImportPlan
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/** Generic 策略：无 React/Vue 依赖的项目（如纯 Node 工具）。
 * 占位符 `{0}`、参数 key `0`（加引号）；折叠插值沿用 React 路径
 * （现有代码中 isVue=false 时即走 React 分支，保持一致）。
 */
object GenericStrategy : I18nFramework {
    override val id = "generic"
    override val tFunctionName = "\$t"
    override val hookImport = null
    override val bootstrapDeps = emptyList<String>()
    override val paramKeyNeedsQuote = true
    override val scanner: com.pan.extractor.scanner.SourceScanner =
        com.pan.extractor.scanner.JsScanner

    /** Generic 恒为兜底：匹配语义由 [I18nFrameworkRegistry.detect] 的 fallback 通道兜底。 */
    override fun matches(element: PsiElement): Boolean = true

    override val isFallback: Boolean get() = true

    override fun placeholderFor(index: Int): String = "{$index}"
    override fun paramKey(index: Int): String = index.toString()

    override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
        ReactI18nextStrategy.interpolatePlaceholders(value, params)

    override fun buildInitFile(defaultLocale: String, entryImport: String?): String =
        ReactI18nextStrategy.buildInitFile(defaultLocale, entryImport)

    /**
     * §11 收敛点 — Generic 不注入任何 hook / 全局别名：只描述空计划（fileName / frameworkId）。
     */
    override fun buildImportPlan(
        file: PsiFile,
        tName: String,
        d: ImportManager.InjectionDecision,
        injector: ImportManager,
    ): ImportPlan = ImportPlan(fileName = file.name, frameworkId = id)
}