package com.pan.extractor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 Util.kt 的「TS 静态读取 / 写入 / spread 路由」增强点做补充测试：
 *
 * 读取增强：
 *   1. export const <name>: T = {...} 带类型标注（Record<> / 内联对象类型）
 *   2. export default defineXxx({...}) 包裹函数
 * 写入增强：
 *   3. 单行 key 重写时保留尾注释（// xxx 与 /* xxx */）
 *   4. 多行对象块重写时保留「key: {」行尾注释
 * spread 路由增强：
 *   5. const 纯转发光束 → 多级递归路由到最深的可写非入口文件
 *   6. const 相互 spread（循环引用）→ 深度/循环防护，不无限递归、不崩溃
 */
class UtilTsReadWriteEnhanceTest : BasePlatformTestCase() {

    // ─────────────────────────────────────────
    // 读取增强：带类型标注的 export const
    // ─────────────────────────────────────────

    @Test
    fun testExportConstWithTypeAnnotationRecord() {
        val ts = """
            export const messages: Record<string, string> = {
              'hello': '你好',
              'bye': '再见',
            }
        """.trimIndent()

        val info = TsFileEditor.parseTsExportedObject(ts)
        assertNotNull("应能解析带 Record<> 类型标注的 export const", info)
        assertEquals("应解析出 'hello'", "你好", info!!.staticKV["hello"])
        assertEquals("应解析出 'bye'", "再见", info.staticKV["bye"])
    }

    @Test
    fun testExportConstWithInlineObjectTypeAnnotation() {
        // 类型标注内含 '{'，对象字面量起点应定位到 '=' 之后的第一个 '{'
        val ts = """
            export const locale: { hello: string; goodbye: string } = {
              'hello': '你好',
              'goodbye': '再见',
            }
        """.trimIndent()

        val info = TsFileEditor.parseTsExportedObject(ts)
        assertNotNull("应能解析类型标注内含 '{' 的 export const", info)
        assertEquals("应解析出 'hello'", "你好", info!!.staticKV["hello"])
        assertEquals("应解析出 'goodbye'", "再见", info.staticKV["goodbye"])
        // 对象体应定位正确：objectRange 指向真正的对象字面量
        val objBody = ts.substring(info.objectRange)
        assertTrue("对象体应包含 'hello' key", objBody.contains("'hello'"))
    }

    @Test
    fun testExportConstWithTypeAnnotationRecognizedAsNamed() {
        val ts = "export const messages: Record<string, string> = { 'a': 'b' }"
        val info = TsFileEditor.parseTsExportedObject(ts)
        assertNotNull(info)
        assertTrue("exportType 应标记为 named", info!!.exportType.startsWith("named:") && info.exportType.contains("messages"))
    }

    // ─────────────────────────────────────────
    // 读取增强：export default defineXxx({...})
    // ─────────────────────────────────────────

    @Test
    fun testExportDefaultDefineXxxWrapped() {
        for (wrapper in listOf("defineIntlConfig", "defineMessages", "defineConfig", "createI18n")) {
            val ts = """
                export default $wrapper({
                  'hello': '你好',
                  'bye': '再见',
                })
            """.trimIndent()

            val info = TsFileEditor.parseTsExportedObject(ts)
            assertNotNull("应能解析 export default $wrapper({...})", info)
            assertEquals("应解析出 'hello'（$wrapper）", "你好", info!!.staticKV["hello"])
            assertEquals("应解析出 'bye'（$wrapper）", "再见", info.staticKV["bye"])
        }
    }

    @Test
    fun testExportDefaultDefineXxxWrappedRewriteAddsKey() {
        val ts = """
            export default defineIntlConfig({
              'hello': '你好',
            })
        """.trimIndent()
        val info = TsFileEditor.parseTsExportedObject(ts)!!
        val merged = linkHashMapOf(info.staticKV, "goodbye" to "再见")
        val oldBody = ts.substring(info.objectRange)
        val rewritten = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)
        assertTrue("包裹函数内应新增 'goodbye'，result:\n$rewritten", rewritten.contains("goodbye"))
        assertTrue("应保留 'hello'，result:\n$rewritten", rewritten.contains("hello"))
    }

    // ─────────────────────────────────────────
    // 写入增强：单行 key 重写保留尾注释
    // ─────────────────────────────────────────

    @Test
    fun testSingleLineRewritePreservesLineComment() {
        val oldBody = """
            '首页': '首页',
            '退出': '退出', // 这是个重要说明
        """.trimIndent()
        val merged = linkedMapOf("首页" to "首页", "退出" to "退出V2")
        val result = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)
        assertTrue("重写后应保留 // 尾注释，result:\n$result", result.contains("// 这是个重要说明"))
        assertTrue("'退出' 值应更新为 V2，result:\n$result", result.contains("'退出V2'"))
        assertTrue("应保留 '首页'，result:\n$result", result.contains("'首页'"))
    }

    @Test
    fun testSingleLineRewritePreservesBlockComment() {
        val oldBody = """
            '首页': '首页',
            '退出': '退出', /* 块状备注 */
        """.trimIndent()
        val merged = linkedMapOf("首页" to "首页", "退出" to "退出V2")
        val result = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)
        assertTrue("重写后应保留 /* */ 尾注释，result:\n$result", result.contains("/* 块状备注 */"))
        assertTrue("'退出' 值应更新，result:\n$result", result.contains("'退出V2'"))
    }

    @Test
    fun testSingleLineUnchangedKeyKeepsOriginalComment() {
        // key 不在 mergedKeys 中 → 整行原样保留（含注释）
        val oldBody = "'保留': '原值', // 原样注释"
        val merged = linkedMapOf("新增" to "新值")
        val result = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)
        assertTrue("未改动的 key 行应原样保留注释，result:\n$result", result.contains("// 原样注释"))
        assertTrue("应追加新增 key，result:\n$result", result.contains("新增"))
    }

    // ─────────────────────────────────────────
    // 写入增强：多行对象块重写保留「key: {」行尾注释
    // ─────────────────────────────────────────

    @Test
    fun testMultiLineBlockRewritePreservesOpeningLineComment() {
        val oldBody = """
            '用户': { // 用户组说明
              'name': '姓名',
            },
            '退出': '退出',
        """.trimIndent()
        val merged = linkedMapOf(
            "用户" to linkedMapOf("name" to "姓名", "age" to "年龄"),
            "退出" to "退出",
        )
        val result = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)
        assertTrue("多行块重写后应保留 'key: {' 行尾注释，result:\n$result", result.contains("// 用户组说明"))
        assertTrue("块内应新增 'age'，result:\n$result", result.contains("age"))
        assertTrue("应保留 'name'，result:\n$result", result.contains("name"))
    }

    // ─────────────────────────────────────────
    // spread 路由增强：multi-level const 多级路由到最深可写文件
    // ─────────────────────────────────────────

    private fun createEntry(relPath: String, content: String): VirtualFile {
        return myFixture.addFileToProject(relPath, content).virtualFile
    }

    private fun countOccurrences(text: String, needle: String): Int = text.split(needle).size - 1

    @Test
    fun testMultiLevelConstChainsToDeepestWritableFile() {
        // 入口 const common 是纯转发光束（无自身静态 key），应继续下钻到 common.ts
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
            const composed = { ...common }
            export default {
              ...composed,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("首页" to "首页", "退出" to "退出")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("应识别多级 const 转发光束并路由", writes)
        // 入口 + common.ts
        assertEquals("应产生入口 + common.ts 两次写盘", 2, writes!!.size)
        val commonWrite = writes.firstOrNull { it.first.path.endsWith("common.ts") }
        assertNotNull("应包含 common.ts 的写盘", commonWrite)
        assertTrue("common.ts 应包含新 key '退出'，result:\n${commonWrite!!.second}", commonWrite.second.contains("'退出'"))
        // 入口顶层（export default 之后）不应新增 '退出'
        val entryCombined = writes.first { it.first.path == entry.path }.second
        val exportBlock = entryCombined.substringAfter("export default")
        assertEquals("入口 export default 顶层不应新增 '退出'，result:\n$exportBlock", 0, countOccurrences(exportBlock, "'退出'"))
    }

    @Test
    fun testCircularConstSpreadDoesNotInfiniteLoop() {
        // const a = {...b} / const b = {...a} 相互引用 → 不应无限递归/栈溢出
        val entry = createEntry(
            "src/zh.ts",
            """
            const a = { ...b }
            const b = { ...a }
            export default {
              ...a,
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("退出" to "退出")
        // 若循环防护失效，此调用会栈溢出/超时；能正常返回即通过
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull("循环 const spread 应能正常处理", writes)
        // 至少应该把入口写回（新 key 落到某个可写容器或入口）
        val entryCombined = writes!!.first { it.first.path == entry.path }.second
        assertTrue("新 key '退出' 应写回文件中，result:\n$entryCombined", entryCombined.contains("'退出'"))
        assertTrue("应保留 '首页'，result:\n$entryCombined", entryCombined.contains("'首页'"))
    }

    @Test
    fun testCircularConstSpreadWithWriteToConstBlock() {
        // a/b 循环且都没有静态 key → 新 key 应落到入口 const a 块（无更深的可写目标）
        val entry = createEntry(
            "src/zh.ts",
            """
            const a = { ...b }
            const b = { ...a }
            export default { ...a }
            """.trimIndent()
        )
        val newFlat = linkedMapOf("退出" to "退出")
        val writes = TsFileEditor.regenerateTsFileWithSpreadRouting(project, entry, newFlat)
        assertNotNull(writes)
        val entryCombined = writes!!.first { it.first.path == entry.path }.second
        assertTrue("'退出' 应写入，result:\n$entryCombined", entryCombined.contains("'退出'"))
        // const a 块内应包含 '退出'
        val constABlock = entryCombined.substringAfter("const a").substringBefore("const b")
        assertTrue("const a 块应包含 '退出'，result:\n$constABlock", constABlock.contains("'退出'"))
    }

    /** 便捷构造：在已有 map 基础上追加若干 kv 生成 LinkedHashMap。 */
    private fun linkHashMapOf(base: Map<String, Any?>, vararg pairs: Pair<String, Any?>): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m.putAll(base)
        for ((k, v) in pairs) m[k] = v
        return m
    }
}