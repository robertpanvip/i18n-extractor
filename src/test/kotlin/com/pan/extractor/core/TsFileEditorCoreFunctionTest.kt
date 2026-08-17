package com.pan.extractor.core

import com.pan.extractor.TsFileEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专门测试核心纯函数：TsFileEditor 的对象字面量解析 / 扁平合并 / 再生成。
 *
 * 这些方法不依赖 IntelliJ 平台（纯字符串处理），因此作为纯单元测试运行，
 * 速度快、无需启动 IDE 测试框架。覆盖：
 *   1. parseObjectLiteralBody —— 静态 KV 抽取（含嵌套 / 数组 / 注释 / 非静态跳过）
 *   2. findBalancedCloseBrace —— 括号平衡（正确处理字符串内容与注释）
 *   3. mergeFlatIntoNested   —— 扁平 key 展开为嵌套（含点式 key 与冲突退化）
 *   4. regenerateObjectLiteralBody —— 保留非静态行 + 追加新 key
 *   5. stripValueSuffixes    —— 剥离 `as const` / `satisfies <T>` 后缀
 */
class TsFileEditorCoreFunctionTest {

    // ── parseObjectLiteralBody ─────────────────────────────────

    @Test
    fun parseSimpleFlatObject() {
        val map = TsFileEditor.parseObjectLiteralBody("{ a: 1, b: 'x', c: true }")
        assertEquals(1L, map["a"])
        assertEquals("x", map["b"])
        assertEquals(true, map["c"])
    }

    @Test
    fun parseNestedObject() {
        val map = TsFileEditor.parseObjectLiteralBody("{ outer: { inner: 'deep' } }")
        val nested = map["outer"] as Map<*, *>
        assertEquals("deep", nested["inner"])
    }

    @Test
    fun parseSkippedNonStaticProperties() {
        // spread / 函数调用 / 引用 / 三元 应被整条跳过，不抛错
        val map = TsFileEditor.parseObjectLiteralBody(
            "{ keep: 'ok', spread: ...other, fn: build(), ref: someVar, tern: a ? 1 : 2 }"
        )
        assertEquals(mapOf("keep" to "ok"), map)
    }

    @Test
    fun parsePropertyWithLeadingComment() {
        // Bug A6：属性片断以注释开头，应剥离注释后正常解析
        val map = TsFileEditor.parseObjectLiteralBody(
            "{ // 说明\n value: 'hello' }"
        )
        assertEquals("hello", map["value"])
    }

    @Test
    fun parseQuotedAndComputedKeys() {
        val map = TsFileEditor.parseObjectLiteralBody(
            "{ 'quoted': 1, \"dq\": 2, [123]: 'num', [\"ck\"]: 'computed' }"
        )
        assertEquals(1L, map["quoted"])
        assertEquals(2L, map["dq"])
        assertEquals("num", map["123"])
        assertEquals("computed", map["ck"])
    }

    @Test
    fun parseArrayLiteral() {
        val map = TsFileEditor.parseObjectLiteralBody("{ list: [1, 'a', { x: 2 }] }")
        val list = map["list"] as List<*>
        assertEquals(3, list.size)
        assertEquals(1L, list[0])
        assertEquals("a", list[1])
        @Suppress("UNCHECKED_CAST")
        assertEquals(2L, (list[2] as Map<String, Any?>)["x"])
    }

    @Test
    fun parseBlankReturnsEmpty() {
        assertTrue(TsFileEditor.parseObjectLiteralBody("  ").isEmpty())
    }

    // ── findBalancedCloseBrace ─────────────────────────────────

    @Test
    fun balancedBraceIgnoresStringContent() {
        // 字符串内的 { } 不应影响平衡
        val text = """{ "braces": "}{", b: 1 }"""
        val end = TsFileEditor.findBalancedCloseBrace(text, 0)
        assertEquals(text.length, end)
    }

    @Test
    fun balancedBraceIgnoresComments() {
        val text = "{ /* } */ a: 1 }"
        val end = TsFileEditor.findBalancedCloseBrace(text, 0)
        assertEquals(text.length, end)
    }

    @Test
    fun balancedBraceNullWhenUnclosed() {
        assertNull(TsFileEditor.findBalancedCloseBrace("{ a: 1", 0))
    }

