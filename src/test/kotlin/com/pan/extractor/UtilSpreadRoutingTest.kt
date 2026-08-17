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
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
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
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
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
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别 ...common 并路由到本地 import 的 JSON", writes)
        assertEquals("应产生入口 + common.json 两次写盘", 2, writes!!.size)
        val jsonWrite = writes.firstOrNull { it.first.path.endsWith("common.json") }
        assertNotNull("应包含 common.json 的写盘", jsonWrite)
        assertTrue("common.json 应包含新 key '退出'，result:\n${jsonWrite!!.second}", jsonWrite.second.contains("\"退出\""))
        val entryWrite = writes.firstOrNull { it.first.path.endsWith("zh.ts") }!!
        assertEquals("入口对象顶层不应新增 '退出'，result:\n${entryWrite.second}", 0, countOccurrences(entryWrite.second.substringAfter("export default"), "'退出'"))
    }

    // ─────────────────────────────────────────
    // 4) node_modules（裸包名）import → 只读识别内容，不写盘
    // ─────────────────────────────────────────
    fun testNodeModulesRecognizedReadOnly() {
        createEntry(
            "node_modules/i18n-common/index.js",
            """
            module.exports = {
              '标题': '标题',
            }
            """.trimIndent()
        )
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
        // 新 key 里 '标题' 已存在于 node_modules（被 spread 覆盖），'退出' 是真正新增
        val newFlat = linkedMapOf("首页" to "首页", "标题" to "标题", "退出" to "退出")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别 node_modules 内容（只读），不返回 null", writes)
        assertEquals("node_modules 只读：只写盘入口一次", 1, writes!!.size)
        assertEquals("写盘目标应为入口文件", entry.path, writes[0].first.path)
        val result = writes[0].second
        // 真正新增的 '退出' 应写进入口对象
        assertTrue("入口对象应包含新 key '退出'，result:\n$result", result.substringAfter("export default").contains("'退出'"))
        // 已在 node_modules 里的 '标题' 不应被重复写进入口
        assertEquals("已在 node_modules 的 '标题' 不应重复写入入口，result:\n$result", 0, countOccurrences(result.substringAfter("export default"), "'标题'"))
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
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNull("无法解析 spread 目标时应返回 null 回退旧逻辑", writes)
    }

    // ─────────────────────────────────────────
    // 6) 嵌套对象里的 spread：nav: { ...common }，nav 下的新 key 路由到 common 目标
    // ─────────────────────────────────────────
    fun testNestedSpreadRoutesToTarget() {
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
              nav: {
                ...common,
              },
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "nav.退出" to "退出")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别嵌套 spread 并路由到 common", writes)
        // common.ts 应主要路由 nav 下的新 key
        val commonWrite = writes!!.firstOrNull { it.first.path.endsWith("common.ts") }
        assertNotNull("应包含 common.ts 的写盘", commonWrite)
        assertTrue("common.ts 应包含新 key '退出'，result:\n${commonWrite!!.second}", commonWrite.second.contains("'退出'"))
        // 入口 nav 容器不应把 '退出' 重复写进去
        val entryWrite = writes.firstOrNull { it.first.path.endsWith("zh.ts") }!!
        val exportBlock = entryWrite.second.substringAfter("export default")
        assertEquals("入口对象不应重复新增 nav 下的 '退出'，result:\n$exportBlock", 0, countOccurrences(exportBlock, "'退出'"))
    }

    // ─────────────────────────────────────────
    // 7) 命名空间导入：import * as common from './common' → 解析到默认导出对象
    // ─────────────────────────────────────────
    fun testNamespaceImportSpreadRoutesToTarget() {
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
            import * as common from './common'
            export default {
              ...common,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "退出" to "退出")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别 import * as 命名空间导入并路由", writes)
        val commonWrite = writes!!.firstOrNull { it.first.path.endsWith("common.ts") }
        assertNotNull("应包含 common.ts 的写盘", commonWrite)
        assertTrue("common.ts 应包含新 key '退出'，result:\n${commonWrite!!.second}", commonWrite.second.contains("'退出'"))
    }

    // ─────────────────────────────────────────
    // 8) 嵌套对象值带 as const（TS 收紧类型的常见写法）：点式新 key 归并进现有对象，不产生重复
    // ─────────────────────────────────────────
    fun testNestedAsConstMergesDottedKey() {
        val entry = createEntry(
            "src/zh.ts",
            """
            export default {
              nav: { '标题': '标题' } as const,
            }
            """.trimIndent()
        )
        val newText = TsFileEditor.regenerateTsFileWithNewJson(project, entry, linkedMapOf("nav.退出" to "退出"))
        assertNotNull("应能解析带 as const 的嵌套对象并重写", newText)
        val exportBlock = newText!!.substringAfter("export default")
        assertEquals("nav 容器不应重复出现，result:\n$exportBlock", 1, countOccurrences(exportBlock, "nav:"))
        assertTrue("nav 内应包含 '退出'，result:\n$exportBlock", exportBlock.contains("'退出'"))
        assertTrue("应保留原有 '标题'，result:\n$exportBlock", exportBlock.contains("'标题'"))
    }

    // ─────────────────────────────────────────
    // 9) 多个 spread 变量（嵌套容器）分别路由到各自目标文件
    // ─────────────────────────────────────────
    fun testMultipleSpreadVarsRouteToSeparateFiles() {
        createEntry(
            "src/common.ts",
            """
            export default {
              '标题': '标题',
            }
            """.trimIndent()
        )
        createEntry(
            "src/extra.ts",
            """
            export default {
              '菜单': '菜单',
            }
            """.trimIndent()
        )
        val entry = createEntry(
            "src/zh.ts",
            """
            import common from './common'
            import extra from './extra'
            export default {
              nav: { ...common },
              menu: { ...extra },
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("nav.退出" to "退出", "menu.设置" to "设置")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别多个 spread 并分别路由", writes)

        // nav 下的新 key 应路由到 common.ts
        val commonWrite = writes!!.firstOrNull { it.first.path.endsWith("common.ts") }
        assertNotNull("应写盘 common.ts", commonWrite)
        assertTrue("common.ts 应包含 '退出'，result:\n${commonWrite!!.second}", commonWrite.second.contains("'退出'"))
        // menu 下的新 key 应路由到 extra.ts
        val extraWrite = writes.firstOrNull { it.first.path.endsWith("extra.ts") }
        assertNotNull("应写盘 extra.ts", extraWrite)
        assertTrue("extra.ts 应包含 '设置'，result:\n${extraWrite!!.second}", extraWrite.second.contains("'设置'"))
        // 入口容器不应重复写入
        val entryWrite = writes.firstOrNull { it.first.path.endsWith("zh.ts") }!!
        val exportBlock = entryWrite.second.substringAfter("export default")
        assertEquals("入口不应重复新增 nav/menu 下的 key，result:\n$exportBlock", 0,
            countOccurrences(exportBlock, "'退出'") + countOccurrences(exportBlock, "'设置'"))
    }

    // ─────────────────────────────────────────
    // 10) 同名变量：const 声明优先于 import（遮蔽），新 key 路由进 const 块而非 import 目标
    // ─────────────────────────────────────────
    fun testConstShadowsImportForSpread() {
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
            const common = {
              '本地': '本地',
            }
            export default {
              ...common,
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("退出" to "退出")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应解析到 const 声明（优先于 import）", writes)
        // 只写盘入口（const 与入口同文件），common.ts 不被写
        assertEquals("const 遮蔽：只写盘入口一次", 1, writes!!.size)
        assertEquals("写盘目标应为入口文件", entry.path, writes[0].first.path)
        val constBlock = writes[0].second.substringAfter("const common").substringBefore("export default")
        assertTrue("const 块应包含新 key '退出'，result:\n${writes[0].second}", constBlock.contains("'退出'"))
    }

    // ─────────────────────────────────────────
    // 11) 韧性：目标文件自带嵌套 spread（无法继续解析）时不应崩溃，仍能正常路由
    // ─────────────────────────────────────────
    fun testTargetWithOwnNestedSpreadIsResilient() {
        createEntry(
            "src/common.ts",
            """
            export default {
              '标题': '标题',
              sub: { ...deeper },
            }
            """.trimIndent()
        )
        val entry = createEntry(
            "src/zh.ts",
            """
            import common from './common'
            export default {
              ...common,
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("退出" to "退出")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("目标自带嵌套 spread 不应崩溃", writes)
        val commonWrite = writes!!.firstOrNull { it.first.path.endsWith("common.ts") }
        assertNotNull("应写盘 common.ts", commonWrite)
        assertTrue("common.ts 应包含新 key '退出'，result:\n${commonWrite!!.second}", commonWrite.second.contains("'退出'"))
        // 原有 sub: { ...deeper } 动态表达式应原样保留（不崩溃、不丢失）
        assertTrue("应保留 sub 动态表达式，result:\n${commonWrite.second}", commonWrite.second.contains("...deeper"))
    }
}