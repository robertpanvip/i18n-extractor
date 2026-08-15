package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 可配置目标语言（中文/日文/韩文）的测试：
 *  - 默认只提取中文（向后兼容）
 *  - LanguageExtractor 各语言判定函数正确
 *  - Util.containsTargetLanguage / Util.hasChinese 随设置联动
 *  - 入口文件识别随设置联动（ja 启用后能命中 ja 入口）
 *
 * 注意：I18nSettings 是应用级单例，测试间共享，必须在 setUp/tearDown 保存并恢复，
 * 避免污染其它测试类。
 */
class LanguageExtractorSettingsTest : BasePlatformTestCase() {

    private lateinit var originalIds: Set<String>

    override fun setUp() {
        super.setUp()
        originalIds = I18nSettings.getInstance().languageIds().toSet()
        // 每测从干净默认开始
        I18nSettings.getInstance().setLanguageIds(listOf("zh"))
    }

    override fun tearDown() {
        try {
            I18nSettings.getInstance().setLanguageIds(originalIds)
        } finally {
            super.tearDown()
        }
    }

    private fun createEntry(relPath: String, content: String) {
        myFixture.addFileToProject(relPath, content)
    }

    // ─────────────────────────────────────────
    // Settings 默认与回退
    // ─────────────────────────────────────────

    fun testDefaultActiveLanguageIsChineseOnly() {
        assertEquals(setOf("zh"), I18nSettings.getInstance().activeLanguageIds())
        assertEquals(listOf("zh"), I18nSettings.getInstance().activeExtractors().map { it.id })
    }

    fun testEmptyLanguageIdsFallsBackToChinese() {
        I18nSettings.getInstance().setLanguageIds(emptyList())
        assertEquals("空配置应回退为纯中文", setOf("zh"), I18nSettings.getInstance().activeLanguageIds())
    }

    fun testSettingsRoundTrip() {
        I18nSettings.getInstance().setLanguageIds(listOf("ja", "ko"))
        assertEquals(setOf("ja", "ko"), I18nSettings.getInstance().activeLanguageIds())
        assertEquals(setOf("ja", "ko"), I18nSettings.getInstance().activeExtractors().map { it.id }.toSet())
    }

    fun testActiveLocaleCandidatesFollowEnabledLanguages() {
        // 默认仅中文候选
        val zhOnly = I18nSettings.getInstance().activeLocaleCandidates()
        assertTrue(zhOnly.contains("zh-CN"))
        assertFalse(zhOnly.contains("ja-JP"))
        // 启用日文后包含 ja-JP
        I18nSettings.getInstance().setLanguageIds(listOf("zh", "ja"))
        val zhJa = I18nSettings.getInstance().activeLocaleCandidates()
        assertTrue(zhJa.contains("zh-CN"))
        assertTrue(zhJa.contains("ja-JP"))
    }

    // ─────────────────────────────────────────
    // 各语言判定函数
    // ─────────────────────────────────────────

    fun testChineseJudge() {
        assertTrue(ChineseExtractor.judge("你好世界"))
        assertTrue(ChineseExtractor.judge("abc你好"))
        assertFalse(ChineseExtractor.judge("hello world 123"))
        assertFalse(ChineseExtractor.judge("こんにちは")) // 纯假名，非汉字
        assertFalse(ChineseExtractor.judge("안녕하세요"))
    }

    fun testJapaneseJudge() {
        assertTrue(JapaneseExtractor.judge("こんにちは"))
        assertTrue(JapaneseExtractor.judge("カタカナ"))
        assertFalse(JapaneseExtractor.judge("hello world"))
        assertFalse(JapaneseExtractor.judge("안녕하세요"))
    }

    fun testKoreanJudge() {
        assertTrue(KoreanExtractor.judge("안녕하세요"))
        assertTrue(KoreanExtractor.judge("한국어"))
        assertFalse(KoreanExtractor.judge("hello world"))
        assertFalse(KoreanExtractor.judge("こんにちは"))
    }

    // ─────────────────────────────────────────
    // Util.containsTargetLanguage / hasChinese 随设置联动
    // ─────────────────────────────────────────

    fun testContainsTargetLanguageDefaultChineseOnly() {
        assertTrue(Util.containsTargetLanguage("你好"))
        assertFalse(Util.containsTargetLanguage("こんにちは"))
        assertFalse(Util.containsTargetLanguage("안녕하세요"))
        assertFalse(Util.containsTargetLanguage(null))
        assertFalse(Util.containsTargetLanguage(""))
    }

    fun testContainsTargetLanguageFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("ja"))
        assertTrue(Util.containsTargetLanguage("こんにちは"))
        assertFalse(Util.containsTargetLanguage("你好"))
        assertFalse(Util.containsTargetLanguage("안녕하세요"))

        I18nSettings.getInstance().setLanguageIds(listOf("ko"))
        assertTrue(Util.containsTargetLanguage("안녕하세요"))
        assertFalse(Util.containsTargetLanguage("你好"))
    }

    fun testHasChineseAliasFollowsSettings() {
        // 默认：hasChinese 命中中文
        assertTrue(Util.hasChinese("你好"))
        assertFalse(Util.hasChinese("こんにちは"))
        // 启用日文后，hasChinese 也命中日文（语义变为“目标语言”）
        I18nSettings.getInstance().setLanguageIds(listOf("ja"))
        assertTrue(Util.hasChinese("こんにちは"))
    }

    // ─────────────────────────────────────────
    // 入口文件识别随设置联动
    // ─────────────────────────────────────────

    fun testChineseEntryFoundWhenDefault() {
        createEntry("package.json", "{}")
        createEntry("src/locales/zh.ts", "export default { '首页': '首页' }")
        val context = myFixture.addFileToProject("src/App.tsx", "export default () => <div>hi</div>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("默认应命中中文入口", found)
        assertTrue("应命中 src/locales/zh.ts，实际：${found!!.path}", found.path.endsWith("locales/zh.ts"))
    }

    fun testJapaneseEntryFoundWhenJapaneseEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("ja"))
        createEntry("package.json", "{}")
        createEntry("src/locales/ja.ts", "export default { 'こんにちは': 'こんにちは' }")
        val context = myFixture.addFileToProject("src/App.tsx", "export default () => <div>hi</div>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用日文后应命中 ja 入口", found)
        assertTrue("应命中 src/locales/ja.ts，实际：${found!!.path}", found.path.endsWith("locales/ja.ts"))
    }

    fun testJapaneseEntryNotFoundWhenChineseOnly() {
        // 默认仅中文：ja.ts 不应被当作入口
        createEntry("package.json", "{}")
        createEntry("src/locales/ja.ts", "export default { 'こんにちは': 'こんにちは' }")
        val context = myFixture.addFileToProject("src/App.tsx", "export default () => <div>hi</div>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertTrue("默认不启用日文时不应命中 ja.ts，实际：${found?.path}", found == null || !found.path.endsWith("locales/ja.ts"))
    }

    fun testKoreanEntryFoundWhenKoreanEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("ko"))
        createEntry("package.json", "{}")
        createEntry("src/locales/ko.ts", "export default { '안녕하세요': '안녕하세요' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用韩文后应命中 ko 入口", found)
        assertTrue("应命中 src/locales/ko.ts，实际：${found!!.path}", found.path.endsWith("locales/ko.ts"))
    }
}