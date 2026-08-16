package com.pan.extractor.core

import com.pan.extractor.I18nBootstrapSupport
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
        assertEquals(I18nBootstrapSupport.Framework.REACT, missing!!.framework)
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
        assertEquals(I18nBootstrapSupport.Framework.VUE, missing!!.framework)
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
        assertNull("非 React/Vue 不应命中", missing)
    }

    @Test
    fun missingDependencyLabelJoinsWithPlus() {
        val missing = I18nBootstrapSupport.MissingBootstrap(
            I18nBootstrapSupport.Framework.REACT,
            listOf("i18next", "react-i18next"),
        )
        assertEquals("i18next + react-i18next", missing.dependencyLabel)
    }

    // ── buildInitFileContent ──────────────────────────────────

    @Test
    fun reactInitFileContainsImportsAndInit() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            I18nBootstrapSupport.Framework.REACT, "zh", "zh-CN"
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
            I18nBootstrapSupport.Framework.REACT, "zh", null
        )
        assertFalse(content.contains("import zh"))
        assertFalse(content.contains("resources:"))
        assertTrue(content.contains("export default i18n;"))
    }

    @Test
    fun vueInitFileContainsCreateI18n() {
        val content = I18nBootstrapSupport.buildInitFileContent(
            I18nBootstrapSupport.Framework.VUE, "zh-CN", "zh-CN"
        )
        assertTrue(content.contains("import { createI18n } from 'vue-i18n';"))
        assertTrue(content.contains("import zh from './locales/zh-CN';"))
        assertTrue(content.contains("const i18n = createI18n("))
        assertTrue(content.contains("legacy: false"))
        assertTrue(content.contains("locale: 'zh-CN'"))
        assertTrue(content.contains("export default i18n;"))
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