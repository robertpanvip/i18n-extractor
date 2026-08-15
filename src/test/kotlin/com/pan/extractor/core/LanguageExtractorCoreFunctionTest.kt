package com.pan.extractor.core

import com.pan.extractor.EnglishExtractor
import com.pan.extractor.LanguageRegistry
import com.pan.extractor.LanguageExtractor
import com.pan.extractor.SiteKind
import com.pan.extractor.Util
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专门测试核心纯函数：语言提取器（LanguageExtractor）判定逻辑 + Util 纯文本门面方法。
 *
 * 这些方法不依赖 IntelliJ 平台，可作纯单元测试：
 *   1. 各内置语言 judge 判定（zh / en / fr / ru / de / es / it / pt / ko / ja）
 *   2. LanguageRegistry 注册表查找
 *   3. localeNameCandidates 与 langTagPrefix 元数据
 *   4. Util.getJsonContent 花括号剥离
 */
class LanguageExtractorCoreFunctionTest {

    // ── judge 判定 ─────────────────────────────────────────────

    @Test
    fun chineseJudge() {
        assertTrue(LanguageRegistry.byId("zh")!!.judge("你好世界"))
        assertFalse(LanguageRegistry.byId("zh")!!.judge("hello"))
    }

    @Test
    fun englishJudgeAcceptsSentenceLikeText() {
        assertTrue(EnglishExtractor.judge("Hello world"))
        assertTrue(EnglishExtractor.judge("Save your changes before closing."))
    }

    @Test
    fun englishJudgeRejectsCodeLikeAndUrl() {
        // 单 token 标识符 / URL / 含其它语言字符 → 不算英文句子
        assertFalse(EnglishExtractor.judge("main"))
        assertFalse(EnglishExtractor.judge("https://example.com"))
        assertFalse(EnglishExtractor.judge("你好 hello"))
    }

    @Test
    fun frenchJudgeDetectsAccents() {
        assertTrue(LanguageRegistry.byId("fr")!!.judge("Bonjour à tous"))
        assertFalse(LanguageRegistry.byId("fr")!!.judge("hello"))
    }

    @Test
    fun russianJudgeDetectsCyrillic() {
        assertTrue(LanguageRegistry.byId("ru")!!.judge("Привет мир"))
        assertFalse(LanguageRegistry.byId("ru")!!.judge("hello"))
    }

    @Test
    fun germanJudgeDetectsUmlauts() {
        assertTrue(LanguageRegistry.byId("de")!!.judge("Grüße"))
        assertFalse(LanguageRegistry.byId("de")!!.judge("hello"))
    }

    @Test
    fun spanishJudgeDetectsSpecialChars() {
        assertTrue(LanguageRegistry.byId("es")!!.judge("¿Cómo estás?"))
        assertFalse(LanguageRegistry.byId("es")!!.judge("hello"))
    }

    @Test
    fun italianJudgeDetectsAccentedVowels() {
        assertTrue(LanguageRegistry.byId("it")!!.judge("Città"))
        assertFalse(LanguageRegistry.byId("it")!!.judge("hello"))
    }

    @Test
    fun portugueseJudgeDetectsTildeVowels() {
        assertTrue(LanguageRegistry.byId("pt")!!.judge("Ação"))
        assertFalse(LanguageRegistry.byId("pt")!!.judge("hello"))
    }

    @Test
    fun japaneseJudgeDetectsKana() {
        assertTrue(LanguageRegistry.byId("ja")!!.judge("こんにちは"))
        assertFalse(LanguageRegistry.byId("ja")!!.judge("hello"))
    }

    @Test
    fun koreanJudgeDetectsHangul() {
        assertTrue(LanguageRegistry.byId("ko")!!.judge("안녕하세요"))
        assertFalse(LanguageRegistry.byId("ko")!!.judge("hello"))
    }

    // ── accepts 上下文 ─────────────────────────────────────────

    @Test
    fun cjkAcceptsAllSites() {
        val cjk = LanguageRegistry.all.filter { it.id in setOf("zh", "ja", "ko") }
        cjk.forEach { ex ->
            SiteKind.entries.forEach { kind ->
                assertTrue("${ex.id} 应接受 $kind", ex.accepts(kind))
            }
        }
    }

    @Test
    fun englishAcceptsAllSitesByDefault() {
        // EnglishExtractor 未 override accepts → 默认全接受（与代码当前实现一致）
        assertTrue(EnglishExtractor.accepts(SiteKind.ATTRIBUTE))
        assertTrue(EnglishExtractor.accepts(SiteKind.TEXT))
    }

    // ── LanguageRegistry ───────────────────────────────────────

    @Test
    fun registryByIdFindsKnownLanguages() {
        assertEquals("zh", LanguageRegistry.byId("zh")?.id)
        assertEquals("en", LanguageRegistry.byId("en")?.id)
        assertEquals("fr", LanguageRegistry.byId("fr")?.id)
        assertNull(LanguageRegistry.byId("xx"))
    }

    @Test
    fun registryContainsAllExpectedLanguages() {
        val ids = LanguageRegistry.all.map { it.id }.toSet()
        assertEquals(
            setOf("zh", "ja", "ko", "en", "fr", "ru", "de", "es", "it", "pt"),
            ids
        )
    }

    // ── locale 元数据 ──────────────────────────────────────────

    @Test
    fun chineseLocaleCandidatesContainCommonForms() {
        val candidates = LanguageRegistry.byId("zh")!!.localeNameCandidates()
        assertTrue("zh-CN" in candidates)
        assertTrue("zh_CN" in candidates)
        assertTrue("zh-Hans" in candidates)
    }

    @Test
    fun langTagPrefixCorrect() {
        assertEquals("zh", LanguageRegistry.byId("zh")!!.langTagPrefix)
        assertEquals("en", LanguageRegistry.byId("en")!!.langTagPrefix)
        assertEquals("ja", LanguageRegistry.byId("ja")!!.langTagPrefix)
    }

    // ── Util 纯文本门面 ────────────────────────────────────────

    @Test
    fun getJsonContentStripsBraces() {
        assertEquals("\"a\": 1", Util.getJsonContent("{ \"a\": 1 }"))
        assertEquals("\"a\": 1", Util.getJsonContent("{\n  \"a\": 1\n}"))
    }

    @Test
    fun getJsonContentHandlesNoBraces() {
        assertEquals("\"a\": 1", Util.getJsonContent("\"a\": 1"))
    }

    @Test
    fun getJsonContentPreservesNestedBraces() {
        // 只剥离最外层成对花括号，内部保留
        assertEquals("\"a\": { \"b\": 2 }", Util.getJsonContent("{ \"a\": { \"b\": 2 } }"))
    }
}