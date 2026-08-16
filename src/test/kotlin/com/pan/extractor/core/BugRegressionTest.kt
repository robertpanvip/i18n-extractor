package com.pan.extractor.core

import com.pan.extractor.EnglishExtractor
import com.pan.extractor.FrenchExtractor
import com.pan.extractor.GermanExtractor
import com.pan.extractor.ItalianExtractor
import com.pan.extractor.PortugueseExtractor
import com.pan.extractor.SpanishExtractor
import com.pan.extractor.TsFileEditor
import com.pan.extractor.isLatinAlphabetSentence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 潜在 Bug 回归测试：验证从代码审查中发现的疑似问题。
 *
 * 这些测试不依赖 IntelliJ 平台，仅对纯函数进行黑盒验证。
 */
class BugRegressionTest {

    // ═══════════════════════════════════════════════════════════
    // Bug 1: SENTENCE_HINT 含中文标点，导致中文标点结尾的
    //        纯 ASCII 字符串被误判为拉丁字母句子
    // ═══════════════════════════════════════════════════════════

    @Test
    fun latinSentenceShouldRejectChinesePunctuation() {
        // 「hello。」是中文语境下的文案片段，不应被判定为拉丁字母句子
        // SENTENCE_HINT = " .,!?;:，。！？；：" 中包含了中文标点
        // 这导致 "hello。" 被误判为拉丁句子
        assertFalse(
            "中文句号结尾的文本不应被判定为拉丁字母句子",
            isLatinAlphabetSentence("hello。")
        )
        assertFalse(
            "中文逗号结尾的文本不应被判定为拉丁字母句子",
            isLatinAlphabetSentence("hello，")
        )
        assertFalse(
            "中文感叹号结尾的文本不应被判定为拉丁字母句子",
            isLatinAlphabetSentence("hello！")
        )
        assertFalse(
            "中文问号结尾的文本不应被判定为拉丁字母句子",
            isLatinAlphabetSentence("hello？")
        )
        assertFalse(
            "中文分号结尾的文本不应被判定为拉丁字母句子",
            isLatinAlphabetSentence("hello；")
        )
        assertFalse(
            "中文冒号结尾的文本不应被判定为拉丁字母句子",
            isLatinAlphabetSentence("hello：")
        )
    }

    @Test
    fun latinSentenceShouldAcceptWesternPunctuation() {
        // 英文标点结尾的文本应正常判定为拉丁字母句子
        assertTrue(isLatinAlphabetSentence("hello."))
        assertTrue(isLatinAlphabetSentence("hello,"))
        assertTrue(isLatinAlphabetSentence("hello!"))
        assertTrue(isLatinAlphabetSentence("hello?"))
        assertTrue(isLatinAlphabetSentence("hello;"))
        assertTrue(isLatinAlphabetSentence("hello:"))
    }

    @Test
    fun latinSentenceShouldRejectMixedCjkAndLatin() {
        // 中文 + 拉丁字母混合文本不应被判定为拉丁字母句子
        assertFalse(isLatinAlphabetSentence("hello 你好"))
        assertFalse(isLatinAlphabetSentence("你好 hello"))
    }

    // ═══════════════════════════════════════════════════════════
    // Bug 2: isSingleLineStaticValue 中 t.contains("=>")
    //        会误判字符串值中含 "=>" 的普通文本
    // ═══════════════════════════════════════════════════════════

    @Test
    fun parseObjectLiteralBodyWithArrowInStringValue() {
        // 字符串值中包含 "=>" 不应被误判为箭头函数
        // 例如翻译文本 "click => action" 或 "press => go"
        val map = TsFileEditor.parseObjectLiteralBody(
            """{ hint: 'click => action', desc: "press => go" }"""
        )
        assertEquals("click => action", map["hint"])
        assertEquals("press => go", map["desc"])
    }

    @Test
    fun parseObjectLiteralBodyWithFunctionKeywordInString() {
        // 字符串值中包含 "function" 不应被误判为函数
        val map = TsFileEditor.parseObjectLiteralBody(
            """{ label: 'function test', tip: "my function" }"""
        )
        assertEquals("function test", map["label"])
        assertEquals("my function", map["tip"])
    }

    // ═══════════════════════════════════════════════════════════
    // Bug 3: findExportedObjectStart mode 6 正则不支持
    //        TypeScript 泛型参数
    // ═══════════════════════════════════════════════════════════

    @Test
    fun parseTsExportedObjectWithTypeScriptGenerics() {
        // export default defineMessages<SomeType>({ ... })
        val text = """
            import { defineMessages } from 'react-intl'
            export default defineMessages<SomeType>({
              hello: 'Hello',
              world: 'World',
            })
        """.trimIndent()
        val info = TsFileEditor.parseTsExportedObject(text)
        assertNotNull("含泛型的 defineMessages 应能解析", info)
        if (info != null) {
            assertEquals("Hello", info.staticKV["hello"])
            assertEquals("World", info.staticKV["world"])
        }
    }

