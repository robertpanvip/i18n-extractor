package com.pan.extractor

import com.pan.extractor.strategy.GenericStrategy
import com.pan.extractor.strategy.I18nFramework
import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.strategy.SolidI18nStrategy
import com.pan.extractor.strategy.VueI18nStrategy

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame

/**
 * 框架检测（[I18nFrameworkRegistry.detect]）测试矩阵。
 *
 * 覆盖 BUG_ANALYSIS 3.1/3.2 的建议：
 *  - 单框架：Vue / React / Solid / Generic 各自识别
 *  - 混合优先级：Vue+Solid / Vue+React / Solid+React
 *  - Monorepo：子包最近 package.json 优先，不受 root package.json 依赖影响
 *  - 无 package.json 的历史兜底
 *
 * 背景：`I18nFrameworkRegistry` 从"硬编码 if-else 链"改为"遍历注册表 + 各策略
 * `matches`"后，行为必须与历史完全一致。本测试即固化这一行为契约。
 */
class I18nFrameworkDetectionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // setUp 默认无 package.json；各测试自行添加。
    }

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

    /**
     * 在指定路径添加源码文件并返回其 PsiFile。带子目录路径能正确落到 VFS 上，
     * 从而让 [ProjectStructure] 向上查找最近 package.json 生效（Monorepo 场景依赖此点）。
     */
    private fun detectAt(fileName: String, text: String): I18nFramework =
        I18nFrameworkRegistry.detect(myFixture.addFileToProject(fileName, text))

    // ─────────────────────────────────────────────────────────────
    // 1. 单框架识别
    // ─────────────────────────────────────────────────────────────

    fun testDetectVueProject() {
        addPackageJson("package.json", """{ "vue": "^3" }""")
        val fw = detectAt("src/util.ts", "export const a = 1")
        assertSame("依赖 vue 的 .ts 项目应判 Vue", VueI18nStrategy, fw)
    }

    fun testDetectReactProject() {
        addPackageJson("package.json", """{ "react": "^18", "react-dom": "^18" }""")
        val fw = detectAt("src/App.tsx", "export function App() { return 'hi' }")
        assertSame("依赖 react 应判 React", ReactI18nextStrategy, fw)
    }

    fun testDetectSolidProject() {
        addPackageJson("package.json", """{ "solid-js": "^1.8" }""")
        val fw = detectAt("src/App.tsx", "export function App() { return 'hi' }")
        assertSame("依赖 solid-js 应判 Solid", SolidI18nStrategy, fw)
    }

    fun testDetectPreactAsReact() {
        addPackageJson("package.json", """{ "preact": "^10" }""")
        val fw = detectAt("src/App.tsx", "export function App() { return 'hi' }")
        assertSame("依赖 preact 应判 React", ReactI18nextStrategy, fw)
    }

    fun testDetectGenericProject() {
        addPackageJson("package.json", """{ "express": "^4" }""")
        val fw = detectAt("src/util.ts", "export function f() { return 'x' }")
        assertSame("无 vue/react/solid 依赖应判 Generic", GenericStrategy, fw)
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 混合优先级（注册顺序：Vue > Solid > React > Generic）
    // ─────────────────────────────────────────────────────────────

    fun testMixedProjectPrefersVueOverReact() {
        addPackageJson("package.json", """{ "vue": "^3", "react": "^18" }""")
        val fw = detectAt("src/util.ts", "export const a = 1")
        assertSame("vue+react 混合应优先 Vue", VueI18nStrategy, fw)
    }

    fun testMixedProjectPrefersVueOverSolid() {
        addPackageJson("package.json", """{ "vue": "^3", "solid-js": "^1.8" }""")
        val fw = detectAt("src/util.ts", "export const a = 1")
        assertSame("vue+solid 混合应优先 Vue", VueI18nStrategy, fw)
    }

    fun testMixedProjectPrefersSolidOverReact() {
        addPackageJson("package.json", """{ "solid-js": "^1.8", "react": "^18" }""")
        val fw = detectAt("src/App.tsx", "export function App() { return 'hi' }")
        assertSame("solid+react 混合应优先 Solid", SolidI18nStrategy, fw)
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Monorepo：子包最近 package.json 优先
    // ─────────────────────────────────────────────────────────────

    fun testMonorepoRootReactSubPackageVueUsesSubPackage() {
        // root 装 react，但 packages/vue-app 子包装 vue → 子包内的文件应判 Vue
        addPackageJson("package.json", """{ "react": "^18" }""")
        addPackageJson("packages/vue-app/package.json", """{ "vue": "^3" }""")
        val fw = detectAt("packages/vue-app/util.ts", "export const a = 1")
        assertSame("Monorepo 子包装 vue 应判 Vue（用最近 package.json）", VueI18nStrategy, fw)
    }

    fun testMonorepoRootVueSubPackageReactUsesSubPackage() {
        // root 装 vue，但 packages/react-app 子包装 react → 子包内的文件应判 React
        addPackageJson("package.json", """{ "vue": "^3" }""")
        addPackageJson("packages/react-app/package.json", """{ "react": "^18" }""")
        val fw = detectAt("packages/react-app/App.tsx", "export function App() { return 'hi' }")
        assertSame("Monorepo 子包装 react 应判 React（用最近 package.json）", ReactI18nextStrategy, fw)
    }

    fun testMonorepoSubPackageWithoutInheritingRootReact() {
        // root 装 react（container），子包只装 vue → 子包文件判 Vue 而非 React
        addPackageJson("package.json", """{ "react": "^18" }""")
        addPackageJson("packages/vue-only/package.json", """{ "vue": "^3" }""")
        val fw = detectAt("packages/vue-only/util.ts", "export const a = 1")
        assertSame("子包只装 vue 不应继承 root 的 react", VueI18nStrategy, fw)
    }

    fun testMonorepoSubPackageNoOwnPackageUsesRoot() {
        // 子包没有自己的 package.json → 向上命中 root package.json
        addPackageJson("package.json", """{ "solid-js": "^1.8" }""")
        val fw = detectAt("packages/app/App.tsx", "export function App() { return 'hi' }")
        assertSame("子包无 package.json 应向上命中 root 的 solid 依赖", SolidI18nStrategy, fw)
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 无 package.json 的历史兜底
    // ─────────────────────────────────────────────────────────────

    fun testNoPackageJsonTsFileFallsBackToVue() {
        // 无 package.json 的 .ts 文件：isReact=false → isVue fallback true → Vue
        val fw = detectAt("src/util.ts", "export const a = 'x'")
        assertSame("无 package.json 的 .ts 文件历史兜底为 Vue", VueI18nStrategy, fw)
    }

    // ─────────────────────────────────────────────────────────────
    // 3b. shared package / package ownership（§13：shared package 行为明确化）
    // 行为契约：文件归属 = 其自身最近的可解析 package.json；只有当路径上根本不存在
    // 更近的 package.json 时才继承 consumer / root 的依赖。
    // ─────────────────────────────────────────────────────────────

    fun testSharedPackageOwnPackageNoFrameworkStaysGeneric() {
        // root 装 react，但 packages/shared 有自己的 package.json（仅 lodash，无框架）→
        // shared 内文件判 Generic，绝不继承 root 的 react（consumers 推断被禁止）。
        addPackageJson("package.json", """{ "react": "^18" }""")
        addPackageJson("packages/shared/package.json", """{ "lodash": "^4" }""")
        val fw = detectAt("packages/shared/util.ts", "export const shared = 1")
        assertSame("shared 自有 package.json 无框架依赖应判 Generic", GenericStrategy, fw)
    }

    fun testSharedPackageOwnPackageFrameworkOwnsIt() {
        // root 装 vue，但 packages/shared 的 package.json 声明了 react →
        // shared 内文件由自身 package.json 定属 React（即便 root 是 Vue 项目）。
        addPackageJson("package.json", """{ "vue": "^3" }""")
        addPackageJson("packages/shared/package.json", """{ "react": "^18", "react-dom": "^18" }""")
        val fw = detectAt("packages/shared/ui.tsx", "export function Shared() { return 'hi' }")
        assertSame("shared 自有 package.json 声明 react 应判 React", ReactI18nextStrategy, fw)
    }

    fun testSharedWithoutOwnPackageInheritsRoot() {
        // 关键回归：共享目录下若【不存在】自有 package.json，才允许向上继承 root 的依赖；
        // 一旦存在自有 package.json（即使无框架），就由它定属、禁止 consumer 推断。
        addPackageJson("package.json", """{ "react": "^18" }""")
        val fw = detectAt("packages/shared/utils.ts", "export function util() { return 'x' }")
        assertSame("shared 无自有 package.json 才允许继承 root，此处判 React", ReactI18nextStrategy, fw)
    }

    // ─────────────────────────────────────────────────────────────
    // 5. detect 完整等价断言
    // ─────────────────────────────────────────────────────────────

    fun testRegistryContainsAllBuiltinStrategiesInPriorityOrder() {
        // 通过匹配到的顺序反推注册顺序，保证 detect 稳定
        addPackageJson("package.json", """{ "vue": "^3" }""")
        assertSame(VueI18nStrategy, I18nFrameworkRegistry.detect(myFixture.addFileToProject("a.ts", "export const a = 1")))
        addPackageJson("p2/package.json", """{ "solid-js": "^1.8" }""")
        assertSame(SolidI18nStrategy, I18nFrameworkRegistry.detect(myFixture.addFileToProject("p2/b.ts", "")))
    }

    fun testGenericIsRegistrationBasedFallback() {
        // Generic 恒 true 兜底，且排在最后 → 无其他命中时返回 Generic
        addPackageJson("package.json", """{ "lodash": "^4" }""")
        val fw = detectAt("src/util.ts", "export const a = 1")
        assertSame(GenericStrategy, fw)
    }
}