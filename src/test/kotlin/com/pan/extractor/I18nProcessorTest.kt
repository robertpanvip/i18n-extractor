package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * JS/TS 通用 i18n 提取测试
 *
 * 覆盖：普通字符串、模板字面量、JSX、已有 $t() 跳过等通用场景。
 * Vue 专属测试见 VueI18nProcessorTest。
 */
class I18nProcessorTest : BasePlatformTestCase() {

    /**
     * 测试 JS 字符串
     */
    fun testJavaScriptStringExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const message = "你好"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("你好", processor.extractedStrings.values.first())
    }

    /**
     * 测试 JSX
     */
    fun testReactJsxExtract() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App(){

                return (
                    <div>
                        你好
                    </div>
                )

            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
    }

    /**
     * 测试已经存在 t 函数跳过
     */
    fun testExistingTShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
           const msg = ${'$'}t("你好")
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        val extractedStr = processor.extractedStrings.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val existingStr = processor.existingStrings.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (processor.extractedStrings.isNotEmpty()) {
            throw RuntimeException("extractedStrings should be empty but got: $extractedStr; existingStrings: $existingStr; effects: ${processor.effects.size}")
        }
    }

    // ============================================================
    // 模板字面量相关
    // ============================================================

    /**
     * 测试模板字面量中纯字符串插值应内联合并
     * 如 `插件管理${'这是我的测试'}` -> $t('插件管理这是我的测试')
     */
    fun testTemplateLiteralPureStringInterpolation() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `插件管理${'$'}{'这是我的测试'}`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("插件管理这是我的测试", processor.extractedStrings.values.first())
    }

    /**
     * 测试模板字面量中双引号纯字符串插值
     */
    fun testTemplateLiteralDoubleQuotePureStringInterpolation() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `前缀${'$'}{"中文后缀"}`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("前缀中文后缀", processor.extractedStrings.values.first())
    }

    /**
     * 测试模板字面量中变量插值不应被内联
     * 如 `你好${name}` -> $t('你好{0}', { "0": name })
     */
    fun testTemplateLiteralVariableInterpolation() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `你好${'$'}{name}`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("你好{0}", processor.extractedStrings.values.first())
    }

    /**
     * 测试模板字面量中混合插值（纯字符串 + 变量）
     */
    fun testTemplateLiteralMixedInterpolation() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `前缀${'$'}{'中间'}${'$'}{var}后缀`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("前缀中间{0}后缀", processor.extractedStrings.values.first())
    }

    /**
     * 测试中文判断
     */
    fun testHasChinese() {
        val file = myFixture.configureByText("test.ts", "")
        val processor = I18nProcessor(project, file)

        assertTrue(processor.hasChinese("你好"))
        assertFalse(processor.hasChinese("hello"))
    }

    /**
     * 测试生成表达式
     */
    fun testBuildTExpression() {
        val file = myFixture.configureByText("test.ts", "")
        val processor = I18nProcessor(project, file)

        assertEquals(
            "\$t('你好')",
            processor.buildTFunctionExpr("你好", "{}")
        )
    }
}
