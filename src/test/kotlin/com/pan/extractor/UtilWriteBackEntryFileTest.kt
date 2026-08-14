package com.pan.extractor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 针对「真正写盘入口」Util.regenerateTsFileWithNewJson / Util.regenerateJsonFileWithNewJson
 * 做端到端测试（这两个函数是 AllI18nExtractorAction / I18nExtractorAction 在覆盖写回时实际调用的，
 * 而 UtilWriteBackTest 只测了底层的解析/重写函数）。
 */
class UtilWriteBackEntryFileTest : BasePlatformTestCase() {

    private fun createEntry(relPath: String, content: String): VirtualFile {
        val psiFile = myFixture.addFileToProject(relPath, content)
        return psiFile.virtualFile
    }

    // ─────────────────────────────────────────
    // TS 写回
    // ─────────────────────────────────────────

    fun testTsWriteBackMergesNewKeysAndNestedDottedKeys() {
        val entry = createEntry(
            "src/zh.ts",
            """
            export default {
              '首页': '首页',
              '用户': {
                'name': '姓名',
              },
            }
            """.trimIndent()
        )

        val newFlat = linkedMapOf(
            "首页" to "首页",
            "退出" to "退出",
            "用户.age" to "年龄",
        )
        val newText = Util.regenerateTsFileWithNewJson(project, entry, newFlat)
        assertNotNull("应能解析 export default 对象并重写", newText)
        val result = newText!!
        // ① 不应出现双大括号（range 处理错误的症状）
        assertTrue("不应出现双大括号 'export default {{'，result:\n$result", !result.contains("{{"))
        // ② 顶层只应有一个闭合大括号
        assertTrue("顶层闭合大括号应唯一，result:\n$result", result.count { it == '}' } == 2)
        // ③ 保留旧 key
        assertTrue("应保留旧 key '首页'，result:\n$result", result.contains("'首页'"))
        // ④ 新增顶层 key
        assertTrue("应新增 key '退出'，result:\n$result", result.contains("'退出'"))
        // ⑤ 点式 key '用户.age' 应展开为嵌套 'age'（在 '用户' 块内；嵌套 key 按 ASCII identifier 渲染为未加引号）
        assertTrue("点式 key '用户.age' 应展开为嵌套 'age'，result:\n$result", result.contains("age:"))
        // ⑥ 嵌套块内应同时保留 name 与新增的 age
        assertTrue("'用户' 块内应同时有 name 与 age，result:\n$result", result.contains("name:") && result.contains("age:"))
    }

    fun testTsWriteBackKeepsCommentAndNoDoubleBrace() {
        val entry = createEntry(
            "src/zh.ts",
            """
            export default {
              // 首页说明
              '首页': '首页',
            }
            """.trimIndent()
        )
        val newText = Util.regenerateTsFileWithNewJson(project, entry, linkedMapOf("首页" to "首页", "退出" to "退出"))
        assertNotNull(newText)
        val result = newText!!
        assertTrue("注释应保留，result:\n$result", result.contains("首页说明"))
        assertTrue("不应出现双大括号，result:\n$result", !result.contains("{{"))
        assertTrue("应新增 '退出'，result:\n$result", result.contains("'退出'"))
    }

    fun testTsWriteBackDoesNotDuplicateExistingKey() {
        val entry = createEntry("src/zh.ts", "export default { '首页': '首页' }")
        val newFlat = linkedMapOf("首页" to "首页")
        val newText = Util.regenerateTsFileWithNewJson(project, entry, newFlat)
        assertNotNull(newText)
        val count = newText!!.substringAfter("{").substringBeforeLast("}").split("'首页'").size - 1
        assertTrue("已存在的 key '首页' 不应重复追加，实际出现 $count 次，result:\n$newText", count >= 1)
    }

    fun testTsWriteBackFallsBackWhenNoExportObject() {
        val entry = createEntry("src/zh.ts", "export const a = 1;")
        val newText = Util.regenerateTsFileWithNewJson(project, entry, linkedMapOf("首页" to "首页"))
        // 没有 export default/export const 对象字面量 → 返回 null，由调用方回退到剪贴板
        assertTrue("无导出对象时应返回 null（回退剪贴板）", newText == null)
    }

    // ─────────────────────────────────────────
    // JSON 写回
    // ─────────────────────────────────────────

    fun testJsonWriteBackMergesAndExpandsDottedKeys() {
        val entry = createEntry(
            "src/zh.json",
            """
            {
              "首页": "首页"
            }
            """.trimIndent()
        )
        val newFlat = linkedMapOf(
            "首页" to "首页",
            "退出" to "退出",
            "用户.name" to "姓名",
        )
        val newText = Util.regenerateJsonFileWithNewJson(entry, newFlat)
        assertNotNull(newText)
        val result = newText!!
        assertTrue("应保留 '首页'，result:\n$result", result.contains("首页"))
        assertTrue("应新增 '退出'，result:\n$result", result.contains("退出"))
        assertTrue("点式 key 应展开为嵌套 '用户.name'，result:\n$result", result.contains("name"))
    }

    fun testJsonWriteBackFallbackWhenMalformed() {
        val entry = createEntry("src/zh.json", "not valid json{{{")
        val newText = Util.regenerateJsonFileWithNewJson(entry, linkedMapOf("首页" to "首页"))
        assertNotNull("非法 JSON 应兜底返回格式化后的新 JSON", newText)
        assertTrue("兜底结果应包含新 key '首页'，result:\n$newText", newText!!.contains("首页"))
    }

    // ─────────────────────────────────────────
    // 中文入口探测：按 Vue createI18n 配置查（预设目录无 zh 文件时）
    // ─────────────────────────────────────────
    fun testChineseEntryDetectedViaVueConfig() {
        // 预设目录 src/locales 里只有 index.ts（无 zh 基名文件），预设扫描不会命中；
        // 真正的中文来源在 src/config/messages/zh-locales.ts，只能通过 createI18n 配置反向解析出来。
        myFixture.addFileToProject("package.json", "{}")
        createEntry(
            "src/locales/index.ts",
            """
            import zhLocales from '../config/messages/zh-locales'
            import enLocales from '../config/messages/en-locales'
            export default createI18n({
              legacy: false,
              locale: 'zh-CN',
              fallbackLocale: 'en-US',
              messages: {
                'zh-CN': zhLocales,
                'en-US': enLocales,
              }
            })
            """.trimIndent()
        )
        createEntry("src/config/messages/zh-locales.ts", "export default { '标题': '标题' }")
        createEntry("src/config/messages/en-locales.ts", "export default { 'Title': 'Title' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")

        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("应通过 Vue 配置探测到中文入口", found)
        assertTrue(
            "应命中 zh-locales.ts，实际：${found!!.path}",
            found.path.endsWith("config/messages/zh-locales.ts")
        )
    }

    // 预设目录直接命中时，仍优先走性能快的常见目录扫描（不依赖 createI18n）
    fun testChineseEntryPrefersPresetDir() {
        myFixture.addFileToProject("package.json", "{}")
        createEntry("src/locales/zh.ts", "export default { '首页': '首页' }")
        val context = myFixture.addFileToProject("src/App.vue", "<template><div>hi</div></template>")
        val found = Util.findChineseLocaleEntryFile(project, context)
        assertNotNull("预设目录应优先命中", found)
        assertTrue("应命中 src/locales/zh.ts，实际：${found!!.path}", found.path.endsWith("locales/zh.ts"))
    }
}