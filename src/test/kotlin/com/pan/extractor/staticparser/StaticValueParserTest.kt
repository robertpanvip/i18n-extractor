package com.pan.extractor.staticparser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StaticValueParser 纯单元测试（不依赖 IntelliJ 平台，纯 Kotlin JUnit）。
 * 覆盖所有静态值识别形态，以及非静态的拒绝场景。
 */
class StaticValueParserTest {

    private fun parse(expr: String) = StaticValueParser.tryParseStaticValue(expr)

    // ── 字面量 ────────────────────────────────────────────────
    @Test fun `null literal`() { assertNull(parse("null")); assertNull(parse("undefined")) }
    @Test fun `boolean literal`() {
        assertEquals(true, parse("true"))
        assertEquals(false, parse("false"))
    }

    // ── 数字 ──────────────────────────────────────────────────
    @Test fun `decimal int`() {
        assertEquals(42L, parse("42"))
        assertEquals(-7L, parse("-7"))
    }
    @Test fun `decimal float`() {
        assertEquals(3.14, parse("3.14"))
        assertEquals(-0.5, parse("-0.5"))
    }
    @Test fun `scientific notation`() {
        assertEquals(1000.0, parse("1e3"))
        assertEquals(0.015, parse("1.5E-2"))
    }
    @Test fun `numeric separators`() {
        assertEquals(1000000L, parse("1_000_000"))
        assertEquals(1.5, parse("1_500e-3"))
    }
    @Test fun `bigint`() { assertEquals(99L, parse("99n")) }
    @Test fun `hex literal`() { assertEquals(0x1FL, parse("0x1F")); assertEquals(255L, parse("0xff")) }
    @Test fun `binary literal`() { assertEquals(5L, parse("0b101")) }
    @Test fun `octal literal`() { assertEquals(15L, parse("0o17")) }
    @Test fun `hex with separators`() { assertEquals(65535L, parse("0xFF_FF")) }

    // ── 一元运算 ──────────────────────────────────────────────
    @Test fun `unary minus on int`() { assertEquals(-1L, parse("-1")) }
    @Test fun `unary plus on int`() { assertEquals(1L, parse("+1")) }
    @Test fun `unary minus on float`() { assertEquals(-3.14, parse("-3.14")) }
    @Test fun `unary not on boolean`() {
        assertEquals(false, parse("!true"))
        assertEquals(true, parse("!false"))
    }
    @Test fun `unary on non-static rejects`() { assertNull(parse("!foo")) }

    // ── 字符串 ────────────────────────────────────────────────
    @Test fun `single quoted string`() { assertEquals("hello", parse("'hello'")) }
    @Test fun `double quoted string`() { assertEquals("world", parse("\"world\"")) }
    @Test fun `template literal no interpolation`() { assertEquals("hi", parse("`hi`")) }
    @Test fun `string escape sequences`() {
        assertEquals("a\nb", parse("'a\\nb'"))
        assertEquals("a\tb", parse("\"a\\tb\""))
        assertEquals("a'b", parse("'a\\'b'"))
    }
    @Test fun `unicode escape`() { assertEquals("中", parse("'\\u4E2D'")) }
    @Test fun `hex escape`() { assertEquals("A", parse("'\\x41'")) }

    // ── 模板字面量带静态插值 ─────────────────────────────────
    @Test fun `template with static string interpolation`() {
        assertEquals("prefix-static-suffix", parse("`prefix-\${'static'}-suffix`"))
    }
    @Test fun `template with static number interpolation`() {
        assertEquals("count=42", parse("`count=\${42}`"))
    }
    @Test fun `template with dynamic interpolation rejects`() {
        assertNull(parse("`a\${var}b`"))
    }
    @Test fun `nested template in interpolation`() {
        assertEquals("outer-inner", parse("`outer-\${`inner`}`"))
    }

    // ── 字符串拼接（全字面量）────────────────────────────────
    @Test fun `concat all string literals`() {
        assertEquals("abc", parse("'a' + 'b' + \"c\""))
    }
    @Test fun `concat with template literal`() {
        assertEquals("xy", parse("`x` + 'y'"))
    }
    @Test fun `concat with non-string rejects`() {
        assertNull(parse("'a' + foo"))
    }
    @Test fun `arithmetic plus not treated as concat`() {
        // 1 + 2 不进入拼接路径（首字符非字符串）
        assertNull(parse("1 + 2"))
    }

    // ── TS 类型断言 ───────────────────────────────────────────
    @Test fun `as const suffix`() { assertEquals("hello", parse("'hello' as const")) }
    @Test fun `as Type suffix`() { assertEquals("hello", parse("'hello' as string")) }
    @Test fun `satisfies Type suffix`() { assertEquals("hello", parse("'hello' satisfies string")) }
    @Test fun `object as const`() {
        val r = parse("{ a: 1 } as const")
        @Suppress("UNCHECKED_CAST")
        assertEquals(1L, (r as Map<String, Any?>)["a"])
    }

    // ── 对象 / 数组 ───────────────────────────────────────────
    @Test fun `object literal`() {
        val r = parse("{ a: 1, b: 'x', c: true }") as Map<*, *>
        assertEquals(1L, r["a"])
        assertEquals("x", r["b"])
        assertEquals(true, r["c"])
    }
    @Test fun `nested object literal`() {
        val r = parse("{ outer: { inner: 'deep' } }") as Map<*, *>
        @Suppress("UNCHECKED_CAST")
        val inner = r["outer"] as Map<String, Any?>
        assertEquals("deep", inner["inner"])
    }
    @Test fun `array literal`() {
        val r = parse("[1, 'two', true]") as List<*>
        assertEquals(1L, r[0])
        assertEquals("two", r[1])
        assertEquals(true, r[2])
    }
    @Test fun `array with spread rejects`() {
        val r = parse("[1, ...rest]") as List<*>
        assertEquals(1, r.size) // spread 元素整条跳过
        assertEquals(1L, r[0])
    }

    // ── 非静态拒绝 ────────────────────────────────────────────
    @Test fun `identifier reference rejects`() { assertNull(parse("someVar")) }
    @Test fun `function call rejects`() { assertNull(parse("makeText()")) }
    @Test fun `spread rejects`() { assertNull(parse("...arr")) }
    @Test fun `ternary with variable rejects`() { assertNull(parse("cond ? 'a' : 'b'")) }
}
