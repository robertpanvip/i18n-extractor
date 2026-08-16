package com.pan.extractor

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * AllI18nExtractorAction「全项目 transform 管线」可测部分：
 *  1) parseTsConfigInclude —— tsconfig include 解析（纯函数）
 *  2) resolveScanFiles   —— 文件发现：tsconfig include 命中 / 无 tsconfig 全量回退
 *  3) update()             —— 菜单可用性：翻译资源禁用 / 支持后缀启用 / 其他禁用
 *
 * 可测性评估：管线中 Dialog（模态）、ProgressManager/Task、CopyPasteManager、
 * WriteCommandAction + EDT 的编排依赖 IntelliJ UI 框架，单测无法稳定驱动，
 * 属于「需重构提取纯函数后才可测」的部分；本文件只覆盖上面三个纯读入口。
 */
class AllI18nExtractorActionTest : BasePlatformTestCase() {

    private val action = AllI18nExtractorAction()

    // ─────────────────────────────────────────
    // 1. parseTsConfigInclude
    // ─────────────────────────────────────────
    fun testParseTsConfigIncludeReturnsStringArray() {
        val vf = myFixture.addFileToProject(
            "tsconfig.json",
            """{ "compilerOptions": {}, "include": ["src/**/*.ts", "src/**/*.tsx"] }"""
        ).virtualFile
        assertEquals(listOf("src/**/*.ts", "src/**/*.tsx"), action.parseTsConfigInclude(vf))
    }

    fun testParseTsConfigIncludeMissingFieldReturnsEmpty() {
        val vf = myFixture.addFileToProject("tsconfig.json", """{ "compilerOptions": {} }""").virtualFile
        assertTrue("无 include 字段应返回空列表", action.parseTsConfigInclude(vf).isEmpty())
    }

    fun testParseTsConfigIncludeNonArrayReturnsEmpty() {
        val vf = myFixture.addFileToProject(
            "tsconfig.json",
            """{ "include": "src/**/*.ts" }"""
        ).virtualFile
        assertTrue("include 非数组应返回空列表", action.parseTsConfigInclude(vf).isEmpty())
    }

    fun testParseTsConfigIncludeMalformedJsonReturnsEmpty() {
        val vf = myFixture.addFileToProject("tsconfig.json", "not a json{{{").virtualFile
        assertTrue("非法 JSON 应返回空列表", action.parseTsConfigInclude(vf).isEmpty())
    }

    // ─────────────────────────────────────────
    // 2. resolveScanFiles
    // ─────────────────────────────────────────
    /**
     * findTsConfigFile 走 getBaseDirectories().first()（fixture VFS 根），
     * findFilesByIncludePatterns 走真实文件系统 project.basePath 的 walk；
     * 两者在 fixture 里指向不同目录，因此 tsconfig 放 VFS 根、待匹配文件物理写入 basePath。
     */
    private fun writeReal(relPath: String, content: String) {
        val f = java.io.File(project.basePath!!, relPath)
        f.parentFile.mkdirs()
        f.writeText(content)
    }

    fun testGetIncludesFileMatchesTsConfigPattern() {
        // tsconfig 放 fixture VFS 根：findTsConfigFile 能发现
        myFixture.addFileToProject(
            "tsconfig.json",
            """{ "include": ["src/**/*.ts"] }"""
        )
        // 真实文件放 project.basePath：findFilesByIncludePatterns 才能 walk 命中
        writeReal("src/app.ts", "export const a = 1")
        writeReal("src/styles.css", "body {}")
        writeReal("src/data.json", "{}")
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refresh(false)

        val found = action.resolveScanFiles(project)
        assertTrue("应命中 src/app.ts，实际：$found", found.any { it.path.endsWith("src/app.ts") })
        assertTrue(
            "不应包含 css/json，实际：$found",
            found.none { it.path.endsWith(".css") || it.path.endsWith(".json") }
        )
    }

    fun testGetIncludesFileFallsBackToAllRelevantFilesWithoutTsConfig() {
        // 无 tsconfig.json → findTsConfigFile 返回 null → getAllRelevantFiles（FilenameIndex 全量）
        myFixture.addFileToProject("src/app.ts", "export const a = 1")
        myFixture.addFileToProject("src/comp.vue", "<template><div/></template>")
        myFixture.addFileToProject("src/styles.css", "body {}") // css 不属于 ts/tsx/vue，不应返回

        val found = action.resolveScanFiles(project)
        assertTrue("无 tsconfig 应回退到全量相关文件，实际：$found", found.isNotEmpty())
        assertTrue("应包含 src/app.ts，实际：$found", found.any { it.path.endsWith("src/app.ts") })
        assertTrue("应包含 src/comp.vue，实际：$found", found.any { it.path.endsWith("src/comp.vue") })
        assertTrue("不应包含 css，实际：$found", found.none { it.path.endsWith(".css") })
    }

    // ─────────────────────────────────────────
    // 3. update() 菜单可用性
    // ─────────────────────────────────────────
    private fun createEvent(psi: PsiFile?): AnActionEvent {
        val dc = object : DataContext {
            override fun getData(dataId: String): Any? = when (dataId) {
                CommonDataKeys.PSI_FILE.name -> psi
                else -> null
            }
        }
        return AnActionEvent.createFromDataContext("test", Presentation(), dc)
    }

    fun testUpdateEnabledOnSupportedFile() {
        val psi = myFixture.addFileToProject("src/App.tsx", "export default () => <div/>")
        val event = createEvent(psi)
        action.update(event)
        assertTrue("支持文件（.tsx）应启用菜单", event.presentation.isEnabledAndVisible)
    }

    fun testUpdateDisabledOnTranslationResource() {
        val psi = myFixture.addFileToProject("src/locales/zh-CN.ts", "export default {}")
        val event = createEvent(psi)
        action.update(event)
        assertFalse("翻译资源文件应禁用菜单", event.presentation.isEnabledAndVisible)
    }

    fun testUpdateDisabledOnUnsupportedFile() {
        val psi = myFixture.addFileToProject("src/style.css", "body {}")
        val event = createEvent(psi)
        action.update(event)
        assertFalse("不支持后缀（.css）应禁用菜单", event.presentation.isEnabledAndVisible)
    }

    fun testUpdateDisabledWhenNoFile() {
        val event = createEvent(null)
        action.update(event)
        assertFalse("无上下文文件应禁用菜单", event.presentation.isEnabledAndVisible)
    }
}
