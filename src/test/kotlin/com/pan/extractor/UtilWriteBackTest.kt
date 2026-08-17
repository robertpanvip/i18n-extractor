package com.pan.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 Util.kt 的「TS 导出对象 → 解析 → 合并 → 重写」写回管线做单元测试。
 *
 * 这些是纯函数，不需要 IntelliJ fixture，直接调用即可。
 * 注意：parsePropertyKey 只接受「引号包裹的 key」或「ASCII identifier」，中文 key 需用引号包裹。
 */
class UtilWriteBackTest {

    // ─────────────────────────────────────────────
    // A5：多行静态 value 的 key 不应被重复追加
    // ─────────────────────────────────────────────
    /**
     * oldObjBody 里有一个多行嵌套对象值（如 "用户": { ... }），mergedNested 也包含该 key。
     * 期望：只保留一份，不应在对象末尾重复追加该 key。
     */
    @Test
    fun testMultiLineNestedValueKeyNotDuplicated() {
        val oldBody = """
            '首页': '首页',
            '用户': {
              'name': '姓名',
              'age': '年龄',
            },
            '退出': '退出',
        """.trimIndent()

        // mergedNested 与旧对象一致（模拟"已存在、无需更新"的场景）
        val merged: Map<String, Any?> = linkedMapOf(
            "首页" to "首页",
            "用户" to linkedMapOf("name" to "姓名", "age" to "年龄"),
            "退出" to "退出",
        )

        val result = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)

        // ① 顶层 key「用户」不应出现两次
        val topKeys = result.lineSequence()
            .map { it.trimStart() }
            .filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("/*") }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon > 0) line.substring(0, colon).trim() else null
            }
            .toList()
        val userCount = topKeys.count { it.trim('\'', '"') == "用户" }
        assertEquals("多行嵌套 value 的 key '用户' 不应被重复追加，出现 $userCount 次，result:\n$result", 1, userCount)

        // ② 退出仍应保留
        assertTrue("'退出' 应保留", result.contains("退出"))
    }

    // ─────────────────────────────────────────────
    // A6：值/注释里含 } 不应截断对象（解析一致性）
    // ─────────────────────────────────────────────
    /**
     * splitTopLevelProperties 已处理注释；含块注释的值应正确解析。
     */
    @Test
    fun testParseObjectLiteralBodyWithBlockCommentValue() {
        val raw = """
            '首页': '首页',
            '说明': /* 备注 */ '说明文本',
            '退出': '退出',
        """.trimIndent()
        val parsed = TsFileEditor.parseObjectLiteralBody(raw)
        assertEquals("应解析出 '首页'，got $parsed", "首页", parsed["首页"])
        assertTrue("应解析出 '退出'，got $parsed", parsed.containsKey("退出"))
    }

    // ─────────────────────────────────────────────
    // A6b：注释里含 } 不应提前截断导出对象
    // ─────────────────────────────────────────────
    @Test
    fun testBraceInsideCommentShouldNotTruncateObject() {
        val ts = """
            export default {
              '首页': '首页',
              // 说明：这里写了一个 } 测试注释
              '退出': '退出',
            }
        """.trimIndent()

        val info = TsFileEditor.parseTsExportedObject(ts)
        assertNotNull("注释含 } 不应导致对象解析失败", info)
        assertEquals("应仍解析出 '退出'", "退出", info!!.staticKV["退出"])
    }

    // ─────────────────────────────────────────────
    // parseTsExportedObject + 重写 整体往返
    // ─────────────────────────────────────────────
    @Test
    fun testParseTsExportedObjectAndRegenerateRoundTrip() {
        val ts = """
            export default {
              '首页': '首页',
              '退出': '退出',
            }
        """.trimIndent()

        val info = TsFileEditor.parseTsExportedObject(ts)
        assertNotNull("应能找到导出对象", info)
        assertEquals("应解析出 '首页'", "首页", info!!.staticKV["首页"])
        assertEquals("应解析出 '退出'", "退出", info.staticKV["退出"])

        // 重写：新增一个 key
        val merged = linkedMapOf<String, Any?>()
        merged.putAll(info.staticKV)
        merged["新增"] = "新增文本"
        val oldBody = ts.substring(info.objectRange)
        val rewritten = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)
        assertTrue("重写后应包含新增 key", rewritten.contains("新增"))
    }
}