package com.pan.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * MergeApplier 纯函数单元测试（不依赖 PSI/平台）。
 *
 * 覆盖：
 *   1. buildPlaceholderRewrite —— Vue({N0}) / React({{0}}) / 通用({0}) 占位映射
 *   2. buildParamsObjectString —— 参数对象字符串拼接
 *   3. renderLiteralValue —— 差异段渲染：数字裸写、字符串加引号、引号转义
 *   4. renderDigitLiteral —— 数字抽取渲染：前导零必须加引号（避免 strict 模式 octal 语法错误）
 */
class MergeApplierPureTest {

    // ── buildPlaceholderRewrite ────────────────────────────────
    @Test
    fun testPlaceholderVueUsesNamedN0() {
        val map = MergeApplier.buildPlaceholderRewrite(isVue = true, isReact = false, pairs = listOf("N0" to "x"))
        assertEquals("{N0}" to "N0", map["N0"])
        assertEquals("Vue 占位应为 {N0}", "{N0}", map["N0"]!!.first)
    }

    @Test
    fun testPlaceholderReactUsesDoubleCurlyIndex() {
        val map = MergeApplier.buildPlaceholderRewrite(isVue = false, isReact = true, pairs = listOf("N0" to "x"))
        assertEquals("{{0}}" to "\"0\"", map["N0"])
        assertEquals("React 占位应为 {{0}}", "{{0}}", map["N0"]!!.first)
    }

    @Test
    fun testPlaceholderPlainUsesSingleCurlyIndex() {
        val map = MergeApplier.buildPlaceholderRewrite(isVue = false, isReact = false, pairs = listOf("N3" to "x"))
        assertEquals("{3}", map["N3"]!!.first)
        assertEquals("\"3\"", map["N3"]!!.second)
    }

    @Test
    fun testPlaceholderMultiplePairsPreservesOrder() {
        val map = MergeApplier.buildPlaceholderRewrite(isVue = true, isReact = false, pairs = listOf("N0" to "a", "N1" to "b"))
        assertEquals("{N0}", map["N0"]!!.first)
        assertEquals("{N1}", map["N1"]!!.first)
    }

    @Test
    fun testPlaceholderNonNPrefixThrows() {
        assertThrows("占位 key 必须以 N 开头", IllegalArgumentException::class.java) {
            MergeApplier.buildPlaceholderRewrite(false, false, listOf("foo" to "x"))
        }
    }

    // ── buildParamsObjectString ───────────────────────────────
    @Test
    fun testParamsEmptyReturnsEmptyBrace() {
        assertEquals("{}", MergeApplier.buildParamsObjectString(true, emptyList()))
    }

    @Test
    fun testParamsSinglePair() {
        val s = MergeApplier.buildParamsObjectString(true, listOf("N0" to "'标题'"))
        assertEquals("{ N0: '标题' }", s)
    }

    @Test
    fun testParamsMultiplePairsJoined() {
        val s = MergeApplier.buildParamsObjectString(true, listOf("N0" to "'1'", "N1" to "'2'"))
        assertEquals("{ N0: '1', N1: '2' }", s)
    }

    // ── renderLiteralValue ─────────────────────────────────────
    @Test
    fun testRenderPlainNumberBare() {
        assertEquals("123", MergeApplier.renderLiteralValue("123"))
    }

    @Test
    fun testRenderNegativeDecimalBare() {
        assertEquals("-45.6", MergeApplier.renderLiteralValue("-45.6"))
    }

    @Test
    fun testRenderStringQuoted() {
        assertEquals("'标题'", MergeApplier.renderLiteralValue("标题"))
    }

    @Test
    fun testRenderStringWithSingleQuoteUsesDoubleQuote() {
        assertEquals("\"it's\"", MergeApplier.renderLiteralValue("it's"))
    }

    // ── renderDigitLiteral ─────────────────────────────────────
    @Test
    fun testDigitPlainNumberBare() {
        assertEquals("5", MergeApplier.renderDigitLiteral("5"))
    }

    @Test
    fun testDigitLeadingZeroMustBeQuoted() {
        // 0755 裸写是 strict 模式 octal 语法错误 → 必须带引号
        assertEquals("'0755'", MergeApplier.renderDigitLiteral("0755"))
    }

    @Test
    fun testDigitDecimalWithPointIsBare() {
        assertEquals("0.5", MergeApplier.renderDigitLiteral("0.5"))
    }

    @Test
    fun testDigitNonNumberQuoted() {
        assertEquals("'0x'", MergeApplier.renderDigitLiteral("0x"))
    }
}