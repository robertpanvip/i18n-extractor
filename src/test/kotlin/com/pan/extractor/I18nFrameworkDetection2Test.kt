package com.pan.extractor

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.extractor.core.ImportManager
import com.pan.extractor.planner.ImportPlan
import com.pan.extractor.strategy.GenericStrategy
import com.pan.extractor.strategy.I18nFramework
import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.strategy.VueI18nStrategy
import org.junit.Assert.assertSame

/**
 * BUG_ANALYSIS 5.2 — Framework Detection Matrix 补充用例。
 *
 * 在 [I18nFrameworkDetectionTest]（Vue/Solid/React 两两混合 + Monorepo 最近 package.json 优先）
 * 基础上，进一步覆盖：
 *  - 三元混合：Vue + React + Solid 同时存在 → 按优先级判 Vue（注册顺序 Vue > Solid > React）
 *  - 自定义框架注册：`register()` 追加不破坏内置框架检测（如实说明当前实现局限）
 *  - workspace 目录形态：root 无 package.json 时，各子包最近的 package.json 各自生效
 *  - 嵌套优先：最深子树最近的 package.json 优先，覆盖外层 package.json
 *
 * 背景：`I18nFrameworkRegistry.detect` 遍历注册表按序首个 `matches` 命中即返回；
 * `readPackageJsonDependencies`（[ProjectStructure]）从文件目录向上找最近的 package.json，
 * 只依据该最近包判定依赖，不继承 root。
 */
class I18nFrameworkDetection2Test : BasePlatformTestCase() {

    private fun addPackageJson(path: String, json: String) {
        myFixture.addFileToProject(
            path,
            """
            {
              "name": "test",
              "dependencies": $json
            }
            """.trimIndent()
        )
    }

    private fun detectAt(fileName: String, text: String): I18nFramework =
        I18nFrameworkRegistry.detect(myFixture.addFileToProject(fileName, text))

    // ─────────────────────────────────────────────────────────────
    // 1. 三元混合：Vue + React + Solid 同时存在 → Vue（Vue > Solid > React）
    // ─────────────────────────────────────────────────────────────

