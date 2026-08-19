package com.pan.extractor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Svelte i18n 策略端到端测试（面向 `svelte-i18n`）。
 *
 * 测试环境未捆绑 Svelte 语言插件，`.svelte` SFC 模板（XmlText/YXmlAttributeValue）无法解析，
 * 因此此处聚焦 **依赖识别 + JS 字符串提取 + import 注入** 这些可由 `.ts/.js` 文件触发的路径：
 *   1. 含 `svelte` 依赖（无 vue/solid/react）的项目 → 命中 [SvelteI18nStrategy]。
 *   2. JS 字符串提取 → 包装为 `$t('中文')`，并注入 `import { t } from 'svelte-i18n'`。
 *   3. 占位符为 messageformat 位置参数 `{0}`，参数 key `"0"`（需引号）。
 *   4. 已有 import / 已有 `$t` 调用去重。
 *
 * 模板侧（`{$t('key')}` 单花括号包装）依赖 Svelte 语言插件，配合 [SiteForm.SVELTE_BINDING]
 * 由 analyzer 的 SVELTE_BINDING 分支覆盖，此处不重复引入 Svelte 插件以避免不稳定。
 */
class SvelteI18nProcessorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Svelte 项目：仅依赖 svelte，不含 vue / solid-js / react（保证 isSvelte 命中且互斥）
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "svelte-test-project",
              "dependencies": {
                "svelte": "^4.0.0",
                "svelte-i18n": "^4.0.0"
              }
            }
            """.trimIndent()
        )
    }

    private fun configureFile(fileName: String, text: String): PsiFile {
        return if (fileName.contains('/') || fileName.contains('\\')) {
            val psiFile = myFixture.addFileToProject(fileName, text)
            myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
            psiFile
        } else {
            myFixture.configureByText(fileName, text)
        }
    }

    private fun compact(file: PsiFile): String = file.text.replace("\\s+".toRegex(), "")

    // ============================================================
    // 1. 框架识别：svelte 依赖 → SvelteI18nStrategy
    // ============================================================

    /**
     * 无 package.json 之外的上下文，仅凭 `svelte` 依赖判定项目为 Svelte。
     */
    fun testSvelteProjectDetected() {
        val file = myFixture.configureByText("App.ts", "export const x = 1;")
        assertTrue(
            "isSvelte should be true for a svelte-dependent project, got false",
            Util.isSvelte(file)
        )
        val strategy = I18nFrameworkRegistry.detect(file)
        assertEquals("detect should return SvelteI18nStrategy", "svelte-i18n", strategy.id)
        assertEquals("\$t", strategy.tFunctionName)
    }

    /**
     * Svelte 策略的占位符 / 参数 key 形态（messageformat 位置参数，需引号）。
     */
    fun testSvelteStrategyPlaceholderShape() {
        val file = myFixture.configureByText("App.ts", "export const x = 1;")
        val strategy = I18nFrameworkRegistry.detect(file)
        assertEquals("{0}", strategy.placeholderFor(0))
        assertEquals("{1}", strategy.placeholderFor(1))
        assertEquals("0", strategy.paramKey(0))
        assertTrue("paramKeyNeedsQuote should be true", strategy.paramKeyNeedsQuote)
        assertEquals("svelte-i18n", strategy.bootstrapDeps.single())
    }

    // ============================================================
    // 2. JS 字符串提取 + import 注入
    // ============================================================

    /**
     * Svelte 项目中的 .ts 字符串字面量提取为 `$t('中文')`，并注入 svelte-i18n import。
     */
    fun testSvelteJsStringExtractAndInjectImport() {
        val file = configureFile(
            "src/utils/msg.ts",
            """
            export function greet() {
                return "您好";
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        assertTrue(
            "Extracted string should be wrapped as \$t('您好'), got:\n${file.text}",
            c.contains("\$t('您好')")
        )
        assertTrue(
            "Should inject import { t } from 'svelte-i18n', got:\n${file.text}",
            c.contains("import{t}from'svelte-i18n'")
        )
    }

    /**
     * 带插值参数的字符串：占位符 {0} + 参数对象 `{ "0": ... }`（引号 key）。
     */
    fun testSvelteJsStringWithParams() {
        val file = configureFile(
            "src/utils/count.ts",
            """
            export function count(n: number) {
                return "共 " + n + " 个";
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        val text = file.text
        assertTrue("Should extract '共 {0} 个' as key, got: $text", text.contains("共 {0} 个"))
        assertTrue(
            "Call should be \$t('共 {0} 个', ...), got:\n$text",
            text.contains("\$t('共 {0} 个'")
        )
        // 参数 key 走 paramKeyNeedsQuote=true → `"0"`（引号 key，compact 后空格被移除）
        assertTrue(
            "Param key should be quoted \"0\", got:\n$c",
            c.contains("\$t('共{0}个',{\"0\":n})")
        )
    }

    // ============================================================
    // 3. 已有调用 / import 去重
    // ============================================================

    /**
     * 已有 `import { t } from 'svelte-i18n'` 时不再重复注入。
     */
    fun testSvelteExistingImportNotDuplicated() {
        val file = configureFile(
            "src/components/Badge.ts",
            """
            import { t } from 'svelte-i18n';

            export const label = () => "徽章";
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val count = file.text.split("svelte-i18n").size - 1
        assertEquals(
            "svelte-i18n import should appear exactly once, got $count times:\n${file.text}",
            1, count
        )
    }

    /**
     * 已有 `$t('...')` 调用的字符串不再被重复提取进 extractedStrings。
     */
    fun testSvelteExistingTCallNotExtracted() {
        val file = configureFile(
            "src/components/Tag.ts",
            """
            import { t } from 'svelte-i18n';
            export const done = () => t('completed');
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertFalse(
            "Existing t('completed') must not enter extractedStrings, got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("completed")
        )
    }

    // ============================================================
    // 4. 不注入其他框架的 hook / 依赖
    // ============================================================

    /**
     * Svelte 策略不应注入 Vue 的 useI18n / react-i18next 的 useTranslation。
     */
    fun testSvelteNeverInjectsForeignFrameworks() {
        val file = configureFile(
            "src/components/Card.ts",
            """
            export function title() { return "卡片"; }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val text = file.text
        assertFalse("Svelte should not import vue-i18n, got:\n$text", text.contains("vue-i18n"))
        assertFalse("Svelte should not import react-i18next, got:\n$text", text.contains("react-i18next"))
        assertFalse("Svelte should not import @solid-primitives/i18n, got:\n$text", text.contains("@solid-primitives/i18n"))
        assertTrue("Should instead import svelte-i18n, got:\n$text", text.contains("svelte-i18n"))
    }
}