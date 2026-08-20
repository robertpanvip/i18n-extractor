package com.pan.extractor.core

import com.pan.extractor.editor.TsFileEditor
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
    fun parseBareChineseKey() {
        // Bug：zh.ts 常见 `中文: "中文"` 这种不带引号的裸中文 key，应能被解析
        val map = TsFileEditor.parseObjectLiteralBody("{ 中文: \"中文\", 搜索关键词: '搜索关键词' }")
        assertEquals("中文", map["中文"])
        assertEquals("搜索关键词", map["搜索关键词"])
    }

    @Test
    fun parseBareNonChineseKeys() {
        // 任意语言字母裸 key（日语/法语/德语/组合变音）都应被解析，不限中文
        val map = TsFileEditor.parseObjectLiteralBody(
            "{ こんにちは: 'こんにちは', 日本語: '日本語', café: 'café', über: 'über', grüß: 'grüß' }"
        )
        assertEquals("こんにちは", map["こんにちは"])
        assertEquals("日本語", map["日本語"])
        assertEquals("café", map["café"])
        assertEquals("über", map["über"])
        assertEquals("grüß", map["grüß"])
        // 兼容"基础字母+组合变音"（decomposed accent：cafe + U+0301）
        val decomposed = TsFileEditor.parseObjectLiteralBody("{ cafe\u0301: 'x' }")
        assertEquals("x", decomposed["cafe\u0301"])
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

    // ── JSON 写回格式边界（P1 §11 Resource Writer）────────────────
    // 检测原文件的 UTF-8 BOM 与换行风格，写回时格式不漂移。

    @Test
    fun detectFormatPlainLf() {
        val fmt = TsFileEditor.detectJsonWriteFormat("{\n  \"a\": 1\n}\n")
        assertEquals(false, fmt.bom)
        assertEquals(false, fmt.crlf)
        assertEquals("\n", fmt.newline)
    }

    @Test
    fun detectFormatCrlf() {
        val fmt = TsFileEditor.detectJsonWriteFormat("{\r\n  \"a\": 1\r\n}\r\n")
        assertEquals(false, fmt.bom)
        assertEquals(true, fmt.crlf)
        assertEquals("\r\n", fmt.newline)
    }

    @Test
    fun detectFormatBomLf() {
        val fmt = TsFileEditor.detectJsonWriteFormat("\uFEFF{\n  \"a\": 1\n}\n")
        assertEquals(true, fmt.bom)
        assertEquals(false, fmt.crlf)
    }

    @Test
    fun detectFormatBomCrlf() {
        val fmt = TsFileEditor.detectJsonWriteFormat("\uFEFF{\r\n  \"a\": 1\r\n}\r\n")
        assertEquals(true, fmt.bom)
        assertEquals(true, fmt.crlf)
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

    // ── 回归：原实现因正则 `[\w$.<>\[\],()|&'" ?]+$` 在复杂类型下触发
    //    Java Pattern 编译阶段栈溢出（PatternSyntaxException wrapping StackOverflowError）

    /** 程序构造 N 层嵌套泛型（严格括号平衡，避免手写字数错）。 */
    private fun nestedGenerics(n: Int, letterSeed: Char = 'A'): String =
        (0 until n).fold("X") { acc, i -> "${(letterSeed.code + i).toChar()}<$acc>" } +
            ">".repeat(0)   // fold 已保证每一层都配对

    @Test
    fun stripDeeplyNestedGenericAsType() {
        // 20 层嵌套泛型：旧正则只要字符类里含括号字符，编译时就会递归爆炸
        assertEquals("val", TsFileEditor.stripValueSuffixes("val as " + nestedGenerics(20, 'A')))
    }

    @Test
    fun stripDeeplyNestedSatisfiesType() {
        // 倒序字母：Z<Y<X<...<X>...>>
        val t = (0 until 20).fold("X") { acc, i -> "${('Z'.code - i).toChar()}<$acc>" }
        assertEquals("42", TsFileEditor.stripValueSuffixes("42 satisfies $t"))
    }

    @Test
    fun stripComplexGenericAsType() {
        assertEquals("{ a: 1 }", TsFileEditor.stripValueSuffixes("{ a: 1 } as Map<String, List<Int?>>"))
    }

    @Test
    fun stripFunctionTypeAsSuffix() {
        assertEquals("fn", TsFileEditor.stripValueSuffixes("fn as (a: Int, b: String) -> Boolean"))
    }

    @Test
    fun stripUnionIntersectionAsType() {
        assertEquals("x", TsFileEditor.stripValueSuffixes("x as A | B | (C & D)"))
    }

    @Test
    fun stripArrayTupleAsType() {
        assertEquals("x", TsFileEditor.stripValueSuffixes("x as [string, number, ...boolean[]]"))
    }

    @Test
    fun stripIndexedAccessGenericArrayAsType() {
        assertEquals("x", TsFileEditor.stripValueSuffixes("x as Foo<string>[]"))
    }

    @Test
    fun stripStringLiteralUnionAsType() {
        // 类型里的字符串字面量联合
        assertEquals("x", TsFileEditor.stripValueSuffixes("""x as 'foo' | "bar" | `baz`"""))
    }

    // ── 语义修复：关键字出现在字符串内部时，**不能**误剥为类型后缀 ──

    @Test
    fun doNotStripAsInsideSingleQuotedString() {
        val s = "'hello as world'"
        assertEquals(s, TsFileEditor.stripValueSuffixes(s))
    }

    @Test
    fun doNotStripSatisfiesInsideDoubleQuotedString() {
        val s = """"something satisfies config""""
        assertEquals(s, TsFileEditor.stripValueSuffixes(s))
    }

    @Test
    fun doNotStripAsInsideTemplateLiteral() {
        val s = "`template as content`"
        assertEquals(s, TsFileEditor.stripValueSuffixes(s))
    }

    // 字符串里即使包含合法类型写法，也不能剥
    @Test
    fun doNotStripAsFakeTypeInsideString() {
        val s = """'x as Map<A, B>'"""
        assertEquals(s, TsFileEditor.stripValueSuffixes(s))
    }

    // ── 边界：不应剥除的异常情况 ─────────────────────────────────

    @Test
    fun doNotStripBlankTypeSuffix() {
        // 末尾 `as ` 后跟空白，没有实际类型 → 不剥
        val s = "foo as "
        assertEquals(s.trim(), TsFileEditor.stripValueSuffixes(s))
    }

    @Test
    fun doNotStripUnbalancedAngleBracketType() {
        // `<` 没有闭合 `>`，不是合法类型后缀 → 不剥，保守
        val s = "foo as Foo<Bar"
        assertEquals(s, TsFileEditor.stripValueSuffixes(s))
    }

    @Test
    fun doNotStripAsKeywordInIdentifier() {
        // `hasClass` 中的 `as` 是标识符一部分，不能当关键字
        val s = "hasClass"
        assertEquals(s, TsFileEditor.stripValueSuffixes(s))
    }

    // ── 链式剥除 ────────────────────────────────────────────────

    @Test
    fun stripChainedAsConstAndAsType() {
        // `as const as string` 这种链式，按 while 循环应当两层都剥掉
        assertEquals("x", TsFileEditor.stripValueSuffixes("x as const as string"))
    }

    @Test
    fun stripAsConstChained() {
        // 两次 `as const`（虽不太合理，但 while 语义保证可剥）
        assertEquals("x", TsFileEditor.stripValueSuffixes("x as const as const"))
    }
}