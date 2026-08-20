package com.pan.extractor.core

import com.pan.extractor.bootstrap.I18nBootstrapSupport
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.strategy.SolidI18nStrategy
import com.pan.extractor.strategy.VueI18nStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 i18n 引导（Bootstrap）支持类：依赖检测 / 初始化文件生成 / package.json 依赖追加。
 * 全部为纯函数，不依赖 IDE 平台。
 */
class I18nBootstrapSupportTest {

    // ── detectMissing ─────────────────────────────────────────

    @Test
    fun reactProjectMissingDepsFlagsBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "react": "^18" } }""",
            hasInitFile = false,
            hasReactDep = true,
            hasVueDep = false,
        )
        assertNotNull("React 缺 i18next 应命中", missing)
        assertEquals(ReactI18nextStrategy, missing!!.framework)
        assertEquals(listOf("i18next", "react-i18next"), missing.depsToAdd)
    }

    @Test
    fun reactProjectWithI18nextNoBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "react": "^18", "i18next": "^23" } }""",
            hasInitFile = false,
            hasReactDep = true,
            hasVueDep = false,
        )
        assertNull("已装 i18next 不应命中", missing)
    }

    @Test
    fun reactProjectWithInitFileNoBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "react": "^18" } }""",
            hasInitFile = true,
            hasReactDep = true,
            hasVueDep = false,
        )
        assertNull("已初始化不应命中", missing)
    }

    @Test
    fun vueProjectMissingVueI18nFlagsBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "vue": "^3" } }""",
            hasInitFile = false,
            hasReactDep = false,
            hasVueDep = true,
        )
        assertNotNull("Vue 缺 vue-i18n 应命中", missing)
        assertEquals(VueI18nStrategy, missing!!.framework)
        assertEquals(listOf("vue-i18n"), missing.depsToAdd)
    }

    @Test
    fun vueProjectWithVueI18nNoBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "vue": "^3", "vue-i18n": "^9" } }""",
            hasInitFile = false,
            hasReactDep = false,
            hasVueDep = true,
        )
        assertNull("已装 vue-i18n 不应命中", missing)
    }

    @Test
    fun nonFrameworkProjectNoBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": {} }""",
            hasInitFile = false,
            hasReactDep = false,
            hasVueDep = false,
        )
        assertNull("非 React/Vue/Solid 不应命中", missing)
    }

    // ── SolidJS ───────────────────────────────────────────────

    @Test
    fun solidProjectMissingDepsFlagsBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "solid-js": "^1.8" } }""",
            hasInitFile = false,
            hasReactDep = false,
            hasVueDep = false,
            hasSolidDep = true,
        )
        assertNotNull("Solid 缺 @solid-primitives/i18n 应命中", missing)
        assertEquals(SolidI18nStrategy, missing!!.framework)
        assertEquals(listOf("@solid-primitives/i18n"), missing.depsToAdd)
    }

    @Test
    fun solidProjectWithI18nDepNoBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "solid-js": "^1.8", "@solid-primitives/i18n": "^2.0" } }""",
            hasInitFile = false,
            hasReactDep = false,
            hasVueDep = false,
            hasSolidDep = true,
        )
        assertNull("已装 @solid-primitives/i18n 不应命中", missing)
    }

    @Test
    fun solidProjectWithInitFileNoBootstrap() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "solid-js": "^1.8" } }""",
            hasInitFile = true,
            hasReactDep = false,
            hasVueDep = false,
            hasSolidDep = true,
        )
        assertNull("已初始化不应命中", missing)
    }

    @Test
    fun solidVueMixedProjectPrefersVue() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "solid-js": "^1.8", "vue": "^3" } }""",
            hasInitFile = false,
            hasReactDep = false,
            hasVueDep = true,
            hasSolidDep = true,
        )
        assertNotNull("混合项目应命中", missing)
        assertEquals("Vue 优先级高于 Solid", VueI18nStrategy, missing!!.framework)
    }

    @Test
    fun solidReactMixedProjectPrefersSolid() {
        val missing = I18nBootstrapSupport.detectMissing(
            packageJsonText = """{ "dependencies": { "solid-js": "^1.8", "react": "^18" } }""",
            hasInitFile = false,
            hasReactDep = true,
            hasVueDep = false,
            hasSolidDep = true,
        )
        assertNotNull("混合项目应命中", missing)
        assertEquals("Solid 优先级高于 React", SolidI18nStrategy, missing!!.framework)
    }

    @Test
    fun solidInitFileContainsUseI18n() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            SolidI18nStrategy, "zh", "zh-CN"
        )
        assertTrue(content.contains("import { useI18n } from '@solid-primitives/i18n';"))
        assertTrue(content.contains("import zh from './locales/zh-CN';"))
        assertTrue(content.contains("useI18n(dict, () => 'zh')"))
        assertTrue(content.contains("export function createAppI18n"))
    }

    @Test
    fun missingDependencyLabelJoinsWithPlus() {
        val missing = I18nBootstrapSupport.MissingBootstrap(
            ReactI18nextStrategy,
            listOf("i18next", "react-i18next"),
        )
        assertEquals("i18next + react-i18next", missing.dependencyLabel)
    }

    // ── buildInitFileContent ──────────────────────────────────

    @Test
    fun reactInitFileContainsImportsAndInit() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            ReactI18nextStrategy, "zh", "zh-CN"
        )
        assertTrue(content.contains("import i18n from 'i18next';"))
        assertTrue(content.contains("import { initReactI18next } from 'react-i18next';"))
        assertTrue(content.contains("import zh from './locales/zh-CN';"))
        assertTrue(content.contains("i18n.use(initReactI18next).init("))
        assertTrue(content.contains("lng: 'zh'"))
        assertTrue(content.contains("export default i18n;"))
        assertTrue(content.contains("resources:"))
    }

    @Test
    fun reactInitFileWithoutEntryOmitsResources() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            ReactI18nextStrategy, "zh", null
        )
        assertFalse(content.contains("import zh"))
        assertFalse(content.contains("resources:"))
        assertTrue(content.contains("export default i18n;"))
    }

    @Test
    fun vueInitFileContainsCreateI18n() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            VueI18nStrategy, "zh-CN", "zh-CN"
        )
        assertTrue(content.contains("import { createI18n } from 'vue-i18n';"))
        assertTrue(content.contains("import zh from './locales/zh-CN';"))
        assertTrue(content.contains("const i18n = createI18n("))
        assertTrue(content.contains("legacy: false"))
        assertTrue(content.contains("locale: 'zh-CN'"))
        assertTrue(content.contains("export default i18n;"))
    }

    // ── buildInitFileContent 格式（trimMargin 缩进修复回归）───────────
    // 旧实现用 trimIndent()，插值块（resources/messages）内部的 2/4 空格相对缩进会把
    // 最小缩进基准拉低，导致所有顶层行多出不该有的前导空格。以下精确到字符校验格式。

    @Test
    fun reactInitFileFormatIsClean() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            ReactI18nextStrategy, "zh", "zh-CN"
        )
        val expected = """
            |import i18n from 'i18next';
            |import { initReactI18next } from 'react-i18next';
            |import zh from './locales/zh-CN';
            |
            |i18n.use(initReactI18next).init({
            |  lng: 'zh',
            |  fallbackLng: 'zh',
            |  resources: {
            |    zh: { translation: zh },
            |  },
            |});
            |
            |export default i18n;
        """.trimMargin() + "\n"
        assertEquals("React 初始化文件顶层不应有多余前导缩进", expected, content)
    }

    @Test
    fun vueInitFileFormatIsClean() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            VueI18nStrategy, "zh-CN", "zh-CN"
        )
        val expected = """
            |import { createI18n } from 'vue-i18n';
            |import zh from './locales/zh-CN';
            |
            |const i18n = createI18n({
            |  legacy: false,
            |  locale: 'zh-CN',
            |  fallbackLocale: 'zh-CN',
            |  messages: {
            |    zh-CN: zh,
            |  },
            |});
            |
            |export default i18n;
        """.trimMargin() + "\n"
        assertEquals("Vue 初始化文件顶层不应有多余前导缩进", expected, content)
    }

    @Test
    fun solidInitFileFormatIsClean() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            SolidI18nStrategy, "zh", "zh"
        )
        val expected = """
            |import { useI18n } from '@solid-primitives/i18n';
            |import zh from './locales/zh';
            |
            |const dict = { zh: zh };
            |
            |export function createAppI18n() {
            |  const [t, { locale }] = useI18n(dict, () => 'zh');
            |  return { t, locale };
            |}
        """.trimMargin() + "\n"
        assertEquals("Solid 初始化文件顶层不应有多余前导缩进（且 import/dict 行不应塌缩到 0 缩进）", expected, content)
    }

    // ── addDepsToPackageJson ──────────────────────────────────

    @Test
    fun addDepsCreatesDependenciesSection() {
        val result = I18nBootstrapSupport.addDepsToPackageJson(
            """{ "name": "app" }""",
            listOf("i18next", "react-i18next"),
        )
        assertTrue(result.contains("\"i18next\""))
        assertTrue(result.contains("\"react-i18next\""))
        assertTrue(result.contains("\"dependencies\""))
    }

    @Test
    fun addDepsMergesIntoExistingDependencies() {
        val result = I18nBootstrapSupport.addDepsToPackageJson(
            """{ "name": "app", "dependencies": { "react": "^18" } }""",
            listOf("i18next"),
            "latest",
        )
        assertTrue(result.contains("\"react\": \"^18\""))
        assertTrue(result.contains("\"i18next\": \"latest\""))
    }

    @Test
    fun addDepsDoesNotDuplicateExistingDep() {
        val result = I18nBootstrapSupport.addDepsToPackageJson(
            """{ "dependencies": { "i18next": "^23" } }""",
            listOf("i18next"),
            "latest",
        )
        assertTrue(result.contains("\"i18next\": \"^23\""))
        assertTrue(!result.contains("\"i18next\": \"latest\""))
    }

    @Test
    fun addDepsReturnsOriginalOnInvalidJson() {
        val original = "not json at all"
        assertEquals(original, I18nBootstrapSupport.addDepsToPackageJson(original, listOf("i18next")))
    }

    // ── buildLocaleEntryFileContent ───────────────────────────

    @Test
    fun localeEntryFileContentIsRewritableExportDefault() {
        val content = I18nBootstrapSupport.buildLocaleEntryFileContent()
        // 必须能被 TsFileEditor 的 export default 对象字面量解析器识别（写回需用）
        assertTrue("入口文件须含 export default { ，实际: $content", content.contains("export default {"))
        assertTrue("入口文件须含闭合大括号", content.contains("}"))
    }
}