package com.pan.extractor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 针对「export default { ...common }」的 spread 路由写回做端到端测试：
 * 新 key 应写进 spread 变量指向的文件（同文件 const / 本地 import 的 TS / 本地 import 的 JSON），
 * 而不是追加到入口对象顶层。
 */
class UtilSpreadRoutingTest : BasePlatformTestCase() {

    private fun createEntry(relPath: String, content: String): VirtualFile {
        return myFixture.addFileToProject(relPath, content).virtualFile
    }

    private fun countOccurrences(text: String, needle: String): Int {
        return text.split(needle).size - 1
    }

    // ─────────────────────────────────────────
    // 1) 同文件 const 对象
    // ─────────────────────────────────────────
    fun testSameFileConstGetsNewKeys() {
        val entry = createEntry(
            "src/zh.ts",
            """
            const common = {
              '标题': '标题',
            }
            export default {
              ...common,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "退出" to "退出")
        val writes = Util.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别 ...common 并路由到同文件 const", writes)
        assertEquals("同文件 const 只产生 1 次写盘", 1, writes!!.size)
        assertEquals("写盘目标应为入口文件", entry.path, writes[0].first.path)
        val result = writes[0].second
        // 新 key '退出' 应写进 const common 块。注意 i18n 里 key 与 value 常相同（'退出': '退出'），
        // 因此按「key + 冒号」统计，避免被 value 干扰。
        assertEquals("'退出' key 应只出现在 const 块中一次，result:\n$result", 1, countOccurrences(result, "'退出':"))
        // const 块内包含 '退出'
        val constBlock = result.substringAfter("const common").substringBefore("export default")
        assertTrue("const common 块应包含 '退出'，result:\n$result", constBlock.contains("'退出'"))
        // 入口对象顶层不应新增 '退出'（入口对象在 export default 之后）
        val exportBlock = result.substringAfter("export default")
        assertEquals("入口对象顶层不应新增 '退出'，result:\n$result", 0, countOccurrences(exportBlock, "'退出'"))
        // 原有 key 保留
        assertTrue("应保留 '首页'，result:\n$result", result.contains("'首页'"))
        // 不应出现双大括号
        assertTrue("不应出现 '{{'，result:\n$result", !result.contains("{{"))
    }

    // ─────────────────────────────────────────
    // 2) 本地 import 的 TS
    // ─────────────────────────────────────────
    fun testLocalImportTsGetsNewKeys() {
        createEntry(
            "src/common.ts",
            """
            export default {
              '标题': '标题',
            }
            """.trimIndent()
        )
        val entry = createEntry(
            "src/zh.ts",
            """
            import common from './common'
            export default {
              ...common,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "退出" to "退出")
        val writes = Util.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别 ...common 并路由到本地 import 的 TS", writes)
        assertEquals("应产生入口 + common.ts 两次写盘", 2, writes!!.size)
        // 找到 common.ts 的写盘结果
        val commonWrite = writes.firstOrNull { it.first.path.endsWith("common.ts") }
        assertNotNull("应包含 common.ts 的写盘", commonWrite)
        assertTrue("common.ts 应包含新 key '退出'，result:\n${commonWrite!!.second}", commonWrite.second.contains("'退出'"))
        // 入口不应新增 '退出' 到顶层
        val entryWrite = writes.firstOrNull { it.first.path.endsWith("zh.ts") }!!
        assertEquals("入口对象顶层不应新增 '退出'，result:\n${entryWrite.second}", 0, countOccurrences(entryWrite.second.substringAfter("export default"), "'退出'"))
    }

    // ─────────────────────────────────────────
    // 3) 本地 import 的 JSON
    // ─────────────────────────────────────────
    fun testLocalImportJsonGetsNewKeys() {
        createEntry(
            "src/common.json",
            """
            { "标题": "标题" }
            """.trimIndent()
        )
        val entry = createEntry(
            "src/zh.ts",
            """
            import common from './common.json'
            export default {
              ...common,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "退出" to "退出")
        val writes = Util.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别 ...common 并路由到本地 import 的 JSON", writes)
        assertEquals("应产生入口 + common.json 两次写盘", 2, writes!!.size)
        val jsonWrite = writes.firstOrNull { it.first.path.endsWith("common.json") }
        assertNotNull("应包含 common.json 的写盘", jsonWrite)
        assertTrue("common.json 应包含新 key '退出'，result:\n${jsonWrite!!.second}", jsonWrite.second.contains("\"退出\""))
        val entryWrite = writes.firstOrNull { it.first.path.endsWith("zh.ts") }!!
        assertEquals("入口对象顶层不应新增 '退出'，result:\n${entryWrite.second}", 0, countOccurrences(entryWrite.second.substringAfter("export default"), "'退出'"))
    }

    // ─────────────────────────────────────────
    // 4) node_modules（裸包名）import → 回退（返回 null）
    // ─────────────────────────────────────────
    fun testNodeModulesImportFallsBack() {
        val entry = createEntry(
            "src/zh.ts",
            """
            import common from 'i18n-common'
            export default {
              ...common,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "退出" to "退出")
        val writes = Util.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNull("裸包名 import 应视为非本地，返回 null 回退旧逻辑", writes)
    }

    // ─────────────────────────────────────────
    // 5) 无可解析目标（const 不存在、也非 import）→ 回退 null
    // ─────────────────────────────────────────
    fun testUnresolvableSpreadFallsBack() {
        val entry = createEntry(
            "src/zh.ts",
            """
            export default {
              ...undefinedRef,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "退出" to "退出")
        val writes = Util.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNull("无法解析 spread 目标时应返回 null 回退旧逻辑", writes)
    }
}