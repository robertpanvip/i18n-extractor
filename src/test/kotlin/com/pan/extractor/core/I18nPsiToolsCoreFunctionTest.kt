package com.pan.extractor.core

import com.pan.extractor.I18nPsiTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专门测试核心纯函数：I18nPsiTools 的纯文本/纯逻辑方法。
 *
 * 这些方法不依赖 PSI 平台（只对字符串或简单参数做判定），可作纯单元测试：
 *   1. isMustache / rm —— Vue 插值判定与花括号替换
 *   2. isJSTemplateLiteral / extractPureStringContent —— 模板字符串与纯字符串字面量判定
 *   3. isBlock —— 块级花括号判定
 *   4. isVueDirective —— Vue 指令（含缩写 @ : #）识别
 *   5. stripSurroundingQuotes —— 成对引号剥离
 *   6. buildTExprForRawText —— 纯文本构建 t() 调用（含换行/引号转义/骨架 key 覆盖）
 */
class I18nPsiToolsCoreFunctionTest {

    // ── isMustache / rm ────────────────────────────────────────

    @Test
    fun isMustacheTrueWhenBothCurly() {
        assertTrue(I18nPsiTools.isMustache("{{ value }}"))
        assertFalse(I18nPsiTools.isMustache("{{ partial"))
        assertFalse(I18nPsiTools.isMustache("}} only"))
    }

    // ── isJSTemplateLiteral / extractPureStringContent ─────────

    @Test
    fun jsTemplateLiteralRequiresInterpolation() {
        assertTrue(I18nPsiTools.isJSTemplateLiteral("`a${'$'}{b}`"))
        assertFalse(I18nPsiTools.isJSTemplateLiteral("`plain`"))
    }

    @Test
    fun extractPureStringContentDoubleQuote() {
        assertEquals("hi", I18nPsiTools.extractPureStringContent("\"hi\""))
    }

    @Test
    fun extractPureStringContentSingleQuote() {
        assertEquals("hi", I18nPsiTools.extractPureStringContent("'hi'"))
    }

    @Test
    fun extractPureStringContentBacktickWithoutInterpolation() {
        assertEquals("hi", I18nPsiTools.extractPureStringContent("`hi`"))
    }

    @Test
    fun extractPureStringContentNullForInterpolation() {
        assertNull(I18nPsiTools.extractPureStringContent("`a${'$'}{b}`"))
    }

    @Test
    fun extractPureStringContentEmptyForEmptyString() {
        // '' 长度正好 2，不满足 < 2，返回剥离引号后的空串
        assertEquals("", I18nPsiTools.extractPureStringContent("''"))
    }

    // ── isBlock ────────────────────────────────────────────────

    @Test
    fun isBlockTrueOnlyForOuterBraces() {
        assertTrue(I18nPsiTools.isBlock("{ a: 1 }"))
        assertFalse(I18nPsiTools.isBlock("not a block"))
        assertFalse(I18nPsiTools.isBlock("{ unbalanced"))
    }

    // ── isVueDirective ─────────────────────────────────────────

    @Test
    fun detectsVueDirectives() {
        assertTrue(I18nPsiTools.isVueDirective("v-if"))
        assertTrue(I18nPsiTools.isVueDirective("v-on:click"))
        assertTrue(I18nPsiTools.isVueDirective(":class"))
        assertTrue(I18nPsiTools.isVueDirective("@click"))
        assertTrue(I18nPsiTools.isVueDirective("#slot"))
        assertTrue(I18nPsiTools.isVueDirective("v-model"))
        assertFalse(I18nPsiTools.isVueDirective("title"))
        assertFalse(I18nPsiTools.isVueDirective("class"))
    }

    // ── stripSurroundingQuotes ─────────────────────────────────

    @Test
    fun stripMatchedQuotes() {
        assertEquals("abc", I18nPsiTools.stripSurroundingQuotes("'abc'"))
        assertEquals("abc", I18nPsiTools.stripSurroundingQuotes("\"abc\""))
        assertEquals("abc", I18nPsiTools.stripSurroundingQuotes("`abc`"))
    }

    @Test
    fun stripUnmatchedQuotesKeepsOriginal() {
        assertEquals("'abc", I18nPsiTools.stripSurroundingQuotes("'abc"))
        assertEquals("a'b", I18nPsiTools.stripSurroundingQuotes("a'b"))
    }

    @Test
    fun stripTooShortKeepsOriginal() {
        assertEquals("'", I18nPsiTools.stripSurroundingQuotes("'"))
    }

    // ── buildTExprForRawText ───────────────────────────────────

    @Test
    fun buildTExprUsesDollarTAndSingleQuote() {
        assertEquals("\$t('标题')", I18nPsiTools.buildTExprForRawText("标题", "{}", isVue = false, isReact = false))
    }

    @Test
    fun buildTExprEscapesSingleQuote() {
        assertEquals("\$t('it\\'s')", I18nPsiTools.buildTExprForRawText("it's", "{}", isVue = true, isReact = false))
    }

    @Test
    fun buildTExprUsesBacktickForMultiline() {
        val out = I18nPsiTools.buildTExprForRawText("第一行\n第二行", "{}", isVue = true, isReact = false)
        assertTrue("多行应使用反引号模板", out.startsWith("\$t(`"))
        assertTrue(out.endsWith("`)"))
    }

    @Test
    fun buildTExprOmitsEmptyParamsObject() {
        assertEquals("\$t('标题')", I18nPsiTools.buildTExprForRawText("标题", "{ }", isVue = true, isReact = false))
    }

    @Test
    fun buildTExprIncludesParamsObject() {
        assertEquals(
            "\$t('标题', { N0: 'x' })",
            I18nPsiTools.buildTExprForRawText("标题", "{ N0: 'x' }", isVue = true, isReact = false)
        )
    }

    @Test
    fun buildTExprUsesSkeletonKeyOverride() {
        assertEquals("\$t('skeleton.key')", I18nPsiTools.buildTExprForRawText(
            "标题", "{}", isVue = true, isReact = false, skeletonKeyOverride = "skeleton.key"
        ))
    }
}