    fun testTripleMixinPrefersVue() {
        addPackageJson("package.json", """{ "vue": "^3", "solid-js": "^1.8", "react": "^18" }""")
        val fw = detectAt("src/util.ts", "export const a = 1")
        assertSame("vue+react+solid 三元混合应优先 Vue", VueI18nStrategy, fw)
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 自定义框架注册：追加不破坏内置框架
    // ─────────────────────────────────────────────────────────────

    /**
     * 注册一个自定义框架，验证它不破坏内置框架检测，同时在 finally 中 unregister 清理。
     *
     * 现在 [I18nFrameworkRegistry] 已支持自定义注册真正参与检测：[GenericStrategy] 是
     * fallback（[I18nFramework.isFallback]=true），[detect] 在常规匹配中跳过 fallback，
     * 故注册的非 fallback 自定义框架若 `matches` 命中即可被返回，不再被 Generic 恒 true 遮蔽；
     * 并新增 [I18nFrameworkRegistry.unregister] 便于测试清理。
     */
    fun testCustomRegistrationDoesNotBreakBuiltinDetection() {
        val custom = object : I18nFramework {
            override val id = "custom-fw"
            override val tFunctionName = "\$t"
            override val hookImport: String? = null
            override val paramKeyNeedsQuote = true
            override val bootstrapDeps = emptyList<String>()
            override val scanner: com.pan.extractor.scanner.SourceScanner =
                com.pan.extractor.scanner.JsScanner
            override fun matches(element: PsiElement): Boolean = true
            override fun placeholderFor(index: Int): String = "{$index}"
            override fun paramKey(index: Int): String = index.toString()
            override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
                GenericStrategy.interpolatePlaceholders(value, params)
            override fun buildInitFile(defaultLocale: String, entryImport: String?): String = ""
            override fun buildImportPlan(
                file: PsiFile,
                tName: String,
                decision: ImportManager.InjectionDecision,
                injector: ImportManager,
            ): ImportPlan = ImportPlan(fileName = file.name, frameworkId = id)
        }

        I18nFrameworkRegistry.register(custom)
        try {
            // 内置 Vue 检测不受自定义注册影响
            addPackageJson("package.json", """{ "vue": "^3" }""")
            assertSame("注册自定义框架后内置 Vue 检测不受影响", VueI18nStrategy, detectAt("src/util.ts", "export const a = 1"))

            // 内置 React 检测同样不受影响（用独立子目录避免污染同一目录包）
            addPackageJson("react-app/package.json", """{ "react": "^18" }""")
            assertSame("注册自定义框架后内置 React 检测不受影响", ReactI18nextStrategy, detectAt("react-app/App.tsx", "export function App() { return 'hi' }"))
        } finally {
            // 清理：移除自定义框架，避免污染全局注册表影响其他测试
            I18nFrameworkRegistry.unregister(custom)
        }
    }

    /** 自定义（非 fallback）框架命中时应被 detect 返回（Generic fallback 不再遮蔽）。 */
    fun testCustomRegisteredFrameworkWinsWhenItMatches() {
        val custom = object : I18nFramework {
            override val id = "custom-fw"
            override val tFunctionName = "\$t"
            override val hookImport: String? = null
            override val paramKeyNeedsQuote = true
            override val bootstrapDeps = emptyList<String>()
            override val scanner: com.pan.extractor.scanner.SourceScanner =
                com.pan.extractor.scanner.JsScanner
            override fun matches(element: PsiElement): Boolean = true
            override fun placeholderFor(index: Int): String = "{$index}"
            override fun paramKey(index: Int): String = index.toString()
            override fun interpolatePlaceholders(value: String, params: Map<String, String>): String =
                GenericStrategy.interpolatePlaceholders(value, params)
            override fun buildInitFile(defaultLocale: String, entryImport: String?): String = ""
            override fun buildImportPlan(
                file: PsiFile,
                tName: String,
                decision: ImportManager.InjectionDecision,
                injector: ImportManager,
            ): ImportPlan = ImportPlan(fileName = file.name, frameworkId = id)
        }

        I18nFrameworkRegistry.register(custom)
        try {
            // 无 vue/react/solid 依赖的普通项目，内置策略皆不命中 → 返回自定义框架（而非 Generic）
            addPackageJson("package.json", """{ "lodash": "^4" }""")
            assertSame("自定义非 fallback 框架命中时应被 detect 返回", custom, detectAt("src/util.ts", "export const a = 1"))
        } finally {
            // 验证 unregister 后不再命中，变回 Generic 兜底
            I18nFrameworkRegistry.unregister(custom)
            assertSame("unregister 后自定义框架不再命中，Global fallback 恢复", GenericStrategy, detectAt("src/other.ts", "export const a = 1"))
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. workspace 目录形态：root 无 package.json，子包最近 package.json 各自生效
    // ─────────────────────────────────────────────────────────────

    fun testWorkspaceRootWithoutPackageJsonReactAppUsesOwn() {
        // root 无 package.json；packages/react-app 子包依赖 react
        addPackageJson("packages/react-app/package.json", """{ "react": "^18" }""")
        val fw = detectAt("packages/react-app/App.tsx", "export function App() { return 'hi' }")
        assertSame("root 无 package.json 时，react-app 子包内文件应判 React", ReactI18nextStrategy, fw)
    }

    fun testWorkspaceRootWithoutPackageJsonVueAppUsesOwn() {
        // root 无 package.json；packages/vue-app 子包依赖 vue
        addPackageJson("packages/vue-app/package.json", """{ "vue": "^3" }""")
        val fw = detectAt("packages/vue-app/util.ts", "export const a = 1")
        assertSame("root 无 package.json 时，vue-app 子包内文件应判 Vue", VueI18nStrategy, fw)
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 嵌套优先：最近 package.json 优先，最深子树优先于外层
    // ─────────────────────────────────────────────────────────────

    fun testNestedPackageJsonNearestWins() {
        // root 依赖 react、packages/a 依赖 solid-js、packages/a/b 依赖 vue
        addPackageJson("package.json", """{ "react": "^18" }""")
        addPackageJson("packages/a/package.json", """{ "solid-js": "^1.8" }""")
        addPackageJson("packages/a/b/package.json", """{ "vue": "^3" }""")
        val fw = detectAt("packages/a/b/x.ts", "export const a = 1")
        assertSame("最深子树最近的 package.json（vue）应优先，覆盖外层 solid/react", VueI18nStrategy, fw)
    }
}