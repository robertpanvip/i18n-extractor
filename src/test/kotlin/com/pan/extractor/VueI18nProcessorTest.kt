package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Vue 模板 i18n 提取测试
 *
 * 覆盖 {{ }} Mustache 表达式中的各种场景：
 * - 纯文本
 * - 变量/成员变量
 * - 函数调用
 * - 三目表达式
 * - 已有 $t() 调用（各种引号风格）
 * - 混合场景
 */
class VueI18nProcessorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // 创建 Vue 项目的 package.json（包含 vue 依赖，无 react 依赖）
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "vue-test-project",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0"
              }
            }
            """.trimIndent()
        )
    }

    // ============================================================
    // 1. 基础文本提取
    // ============================================================

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

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertEquals("你好", processor.extractedStrings.values.first())
    }

    // ============================================================
    // 2. {{ }} 中已有的 $t() 调用 - 各种引号风格
    // ============================================================

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

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
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

    // ============================================================
    // 3. {{ }} 中变量/成员变量表达式 - 不应提取
    // ============================================================

    /**
     * 测试 {{ 变量名 }} 不应提取（纯变量，无中文）
     */
    fun testVueVariableExpressionShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ message }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for variable expression, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试 {{ 对象.属性 }} 成员变量表达式 - 不应提取
     */
    fun testVueMemberExpressionShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ user.name }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for member expression, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试 {{ 数组[索引] }} 表达式 - 不应提取
     */
    fun testVueArrayIndexExpressionShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ items[0] }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for array index expression, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    // ============================================================
    // 4. {{ }} 中函数调用表达式
    // ============================================================

    /**
     * 测试 {{ 函数调用() }} 无参函数调用 - 不应提取
     */
    fun testVueFunctionCallNoArgsShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ getMessage() }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for function call, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试 {{ 函数调用('中文') }} 函数字符串参数 - 应提取参数中的中文
     */
    fun testVueFunctionCallWithStringArgShouldExtract() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ formatMessage('你好世界') }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '你好世界' but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好世界")
        )
    }

    /**
     * 测试 {{ 对象.方法('中文') }} 方法调用带字符串参数 - 应提取参数中的中文
     */
    fun testVueMethodCallWithStringArgShouldExtract() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ utils.format('提示信息') }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '提示信息' but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("提示信息")
        )
    }

    // ============================================================
    // 5. {{ }} 中三目表达式
    // ============================================================

    /**
     * 测试 {{ 条件 ? '中文1' : '中文2' }} 三目表达式 - 两个分支都应提取
     */
    fun testVueTernaryExpressionBothChinese() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ isVip ? '会员专享' : '普通用户' }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(
            "Should extract 2 strings from ternary, but got: ${processor.extractedStrings}",
            2,
            processor.extractedStrings.size
        )
        assertTrue(
            "Should contain '会员专享', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("会员专享")
        )
        assertTrue(
            "Should contain '普通用户', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("普通用户")
        )
    }

    /**
     * 测试 {{ 条件 ? '中文' : variable }} 三目表达式只有一个分支有中文
     */
    fun testVueTernaryExpressionOneChinese() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ status === 1 ? '已启用' : statusText }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(
            "Should extract 1 string from ternary, but got: ${processor.extractedStrings}",
            1,
            processor.extractedStrings.size
        )
        assertTrue(
            "Should contain '已启用', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("已启用")
        )
    }

    /**
     * 测试 {{ 条件 ? $t('key1') : $t('key2') }} 三目表达式已有 $t() 应跳过
     */
    fun testVueTernaryExpressionWithExistingTShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ isVip ? ${'$'}t('会员专享') : ${'$'}t('普通用户') }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for existing ${'$'}t() in ternary, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试嵌套三目表达式 {{ a ? '中文1' : b ? '中文2' : '中文3' }}
     */
    fun testVueNestedTernaryExpression() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ level === 1 ? '高级' : level === 2 ? '中级' : '初级' }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(
            "Should extract 3 strings from nested ternary, but got: ${processor.extractedStrings}",
            3,
            processor.extractedStrings.size
        )
        assertTrue(processor.extractedStrings.containsValue("高级"))
        assertTrue(processor.extractedStrings.containsValue("中级"))
        assertTrue(processor.extractedStrings.containsValue("初级"))
    }

    // ============================================================
    // 6. {{ }} 中模板字符串（反引号）
    // ============================================================

    /**
     * 测试 {{ `模板字符串${变量}` }} 模板字符串表达式
     */
    fun testVueTemplateLiteralExpression() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ `你好${'$'}{name}` }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '你好{0}' but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{0}")
        )
    }

    /**
     * 测试 {{ `模板字符串${'纯字符串'}` }} 模板字符串中纯字符串插值应内联
     */
    fun testVueTemplateLiteralWithPureStringInterpolation() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ `前缀${'$'}{'中间文本'}后缀` }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '前缀中间文本后缀' but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("前缀中间文本后缀")
        )
    }

    // ============================================================
    // 7. 混合场景
    // ============================================================

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

        assertFalse(
            "extractedStrings should not contain '确定'",
            processor.extractedStrings.containsValue("确定")
        )
        assertTrue(
            "extractedStrings should contain '其他文本' but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("其他文本")
        )
        assertTrue(
            "existingStrings should contain '确定'",
            processor.existingStrings.containsValue("确定")
        )
    }

    /**
     * 测试多个 {{ }} 表达式混合场景
     */
    fun testVueMultipleMustacheExpressions() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>
                    <span>{{ title }}</span>
                    <span>{{ ${'$'}t('已保存') }}</span>
                    <span>{{ '静态文本' }}</span>
                    <span>{{ isOk ? '成功' : '失败' }}</span>
                </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // '已保存' 是已有 $t()，不应提取
        assertFalse(processor.extractedStrings.containsValue("已保存"))
        // '静态文本' 应提取
        assertTrue(processor.extractedStrings.containsValue("静态文本"))
        // '成功' 和 '失败' 应提取
        assertTrue(processor.extractedStrings.containsValue("成功"))
        assertTrue(processor.extractedStrings.containsValue("失败"))
        // '已保存' 应在 existingStrings 中
        assertTrue(processor.existingStrings.containsValue("已保存"))
    }

    // ============================================================
    // 8. 属性值中的表达式
    // ============================================================

    /**
     * 测试 :title="'中文'" 动态属性中的字符串应提取
     */
    fun testVueDynamicAttributeStringShouldExtract() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div :title="'提示信息'">hover me</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '提示信息' but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("提示信息")
        )
    }

    /**
     * 测试 :title="$t('key')" 动态属性中已有 $t() 应跳过
     */
    fun testVueDynamicAttributeWithExistingTShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div :title="${'$'}t('提示信息')">hover me</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertFalse(
            "extractedStrings should not contain '提示信息' for existing ${'$'}t()",
            processor.extractedStrings.containsValue("提示信息")
        )
        assertTrue(
            "existingStrings should contain '提示信息'",
            processor.existingStrings.containsValue("提示信息")
        )
    }

    /**
     * 测试 @click="handleClick()" 事件处理 - 事件内无字符串不应提取
     * 按钮文本为中文时应被正常提取
     */
    fun testVueClickHandlerNoStringShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <button @click="handleClick()">点击</button>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 只有 '点击' 按钮文本应被提取，@click 中的 handleClick() 不应提取
        assertEquals(1, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("点击"))
    }

    // ============================================================
    // 9. 注释和 style 跳过
    // ============================================================

    /**
     * 测试 HTML 注释中的中文应跳过
     */
    fun testVueCommentShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <!-- 这是注释，不应被提取 -->
                <div>真实文本</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("真实文本"))
        assertFalse(processor.extractedStrings.containsValue("这是注释，不应被提取"))
    }

    /**
     * 测试 style 标签中的中文应跳过
     */
    fun testVueStyleShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>真实文本</div>
            </template>
            <style>
                .test {
                    font-family: "微软雅黑";
                }
            </style>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("真实文本"))
    }

    // ============================================================
    // 10. Vue 项目中的 TSX 文件（Vue 3 + TSX 场景）
    // ============================================================

    /**
     * 测试 Vue 项目中 .tsx 文件的模板字面量插值应使用 Vue 的单括号格式 {key}
     * 而不是 React 的双括号格式 {{key}}
     */
    fun testVueProjectTsxTemplateLiteralUsesSingleBrace() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { defineComponent, ref } from 'vue'

            export default defineComponent({
                setup() {
                    const name = ref('World')
                    const msg = `你好${'$'}{name}`
                    return () => <div>{msg}</div>
                }
            })
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 模板字面量变量插值应使用 Vue 的单括号格式 {0}，而不是 React 的 {{0}}
        assertTrue(
            "Vue 项目中 TSX 的模板字面量插值应使用单括号格式 {0}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{0}")
        )
        assertFalse(
            "Vue 项目中 TSX 的模板字面量插值不应使用双括号格式 {{0}}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{{0}}")
        )
    }

    /**
     * 测试 Vue 项目中 isReact 判断应为 false（即使文件是 .tsx 后缀）
     */
    fun testVueProjectTsxIsReactShouldBeFalse() {
        val file = myFixture.configureByText(
            "Component.tsx",
            """
            import { defineComponent } from 'vue'

            export default defineComponent({
                setup() {
                    return () => <div>测试组件</div>
                }
            })
            """.trimIndent()
        )

        val element = file.firstChild
        val isReact = com.pan.extractor.Util.isReact(element)

        assertFalse(
            "Vue 项目中的 .tsx 文件 isReact 应为 false，因为 package.json 中包含 vue 依赖",
            isReact
        )
    }

    // ============================================================
    // 11. HTML 注释跳过（Bug 修复）
    // ============================================================

    /**
     * 测试 div 中全部是 HTML 注释时不应提取任何内容
     * 场景：<div><!-- 注释1 --><!-- 注释2 --></div>
     */
    fun testDivWithOnlyHtmlCommentsShouldNotExtract() {
        val file = myFixture.configureByText(
            "test.vue",
            """
            <template>
                <div>
                    <!--    <FormList-->
                    <!--      label="输出变量"-->
                    <!--    />-->
                </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "全是 HTML 注释的 div 不应提取任何内容, got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试 div 中 HTML 注释和实际文本混合时，只提取实际文本
     */
    fun testDivWithHtmlCommentsAndTextExtractsOnlyText() {
        val file = myFixture.configureByText(
            "test.vue",
            """
            <template>
                <div>
                    <!-- 这是注释 -->
                    这是实际文本
                </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "应提取实际文本 '这是实际文本', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("这是实际文本")
        )
        assertFalse(
            "不应提取注释内容 '这是注释', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("这是注释")
        )
    }

    // ============================================================
    // 12. Mustache 中 JS 注释跳过（Bug 修复）
    // ============================================================

    /**
     * 测试 {{ }} 中只有 JS 注释时不应提取
     * 场景：<div>{{ //新增按钮 }}</div>
     */
    fun testMustacheWithOnlyJsCommentShouldNotExtract() {
        val file = myFixture.configureByText(
            "test.vue",
            """
            <template>
                <div>{{
                    //新增按钮
                }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertFalse(
            "{{ //新增按钮 }} 中的 JS 注释不应被提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("//新增按钮")
        )
    }

    // ============================================================
    // 13. 模板字符串中已有 $t() 调用不应重复提取（Bug 修复）
    // ============================================================

    /**
     * 测试 {{ }} 中三目表达式已有 $t() 调用时不应修改
     * 场景：record.x ? `${$t("已于")}${record.x}${$t("发布")}` : $t("未发布")
     */
    fun testMustacheWithExistingTCallsShouldNotExtract() {
        val file = myFixture.configureByText(
            "test.vue",
            """
            <template>
                <div>
                    {{
                        record.releaseTime
                            ? `${'$'}t("已于")`${'$'}{record.releaseTime}${'$'}t("发布")`
                            : ${'$'}t("未发布")
                    }}
                </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 所有字符串已在 $t() 中，不应被重复提取
        assertFalse(
            "'已于' 已在 \$t() 中，不应重复提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("已于")
        )
        assertFalse(
            "'发布' 已在 \$t() 中，不应重复提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("发布")
        )
        assertFalse(
            "'未发布' 已在 \$t() 中，不应重复提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("未发布")
        )
        // 应在 existingStrings 中
        assertTrue(
            "'已于' 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("已于")
        )
        assertTrue(
            "'未发布' 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("未发布")
        )
    }

    // ============================================================
    // 14. i18n.global.t 全局调用支持
    // ============================================================

    /**
     * 测试文件中使用 i18n.global.t 时，新提取的字符串应使用 i18n.global.t
     */
    fun testI18nGlobalTDetectionForNewExtraction() {
        val file = myFixture.configureByText(
            "test.vue",
            """
            <template>
                <div>{{ i18n.global.t("已有文本") }}</div>
            </template>
            <script setup lang="ts">
            import { i18n } from './i18n'
            const newMsg = "新提取文本"
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text

        // "已有文本" 应在 existingStrings 中
        assertTrue(
            "'已有文本' 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("已有文本")
        )
        // "新提取文本" 应被提取
        assertTrue(
            "'新提取文本' 应被提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("新提取文本")
        )
        // 新提取的应使用 i18n.global.t
        assertTrue(
            "新提取的字符串应使用 i18n.global.t, got:\n$resultText",
            resultText.contains("i18n.global.t('新提取文本')")
        )
    }

    /**
     * 测试 i18n.global.t 和 $t 可以在同一文件中共存
     * 两者都应被识别为已翻译，新提取使用 i18n.global.t
     */
    fun testI18nGlobalTCoexistWithUseI18n() {
        val file = myFixture.configureByText(
            "test.vue",
            """
            <template>
                <div>
                    <span>{{ ${'$'}t("vue文本") }}</span>
                    <span>{{ i18n.global.t("全局文本") }}</span>
                </div>
            </template>
            <script setup lang="ts">
            import { useI18n } from 'vue-i18n'
            import { i18n } from './i18n'
            const { t: ${'$'}t } = useI18n()
            const newMsg = "待提取"
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 两种形式都应识别为已翻译
        assertTrue(
            "'vue文本' (via \$t) 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("vue文本")
        )
        assertTrue(
            "'全局文本' (via i18n.global.t) 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("全局文本")
        )
        // 新提取的应存在
        assertTrue(
            "'待提取' 应被提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("待提取")
        )
        // 不应重复提取已有文本
        assertFalse(
            "'vue文本' 不应被重复提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("vue文本")
        )
        assertFalse(
            "'全局文本' 不应被重复提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("全局文本")
        )
    }

    // ============================================================
    // 15. i18n.global.t 全局实例导入注入
    // ============================================================

    /**
     * 测试使用 i18n.global.t 但缺少 i18n 实例导入时，应自动注入默认导入。
     * Vue 默认注入命名导入：import { i18n } from 'vue-i18n'
     */
    fun testVueI18nGlobalTInjectImportWhenMissing() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ i18n.global.t("已有文本") }}</div>
            </template>
            <script setup lang="ts">
            const newMsg = "新提取文本"
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        // 应注入 i18n 实例的命名导入
        assertTrue(
            "应注入 import { i18n } from 'vue-i18n', got:\n$resultText",
            resultText.contains("import { i18n } from 'vue-i18n'")
        )
        // 不应注入 useI18n（已使用全局 i18n）
        assertFalse(
            "不应注入 useI18n, got:\n$resultText",
            resultText.contains("useI18n")
        )
    }

    /**
     * 测试已有 i18n 命名导入时不重复注入
     */
    fun testVueI18nGlobalTNotDuplicateNamedImport() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ i18n.global.t("已有文本") }}</div>
            </template>
            <script setup lang="ts">
            import { i18n } from './i18n'
            const newMsg = "新提取文本"
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        // 不应再注入默认路径的 import
        assertFalse(
            "不应重复注入 import { i18n } from 'vue-i18n', got:\n$resultText",
            resultText.contains("from 'vue-i18n'")
        )
        // 原有的 ./i18n 导入应保留
        assertTrue(
            "原有 import { i18n } from './i18n' 应保留, got:\n$resultText",
            resultText.contains("import { i18n } from './i18n'")
        )
    }

    /**
     * 测试已有 i18n 默认导入时不重复注入
     */
    fun testVueI18nGlobalTNotDuplicateDefaultImport() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ i18n.global.t("已有文本") }}</div>
            </template>
            <script setup lang="ts">
            import i18n from './i18n'
            const newMsg = "新提取文本"
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        assertFalse(
            "已有默认导入时不应再注入命名导入, got:\n$resultText",
            resultText.contains("from 'vue-i18n'")
        )
        assertTrue(
            "原有 import i18n from './i18n' 应保留, got:\n$resultText",
            resultText.contains("import i18n from './i18n'")
        )
    }

    /**
     * 测试已有 namespace 导入（import * as i18n）时不重复注入
     */
    fun testVueI18nGlobalTNotDuplicateNamespaceImport() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ i18n.global.t("已有文本") }}</div>
            </template>
            <script setup lang="ts">
            import * as i18n from './i18n'
            const newMsg = "新提取文本"
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        assertFalse(
            "已有 namespace 导入时不应再注入, got:\n$resultText",
            resultText.contains("from 'vue-i18n'")
        )
    }

    /**
     * Vue 项目纯 .ts 文件中的 use 开头自定义 hook，内部硬编码中文应被提取，
     * 且自动注入 import { useI18n } from 'vue-i18n' 与 const { t: $t } = useI18n()。
     */
    fun testVueCustomHookInTsFileInjectsUseI18n() {
        val file = myFixture.configureByText(
            "useCounter.ts",
            """
            export function useCounter() {
                const label = "计数器"
                return { label }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        println("DEBUG useCounter: beforeExecute=\n${file.text}")
        processor.execute()

        val resultText = file.text
        println("DEBUG useCounter: extracted=${processor.extractedStrings}")
        println("DEBUG useCounter: hooks=${com.pan.extractor.Util.findHookFunctions(file).map { it.text.take(40) }}")
        println("DEBUG useCounter: resultText=\n$resultText")
        assertTrue(
            "应注入 import { useI18n } from 'vue-i18n', got:\n$resultText",
            resultText.contains("import { useI18n } from 'vue-i18n'")
        )
        assertTrue(
            "应注入 const { t: \$t } = useI18n(), got:\n$resultText",
            resultText.contains("const { t: \$t } = useI18n()")
        )
        assertTrue(
            "硬编码中文应被替换为 \$t('计数器'), got:\n$resultText",
            resultText.contains("\$t('计数器')")
        )
    }

    /**
     * 箭头函数形式的 use hook（const useXxx = () => {}）也应被识别并注入。
     */
    fun testVueCustomHookArrowFunctionInTsFileInjectsUseI18n() {
        val file = myFixture.configureByText(
            "useToggle.ts",
            """
            export const useToggle = () => {
                const hint = "提示文本"
                return { hint }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        assertTrue(
            "箭头函数 hook 应注入 useI18n 导入, got:\n$resultText",
            resultText.contains("import { useI18n } from 'vue-i18n'")
        )
        assertTrue(
            "箭头函数 hook 体应注入 const { t: \$t } = useI18n(), got:\n$resultText",
            resultText.contains("const { t: \$t } = useI18n()")
        )
    }

    /**
     * 已有 useI18n 调用的 hook 不应重复注入。
     */
    fun testVueCustomHookWithExistingUseI18nNotReInjected() {
        val file = myFixture.configureByText(
            "useUser.ts",
            """
            import { useI18n } from 'vue-i18n'
            export function useUser() {
                const { t: ${'$'}t } = useI18n()
                const label = "用户名"
                return { label }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        // 只应出现一次 useI18n 导入
        assertEquals(
            "useI18n 导入不应重复, got:\n$resultText",
            1,
            resultText.split("import { useI18n } from 'vue-i18n'").size - 1
        )
        // hook 体内的 useI18n 调用也只应有一次
        assertEquals(
            "useI18n() 调用不应重复, got:\n$resultText",
            1,
            resultText.split("const { t: \$t } = useI18n()").size - 1
        )
    }

    /**
     * 非 use 开头的普通函数（如普通工具函数）不应被注入 useI18n。
     */
    fun testVueNonHookFunctionInTsFileNotInjected() {
        val file = myFixture.configureByText(
            "format.ts",
            """
            export function formatDate() {
                const label = "日期"
                return label
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        assertFalse(
            "普通函数不应注入 useI18n 导入, got:\n$resultText",
            resultText.contains("import { useI18n } from 'vue-i18n'")
        )
        assertFalse(
            "普通函数不应注入 useI18n 调用, got:\n$resultText",
            resultText.contains("useI18n()")
        )
    }
}
