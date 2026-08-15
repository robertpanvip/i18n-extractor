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
    private lateinit var originalDest: OutputDestination
    private var originalMinLen: Int = 1
    private var originalMerge: Int = 2
    private lateinit var originalExclude: Set<String>
    private lateinit var originalCustomDirs: List<String>
    private var originalVuePrefix: String = "N"
    private var originalFoldLang: String = "zh"

    override fun setUp() {
        super.setUp()
        originalIds = I18nSettings.getInstance().languageIds().toSet()
        originalDest = I18nSettings.getInstance().outputDestination()
        originalMinLen = I18nSettings.getInstance().minStringLength()
        originalMerge = I18nSettings.getInstance().mergeAffixThreshold()
        originalExclude = I18nSettings.getInstance().excludeDirs()
        originalCustomDirs = I18nSettings.getInstance().customTranslationDirs()
        originalVuePrefix = I18nSettings.getInstance().vuePlaceholderPrefix()
        originalFoldLang = I18nSettings.getInstance().foldDisplayLanguage()
        // 每测从干净默认开始
        I18nSettings.getInstance().setLanguageIds(listOf("zh"))
        I18nSettings.getInstance().setOutputDestination(OutputDestination.ASK)
        I18nSettings.getInstance().setMinStringLength(1)
        I18nSettings.getInstance().setMergeAffixThreshold(2)
        I18nSettings.getInstance().setExcludeDirs(
            listOf("node_modules", ".git", "dist", "build", ".next", ".nuxt", "out")
        )
        I18nSettings.getInstance().setCustomTranslationDirs(emptyList())
        I18nSettings.getInstance().setVuePlaceholderPrefix("N")
        I18nSettings.getInstance().setFoldDisplayLanguage("zh")
    }

    override fun tearDown() {
        try {
            I18nSettings.getInstance().setLanguageIds(originalIds)
            I18nSettings.getInstance().setOutputDestination(originalDest)
            I18nSettings.getInstance().setMinStringLength(originalMinLen)
            I18nSettings.getInstance().setMergeAffixThreshold(originalMerge)
            I18nSettings.getInstance().setExcludeDirs(originalExclude)
            I18nSettings.getInstance().setCustomTranslationDirs(originalCustomDirs)
            I18nSettings.getInstance().setVuePlaceholderPrefix(originalVuePrefix)
            I18nSettings.getInstance().setFoldDisplayLanguage(originalFoldLang)
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

    fun testVuePlaceholderPrefixDefaultAndCoerce() {
        val s = I18nSettings.getInstance()
        assertEquals("默认应为 N", "N", s.vuePlaceholderPrefix())
        s.setVuePlaceholderPrefix("param")
        assertEquals("param", s.vuePlaceholderPrefix())
        s.setVuePlaceholderPrefix("   ")
        assertEquals("空值应钳制回默认 N（vue-i18n 不支持数字 key）", "N", s.vuePlaceholderPrefix())
        s.setVuePlaceholderPrefix("")
        assertEquals("空值应钳制回默认 N", "N", s.vuePlaceholderPrefix())
    }

    fun testFoldDisplayLanguageDefaultAndCoerce() {
        val s = I18nSettings.getInstance()
        assertEquals("默认应为 zh", "zh", s.foldDisplayLanguage())
        s.setFoldDisplayLanguage("en")
        assertEquals("en", s.foldDisplayLanguage())
        s.setFoldDisplayLanguage("xx")
        assertEquals("非法语言应钳制回默认 zh", "zh", s.foldDisplayLanguage())
        s.setFoldDisplayLanguage("ja")
        assertEquals("ja", s.foldDisplayLanguage())
    }

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
    // 英文（句子级启发式 + 上下文收紧）
    // ─────────────────────────────────────────

    fun testEnglishJudgeAcceptsSentenceLikeText() {
        assertTrue(EnglishExtractor.judge("Hello world"))
        assertTrue(EnglishExtractor.judge("Please enter your name"))
        assertTrue(EnglishExtractor.judge("Save your changes."))
        assertTrue(EnglishExtractor.judge("Loading, please wait"))
    }

    fun testEnglishJudgeRejectsCodeLikeText() {
        assertFalse(EnglishExtractor.judge("hello"))                  // 单 token
        assertFalse(EnglishExtractor.judge("user_login_success"))     // 下划线单 token
        assertFalse(EnglishExtractor.judge("main-container"))         // kebab 单 token
        assertFalse(EnglishExtractor.judge("fooBar"))                 // camelCase 单 token
        assertFalse(EnglishExtractor.judge("https://example.com/api"))// URL
        assertFalse(EnglishExtractor.judge("www.example.com"))        // URL
        assertFalse(EnglishExtractor.judge("你好 world"))              // 含中文
        assertFalse(EnglishExtractor.judge("こんにちは world"))         // 含日文
        assertFalse(EnglishExtractor.judge("안녕 world"))              // 含韩文
        assertFalse(EnglishExtractor.judge("12345"))                  // 纯数字
    }

    fun testEnglishAcceptsContext() {
        assertTrue(EnglishExtractor.accepts(SiteKind.TEXT))
        assertTrue(EnglishExtractor.accepts(SiteKind.ATTRIBUTE))
        assertTrue(EnglishExtractor.accepts(SiteKind.JS_STRING))
        assertTrue(EnglishExtractor.accepts(SiteKind.JS_TEMPLATE))
        assertTrue(EnglishExtractor.accepts(SiteKind.OTHER))
    }

    fun testEnglishTextNodeDetectedWhenEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("en"))
        // TEXT 上下文：英文整句命中
        assertTrue(Util.containsTargetLanguage("Hello world", SiteKind.TEXT))
        // ATTRIBUTE 上下文：取 = 后面的值，整句同样命中（英文接受所有上下文）
        assertTrue(Util.containsTargetLanguage("main container", SiteKind.ATTRIBUTE))
        // 单 token 不命中
        assertFalse(Util.containsTargetLanguage("Save", SiteKind.TEXT))
    }

    fun testEnglishJsStringExtractedWhenEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("en"))
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "en-proj",
              "dependencies": { "react": "^18.0.0", "react-dom": "^18.0.0" }
            }
            """.trimIndent()
        )
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                const message = "Hello world"
                return <div>{message}</div>
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()
        assertTrue(
            "应提取英文整句字符串，实际：${processor.extractedStrings.values}",
            processor.extractedStrings.values.contains("Hello world")
        )
    }

    fun testEnglishJsxTextMergedAndExtracted() {
        I18nSettings.getInstance().setLanguageIds(listOf("en"))
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "en-proj",
              "dependencies": { "react": "^18.0.0", "react-dom": "^18.0.0" }
            }
            """.trimIndent()
        )
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return <div>Hello world</div>
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()
        assertTrue(
            "应合并相邻文本节点并提取英文整句，实际：${processor.extractedStrings.values}",
            processor.extractedStrings.values.contains("Hello world")
        )
        assertTrue("应注入 \$t 调用", file.text.contains("\$t("))
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
    // SiteKind 上下文判定（Approach A）
    // ─────────────────────────────────────────

    fun testChineseAcceptsAllSiteKinds() {
        for (kind in SiteKind.values()) {
            assertTrue("中文应接受所有站点上下文 $kind", ChineseExtractor.accepts(kind))
        }
    }

    fun testContainsTargetLanguageWithSiteKind() {
        // CJK 语言默认接受所有上下文，因此带上下文判定退化为纯字符判定
        for (kind in SiteKind.values()) {
            assertTrue("中文文本应命中上下文 $kind", Util.containsTargetLanguage("你好", kind))
            assertFalse("非中文文本不应命中上下文 $kind", Util.containsTargetLanguage("hello", kind))
        }
    }

    fun testSiteKindJudgmentFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("ja"))
        // CJK 语言接受所有上下文：任意 SiteKind 下都命中日文
        for (kind in SiteKind.values()) {
            assertTrue("日文文本在上下文 $kind 应命中", Util.containsTargetLanguage("こんにちは", kind))
        }
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

    // ─────────────────────────────────────────
    // 法语（重音符判定）
    // ─────────────────────────────────────────

    fun testFrenchJudge() {
        assertTrue(FrenchExtractor.judge("café"))
        assertTrue(FrenchExtractor.judge("présentation"))
        assertTrue(FrenchExtractor.judge("Bonjour, comment ça va ?"))
        assertTrue(FrenchExtractor.judge("français"))
        assertFalse(FrenchExtractor.judge("bonjour"))      // 纯 ASCII 法语，无重音符 → 与英文重叠，不判法语
        assertFalse(FrenchExtractor.judge("hello world"))  // 英文
        assertFalse(FrenchExtractor.judge("你好"))          // 中文
        assertFalse(FrenchExtractor.judge("Привет"))       // 俄文
    }

    fun testFrenchJudgmentFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("fr"))
        for (kind in SiteKind.values()) {
            assertTrue("法语文本在上下文 $kind 应命中", Util.containsTargetLanguage("présent", kind))
        }
        assertFalse("禁用中文时中文不应命中", Util.containsTargetLanguage("你好"))
    }

    fun testFrenchEntryFoundWhenFrenchEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("fr"))
        createEntry("package.json", "{}")
        createEntry("src/locales/fr.ts", "export default { 'présent': 'présent' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用法语后应命中 fr 入口", found)
        assertTrue("应命中 src/locales/fr.ts，实际：${found?.path}", found!!.path.endsWith("locales/fr.ts"))
    }

    // ─────────────────────────────────────────
    // 俄语（西里尔字母判定）
    // ─────────────────────────────────────────

    fun testRussianJudge() {
        assertTrue(RussianExtractor.judge("Привет"))
        assertTrue(RussianExtractor.judge("Здравствуйте"))
        assertTrue(RussianExtractor.judge("Добро пожаловать"))
        assertTrue(RussianExtractor.judge("中文Русский"))
        assertFalse(RussianExtractor.judge("hello world"))  // 英文
        assertFalse(RussianExtractor.judge("你好"))          // 中文
        assertFalse(RussianExtractor.judge("café"))         // 法语
    }

    fun testRussianJudgmentFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("ru"))
        for (kind in SiteKind.values()) {
            assertTrue("俄语文本在上下文 $kind 应命中", Util.containsTargetLanguage("Привет", kind))
        }
        assertFalse("禁用中文时中文不应命中", Util.containsTargetLanguage("你好"))
    }

    fun testRussianEntryFoundWhenRussianEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("ru"))
        createEntry("package.json", "{}")
        createEntry("src/locales/ru.ts", "export default { 'Привет': 'Привет' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用俄语后应命中 ru 入口", found)
        assertTrue("应命中 src/locales/ru.ts，实际：${found?.path}", found!!.path.endsWith("locales/ru.ts"))
    }

    // ─────────────────────────────────────────
    // 德语（变音符判定）
    // ─────────────────────────────────────────

    fun testGermanJudge() {
        assertTrue(GermanExtractor.judge("Grüße"))
        assertTrue(GermanExtractor.judge("Schönen Tag noch"))
        assertTrue(GermanExtractor.judge("Straße"))
        assertTrue(GermanExtractor.judge("für"))
        assertFalse(GermanExtractor.judge("Hallo"))      // 纯 ASCII 德语，无变音符 → 与英文重叠，不判德语
        assertFalse(GermanExtractor.judge("hello world"))// 英文
        assertFalse(GermanExtractor.judge("你好"))        // 中文
        assertFalse(GermanExtractor.judge("café"))       // 法语
    }

    fun testGermanJudgmentFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("de"))
        for (kind in SiteKind.values()) {
            assertTrue("德语文本在上下文 $kind 应命中", Util.containsTargetLanguage("Grüße", kind))
        }
        assertFalse("禁用中文时中文不应命中", Util.containsTargetLanguage("你好"))
    }

    fun testGermanEntryFoundWhenGermanEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("de"))
        createEntry("package.json", "{}")
        createEntry("src/locales/de.ts", "export default { 'Grüße': 'Grüße' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用德语后应命中 de 入口", found)
        assertTrue("应命中 src/locales/de.ts，实际：${found?.path}", found!!.path.endsWith("locales/de.ts"))
    }

    // ─────────────────────────────────────────
    // 西班牙语（专属字符判定）
    // ─────────────────────────────────────────

    fun testSpanishJudge() {
        assertTrue(SpanishExtractor.judge("mañana"))
        assertTrue(SpanishExtractor.judge("¡Hola mundo!"))
        assertTrue(SpanishExtractor.judge("¿Cómo estás?"))
        assertTrue(SpanishExtractor.judge("años"))
        assertFalse(SpanishExtractor.judge("hola"))      // 纯 ASCII 西语，无专属字符 → 与英文重叠，不判西语
        assertFalse(SpanishExtractor.judge("hello world"))// 英文
        assertFalse(SpanishExtractor.judge("你好"))        // 中文
        assertFalse(SpanishExtractor.judge("Straße"))    // 德语（ß 为德语独有，无西语字符）
    }

    fun testSpanishJudgmentFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("es"))
        for (kind in SiteKind.values()) {
            assertTrue("西语文本在上下文 $kind 应命中", Util.containsTargetLanguage("años", kind))
        }
        assertFalse("禁用中文时中文不应命中", Util.containsTargetLanguage("你好"))
    }

    fun testSpanishEntryFoundWhenSpanishEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("es"))
        createEntry("package.json", "{}")
        createEntry("src/locales/es.ts", "export default { 'años': 'años' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用西语后应命中 es 入口", found)
        assertTrue("应命中 src/locales/es.ts，实际：${found?.path}", found!!.path.endsWith("locales/es.ts"))
    }

    // ─────────────────────────────────────────
    // 意大利语（重音元音判定）
    // ─────────────────────────────────────────

    fun testItalianJudge() {
        assertTrue(ItalianExtractor.judge("città"))
        assertTrue(ItalianExtractor.judge("perché"))
        assertTrue(ItalianExtractor.judge("più"))
        assertTrue(ItalianExtractor.judge("è bello"))
        assertFalse(ItalianExtractor.judge("ciao"))             // 纯 ASCII 意语，无重音 → 与英文重叠，不判意语
        assertFalse(ItalianExtractor.judge("hello world"))      // 英文
        assertFalse(ItalianExtractor.judge("français"))         // 法语（ç 为法语独有，意语无此字符）
        assertFalse(ItalianExtractor.judge("你好"))              // 中文
    }

    fun testItalianJudgmentFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("it"))
        for (kind in SiteKind.values()) {
            assertTrue("意语文本在上下文 $kind 应命中", Util.containsTargetLanguage("città", kind))
        }
        assertFalse("禁用中文时中文不应命中", Util.containsTargetLanguage("你好"))
    }

    fun testItalianEntryFoundWhenItalianEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("it"))
        createEntry("package.json", "{}")
        createEntry("src/locales/it.ts", "export default { 'città': 'città' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用意语后应命中 it 入口", found)
        assertTrue("应命中 src/locales/it.ts，实际：${found?.path}", found!!.path.endsWith("locales/it.ts"))
    }

    // ─────────────────────────────────────────
    // 葡萄牙语（波浪元音判定）
    // ─────────────────────────────────────────

    fun testPortugueseJudge() {
        assertTrue(PortugueseExtractor.judge("maçã"))
        assertTrue(PortugueseExtractor.judge("pão"))
        assertTrue(PortugueseExtractor.judge("não"))
        assertTrue(PortugueseExtractor.judge("informação"))
        assertFalse(PortugueseExtractor.judge("ola"))           // 纯 ASCII 葡语，无专属字符 → 与英文重叠，不判葡语
        assertFalse(PortugueseExtractor.judge("hello world"))   // 英文
        assertFalse(PortugueseExtractor.judge("cœur"))          // 法语（œ 为法语独有，葡语无此字符）
        assertFalse(PortugueseExtractor.judge("mañana"))        // 西语（ñ 为西语独有，葡语无此字符）
    }

    fun testPortugueseJudgmentFollowsSettings() {
        I18nSettings.getInstance().setLanguageIds(listOf("pt"))
        for (kind in SiteKind.values()) {
            assertTrue("葡语文本在上下文 $kind 应命中", Util.containsTargetLanguage("maçã", kind))
        }
        assertFalse("禁用中文时中文不应命中", Util.containsTargetLanguage("你好"))
    }

    fun testPortugueseEntryFoundWhenPortugueseEnabled() {
        I18nSettings.getInstance().setLanguageIds(listOf("pt"))
        createEntry("package.json", "{}")
        createEntry("src/locales/pt.ts", "export default { 'maçã': 'maçã' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("启用葡语后应命中 pt 入口", found)
        assertTrue("应命中 src/locales/pt.ts，实际：${found?.path}", found!!.path.endsWith("locales/pt.ts"))
    }

    // ─────────────────────────────────────────
    // 最小提取长度
    // ─────────────────────────────────────────

    fun testMinStringLengthDefaultsToOne() {
        assertEquals("默认最小长度为 1（全部提取）", 1, I18nSettings.getInstance().minStringLength())
    }

    fun testMinStringLengthRoundTripAndCoerce() {
        val s = I18nSettings.getInstance()
        s.setMinStringLength(3)
        assertEquals(3, s.minStringLength())
        // 低于 1 的输入被钳制回 1
        s.setMinStringLength(0)
        assertEquals("应钳制回 1", 1, s.minStringLength())
        s.setMinStringLength(-5)
        assertEquals("应钳制回 1", 1, s.minStringLength())
    }

    // ─────────────────────────────────────────
    // 合并建议阈值
    // ─────────────────────────────────────────

    fun testMergeAffixThresholdRoundTrip() {
        val s = I18nSettings.getInstance()
        assertEquals("默认阈值为 2", 2, s.mergeAffixThreshold())
        s.setMergeAffixThreshold(5)
        assertEquals(5, s.mergeAffixThreshold())
        s.setMergeAffixThreshold(0)
        assertEquals("应钳制回 1", 1, s.mergeAffixThreshold())
    }

    // ─────────────────────────────────────────
    // 排除目录
    // ─────────────────────────────────────────

    fun testExcludeDirsDefaultAndRoundTrip() {
        val s = I18nSettings.getInstance()
        assertTrue("默认应包含 node_modules", s.excludeDirs().contains("node_modules"))
        s.setExcludeDirs(listOf("node_modules", "dist", "  ", ""))
        assertEquals("空白项应被过滤", setOf("node_modules", "dist"), s.excludeDirs())
    }

    // ─────────────────────────────────────────
    // 自定义翻译目录
    // ─────────────────────────────────────────

    fun testCustomTranslationDirsDefaultAndRoundTrip() {
        val s = I18nSettings.getInstance()
        assertTrue("默认无自定义目录", s.customTranslationDirs().isEmpty())
        s.setCustomTranslationDirs(listOf("assets/lang", "packages/ui-locales"))
        assertEquals(listOf("assets/lang", "packages/ui-locales"), s.customTranslationDirs())
        // 空白项去除
        s.setCustomTranslationDirs(listOf("  ", "a"))
        assertEquals(listOf("a"), s.customTranslationDirs())
    }
    // ─────────────────────────────────────────
    // 输出去向设置（剪贴板 / 写入文件 / 每次询问）
    // ─────────────────────────────────────────

    fun testOutputDestinationDefaultsToAsk() {
        assertEquals("默认应为「每次询问」", OutputDestination.ASK, I18nSettings.getInstance().outputDestination())
    }

    fun testOutputDestinationSafeValueOf() {
        assertEquals(OutputDestination.CLIPBOARD, OutputDestination.safeValueOf("CLIPBOARD"))
        assertEquals(OutputDestination.FILE, OutputDestination.safeValueOf("FILE"))
        assertEquals(OutputDestination.ASK, OutputDestination.safeValueOf("ASK"))
        assertEquals("空值回退为每次询问", OutputDestination.ASK, OutputDestination.safeValueOf(null))
        assertEquals("未知值回退为每次询问", OutputDestination.ASK, OutputDestination.safeValueOf("WHATEVER"))
    }

    fun testOutputDestinationRoundTrip() {
        assertEquals(OutputDestination.CLIPBOARD, OutputDestination.valueOf(
            I18nSettings.getInstance().run {
                setOutputDestination(OutputDestination.CLIPBOARD); outputDestination().name
            }
        ))
        assertEquals(OutputDestination.FILE, OutputDestination.valueOf(
            I18nSettings.getInstance().run {
                setOutputDestination(OutputDestination.FILE); outputDestination().name
            }
        ))
        assertEquals(OutputDestination.ASK, OutputDestination.valueOf(
            I18nSettings.getInstance().run {
                setOutputDestination(OutputDestination.ASK); outputDestination().name
            }
        ))
    }
}