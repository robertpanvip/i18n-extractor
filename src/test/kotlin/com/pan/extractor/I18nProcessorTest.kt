package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class I18nProcessorTest : BasePlatformTestCase() {


    /**
     * 测试 Vue template 普通文本
     */
    fun testVueTemplateTextExtract() {

        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>
                    你好
                </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        assertEquals(
            1,
            processor.extractedStrings.size
        )

        assertEquals(
            "你好",
            processor.extractedStrings.values.first()
        )
    }


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

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        assertEquals(
            1,
            processor.extractedStrings.size
        )

        assertEquals(
            "你好",
            processor.extractedStrings.values.first()
        )
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

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        assertEquals(
            1,
            processor.extractedStrings.size
        )
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

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        val extractedStr = processor.extractedStrings.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val existingStr = processor.existingStrings.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (processor.extractedStrings.isNotEmpty()) {
            throw RuntimeException("extractedStrings should be empty but got: $extractedStr; existingStrings: $existingStr; effects: ${processor.effects.size}")
        }
    }


    /**
     * 测试 Vue 模板中已存在 $t(`反引号字符串`) 应跳过提取并正确识别为已有
     */
    fun testVueExistingBacktickTShouldSkip() {

        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <Button type="primary" :loading="loading" @click="() => handleSubmit()">
                    {{ ${'$'}t(`确定`) }}
                </Button>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        // 不应提取已有 $t() 调用的文本
        assertTrue(
            "extractedStrings should be empty but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
        // 应正确识别已有 $t() 调用
        assertTrue(
            "existingStrings should contain '确定' but got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("确定")
        )
    }


    /**
     * 测试 Vue 模板中各种引号风格的 $t() 都能跳过并识别
     */
    fun testVueAllQuoteStylesShouldSkip() {
        val styles = listOf(
            "backtick" to "`确定`",
            "double" to "\"确定\"",
            "single" to "'确定'"
        )

        for ((name, argText) in styles) {
            val file = myFixture.configureByText(
                "Test_$name.vue",
                """
                <template>
                    <div>{{ ${'$'}t($argText) }}</div>
                </template>
                """.trimIndent()
            )

            val processor = I18nProcessor(project, file)
            processor.collect()

            assertTrue(
                "[$name] extractedStrings should be empty but got: ${processor.extractedStrings}",
                processor.extractedStrings.isEmpty()
            )
            assertTrue(
                "[$name] existingStrings should contain '确定' but got: ${processor.existingStrings}",
                processor.existingStrings.containsValue("确定")
            )
        }
    }


    /**
     * 测试混合场景：已有 $t(`确定`) 和其他中文文本共存
     * 确保已有的不会被重复提取，新的文本正常提取
     */
    fun testVueMixedContent() {
        val file = myFixture.configureByText(
            "Test.vue",
            """<template>
  <Button type="primary" :loading="loading" @click="() => handleSubmit()">
        {{ ${'$'}t(`确定`) }}
      </Button>
  <div>其他文本</div>
</template>""".trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 已有的 $t(`确定`) 不应被提取
        assertFalse(
            "extractedStrings should not contain '确定'",
            processor.extractedStrings.containsValue("确定")
        )
        // 新的中文文本应被提取
        assertTrue(
            "extractedStrings should contain '其他文本' but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("其他文本")
        )
        // 已有的 $t(`确定`) 应被识别
        assertTrue(
            "existingStrings should contain '确定'",
            processor.existingStrings.containsValue("确定")
        )
    }


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

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        // 纯字符串插值应被内联，整体作为一个字符串提取
        assertEquals(
            1,
            processor.extractedStrings.size
        )
        // 合并后的完整文本
        assertEquals(
            "插件管理这是我的测试",
            processor.extractedStrings.values.first()
        )
    }


    /**
     * 测试模板字面量中双引号纯字符串插值
     * 如 `前缀${"中文后缀"}` -> $t('前缀中文后缀')
     */
    fun testTemplateLiteralDoubleQuotePureStringInterpolation() {

        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `前缀${'$'}{"中文后缀"}`
            """.trimIndent()
        )

        val processor = I18nProcessor(
            project,
            file
        )

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

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("你好{0}", processor.extractedStrings.values.first())
    }


    /**
     * 测试模板字面量中混合插值（纯字符串 + 变量）
     * 如 `前缀${'中间'}${var}后缀` -> $t('前缀中间{0}后缀', { "0": var })
     */
    fun testTemplateLiteralMixedInterpolation() {

        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `前缀${'$'}{'中间'}${'$'}{var}后缀`
            """.trimIndent()
        )

        val processor = I18nProcessor(
            project,
            file
        )

        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("前缀中间{0}后缀", processor.extractedStrings.values.first())
    }


    /**
     * 测试中文判断
     */
    fun testHasChinese() {

        val file = myFixture.configureByText(
            "test.ts",
            ""
        )

        val processor = I18nProcessor(
            project,
            file
        )

        assertTrue(
            processor.hasChinese("你好")
        )

        assertFalse(
            processor.hasChinese("hello")
        )
    }


    /**
     * 测试生成表达式
     */
    fun testBuildTExpression() {

        val file = myFixture.configureByText(
            "test.ts",
            ""
        )

        val processor = I18nProcessor(
            project,
            file
        )

        assertEquals(
            "\$t('你好')",
            processor.buildTFunctionExpr(
                "你好",
                "{}"
            )
        )
    }
}
