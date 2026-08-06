package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * JS/TS 通用 i18n 提取测试
 *
 * 覆盖：普通字符串、模板字面量、字符串拼接、JSX、已有 $t() 跳过、
 * 工具函数（hasChinese / extractPureStringContent / buildTFunctionExpr）等通用场景。
 * Vue 专属测试见 VueI18nProcessorTest。
 */
class I18nProcessorTest : BasePlatformTestCase() {

    // ============================================================
    // 1. 普通字符串字面量
    // ============================================================

    /**
     * 测试双引号 JS 字符串
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
     * 测试单引号 JS 字符串
     */
    fun testJavaScriptSingleQuoteStringExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const message = '你好世界'
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("你好世界", processor.extractedStrings.values.first())
    }

    /**
     * 测试纯英文/数字字符串不应提取
     */
    fun testEnglishStringShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const message = "hello world"
            const num = "12345"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for English strings, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试空字符串不应提取
     */
    fun testEmptyStringShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const a = ""
            const b = ''
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for empty strings, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试中英文混合字符串应提取
     */
    fun testMixedChineseEnglishStringShouldExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = "Hello 你好 World"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("Hello 你好 World"))
    }

    /**
     * 测试多个字符串应全部提取
     */
    fun testMultipleStringsExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const a = "苹果"
            const b = '香蕉'
            const c = "橙子"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(3, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("苹果"))
        assertTrue(processor.extractedStrings.containsValue("香蕉"))
        assertTrue(processor.extractedStrings.containsValue("橙子"))
    }

    /**
     * 测试重复字符串只提取一次（去重）
     */
    fun testDuplicateStringsDeduplicate() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const a = "你好"
            const b = "你好"
            const c = '你好'
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("你好"))
    }

    // ============================================================
    // 2. 已有 $t() / t() 调用跳过
    // ============================================================

    /**
     * 测试已存在 $t() 双引号调用跳过
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

    /**
     * 测试已存在 $t() 单引号调用跳过
     */
    fun testExistingTSingleQuoteShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = ${'$'}t('你好')
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for existing ${'$'}t(), but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
        assertTrue(
            "existingStrings should contain '你好', but got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("你好")
        )
    }

    /**
     * 测试已存在 $t() 反引号调用跳过
     */
    fun testExistingTBacktickShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = ${'$'}t(`你好`)
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for existing ${'$'}t() with backtick, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试函数调用中普通字符串参数应提取（非 $t 函数）
     */
    fun testFunctionCallStringArgShouldExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            console.log("提示信息")
            alert('警告')
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(processor.extractedStrings.containsValue("提示信息"))
        assertTrue(processor.extractedStrings.containsValue("警告"))
    }

    // ============================================================
    // 3. 模板字面量（反引号）
    // ============================================================

    /**
     * 测试纯模板字面量（无插值）应提取
     */
    fun testPlainTemplateLiteralExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `你好世界`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("你好世界", processor.extractedStrings.values.first())
    }

    /**
     * 测试模板字面量中纯字符串插值应内联合并
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
     * 测试模板字面量多个变量插值
     */
    fun testTemplateLiteralMultipleVariables() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `${'$'}{name}，你好，今天是${'$'}{day}`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("{0}，你好，今天是{1}", processor.extractedStrings.values.first())
    }

    /**
     * 测试模板字面量中嵌套模板字符串插值（变量是模板字符串）
     */
    fun testTemplateLiteralNestedTemplateString() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `外层${'$'}{`内层${'$'}{inner}`}文本`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 内层模板字符串的「内层」中文应被提取
        assertTrue(
            "Should contain '内层' in extractedStrings, got: ${processor.extractedStrings}",
            processor.extractedStrings.values.any { it.contains("内层") }
        )
    }

    /**
     * 测试模板字面量全是变量无中文应跳过
     */
    fun testTemplateLiteralAllVariablesShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = `${'$'}{a}${'$'}{b}${'$'}{c}`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for template literal with no Chinese, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    // ============================================================
    // 4. 字符串拼接 (+)
    // ============================================================

    /**
     * 测试字符串 + 字符串拼接
     */
    fun testStringConcatTwoStrings() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = "你好" + "世界"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 两个中文拼接后应合并为一个提取
        assertTrue(
            "extractedStrings should contain '你好世界', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好世界")
        )
    }

    /**
     * 测试字符串 + 变量拼接
     */
    fun testStringConcatVariable() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = "你好" + name
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '你好{0}', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{0}")
        )
    }

    /**
     * 测试变量 + 字符串拼接
     */
    fun testStringConcatVariablePrefix() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = name + "你好"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '{0}你好', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("{0}你好")
        )
    }

    /**
     * 测试多段字符串拼接: "a" + "b" + "c"
     */
    fun testStringConcatMultipleSegments() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = "前" + "中" + "后"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '前中后', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("前中后")
        )
    }

    /**
     * 测试混合拼接: "中文" + var + "中文"
     */
    fun testStringConcatMixed() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = "共" + count + "条记录"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '共{0}条记录', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("共{0}条记录")
        )
    }

    // ============================================================
    // 5. 对象 / 数组中的字符串
    // ============================================================

    /**
     * 测试对象属性中的字符串应提取
     */
    fun testObjectPropertyStringExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const obj = {
                name: "张三",
                label: '标签'
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(processor.extractedStrings.containsValue("张三"))
        assertTrue(processor.extractedStrings.containsValue("标签"))
    }

    /**
     * 测试数组中的字符串应提取
     */
    fun testArrayStringExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const arr = ["苹果", "香蕉", "橙子"]
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(3, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("苹果"))
        assertTrue(processor.extractedStrings.containsValue("香蕉"))
        assertTrue(processor.extractedStrings.containsValue("橙子"))
    }

    // ============================================================
    // 7. 注释跳过
    // ============================================================

    /**
     * 测试单行注释中的中文应跳过
     */
    fun testLineCommentShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            // 这是单行注释的中文
            const real = "真实文本"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("真实文本"))
        assertFalse(processor.extractedStrings.containsValue("这是单行注释的中文"))
    }

    /**
     * 测试多行注释中的中文应跳过
     */
    fun testBlockCommentShouldSkip() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            /*
             * 这是多行注释
             * 包含中文内容
             */
            const real = "真实文本"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("真实文本"))
        assertFalse(processor.extractedStrings.containsValue("包含中文内容"))
    }

    // ============================================================
    // 8. 工具函数单元测试
    // ============================================================

    /**
     * 测试 hasChinese 中文判断
     */
    fun testHasChinese() {
        val file = myFixture.configureByText("test.ts", "")
        val processor = I18nProcessor(project, file)

        assertTrue(processor.hasChinese("你好"))
        assertFalse(processor.hasChinese("hello"))
        assertTrue(processor.hasChinese("hello你好"))
        assertFalse(processor.hasChinese(""))
        assertFalse(processor.hasChinese("123"))
        assertTrue(processor.hasChinese("一"))
    }

    /**
     * 测试 extractPureStringContent 纯字符串提取
     */
    fun testExtractPureStringContent() {
        val file = myFixture.configureByText("test.ts", "")
        val processor = I18nProcessor(project, file)

        // 双引号
        assertEquals("你好", processor.extractPureStringContent("\"你好\""))
        // 单引号
        assertEquals("hello", processor.extractPureStringContent("'hello'"))
        // 反引号纯文本（无插值）
        assertEquals("纯文本", processor.extractPureStringContent("`纯文本`"))
        // 反引号带插值 -> 不是纯字符串
        assertNull(processor.extractPureStringContent("`有\${var}插值`"))
        // 空字符串
        assertEquals("", processor.extractPureStringContent("\"\""))
        // 单字符不是字符串
        assertNull(processor.extractPureStringContent("'"))
        // 首尾有空格
        assertEquals("中文", processor.extractPureStringContent("  \"中文\"  "))
    }

    /**
     * 测试 buildTFunctionExpr 生成 $t 表达式
     */
    fun testBuildTExpression() {
        val file = myFixture.configureByText("test.ts", "")
        val processor = I18nProcessor(project, file)

        // 基本用法
        assertEquals(
            "\$t('你好')",
            processor.buildTFunctionExpr("你好", "{}")
        )

        // 带参数
        assertEquals(
            "\$t('你好{0}', { \"0\": name })",
            processor.buildTFunctionExpr("你好{0}", "{ \"0\": name }")
        )

        // 含单引号的文本应转义
        val result = processor.buildTFunctionExpr("它's", "{}")
        assertTrue("Should escape single quote, got: $result", result.contains("\\'"))
    }

    /**
     * 测试 isTransformedCalled 判断已有 $t 调用
     */
    fun testIsTransformedCalled() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const a = ${'$'}t("已提取")
            const b = "未提取"
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 已提取的不应出现在 extractedStrings
        assertFalse(processor.extractedStrings.containsValue("已提取"))
        // 未提取的应出现在 extractedStrings
        assertTrue(processor.extractedStrings.containsValue("未提取"))
        // 已有的应在 existingStrings 中
        assertTrue(processor.existingStrings.containsValue("已提取"))
    }
}