    // ── mergeFlatIntoNested ────────────────────────────────────

    @Test
    fun mergeDottedKeyIntoNested() {
        val result = TsFileEditor.mergeFlatIntoNested(
            emptyMap(),
            mapOf("common.confirm" to "确定")
        )
        @Suppress("UNCHECKED_CAST")
        val common = result["common"] as Map<String, Any?>
        assertEquals("确定", common["confirm"])
    }

    @Test
    fun mergeConflictDegradesToTopLevel() {
        // 中间段已是字符串，无法写嵌套 → 退化写顶层点式 key
        val result = TsFileEditor.mergeFlatIntoNested(
            mapOf("common" to "old-string"),
            mapOf("common.confirm" to "确定")
        )
        assertEquals("确定", result["common.confirm"])
    }

    @Test
    fun mergeOverridesExistingValue() {
        val result = TsFileEditor.mergeFlatIntoNested(
            mapOf("hello" to "旧值"),
            mapOf("hello" to "新值")
        )
        assertEquals("新值", result["hello"])
    }

    /**
     * Bug 回归（问题 2/3）：中文文案带省略号（如 "加载中..."）作为 key 时，
     * 其点号是文本而非嵌套分隔符。旧逻辑把它当成点式路径拆分，错生成
     * { '加载中': { '': { '': { '': '加载中...' } } } }。
     * 现应整体当作一个扁平 key 字面写回，且覆盖已有同名 key。
     */
    @Test
    fun mergeEllipsisKeyStaysFlat() {
        // 第一次写入：空文件 + "加载中..." → 应整体作为一个扁平 key
        val once = TsFileEditor.mergeFlatIntoNested(
            emptyMap(),
            mapOf("加载中..." to "加载中...")
        )
        assertEquals("加载中...", once["加载中..."])
        assertTrue("带省略号的 key 不应被拆成嵌套，实际: $once", once.keys.all { it == "加载中..." })

        // 第二次写入：已有同名 key，新值应覆盖旧值，且不再嵌套
        val twice = TsFileEditor.mergeFlatIntoNested(
            mapOf("加载中..." to "旧值"),
            mapOf("加载中..." to "加载中...")
        )
        assertEquals("加载中...", twice["加载中..."])
        assertTrue("重复 key 应整体覆盖，实际: $twice", twice.keys.all { it == "加载中..." })

        // 真正干净的点式路径（common.confirm）仍需保持嵌套行为
        val nested = TsFileEditor.mergeFlatIntoNested(
            emptyMap(),
            mapOf("common.confirm" to "确定")
        )
        assertTrue("干净点式 key 仍应嵌套", (nested["common"] as Map<*, *>)["confirm"] == "确定")
    }

    // ── regenerateObjectLiteralBody ────────────────────────────

    @Test
    fun regenerateKeepsNonStaticLines() {
        val old = "{ spread: ...other,\n keep: 'old' }"
        val merged = TsFileEditor.mergeFlatIntoNested(
            mapOf("keep" to "old"),
            mapOf("new" to "added")
        )
        val out = TsFileEditor.regenerateObjectLiteralBody(old, merged)
        assertTrue("应保留非静态 spread 行", out.contains("...other"))
        assertTrue("应包含保留的旧 key", out.contains("keep"))
        assertTrue("应追加新 key", out.contains("new"))
    }

    @Test
    fun regenerateAppendsNewTopLevelKey() {
        val old = "{ a: 1 }"
        val merged = mapOf("a" to 1L, "bbb" to "值")
        val out = TsFileEditor.regenerateObjectLiteralBody(old, merged)
        assertTrue("应追加新 key bbb", out.contains("bbb"))
    }

    // ── stripValueSuffixes ─────────────────────────────────────

    @Test
    fun stripAsConstSuffix() {
        assertEquals("{ a: 1 }", TsFileEditor.stripValueSuffixes("{ a: 1 } as const"))
    }

    @Test
    fun stripSatisfiesType() {
        assertEquals("{ a: 1 }", TsFileEditor.stripValueSuffixes("{ a: 1 } satisfies Foo"))
    }

    @Test
    fun stripNestedSatisfiesType() {
        assertEquals("{ a: 1 }", TsFileEditor.stripValueSuffixes("{ a: 1 } satisfies Main.Messages"))
    }
}