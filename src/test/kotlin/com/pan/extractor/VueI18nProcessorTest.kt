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

    // ============================================================
    // 12. Bug 复现 - 混合引号三目 + 多表达式混合文本
    // ============================================================

    /**
     * Bug1: 三目表达式混合引号（反引号 + 双引号）应都能提取
     * {{ visibleToggle ? `点击展开` : "点击收起" }}
     */
    fun testVueTernaryMixedQuotes() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ visibleToggle ? `点击展开` : "点击收起" }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(
            "Should extract 2 strings from mixed-quote ternary, but got: ${processor.extractedStrings}",
            2,
            processor.extractedStrings.size
        )
        assertTrue(
            "Should contain '点击展开', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("点击展开")
        )
        assertTrue(
            "Should contain '点击收起', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("点击收起")
        )
    }

    /**
     * Bug2: 同一个 XmlText 中多个 {{ }} + 中间普通文本应都能提取
     * <div>{{ "点击收起" }}-测试 {{ "点击收起" }}</div>
     */
    fun testVueMixedMustacheAndPlainText() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ "点击收起" }}-测试 {{ "点击收起" }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // "点击收起" 来自两个 {{ }} 表达式（去重后1个）+ "-测试" 来自中间普通文本
        assertTrue(
            "Should contain '点击收起', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("点击收起")
        )
        assertTrue(
            "Should contain '-测试' plain text between mustaches, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("-测试")
        )
    }

    /**
     * Bug3: 已有双引号 import 时不应重复添加 import
     * import { useI18n } from "vue-i18n"; （双引号）不应再新增一个单引号版本
     */
    fun testVueExistingDoubleQuoteImportShouldNotDuplicate() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <script setup lang="ts">
            import { useI18n } from "vue-i18n";
            import { ref } from "vue";
            const { t: ${'$'}t } = useI18n();
            const visibleToggle = ref();
            </script>

            <template>
              <div>
                {{ visibleToggle ? "点击展开" : "点击收起" }}
              </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        // 统计 vue-i18n import 出现次数，应该只有 1 个
        val importCount = Regex("import\\s*\\{[^}]*useI18n[^}]*\\}\\s*from\\s*['\"]vue-i18n['\"]")
            .findAll(resultText).count()

        assertEquals(
            "Should have exactly 1 vue-i18n import, got $importCount. Result:\n$resultText",
            1,
            importCount
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
    // 13. Mustache 与纯文本混合边界场景
    // ============================================================

    /**
     * 测试纯文本在 mustache 前面：前缀文本 + {{ 表达式 }}
     * 例如：前缀-{{ "内容" }}
     */
    fun testVuePlainTextPrefixBeforeMustache() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>前缀-{{ "内容" }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should contain '内容' from mustache, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("内容")
        )
        assertTrue(
            "Should contain '前缀-' plain text before mustache, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("前缀-")
        )
    }

    /**
     * 测试纯文本在 mustache 后面：{{ 表达式 }} + 后缀文本
     * 例如：{{ "内容" }}-后缀
     */
    fun testVuePlainTextSuffixAfterMustache() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ "内容" }}-后缀</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should contain '内容' from mustache, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("内容")
        )
        assertTrue(
            "Should contain '-后缀' plain text after mustache, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("-后缀")
        )
    }

    /**
     * 测试多个 mustache + 多段纯文本交替
     * 例如：开头 {{ "a" }} 中间 {{ "b" }} 结尾
     */
    fun testVueMultiplePlainTextAndMustaches() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>开头 {{ "苹果" }} 中间 {{ "香蕉" }} 结尾</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should contain '苹果', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("苹果")
        )
        assertTrue(
            "Should contain '香蕉', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("香蕉")
        )
        assertTrue(
            "Should contain '开头' plain text, got: ${processor.extractedStrings}",
            processor.extractedStrings.any { it.value.trim() == "开头" }
        )
        assertTrue(
            "Should contain '中间' plain text, got: ${processor.extractedStrings}",
            processor.extractedStrings.any { it.value.trim() == "中间" }
        )
        assertTrue(
            "Should contain '结尾' plain text, got: ${processor.extractedStrings}",
            processor.extractedStrings.any { it.value.trim() == "结尾" }
        )
    }

    /**
     * 测试三目表达式 + 周围纯文本
     * 例如：状态：{{ isOn ? '开启' : '关闭' }}模式
     */
    fun testVueTernaryWithSurroundingPlainText() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>状态：{{ isOn ? '开启' : '关闭' }}模式</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should contain '开启', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("开启")
        )
        assertTrue(
            "Should contain '关闭', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("关闭")
        )
        assertTrue(
            "Should contain '状态：' plain text, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("状态：")
        )
        assertTrue(
            "Should contain '模式' plain text, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("模式")
        )
    }

    /**
     * 测试只有纯英文的 mustache 间文本应跳过
     * 例如：{{ "hello" }} world {{ "foo" }}
     */
    fun testVueEnglishPlainTextBetweenMustachesShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ "hello" }} world {{ "foo" }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 英文纯文本不应被提取
        assertFalse(
            "English plain text 'world' should not be extracted, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("world")
        )
    }

    /**
     * 测试 v-html 中的中文（属性值，非模板文本）
     * v-html 的值是字符串字面量，会整体被提取
     */
    fun testVueVHtmlAttributeString() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div v-html="'<span>你好</span>'"></div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // v-html 的值是字符串字面量，整个字符串内容会被提取（包含 HTML 标签）
        assertTrue(
            "Should extract string from v-html attribute containing Chinese, got: ${processor.extractedStrings}",
            processor.extractedStrings.any { it.value.contains("你好") }
        )
    }

    /**
     * 测试 v-text 中的中文
     */
    fun testVueVTextAttributeString() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div v-text="'欢迎使用'"></div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should extract '欢迎使用' from v-text attribute, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("欢迎使用")
        )
    }

    /**
     * 测试自定义事件处理函数中的字符串参数
     * 例如：@click="handleClick('提交')"
     */
    fun testVueEventHandlerStringArg() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <button @click="handleClick('提交', '成功')">按钮</button>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should extract '提交' from event handler, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("提交")
        )
        assertTrue(
            "Should extract '成功' from event handler, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("成功")
        )
        assertTrue(
            "Should extract '按钮' from text, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("按钮")
        )
    }

    /**
     * 测试 class 绑定中的中文
     * 例如：:class="isActive ? '激活类' : '默认类'"
     */
    fun testVueClassBindingTernary() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div :class="isActive ? '激活类' : '默认类'">内容</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should extract '激活类' from class binding, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("激活类")
        )
        assertTrue(
            "Should extract '默认类' from class binding, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("默认类")
        )
        assertTrue(
            "Should extract '内容' from text, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("内容")
        )
    }

    /**
     * 测试三目表达式中已有 $t() 调用（反引号版本）应跳过
     */
    fun testVueTernaryWithExistingTBacktickShouldSkip() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ isVip ? ${'$'}t(\`会员专享\`) : ${'$'}t(\`普通用户\`) }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should be empty for existing \$t() with backtick in ternary, but got: ${processor.extractedStrings}",
            processor.extractedStrings.isEmpty()
        )
    }

    /**
     * 测试三目表达式中混合 $t() 和普通字符串
     * 只有普通字符串分支应该被提取
     */
    fun testVueTernaryMixedExistingTAndPlainString() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ isVip ? ${'$'}t('会员专享') : '普通用户' }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(
            "Should extract only 1 string (the plain one) from mixed ternary, got: ${processor.extractedStrings}",
            1,
            processor.extractedStrings.size
        )
        assertTrue(
            "Should contain '普通用户', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("普通用户")
        )
    }

    /**
     * 测试 slot 中的中文内容
     */
    fun testVueSlotContentChinese() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <Modal>
                    <template #header>
                        <span>标题文字</span>
                    </template>
                    <div>主体内容</div>
                </Modal>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should extract '标题文字' from slot, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("标题文字")
        )
        assertTrue(
            "Should extract '主体内容' from slot, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("主体内容")
        )
    }

    /**
     * 测试 v-for 循环中的中文
     * {{ item.name }} - 标签 中，变量表达式和后面的纯文本都会被处理
     */
    fun testVueVForWithChinese() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <ul>
                    <li v-for="item in list" :key="item.id">
                        {{ item.name }} - 标签
                    </li>
                </ul>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 文本中包含中文"标签"，至少应该被提取到（可能是纯文本形式或模板字符串占位形式）
        assertTrue(
            "Should extract text containing '标签' from v-for item, got: ${processor.extractedStrings}",
            processor.extractedStrings.any { it.value.contains("标签") }
        )
    }

    /**
     * 测试计算属性中的模板字符串 + mustache 混合
     * 例如：{{ `共${total}条记录` }}
     */
    fun testVueTemplateLiteralWithVariableInterpolation() {
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>{{ \`共${'$'}{total}条记录\` }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "Should extract template literal with Chinese, got: ${processor.extractedStrings}",
            processor.extractedStrings.any { it.value.contains("共") && it.value.contains("条记录") }
        )
    }
}