    @Test
    fun parseTsExportedObjectWithNestedGenerics() {
        // 嵌套泛型：export default defineMessages<Record<string, string>>({ ... })
        val text = """
            export default defineMessages<Record<string, string>>({
              hello: 'Hello',
            })
        """.trimIndent()
        val info = TsFileEditor.parseTsExportedObject(text)
        assertNotNull("含嵌套泛型的 defineMessages 应能解析", info)
        if (info != null) {
            assertEquals("Hello", info.staticKV["hello"])
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Bug 4: splitTopLevelProperties 中 depth 的 coerceAtLeast(0)
    //        掩蔽了多余的闭合括号，导致后续内容被错误切分
    // ═══════════════════════════════════════════════════════════

    @Test
    fun splitTopLevelPropertiesExtraClosingBrace() {
        // 多余的 } 应被容忍，不应导致崩溃或错误切分
        val parts = TsFileEditor.splitTopLevelProperties("a: 1, }, b: 2")
        // 多余的 } 会被当作顶层内容的一部分，不应崩溃
        assertTrue("至少应产生 2 个属性片段", parts.size >= 2)
    }

    @Test
    fun splitTopLevelPropertiesExtraClosingBracket() {
        // 多余的 ] 应被容忍
        val parts = TsFileEditor.splitTopLevelProperties("a: 1, ], b: 2")
        assertTrue("至少应产生 2 个属性片段", parts.size >= 2)
    }

    // ═══════════════════════════════════════════════════════════
    // Bug 5: parseObjectLiteralBody 在字符串值中含 : 时
    //        可能误判属性边界
    // ═══════════════════════════════════════════════════════════

    @Test
    fun parseObjectLiteralBodyWithColonInStringValue() {
        // 字符串值中包含 ":" 不应被误判为属性分隔符
        val map = TsFileEditor.parseObjectLiteralBody(
            """{ url: 'https://example.com', time: '12:00' }"""
        )
        assertEquals("https://example.com", map["url"])
        assertEquals("12:00", map["time"])
    }

    @Test
    fun parseObjectLiteralBodyWithNestedBracesInString() {
        // 字符串值中包含 {} 不应影响嵌套解析
        val map = TsFileEditor.parseObjectLiteralBody(
            """{ template: 'Hello {name}', code: 'if (x) { return }' }"""
        )
        assertEquals("Hello {name}", map["template"])
        assertEquals("if (x) { return }", map["code"])
    }

    // ═══════════════════════════════════════════════════════════
    // Bug 6: 拉丁语系提取器对纯中文标点文本的判定
    //        （确保中文标点不被误判为拉丁字母句子特征）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun latinExtractorsShouldRejectChinesePunctuationOnlySentences() {
        // 所有拉丁语系提取器对中文标点结尾的纯 ASCII 文本
        // 应仅依赖专属字符判定，不应因 isLatinAlphabetSentence 中的中文标点而误判
        // 注：当前 SENTENCE_HINT 含中文标点，此测试暴露该问题
        assertFalse("英语不应接受中文标点结尾文本", EnglishExtractor.judge("hello。"))
        assertFalse("法语不应接受中文标点结尾文本", FrenchExtractor.judge("hello。"))
        assertFalse("德语不应接受中文标点结尾文本", GermanExtractor.judge("hello。"))
        assertFalse("西语不应接受中文标点结尾文本", SpanishExtractor.judge("hello。"))
        assertFalse("意语不应接受中文标点结尾文本", ItalianExtractor.judge("hello。"))
        assertFalse("葡语不应接受中文标点结尾文本", PortugueseExtractor.judge("hello。"))
    }

    @Test
    fun latinExtractorsOnlyAcceptLatinSentences() {
        // 只有英文标点结尾的才是真正的拉丁句子
        assertTrue(EnglishExtractor.judge("Hello world."))
        assertTrue(FrenchExtractor.judge("Hello world."))
        assertTrue(GermanExtractor.judge("Hello world."))
        assertTrue(SpanishExtractor.judge("Hello world."))
        assertTrue(ItalianExtractor.judge("Hello world."))
        assertTrue(PortugueseExtractor.judge("Hello world."))
    }

    // ═══════════════════════════════════════════════════════════
    // Bug 7: findExportedObjectStart 模式 3 对含 inline 类型标注
    //        的 export const（花括号在类型标注中出现）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun parseTsExportedObjectWithInlineTypeAnnotation() {
        // export const zh: Record<string, string> = { ... }
        val text = """
            export const zh: Record<string, string> = {
              hello: '你好',
            }
        """.trimIndent()
        val info = TsFileEditor.parseTsExportedObject(text)
        assertNotNull("含 Record<> 类型标注的 const 应能解析", info)
        if (info != null) {
            assertEquals("你好", info.staticKV["hello"])
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Bug 8: findBalancedCloseBrace 对嵌套很深的对象
    //        （验证无栈溢出且正确返回）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun balancedBraceDeepNesting() {
        // 深度嵌套（50 层），不应栈溢出
        val deep = "{".repeat(50) + "value" + "}".repeat(50)
        val end = TsFileEditor.findBalancedCloseBrace(deep, 0)
        assertEquals(deep.length, end)
    }

    @Test
    fun balancedBraceNestedStringsAndComments() {
        val text = """{ "key": "value with \" escaped", b: /* { */ 1 }"""
        val end = TsFileEditor.findBalancedCloseBrace(text, 0)
        assertEquals(text.length, end)
    }
}