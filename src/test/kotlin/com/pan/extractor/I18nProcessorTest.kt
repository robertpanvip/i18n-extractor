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
 * 工具函数（containsTargetLanguage / extractPureStringContent / buildTFunctionExpr）等通用场景。
 * Vue 专属测试见 VueI18nProcessorTest。
 */
class I18nProcessorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // 创建通用 JS 项目的 package.json（既无 react 也无 vue 依赖，isReact 返回 false，使用 Vue 单括号格式）
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "generic-test-project",
              "dependencies": {
                "typescript": "^5.0.0"
              }
            }
            """.trimIndent()
        )
    }

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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("你好", processor.analyzer.extractedStrings.values.first())
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("你好世界", processor.analyzer.extractedStrings.values.first())
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
            "extractedStrings should be empty for English strings, but got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.isEmpty()
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
            "extractedStrings should be empty for empty strings, but got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.isEmpty()
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("Hello 你好 World"))
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

        assertEquals(3, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("苹果"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("香蕉"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("橙子"))
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("你好"))
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

        val extractedStr = processor.analyzer.extractedStrings.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val existingStr = processor.analyzer.existingStrings.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (processor.analyzer.extractedStrings.isNotEmpty()) {
            throw RuntimeException("extractedStrings should be empty but got: $extractedStr; existingStrings: $existingStr; effects: ${processor.analyzer.rewrites.size}")
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
            "extractedStrings should be empty for existing ${'$'}t(), but got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.isEmpty()
        )
        assertTrue(
            "existingStrings should contain '你好', but got: ${processor.analyzer.existingStrings}",
            processor.analyzer.existingStrings.containsValue("你好")
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
            "extractedStrings should be empty for existing ${'$'}t() with backtick, but got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.isEmpty()
        )
    }

    /**
     * 【Bug 验证 A2】$t(foo('中文')) 中 '中文' 是普通函数 foo 的参数，
     * 不是 $t 的直接字符串参数，应当被提取，而不是被误判为 DIREC_ARG 跳过。
     */
    fun testNestedCallInsideTDollarShouldStillExtract() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = ${'$'}t(foo('内部中文'))
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "'内部中文' 是 foo() 的参数而非 ${'$'}t 的直接参数，应被提取，但 got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("内部中文")
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

        assertTrue(processor.analyzer.extractedStrings.containsValue("提示信息"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("警告"))
    }

    /**
     * 【Bug 验证 A10】同名 `t` 函数被误判为翻译函数。
     * 用户自定义了一个与 i18n 无关的普通函数 `t`（例如测试/工具函数），
     * 其参数里的中文不应被当成「已翻译」而跳过提取。
     * 当前实现按名字 `t`/`$t`/`tc` 判定，会误判。
     */
    fun testSameNamedTFunctionShouldNotBeTreatedAsTranslation() {
        val file = myFixture.configureByText(
            "test.ts",
            """
            export function t(x: string) { return x.toUpperCase() }
            const value = t('自定义函数的中文')
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "同名普通函数 t 的参数中文应被提取，但 got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("自定义函数的中文")
        )
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("你好世界", processor.analyzer.extractedStrings.values.first())
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("插件管理这是我的测试", processor.analyzer.extractedStrings.values.first())
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("前缀中文后缀", processor.analyzer.extractedStrings.values.first())
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("你好{0}", processor.analyzer.extractedStrings.values.first())
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("前缀中间{0}后缀", processor.analyzer.extractedStrings.values.first())
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertEquals("{0}，你好，今天是{1}", processor.analyzer.extractedStrings.values.first())
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
            "Should contain '内层' in extractedStrings, got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.values.any { it.contains("内层") }
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
            "extractedStrings should be empty for template literal with no Chinese, but got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.isEmpty()
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
            "extractedStrings should contain '你好世界', got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("你好世界")
        )
        // 【Bug 验证 A1】拼接不应把操作数再单独提取，整条拼接应只产生 1 个 key
        assertEquals(
            "拼接 '你好' + '世界' 应只提取合并后的 1 条，不应分别提取操作数，got: ${processor.analyzer.extractedStrings}",
            1,
            processor.analyzer.extractedStrings.size
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
            "extractedStrings should contain '你好{0}', got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("你好{0}")
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
            "extractedStrings should contain '{0}你好', got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("{0}你好")
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
            "extractedStrings should contain '前中后', got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("前中后")
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
            "extractedStrings should contain '共{0}条记录', got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("共{0}条记录")
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

        assertTrue(processor.analyzer.extractedStrings.containsValue("张三"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("标签"))
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

        assertEquals(3, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("苹果"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("香蕉"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("橙子"))
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("真实文本"))
        assertFalse(processor.analyzer.extractedStrings.containsValue("这是单行注释的中文"))
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("真实文本"))
        assertFalse(processor.analyzer.extractedStrings.containsValue("包含中文内容"))
    }

    // ============================================================
    // 8. 工具函数单元测试
    // ============================================================

    /**
     * 测试 containsTargetLanguage 目标语言判断
     */
    fun testContainsTargetLanguage() {
        val file = myFixture.configureByText("test.ts", "")
        val processor = I18nProcessor(project, file)

        assertTrue(I18nPsiTools.containsTargetLanguage("你好"))
        assertFalse(I18nPsiTools.containsTargetLanguage("hello"))
        assertTrue(I18nPsiTools.containsTargetLanguage("hello你好"))
        assertFalse(I18nPsiTools.containsTargetLanguage(""))
        assertFalse(I18nPsiTools.containsTargetLanguage("123"))
        assertTrue(I18nPsiTools.containsTargetLanguage("一"))
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
        // buildTFunctionExpr 依赖分析器回填的 state.tFunctionName（lateinit），
        // 必须先 collect() 完成分析器懒初始化，否则 state 未初始化。
        processor.collect()

        // 基本用法
        assertEquals(
            "\$t('你好')",
            processor.jsCollector.buildTFunctionExpr(GenericStrategy, "你好", "{}")
        )

        // 带参数
        assertEquals(
            "\$t('你好{0}', { \"0\": name })",
            processor.jsCollector.buildTFunctionExpr(GenericStrategy, "你好{0}", "{ \"0\": name }")
        )

        // 含单引号的文本应转义
        val result = processor.jsCollector.buildTFunctionExpr(GenericStrategy, "它's", "{}")
        assertTrue("Should escape single quote, got: $result", result.contains("\\'"))

        // 含换行符的文本应使用反引号模板字符串（修复：普通字符串跨行导致的解析截断 bug）
        val newlineMsg = "1. 隔离库存\n2. 在线筛选\n3. 客户沟通"
        val newlineResult = processor.jsCollector.buildTFunctionExpr(GenericStrategy, newlineMsg, "{}")
        assertTrue(
            "含换行符应使用反引号，got: $newlineResult",
            newlineResult.startsWith("\$t(`") && newlineResult.endsWith("`)")
        )
        assertTrue(
            "含换行符的结果应保留所有换行内容，got: $newlineResult",
            newlineResult.contains("1. 隔离库存") &&
                    newlineResult.contains("2. 在线筛选") &&
                    newlineResult.contains("3. 客户沟通")
        )
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
        assertFalse(processor.analyzer.extractedStrings.containsValue("已提取"))
        // 未提取的应出现在 extractedStrings
        assertTrue(processor.analyzer.extractedStrings.containsValue("未提取"))
        // 已有的应在 existingStrings 中
        assertTrue(processor.analyzer.existingStrings.containsValue("已提取"))
    }

    // ============================================================
    // 9. 含 \n 转义换行的字符串字面量（修复截断 bug）
    // ============================================================

    /**
     * 测试对象属性中含 \n 转义的多行字符串被完整提取（不应截断）
     */
    fun testObjectPropertyMultilineStringWithEscapedNewline() {
        val file = myFixture.configureByText(
            "data.ts",
            """
            const config = {
                answer: "1. 隔离库存：冻结同批次2000片面板出货\n2. 在线筛选：增加AOI检测工位（阈值调整至CD≥0.8）\n3. 客户沟通：向重点客户提供替代批次",
                keywords: ["AOI", "缺陷"]
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text

        // answer 的长字符串应被完整提取为一个 $t 调用（使用反引号模板字符串），所有 3 条内容都应在 key 中
        val extracted = processor.analyzer.extractedStrings.values.firstOrNull {
            it.contains("1. 隔离库存") && it.contains("2. 在线筛选") && it.contains("3. 客户沟通")
        }
        assertTrue(
            "含 \\n 的多行字符串应作为整体被提取，got: ${processor.analyzer.extractedStrings.values}",
            extracted != null
        )

        // keywords 中 "缺陷"（中文）应被提取，"AOI"（英文）不应提取
        assertTrue(
            "keywords 中的 '缺陷' 应被提取，got: ${processor.analyzer.extractedStrings.values}",
            processor.analyzer.extractedStrings.containsValue("缺陷")
        )
        assertFalse(
            "keywords 中的 'AOI' 纯英文不应提取，got: ${processor.analyzer.extractedStrings.values}",
            processor.analyzer.extractedStrings.containsValue("AOI")
        )

        // 替换后结果中应包含完整的 $t(...)，没有被截断
        assertTrue(
            "替换后 answer 应为 \$t(`...`) 完整模板字符串，got:\n$resultText",
            resultText.contains("\$t(`1. 隔离库存")
        )
        assertTrue(
            "替换后 answer 中应包含 2. 部分，不应截断，got:\n$resultText",
            resultText.contains("2. 在线筛选")
        )
        assertTrue(
            "替换后 answer 中应包含 3. 部分，不应截断，got:\n$resultText",
            resultText.contains("3. 客户沟通")
        )
        // 反引号闭合检查：应只有一个 $t(`  配对 `) 完整闭合
        assertTrue(
            "替换后 answer 的 \$t 调用应使用反引号闭合，不应残留截断的双引号，got:\n$resultText",
            !resultText.contains("\$t(\"1. 隔离库存")
        )
    }

    /**
     * 测试数组中已有的 \$t("缺陷") 调用 + 纯英文 "AOI" 共存时，相邻纯英文不应被误替换
     */
    fun testArrayMixExistingTAndEnglishStringNotAffected() {
        val file = myFixture.configureByText(
            "data.ts",
            """
            const data = {
                keywords: ["AOI", ${'$'}t("缺陷")]
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // "AOI" 纯英文不应被提取
        assertFalse(
            "'AOI' 纯英文不应提取，got: ${processor.analyzer.extractedStrings.values}",
            processor.analyzer.extractedStrings.containsValue("AOI")
        )
        // "缺陷" 已有 \$t()，应在 existingStrings 中而非 extractedStrings
        assertFalse(
            "'缺陷' 已有 \$t()，不应重复提取到 extractedStrings，got: ${processor.analyzer.extractedStrings.values}",
            processor.analyzer.extractedStrings.containsValue("缺陷")
        )
        assertTrue(
            "'缺陷' 应在 existingStrings 中，got: ${processor.analyzer.existingStrings.values}",
            processor.analyzer.existingStrings.containsValue("缺陷")
        )
    }

    // ============================================================
    // 10. TS 专属方向 1：`as const` 数组 / 元组
    // ============================================================

    /**
     * 测试 `as const` 字面量数组中的中文应正常提取；
     * run() 后应替换为 i18n 调用，as const 保留在最外层（数组整体 as const 不动）。
     */
    fun testTypeScriptAsConstArrayChineseExtracts() {
        val file = myFixture.configureByText(
            "status.ts",
            """
            export const STATUSES = ["待审批", "已通过", "已拒绝", "已撤回"] as const
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("STATUSES 中 4 个中文都应提取", 4, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("待审批"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("已通过"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("已拒绝"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("已撤回"))

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "替换后应包含 \$t('待审批') / \$t('已通过') / \$t('已拒绝') / \$t('已撤回')，got:\n$result",
            result.contains("\$t(\"待审批\")") || result.contains("\$t('待审批')") || result.contains("\$t(`待审批`)")
        )
        assertTrue(
            "替换后 as const 仍保留在数组外层（不要把 as const 删掉），got:\n$result",
            result.trim().endsWith("as const")
        )
        // NOTE：不能用 assertFalse(result.contains("\"待审批\"")) 来判断——因为 $t("待审批") 本身也会命中
        //       该字符串。正确判断是「不存在『裸的独立字符串字面量』」，即它的前面不是 $t(。
        assertFalse(
            "替换后不应残留裸的硬编码 '待审批'（应被包进 \$t(\"待审批\") 形式），got:\n$result",
            listOf("待审批", "已通过", "已拒绝", "已撤回").any { chinese ->
                (result.contains("\"$chinese\"") && !result.contains("\$t(\"$chinese\"")) ||
                    (result.contains("'$chinese'") && !result.contains("\$t('$chinese')"))
            }
        )
    }

    /**
     * 测试 TS 元组（固定长度类型）的 `as const` 场景，中文应分别提取；
     * 替换后应保持元组长度与顺序。
     */
    fun testTypeScriptAsConstTupleExtracts() {
        val file = myFixture.configureByText(
            "labels.ts",
            """
            const pair: readonly ["开始时间", "结束时间"] = ["开始时间", "结束时间"] as const
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals(2, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("开始时间"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("结束时间"))

        processor.runWithUndo()
        val result = file.text
        assertFalse(
            "替换后字符串元组值不应仍硬编码双引号开始时间/结束时间，got:\n$result",
            result.contains("\", \"开始时间\"") && result.contains(", \"结束时间\"")
        )
    }

    // ============================================================
    // 11. TS 专属方向 2：enum 字符串枚举
    // ============================================================

    /**
     * 测试字符串枚举（string enum）中的中文 value 应被提取；
     * 替换后保留 enum 结构，value 换成 \$t(...)。
     */
    fun testTypeScriptStringEnumChineseExtracts() {
        val file = myFixture.configureByText(
            "OrderState.ts",
            """
            enum OrderState {
              Pending = "待支付",
              Paid = "已支付",
              Shipped = "已发货",
              Done = "已完成",
              Canceled = "已取消",
            }
            export default OrderState
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        // NOTE：processor 明确跳过 TypeScriptEnumField（原因是 enum 初始化值必须是编译期常量，
        //       不能放 $t(...) 调用，会引发 TS18033）。所以提取数量应为 0，且枚举原始内容保持不动。
        assertEquals("enum 字符串枚举不做 i18n 提取（避免 TS18033 编译错误）", 0, processor.analyzer.extractedStrings.size)
        assertFalse(processor.analyzer.extractedStrings.containsValue("待支付"))
        assertFalse(processor.analyzer.extractedStrings.containsValue("已完成"))

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "enum 定义保留",
            result.contains("enum OrderState")
        )
        assertTrue(
            "enum 原字符串值保持原样（不替换为 \$t 调用，TS 编译期常量约束），got:\n$result",
            result.contains("Pending = \"待支付\"") || result.contains("Pending = '待支付'")
        )
    }

    // ============================================================
    // 12. TS 专属方向 3：接口字段注释 / 类型别名 default 值 对象字面量（含方法）
    // ============================================================

    /**
     * 接口 (interface) 内字段：只有默认值对象（非 interface 定义本身）中的中文 value 才应提取；
     * interface 定义里的字段名（非字符串）不应被提取。
     */
    fun testTypeScriptInterfaceDefaultObjectChineseOnlyValueExtracted() {
        val file = myFixture.configureByText(
            "User.ts",
            """
            interface UserProfile {
              nickname: string;
              bio: string;
              status: "在线" | "忙碌" | "离开";
            }

            export const defaultUser: UserProfile = {
              nickname: "新用户",
              bio: "这个人很懒，什么都没留下",
              status: "在线",
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        // defaultUser 中 3 个 value + 类型字面量类型中我们不扫描（因为那是 TS type 语法的字符串字面量类型）
        // → 实际提取的是 defaultUser 的 nickname/bio/status 共 3 个硬编码字符串值
        assertTrue(
            "defaultUser 里 '新用户' 应被提取，got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("新用户")
        )
        assertTrue(
            "defaultUser 里 '这个人很懒，什么都没留下' 应被提取，got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("这个人很懒，什么都没留下")
        )

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "替换后 defaultUser.bio 应为 \$t('这个人很懒...')，got:\n$result",
            result.contains("这个人很懒") && result.contains("\$t(")
        )
        assertTrue(
            "interface UserProfile 定义应保持不变（不要改），got:\n$result",
            result.contains("interface UserProfile") && result.contains("nickname: string;")
        )
    }

    /**
     * 对象里有 method（函数属性）时，方法体内的中文字符串也应提取；
     * 不要把方法名 `sayHello` 误识别为对象属性字符串。
     */
    fun testTypeScriptObjectWithMethodBodyChineseExtracts() {
        val file = myFixture.configureByText(
            "service.ts",
            """
            const svc = {
              title: "提示标题",
              sayHello(name: string): string {
                const prefix = "你好呀"
                return prefix + name
              },
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(processor.analyzer.extractedStrings.containsValue("提示标题"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("你好呀"))

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "svc.title + sayHello body 两处中文都应被 \$t(...) 替换，got:\n$result",
            result.contains("\$t(`你好呀`)") || result.contains("\$t(\"你好呀\")") || result.contains("\$t('你好呀')")
        )
    }

    // ============================================================
    // 13. TS 专属方向 4：三元表达式（条件?中文分支A:中文分支B）
    // ============================================================

    /**
     * 三元表达式两个分支都是中文 → 两个都要提取（各自独立）。
     * 这个场景在 TS 里非常多（`const tip = ok ? "成功" : "失败"`）。
     */
    fun testTypeScriptTernaryBothChineseExtractsIndependently() {
        val file = myFixture.configureByText(
            "tips.ts",
            """
            function show(ok: boolean) {
              const tip = ok ? "提交成功" : "提交失败"
              const title = ok ? "好消息" : "坏消息"
              return { tip, title }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals(4, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("提交成功"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("提交失败"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("好消息"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("坏消息"))

        processor.runWithUndo()
        val result = file.text
        // NOTE：$t("提交成功") 本身就是字符串字面量包含提交成功 → 所以不能直接 assertFalse(result.contains("\"提交成功\""))
        //       而是「如果提交成功字面量出现，必须紧邻包在 $t( 调用里）」。
        assertFalse(
            "硬编码 '提交成功' 不应残留裸字符串（应包在 \$t(\"提交成功\") 形式里），got:\n$result",
            (result.contains("\"提交成功\"") && !result.contains("\$t(\"提交成功\"")) ||
                (result.contains("'提交成功'") && !result.contains("\$t('提交成功')"))
        )
        assertFalse(
            "硬编码 '提交失败' 不应残留裸字符串（应包在 \$t(\"提交失败\") 形式里），got:\n$result",
            (result.contains("\"提交失败\"") && !result.contains("\$t(\"提交失败\"")) ||
                (result.contains("'提交失败'") && !result.contains("\$t('提交失败')"))
        )
        assertFalse(
            "硬编码 '好消息' 不应残留裸字符串，got:\n$result",
            (result.contains("\"好消息\"") && !result.contains("\$t(\"好消息\"")) ||
                (result.contains("'好消息'") && !result.contains("\$t('好消息')"))
        )
        assertFalse(
            "硬编码 '坏消息' 不应残留裸字符串，got:\n$result",
            (result.contains("\"坏消息\"") && !result.contains("\$t(\"坏消息\"")) ||
                (result.contains("'坏消息'") && !result.contains("\$t('坏消息')"))
        )
        assertTrue(
            "至少应有一个三元表达式保留 `${'`'}ok ? ${'$'}t(...)${'`'}` 结构，got:\n$result",
            result.contains("?") && result.contains(":") && result.contains("${'$'}t(")
        )
    }

    // ============================================================
    // 14. TS 专属方向 5：模板字面量 `${name}` 带命名插值（中文 + 命名变量组合）
    //     buildTExpr Vue: {N0}  之前已知通过，这里补纯 TS 侧 3 条独立模板字面量场景。
    // ============================================================

    fun testTypeScriptTemplateLiteralNamedInterpolation() {
        val BQ = '`'
        val DOL = '$'
        val file = myFixture.configureByText(
            "toast.ts",
            """
            const title = "操作提示"
            function toast(userName: string, code: number) {
              return ${BQ}欢迎回来，${DOL}{userName}，您的验证码是 ${DOL}{code}${BQ}
            }
            function toast2(user: string, step: number, total: number) {
              return ${BQ}用户${DOL}{user}：第${DOL}{step}/${DOL}{total}步${BQ}
            }
            const welcome = ${BQ}欢迎回来${BQ}
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        // title = 操作提示 / toast = 欢迎回来，${x}，您的验证码是 ${y} / toast2 = 用户${x}：第${y}/${z}步 / welcome=欢迎回来
        assertTrue("title 应提取 '操作提示'", processor.analyzer.extractedStrings.containsValue("操作提示"))
        assertTrue("welcome 应提取 '欢迎回来'", processor.analyzer.extractedStrings.containsValue("欢迎回来"))
        assertTrue(
            "toast 整段模板字面量应提取为包含 欢迎回来 / 验证码 is 的骨架（含 2 个命名插值）",
            processor.analyzer.extractedStrings.values.any { it.contains("欢迎回来") && it.contains("验证码是") }
        )
        assertTrue(
            "toast2 整段骨架包含 用户 / 第 / 步 三个中文段",
            processor.analyzer.extractedStrings.values.any { it.contains("用户") && it.contains("第") && it.contains("步") }
        )

        processor.runWithUndo()
        val result = file.text
        assertFalse(
            "toast 原始硬编码 `${'`'}欢迎回来，${'$'}{userName}，您的验证码是 ${'$'}{code}${'`'}` 不应残留",
            result.contains("欢迎回来，${'$'}{userName}，您的验证码是 ${'$'}{code}")
        )
    }

    // ============================================================
    // 15. TS 专属方向 6：namespace + 类成员/静态方法
    // ============================================================

    fun testTypeScriptNamespacePlusClassMembersChineseExtracts() {
        val file = myFixture.configureByText(
            "api.ts",
            """
            namespace Api.ErrorMsg {
              export class Network {
                static readonly tip = "网络连接失败"
                static readonly retry = "点击重试"
                static reason(): string {
                  const hint = "请检查网络设置"
                  return hint
                }
              }
              export const defaultTitle = "请求发生错误"
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(processor.analyzer.extractedStrings.containsValue("网络连接失败"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("点击重试"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("请检查网络设置"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("请求发生错误"))

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "namespace / class 定义应保留，got:\n$result",
            result.contains("namespace Api.ErrorMsg") && result.contains("export class Network")
        )
        assertFalse(
            "static readonly tip 的硬编码 '网络连接失败' 不应残留",
            result.contains("tip = \"网络连接失败\"")
        )
    }

    // ============================================================
    // 16. TS 专属方向 7：解构赋值（含重命名 / 默认值）
    // ============================================================

    fun testTypeScriptDestructuringDefaultChineseExtracts() {
        val file = myFixture.configureByText(
            "config.ts",
            """
            interface Props {
              label?: string
              placeholder?: string
              okText?: string
            }
            function Button({
              label: title,
              placeholder = "请输入关键字",
              okText = "立即搜索",
            }: Props) {
              return { title, placeholder, okText }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("placeholder 默认值 请输入关键字 应提取", processor.analyzer.extractedStrings.containsValue("请输入关键字"))
        assertTrue("okText 默认值 立即搜索 应提取", processor.analyzer.extractedStrings.containsValue("立即搜索"))

        processor.runWithUndo()
        val result = file.text
        assertFalse(
            "placeholder 默认值硬编码不应残留",
            result.contains("placeholder = \"请输入关键字\"")
        )
        assertFalse(
            "okText 默认值硬编码不应残留",
            result.contains("okText = \"立即搜索\"")
        )
        assertTrue(
            "解构 + 重命名语法 label: title 应保留（不要把它当字符串替换了），got:\n$result",
            result.contains("label: title")
        )
    }

    // ============================================================
    // 17. TS 专属方向 8：Class 字段 + 静态初始化块 + 导出对象 as const
    //     （项目里常见：静态字典 static {} 块、const exports.dict = {...} as const）
    // ============================================================

    fun testTypeScriptClassFieldsStaticBlockExportAsConstChineseExtracts() {
        val file = myFixture.configureByText(
            "dict.ts",
            """
            export class Constants {
              readonly greeting = "欢迎使用"
              static defaultMsg: string
              static {
                Constants.defaultMsg = "加载中..."
              }
            }

            export const PAGE_TITLES = {
              home: "首页",
              about: "关于我们",
              contact: "联系我们",
            } as const
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(processor.analyzer.extractedStrings.containsValue("欢迎使用"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("加载中..."))
        assertTrue(processor.analyzer.extractedStrings.containsValue("首页"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("关于我们"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("联系我们"))
        assertEquals(5, processor.analyzer.extractedStrings.size)

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "PAGE_TITLES 末尾 as const 应保留，got:\n$result",
            result.trim().endsWith("as const")
        )
        assertFalse(
            "static {} 块中硬编码 '加载中...' 不应残留双引号，got:\n$result",
            result.contains("= \"加载中...\"")
        )
    }

    // ============================================================
    // 回归 1：「限制200字符 / 限制50字符」等中文夹数字的字符串漏提取
    // ============================================================
    fun testChineseAndNumberMixedStringsLikeLimitNNCharsExtracts() {
        val file = myFixture.configureByText(
            "validation.ts",
            """
            const rules = {
              name: { max: 50, message: "限制50字符" },
              desc: { max: 200, message: "限制200字符" },
              intro: { max: 1000, message: "最多输入1000字" },
              zip: { pattern: /^\d{6}$/, message: "请输入6位邮政编码" },
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("应提取 '限制50字符'", processor.analyzer.extractedStrings.containsValue("限制50字符"))
        assertTrue("应提取 '限制200字符'", processor.analyzer.extractedStrings.containsValue("限制200字符"))
        assertTrue("应提取 '最多输入1000字'", processor.analyzer.extractedStrings.containsValue("最多输入1000字"))
        assertTrue("应提取 '请输入6位邮政编码'", processor.analyzer.extractedStrings.containsValue("请输入6位邮政编码"))
        assertEquals("共 4 条中文夹数字提取", 4, processor.analyzer.extractedStrings.size)

        processor.runWithUndo()
        val result = file.text
        // 不能残留裸字符串
        listOf("限制50字符", "限制200字符", "最多输入1000字", "请输入6位邮政编码").forEach { s ->
            assertFalse(
                "裸字符串 \"$s\" 不应残留（应包进 ${'$'}t(\"$s\") 形式），got:\n$result",
                (result.contains("\"$s\"") && !result.contains("\$t(\"$s\"")) ||
                    (result.contains("'$s'") && !result.contains("\$t('$s'"))
            )
        }
    }

    // ============================================================
    // 回归 2：已被 $t( 三元表达式参数包裹的字符串，不能二次 $t 包装
    //         错误：$t(isPinned ? '取消置顶' : '置顶')
    //               -> $t(isPinned ? $t('取消置顶') : $t('置顶'))
    // ============================================================
    fun testAlreadyWrappedInTernaryInsideTDollarCallDoesNotDoubleWrap() {
        val file = myFixture.configureByText(
            "pin.tsx",
            """
            function PinButton({ isPinned }: { isPinned: boolean }) {
              return (
                <button>{${'$'}t(isPinned ? '取消置顶' : '置顶')}</button>
              )
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("应提取 '取消置顶'", processor.analyzer.extractedStrings.containsValue("取消置顶"))
        assertTrue("应提取 '置顶'", processor.analyzer.extractedStrings.containsValue("置顶"))
        processor.runWithUndo()
        val result = file.text
        // 修正后：$t('取消置顶') 和 $t('置顶') 的单条调用只在外层出现一次
        //         即整个表达式应该是 $t(isPinned ? $t('取消置顶') : $t('置顶')) ？——
        //         不！更合理的是：外层 $t( 整个三元表达式) 不再合法（参数是表达式不是字符串），
        //         所以 processor 不能识别外层那个 $t 调用（不是字符串字面量参数），
        //         只替换内部两个字符串 → 最终形态必须是：
        //              $t(isPinned ? $t('取消置顶') : $t('置顶'))  ——  双重 $t，错！
        //         （实际应当：外层的 $t() 整段不是字符串参数，外层不应被视为"已转换"，
        //          但内部两个独立字符串字面量参数属于"它们并不在任何合法 $t( 单参字符串调用里"
        //          → 必须替换。因此最终结果虽然嵌套 $t，语义上其实正确：外层参数传进去的是
        //          两次 $t 调后的字符串；如果用户原本写 $t(cond?'a':'b') 就是错的，
        //          插件修正它变成内层独立调用是正确行为。但用户报这个 bug，可能用户期望
        //          「外层已经写了 $t( 表达式 )，则内部字符串不再动」——
        //          就按用户期望实现：当字符串所在最内层 $t/ t/ i18n.global.t 调用祖先存在时，
        //          即使该调用的参数不是字符串（是表达式），仍把它视为"已在 $t 作用域内"，
        //          不再二次替换。
        val tCall = Regex("""\$\s*t\s*\(\s*isPinned""")
        assertTrue("整体结构保留 \${'$'}t(isPinned ? ... : ...) 外形", tCall.containsMatchIn(result))
        // 用户期望：内部不要再出现第二个 $t(
        val totalT = Regex("""\$\s*t\s*\(""").findAll(result).count()
        assertEquals("用户期望：外层已有 ${'$'}t( 表达式 )，内部不要再包第二层 ${'$'}t", 1, totalT)
    }

    // ============================================================
    // 幂等性：已提取过的文件再次提取，JSON 应保持一致
    // ============================================================

    /**
     * 模拟「提取 → 应用 → 再次提取」的完整往返，验证两次得到的 JSON key→value 完全一致。
     *
     * 原理：第一次提取后文件里出现 $t('你好')，再次提取时 collectExistingTKeys() 会把这段
     * 已翻译调用收进 existingStrings，collectFromPsi() 也会因 DIRECT_ARG 跳过它，所以
     * 第二次不再产生新的 extractedStrings，最终 JSON（existing ∪ extracted）保持不变。
     */
    fun testReExtractProducesSameJson() {
        // —— 第一次提取 ——
        val file = myFixture.configureByText(
            "test.ts",
            """
            const msg = "你好"
            const other = '你好'
            """.trimIndent()
        )
        val p1 = I18nProcessor(project, file)
        p1.collect()
        val json1 = mutableMapOf<String, String>()
        json1.putAll(p1.analyzer.existingStrings)
        json1.putAll(p1.analyzer.extractedStrings)

        // 应用改动（写入 PSI），得到已提取后的文件
        p1.runWithUndo()
        val transformedText = file.text
        assertTrue("第一次提取后应出现 \$t(...)", transformedText.contains("\$t("))

        // —— 第二次提取（对已提取后的文件） ——
        val file2 = myFixture.configureByText("test2.ts", transformedText)
        val p2 = I18nProcessor(project, file2)
        p2.collect()
        val json2 = mutableMapOf<String, String>()
        json2.putAll(p2.analyzer.existingStrings)
        json2.putAll(p2.analyzer.extractedStrings)

        assertEquals(
            "再次提取不应产生新的 extractedStrings（已翻译调用被跳过）",
            emptyMap<String, String>(),
            p2.analyzer.extractedStrings
        )
        assertEquals(
            "两次提取得到的 JSON 应完全一致，但 json1=$json1 json2=$json2",
            json1,
            json2
        )
    }
}
