package com.pan.extractor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 5.5 — Import/Rewrite 组合。
 *
 * 验证在执行提取（[I18nProcessor.collect] + [I18nProcessor.runWithUndo]）后，
 * 不同导出的 i18n 初始化文件形态（export default i18n / export const i18n /
 * src/locales/index.ts 形态）不会导致源文件出现重复的 i18n import，
 * 也不会破坏 i18n 初始化文件自身的导出。
 *
 * 触发路径：源文件在 template 中使用 `i18n.global.t(...)`（Vue 会把 tFunctionName
 * 切到 "i18n.global.t"），且 script 内存在硬编码中文（hasExtractedStrings=true），
 * 于是 [I18nImportInjector.injectVue] 走 ensureI18nInstanceImported 分支，通过
 * [I18nInstanceLocator.findVueI18nInstanceFile] 定位 createI18n 初始化文件并按其
 * 导出方式构造 import。该场景与现有通过用例 testVueI18nGlobalTInjectImportWhenMissing
 * 同构，保证稳定。
 */
class I18nImportRewriteComboTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "import-rewrite-combo",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0"
              }
            }
            """.trimIndent()
        )
    }

    /**
     * 添加 Vue 源文件并执行 collect + runWithUndo。
     * 源文件用 `i18n.global.t`（触发全局 i18n 实例导入路径）且含硬编码中文。
     */
    private fun configureSource(fileName: String): PsiFile {
        val file = myFixture.addFileToProject(
            fileName,
            """
            <template>
                <div>{{ i18n.global.t("已有文本") }}</div>
            </template>
            <script setup lang="ts">
            const newMsg = "新提取文本"
            </script>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()
        return file
    }

    // ── 5.5.a: init 文件 `export default i18n` ────────────────────

    fun testDefaultExportI18nNoDuplicateAndNotBroken() {
        val init = myFixture.addFileToProject(
            "src/locales/i18n.ts",
            """
            import { createI18n } from 'vue-i18n';
            const i18n = createI18n({ legacy: false, locale: 'zh' });
            export default i18n;
            """.trimIndent()
        )
        val initBefore = init.text

        val source = configureSource("src/Test.vue")
        val result = source.text

        // 默认导出 → `import i18n from '@/locales/i18n'`
        assertTrue("应注入默认导入 import i18n from '@/locales/i18n', got:\n$result", result.contains("import i18n from '@/locales/i18n'"))
        // 不重复注入
        assertEquals("不应重复注入 i18n import, got:\n$result", 1, Regex("import i18n from").findAll(result).count())
        assertFalse("tFunctionName=i18n.global.t 时不应注入 useI18n, got:\n$result", result.contains("useI18n"))
        // i18n 初始化文件内容保持不变（export default i18n 未被破坏）
        assertEquals("i18n 初始化文件内容应保持不变", initBefore, init.text)
    }

    // ── 5.5.b: init 文件 `export const i18n` ──────────────────────

    fun testNamedExportI18nNoDuplicate() {
        val init = myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import { createI18n } from 'vue-i18n';
            export const i18n = createI18n({ legacy: false, locale: 'zh' });
            """.trimIndent()
        )
        val initBefore = init.text

        val source = configureSource("src/Test.vue")
        val result = source.text

        // 命名导出 + /index 尾缀 → `import { i18n } from '@/locales'`
        assertTrue("应注入命名导入 import { i18n } from '@/locales', got:\n$result", result.contains("import { i18n } from '@/locales'"))
        assertEquals("不应重复注入 i18n import, got:\n$result", 1, Regex("import \\{ i18n \\} from").findAll(result).count())
        assertEquals("i18n 初始化文件内容应保持不变", initBefore, init.text)
    }

    // ── 5.5.d: init 文件 `export { i18n }`（花括号 re-export 形态）────

    fun testExportBracesI18nNoDuplicate() {
        val init = myFixture.addFileToProject(
            "src/locales/i18n.ts",
            """
            import { createI18n } from 'vue-i18n';
            const i18n = createI18n({ legacy: false, locale: 'zh' });
            export { i18n };
            """.trimIndent()
        )
        val initBefore = init.text

        val source = configureSource("src/Test.vue")
        val result = source.text

        // `export { i18n }` → 命名导入 `import { i18n } from '@/locales/i18n'`
        assertTrue("应注入命名导入 import { i18n } from '@/locales/i18n', got:\n$result", result.contains("import { i18n } from '@/locales/i18n'"))
        assertEquals("不应重复注入 i18n import, got:\n$result", 1, Regex("import \\{ i18n \\} from").findAll(result).count())
        assertFalse("不应注入 useI18n, got:\n$result", result.contains("useI18n"))
        assertEquals("i18n 初始化文件 export { i18n } 不应被破坏", initBefore, init.text)
    }

    // ── 5.5.e: 多个源文件同时修改，均只注入一次 ──────────────────────

    fun testMultipleSourceFilesEachInjectedOnce() {
        val init = myFixture.addFileToProject(
            "src/locales/i18n.ts",
            """
            import { createI18n } from 'vue-i18n';
            const i18n = createI18n({ legacy: false, locale: 'zh' });
            export default i18n;
            """.trimIndent()
        )
        val initBefore = init.text

        val a = configureSource("src/A.vue")
        val b = configureSource("src/B.vue")

        for ((name, result) in mapOf("A.vue" to a.text, "B.vue" to b.text)) {
            assertTrue("$name 应注入默认导入 import i18n from '@/locales/i18n', got:\n$result", result.contains("import i18n from '@/locales/i18n'"))
            assertEquals("$name 不应重复注入 i18n import, got:\n$result", 1, Regex("import i18n from").findAll(result).count())
            assertFalse("$name 不应注入 useI18n, got:\n$result", result.contains("useI18n"))
        }
        assertEquals("多文件各自提取后 i18n 初始化文件应保持不变", initBefore, init.text)
    }
}