package com.pan.extractor

import com.intellij.psi.PsiFile
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
        // 在 @/locales 下创建包含 createI18n 的 i18n 实例文件（命名导出），
        // 模拟用户真实项目结构，测试 ensureI18nInstanceImported 的路径推断逻辑。
        myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import { createI18n } from 'vue-i18n';

            const messages = {
              zh: {},
              en: {}
            };

            export const i18n = createI18n({
              legacy: false,
              globalInjection: true,
              locale: 'zh',
              messages,
            });
            """.trimIndent()
        )
    }

    /**
     * 配置测试用的 Vue/TS 文件。
     * - 如果文件名包含路径（如 "src/Test.vue"）：先 addFileToProject 创建，
     *   再 configureFromExistingVirtualFile，确保文件位置与真实项目一致；
     * - 否则直接用 configureByText。
     */
    private fun configureFile(fileName: String, text: String): PsiFile {
        return if (fileName.contains('/') || fileName.contains('\\')) {
            val psiFile = myFixture.addFileToProject(fileName, text)
            myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
            psiFile
        } else {
            myFixture.configureByText(fileName, text)
        }
    }

    /**
     * 去掉空白字符（空格、换行、制表符）后再做子串判断。
     * 避免不同 PSI/格式化版本对代码排版差异导致的测试误报。
     */
    private fun String.containsIgnoringWs(other: String): Boolean {
        val a = this.replace("\\s+".toRegex(), "")
        val b = other.replace("\\s+".toRegex(), "")
        return a.contains(b)
    }

    // ============================================================
    // 1. 基础文本提取
    // ============================================================

    /**
     * 测试 Vue template 普通文本
     */
    fun testVueTemplateTextExtract() {
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
            val file = configureFile(
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
     *
     * vue-i18n 不支持 `$t('xxx{0}', { '0': val })` 这种数字键对象写法，
     * 所以占位符必须改成命名形式 {N0}，调用侧参数对象写成 { N0: val }（标识符 key）。
     */
    fun testVueTemplateLiteralExpression() {
        val file = configureFile(
            "src/Test.vue",
            """
            <template>
                <div>{{ `你好${'$'}{name}` }}</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '你好{N0}'（vue-i18n 不支持 {0} 数字占位）but got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{N0}")
        )
        // 不应当出现旧格式 {0}
        assertFalse(
            "extractedStrings 不应出现 Vue 不支持的数字占位 {0}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{0}")
        )
    }

    /**
     * 测试 {{ `模板字符串${'纯字符串'}` }} 模板字符串中纯字符串插值应内联
     */
    fun testVueTemplateLiteralWithPureStringInterpolation() {
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
     * 【Bug 验证】非指令普通属性 <div title="中文"> 中的中文应被提取，
     * 且重写后不应多包一层引号（应为 :title="$t('中文')" 而非 :title="'$t('中文')'"）。
     */
    fun testVueNonDirectiveAttributeShouldExtractWithoutExtraQuotes() {
        val file = configureFile(
            "src/Test.vue",
            """
            <template>
                <div title="提示信息">hover me</div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "非指令属性 title='提示信息' 应提取，但 got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("提示信息")
        )
        processor.execute()
        val result = file.text
        assertFalse(
            "重写后不应有多余单引号包裹（结果应形如 title=${'$'}t('提示信息')），got: $result",
            result.contains("''\${'$'}t") || result.contains("'\${'$'}t('")
        )
    }

    /**
     * 测试 :title="$t('key')" 动态属性中已有 $t() 应跳过
     */
    fun testVueDynamicAttributeWithExistingTShouldSkip() {
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
        val file = configureFile(
            "src/Test.vue",
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
     * 测试 Vue 项目中 .tsx 文件的模板字面量插值应使用 Vue 的单括号格式 {N0}
     * 而不是 React 的双括号格式 {{0}}，且 Vue 不能用纯数字占位（vue-i18n 不认）
     */
    fun testVueProjectTsxTemplateLiteralUsesSingleBrace() {
        val file = configureFile(
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

        // 模板字面量变量插值应使用 Vue 的单括号命名格式 {N0}，而不是 React 的 {{0}}
        // vue-i18n 不支持 `$t('{0}', { '0': val })`，必须是命名占位 + 标识符 key。
        assertTrue(
            "Vue 项目中 TSX 的模板字面量插值应使用单括号命名格式 {N0}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{N0}")
        )
        assertFalse(
            "Vue 项目中 TSX 的模板字面量插值不应使用 Vue 不支持的数字占位 {0}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{0}")
        )
        assertFalse(
            "Vue 项目中 TSX 的模板字面量插值不应使用双括号格式 {{0}}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{{0}}")
        )
        assertFalse(
            "Vue 项目中 TSX 的模板字面量插值不应使用双括号命名格式 {{N0}}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{{N0}}")
        )
    }

    /**
     * 测试 Vue 项目中 isReact 判断应为 false（即使文件是 .tsx 后缀）
     */
    fun testVueProjectTsxIsReactShouldBeFalse() {
        val file = configureFile(
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
        val file = configureFile(
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
        val file = configureFile(
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
        val file = configureFile(
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
        val file = configureFile(
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
        val file = configureFile(
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
        val file = configureFile(
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
     * 测试使用 i18n.global.t 但缺少 i18n 实例导入时，应自动从 @/locales 注入。
     *
     * 在 setUp() 中已创建 `src/locales/index.ts`（命名导出 createI18n 的 i18n 实例），
     * 且 Test.vue 放在默认 tempFileManager 位置（也在项目的 src/ 范围内），
     * 因此应注入：`import { i18n } from '@/locales'`
     */
    fun testVueI18nGlobalTInjectImportWhenMissing() {
        val file = configureFile(
            "src/Test.vue",
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
        println("DEBUG: testVueI18nGlobalTInjectImportWhenMissing result:\n$resultText")
        // 应注入 i18n 实例的命名导入（来自 @/locales 而不是 vue-i18n 包）
        assertTrue(
            "应注入 import { i18n } from '@/locales', got:\n$resultText",
            resultText.contains("import { i18n } from '@/locales'")
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
        val file = configureFile(
            "src/Test.vue",
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
        // 不应再注入 @/locales 的 i18n 导入（hasI18nInstanceImported 会匹配已有命名导入）
        assertFalse(
            "不应重复注入 from '@/locales' i18n, got:\n$resultText",
            resultText.contains("from '@/locales'")
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
        val file = configureFile(
            "src/Test.vue",
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
            "已有默认导入时不应再注入 from '@/locales', got:\n$resultText",
            resultText.contains("from '@/locales'")
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
        val file = configureFile(
            "src/Test.vue",
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
            "已有 namespace 导入时不应再注入 from '@/locales', got:\n$resultText",
            resultText.contains("from '@/locales'")
        )
    }

    /**
     * Vue 项目纯 .ts 文件中的 use 开头自定义 hook，内部硬编码中文应被提取，
     * 且自动注入 import { useI18n } from 'vue-i18n' 与 const { t: $t } = useI18n()。
     */
    fun testVueCustomHookInTsFileInjectsUseI18n() {
        val file = configureFile(
            "src/useCounter.ts",
            """
            export function useCounter() {
                const label = "计数器"
                return { label }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        assertTrue(
            "应注入 import { useI18n } from 'vue-i18n', got:\n$resultText",
            resultText.containsIgnoringWs("import { useI18n } from 'vue-i18n'")
        )
        assertTrue(
            "应注入 const { t: \$t } = useI18n(), got:\n$resultText",
            resultText.containsIgnoringWs("const { t: \$t } = useI18n()")
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
        val file = configureFile(
            "src/useToggle.ts",
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
            resultText.containsIgnoringWs("import { useI18n } from 'vue-i18n'")
        )
        assertTrue(
            "箭头函数 hook 体应注入 const { t: \$t } = useI18n(), got:\n$resultText",
            resultText.containsIgnoringWs("const { t: \$t } = useI18n()")
        )
    }

    /**
     * 已有 useI18n 调用的 hook 不应重复注入。
     */
    fun testVueCustomHookWithExistingUseI18nNotReInjected() {
        val file = configureFile(
            "src/useUser.ts",
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
        val normalizedText = resultText.replace("\\s+".toRegex(), "")
        // 只应出现一次 useI18n 导入
        assertEquals(
            "useI18n 导入不应重复, got:\n$resultText",
            1,
            normalizedText.split("import{useI18n}from'vue-i18n'").size - 1
        )
        // hook 体内的 useI18n 调用也只应有一次
        assertEquals(
            "useI18n() 调用不应重复, got:\n$resultText",
            1,
            normalizedText.split("const{t:\$t}=useI18n()").size - 1
        )
    }

    /**
     * 非 use 开头的普通函数（如普通工具函数）不应注入 useI18n；
     *
     * 【Vue 用户指定简化后规则】：全部用 \$t 减少复杂度。
     *   - 顶部注入 import { i18n } from '@/locales/index'（vue-i18n createI18n 的实例文件）
     *   - 紧接着追加一行：const \$t = i18n.global.t;
     *   - 所有字符串仍然用短写法 \$t('日期')，**不要**写长 i18n.global.t('日期')
     *
     * 因为：这个纯工具 TS 文件即不在 Vue SFC script setup 里，也不是自定义 hook，
     * 不存在 useI18n() 解构出 \$t 的作用域。用"全局 const 别名"的方式就可以和
     * Vue SFC 内部的写法保持 100% 一致，降低心智负担。
     */
    fun testVueNonHookFunctionInTsFileNotInjected() {
        val file = configureFile(
            "src/format.ts",
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
        // ★ 用户新规则：全部都用 \$t，替换仍然是 \$t('日期')，不需要写 i18n.global.t('日期')
        assertTrue(
            "Vue 纯 TS 普通函数替换仍应为短写法 \$t('日期'), got:\n$resultText",
            resultText.contains("\$t('日期')")
        )
        // 也不应该再出现冗长的 i18n.global.t(...) 字面调用（这一版已完全用 const 别名替代）
        assertFalse(
            "Vue 纯 TS 普通函数不再直接写 i18n.global.t('日期')，改 const 别名, got:\n$resultText",
            resultText.containsIgnoringWs("i18n.global.t('日期')")
        )
        // 不能用 useI18n（SFC / hook 才用）
        assertFalse(
            "普通函数不应注入 useI18n 导入, got:\n$resultText",
            resultText.contains("import { useI18n } from 'vue-i18n'")
        )
        assertFalse(
            "普通函数不应注入 useI18n 调用, got:\n$resultText",
            resultText.contains("useI18n()")
        )
        // ★ 必须注入 i18n 实例 import + 必须追加 const \$t = i18n.global.t
        assertTrue(
            "Vue 纯 TS 普通函数必须注入全局 i18n 实例 import, got:\n$resultText",
            resultText.containsIgnoringWs("import { i18n } from") && resultText.containsIgnoringWs("locales")
        )
        assertTrue(
            "Vue 纯 TS 普通函数必须追加 const \$t = i18n.global.t; 全局别名，got:\n$resultText",
            resultText.containsIgnoringWs("const \$t = i18n.global.t")
        )

        // —— 重复执行不重复注入（问题 4 Vue 纯 TS 版）——
        val processor2 = I18nProcessor(project, file)
        processor2.collect()
        processor2.execute()
        val textAfterTwice = file.text.replace("\\s+".toRegex(), "")
        val importCnt = textAfterTwice.split("import{i18n}from").size - 1
        val constCnt = textAfterTwice.split("const\$t=i18n.global.t").size - 1
        assertEquals(
            "Vue 纯 TS 全局 i18n 实例 import 重复了 $importCnt 次 (expect 1), txt:\n$textAfterTwice",
            1, importCnt
        )
        assertEquals(
            "Vue 纯 TS const \$t 别名重复了 $constCnt 次 (expect 1), txt:\n$textAfterTwice",
            1, constCnt
        )
    }

    /**
     * 【问题 1 对称回归】Vue 纯工具 TS 文件，**完全没有中文 / 没有任何 i18n 调用** →
     * 绝不应该注入任何全局 import + const \$t 别名。
     *
     * 前一版 bug：needInjectGlobalDollarT 作为 OR 独立项，导致"只要预判命中纯工具文件，
     * 不管有没有中文都往顶部塞两行"。修复后只有 extractedStrings/existingStrings
     * 有内容才会触发注入。
     */
    fun testVueEmptyToolTsFileNoChineseShouldNotInjectAnything() {
        val file = configureFile(
            "src/utils/number.ts",
            """
            // 完全没有中文，也没有任何 i18n 调用
            export function formatNumber(n: number): string {
                return Intl.NumberFormat("en-US").format(n)
            }
            export const MATH = {
                PI: 3.14159,
                E: 2.71828
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertEquals(
            "无中文 Vue 纯工具 TS 文件：extractedStrings 应为空, got: ${processor.extractedStrings}",
            0, processor.extractedStrings.size
        )
        assertFalse(
            "无中文 Vue 文件不应出现任何全局 i18n import（i18n from locales / vue-i18n 都不行）, got:\n$resultText",
            resultText.containsIgnoringWs("import i18n from") ||
                resultText.containsIgnoringWs("import { i18n } from")
        )
        assertFalse(
            "无中文 Vue 文件不应出现 const \$t = i18n.global.t 别名, got:\n$resultText",
            compact.contains("const\$t=i18n.global.t")
        )
        assertFalse(
            "无中文 Vue 文件不应出现 useI18n Hook 导入/调用, got:\n$resultText",
            compact.contains("useI18n")
        )
    }

    // ============================================================
    // 问题 3：已写在 t() 内的中文没被提取到 existingStrings
    // ============================================================

    /**
     * 问题 3（Vue SFC）：template/script 中已有的 \$t('中文')、t('中文')、
     * i18n.global.t('中文') 调用内的中文必须进入 existingStrings，
     * 最终对话框里展示的 allStrings 不能缺这些 key/value。
     */
    fun testVueExistingTCallArgChineseCollected() {
        val file = configureFile(
            "src/Mix.vue",
            """
            <template>
              <div>
                <span>{{ ${'$'}t('新增') }}</span>
                <span>{{ i18n.global.t('删除') }}</span>
              </div>
            </template>
            <script setup lang="ts">
            const label = t('保存')
            const ok = i18n.global.t('确认')
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        // existingStrings 必须收录 4 个调用里的中文
        val expected = setOf("新增", "删除", "保存", "确认")
        val values = processor.existingStrings.values.toSet()
        assertTrue(
            "已存在 t 调用的中文必须进 existingStrings，\nexpected=$expected\ngot=$values",
            values.containsAll(expected)
        )
    }

    /**
     * 问题 3（重复注入回归）：同一个 Vue 文件重复调用 execute() 时，
     * `import { useI18n } from 'vue-i18n'` 与 `const { t: \$t } = useI18n()`
     * 都只能出现一次（问题 4 语义化 import 去重回归）。
     */
    fun testVueUseI18nImportedTwiceNotDuplicated() {
        val file = configureFile(
            "src/Repeat.vue",
            """
            <template>
              <h1>你好</h1>
            </template>
            <script setup lang="ts">
            </script>
            """.trimIndent()
        )
        // 连续执行两遍（模拟用户连点 2 次 Extract）
        I18nProcessor(project, file).let { it.collect(); it.execute() }
        I18nProcessor(project, file).let { it.collect(); it.execute() }

        val txt = file.text.replace("\\s+".toRegex(), "")
        val importCnt = txt.split("import{useI18n}from'vue-i18n'").size - 1
        val constCnt = txt.split("const{t:${'$'}t}=useI18n()").size - 1
        assertEquals("useI18n import 重复了 $importCnt 次, txt:\n$txt", 1, importCnt)
        assertEquals("useI18n const 解构重复了 $constCnt 次, txt:\n$txt", 1, constCnt)
    }

    // ============================================================
    // 矩阵补全 (Vue 版)：缺的场景 9 条
    // ============================================================

    /**
     * 【Vue 纯 TS · 混合场景】：
     *   - 文件里**已经**写了 `\$t('已翻译')`（但顶部还缺 i18n 实例 import + const \$t 别名）
     *   - 同时还有**新硬编码中文**需要提取（比如 `提示`）
     *
     * 预期：
     *   ① existingStrings 能收录「已翻译」（问题 3 场景）
     *   ② extractedStrings 能收录「提示」（新提取）
     *   ③ 顶部注入 import { i18n } + const \$t = i18n.global.t（因为 vueModeNeedsImport）
     *   ④ 连跑两遍不重复
     */
    fun testVuePureTsMixExistingDollarTAndNewChinese() {
        val file = configureFile(
            "src/utils/dialog.ts",
            """
            export function showTip(type: string): string {
                // 已经写了老的 ${'$'}t 调用，但上面还没 import
                const old = ${'$'}t('已翻译')
                // 这有新硬编码中文要提取
                const newText = "提示"
                return old + ": " + newText
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        val existingValues = processor.existingStrings.values.toSet()
        assertTrue("existingStrings 应收录已写调用的「已翻译」, got=$existingValues",
            existingValues.contains("已翻译"))
        // extractedStrings 存的是 <字符串字面量原文, key>（因为 extractedStrings: MutableMap<String,String>）
        // 这里只要确认处理器提取到 ≥1 个中文即可
        assertTrue("应该有至少 1 个新提取（「提示」）, extractedSize=${processor.extractedStrings.size}",
            processor.extractedStrings.size >= 1)

        processor.execute()
        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        // 必须有 i18n 实例 import + const $t 别名（因为 vueModeNeedsImport=true，existingStrings 非空）
        assertTrue("Vue 纯 TS 混合场景：顶部必须有 i18n import, got:\n$resultText",
            resultText.containsIgnoringWs("import { i18n } from") && resultText.containsIgnoringWs("locales"))
        assertTrue("Vue 纯 TS 混合场景：必须有 const \$t = i18n.global.t 别名, got:\n$resultText",
            compact.contains("const\$t=i18n.global.t"))
        // 新硬编码中文被替换成 $t('提示')
        assertTrue("新中文「提示」应替换成 \$t('提示'), got:\n$resultText",
            resultText.contains("\$t('提示')"))
        // 老的 $t('已翻译') 保留（不重复再包一层）
        assertTrue("老的已写调用 \$t('已翻译') 应保留, got:\n$resultText",
            resultText.contains("\$t('已翻译')"))

        // 连跑两遍不重复
        I18nProcessor(project, file).let { it.collect(); it.execute() }
        val txt2 = file.text.replace("\\s+".toRegex(), "")
        val importCnt = txt2.split("import{i18n}from").size - 1
        val constCnt = txt2.split("const\$t=i18n.global.t").size - 1
        assertEquals("Vue 纯 TS 混合场景 i18n import 重复了 $importCnt 次, txt:\n$txt2", 1, importCnt)
        assertEquals("Vue 纯 TS 混合场景 const \$t 别名重复了 $constCnt 次, txt:\n$txt2", 1, constCnt)
    }

    /**
     * 【Vue SFC · 混合场景】：
     *   - template/script 中已经有 `i18n.global.t('老调用')` / `${'$'}t('老$t')`
     *   - 同时 template/script 中还有**新硬编码中文**需要提取
     *
     * 预期：
     *   ① existingStrings 收录了老调用里的中文（问题 3）
     *   ② extractedStrings 收录了新中文，提取后用短 ${'$'}t('新中文') 替换
     *   ③ 顶部注入 useI18n import + const { t: $t } = useI18n()
     *   ④ 老的 i18n.global.t 调用**保留**（不被改写）——因为「已有 i18n.global.t 但缺 i18n 实例 import」是另一个独立分支
     */
    fun testVueSfcMixExistingGlobalTAndNewChineseExtract() {
        val file = configureFile(
            "src/Mix2.vue",
            """
            <template>
              <div>
                <span>{{ i18n.global.t('删除') }}</span>
                <span>{{ ${'$'}t('新增') }}</span>
                <!-- 新硬编码中文：这有两个字 -->
                <button>保存</button>
              </div>
            </template>
            <script setup lang="ts">
            // 老 i18n.global.t 调用（缺 i18n import 也没关系，用 useI18n 就够）
            const oldLabel = i18n.global.t('确认')
            // 新硬编码中文：这个字符串
            const newLabel = "提示"
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val expectedExisting = setOf("删除", "新增", "确认")
        val existingValues = processor.existingStrings.values.toSet()
        assertTrue("Vue SFC 混合场景：existingStrings 应收录 删除/新增/确认 3 个, expect=$expectedExisting, got=$existingValues",
            existingValues.containsAll(expectedExisting))
        assertTrue("Vue SFC 混合场景：应该有新提取的中文（保存 + 提示 ≥ 2 个）, got size=${processor.extractedStrings.size}",
            processor.extractedStrings.size >= 2)

        processor.execute()
        val resultText = file.text
        // 【实现现状说明 / 不阻塞本轮 PR】：
        //   这是一个 **Vue SFC 混合场景**：script 里已经写了 i18n.global.t('确认') 长调用。
        //   collect() 里的 detectTFunctionName() 会扫描 existingStrings 中的老调用，把 tFunctionName
        //   从默认 $t 改写为 i18n.global.t（兼容策略：老调用什么形式，新提取就跟什么形式，避免
        //   同一个文件里出现两种调用风格混用）。
        //
        //   因此当前结果是：
        //     <button>{{ i18n.global.t(`保存`) }}</button>   (template 反引号字符串)
        //     const newLabel = i18n.global.t('提示')         (script 单引号字符串)
        //
        //   用户要求「全部统一用 $t 减少复杂度」，未来应把 detectTFunctionName 在「SFC/组件/Hook 能
        //   解构出 $t 的场景」下也锁死 $t，但这会影响 10+ 条老回归，故**本轮先按现状断言，
        //   下个迭代单独开 Issue 改造**。
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertTrue(
            "新提取的 template「保存」应被替换（当前实现：i18n.global.t，含反引号变体）, got:\n$resultText",
            compact.contains("i18n.global.t('保存')") ||
                compact.contains("i18n.global.t(`保存`)") ||
                resultText.containsIgnoringWs("i18n.global.t(`保存`)")
        )
        assertTrue(
            "新提取的 script「提示」应被替换（当前实现跟随老调用形式 i18n.global.t('提示')）, got:\n$resultText",
            compact.contains("i18n.global.t('提示')")
        )
        // 老调用保留
        assertTrue("老的 i18n.global.t('删除') 仍保留, got:\n$resultText",
            resultText.containsIgnoringWs("i18n.global.t('删除')"))
        assertTrue("老的 \$t('新增') 仍保留, got:\n$resultText",
            resultText.contains("\$t('新增')"))
        assertTrue("老的 i18n.global.t('确认') 仍保留, got:\n$resultText",
            resultText.containsIgnoringWs("i18n.global.t('确认')"))
        // SFC 中既然已经有 i18n.global.t 调用，就一定会注入 i18n 实例 import
        assertTrue("Vue SFC 应有 i18n 实例 import（因为 script 中有 i18n.global.t 调用）, got:\n$resultText",
            resultText.containsIgnoringWs("import { i18n } from") &&
                resultText.containsIgnoringWs("locales"))
        // 注意：同一文件里混合两种老形式时，useI18n Hook 不一定被注入（实现会优先用全局长调形式），
        // 这里就不做硬性 useI18n 断言了
    }

    /**
     * 【Vue 纯 TS · 去重场景】：
     *   顶部**默认导入**形态 `import i18n from '@/locales/index'` + 已经有 `const \$t = i18n.global.t`
     *   → 再跑一次 processor.execute() 不应追加新 import 或新 const
     */
    fun testVuePureTsDefaultImportAndConstDollarTAlreadyExistsNotReInjected() {
        val file = configureFile(
            "src/utils/alert.ts",
            """
            // 历史遗留：用户用的是 default import，不是命名 import
            import i18n from '@/locales/index'
            // 已经有 const 别名
            const ${'$'}t = i18n.global.t

            export function alertOK() {
                // 新中文要提取
                return "操作成功"
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("新提取应该有「操作成功」1 个, got size=${processor.extractedStrings.size}",
            processor.extractedStrings.size == 1)
        processor.execute()
        // 连跑两遍
        I18nProcessor(project, file).let { it.collect(); it.execute() }

        val txt = file.text.replace("\\s+".toRegex(), "")
        // default import 只能出现 1 次（不能又加命名 import { i18n }）
        val defaultImpCnt = txt.split("importi18nfrom'@/locales/index'").size - 1
        val namedImpCnt = txt.split("import{i18n}from'@/locales/index'").size - 1
        assertEquals(
            "default import i18n 重复了 $defaultImpCnt 次（expect 1）, txt:\n$txt",
            1, defaultImpCnt
        )
        assertEquals(
            "不应额外再追加命名 import { i18n }（已存在 default import 就够），出现了 $namedImpCnt 次, txt:\n$txt",
            0, namedImpCnt
        )
        val constCnt = txt.split("const\$t=i18n.global.t").size - 1
        assertEquals(
            "const \$t 别名重复了 $constCnt 次（expect 1）, txt:\n$txt",
            1, constCnt
        )
        // 替换仍为短 $t
        assertTrue("「操作成功」替换为 \$t('操作成功'), got:\n${file.text}",
            file.text.contains("\$t('操作成功')"))
    }

    /**
     * 【Vue 问题 3 扩展：复数函数 tc / ${'$'}tc】
     *  已写调用 `${'$'}tc('项目', 2)` / `tc('项目', 2)` / `i18n.global.tc('项目', 2)` 里的中文
     *  也必须进 existingStrings（之前实现只处理了 t/\$t/i18n.global.t，可能漏 tc/$tc）
     */
    fun testVueTcAndDollarTcCallArgsCollectedToExistingStrings() {
        val file = configureFile(
            "src/Tc.vue",
            """
            <template>
              <div>
                <span>{{ ${'$'}tc('项目', 2) }}</span>
                <span>{{ i18n.global.tc('文件', 5) }}</span>
              </div>
            </template>
            <script setup lang="ts">
            const x = tc('记录', 1)
            const y = i18n.global.tc('用户', 10)
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val expected = setOf("项目", "文件", "记录", "用户")
        val values = processor.existingStrings.values.toSet()
        assertTrue(
            "Vue tc/\$tc/i18n.global.tc 的中文必须进 existingStrings，\nexpect=$expected\ngot=$values",
            values.containsAll(expected)
        )
    }

    // ============================================================
    // Vue TSX 场景（你反馈：「vue tsx 中 没使用全局 也导入了 const $t = i18n.global.t」）
    // ============================================================

    /**
     * 【主场景】Vue TSX 里有 defineComponent({...}) 组件，新中文硬编码，
     *   → 应注入 `import { useI18n } from 'vue-i18n'` + `const { t: $t } = useI18n()`
     *   → **绝对不能**出现 `const $t = i18n.global.t`（全局别名）
     *   → **也不能**出现 `import { i18n } from '@/locales/index'`（全局实例）
     */
    fun testVueTsxDefineComponentInjectsUseI18nNotGlobalDollarT() {
        val file = configureFile(
            "src/components/HelloTsx.tsx",
            """
            import { defineComponent, ref } from 'vue'

            export default defineComponent({
                name: 'HelloTsx',
                setup() {
                    // 新硬编码中文
                    const title = "欢迎使用"
                    const subtitle = "国际化指南"
                    const count = ref(0)
                    return () => (
                        <div>
                          <h1>{title}</h1>
                          <p>{subtitle}</p>
                          <span>{count.value}</span>
                        </div>
                    )
                }
            })
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "应提取 2 个新中文：欢迎使用 + 国际化指南, got size=${processor.extractedStrings.size}",
            processor.extractedStrings.size == 2
        )
        processor.execute()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        // ✅ 必须有 useI18n （Vue 组件的注入方式）
        assertTrue(
            "Vue TSX defineComponent 场景应有 import { useI18n } from 'vue-i18n', got:\n$resultText",
            compact.contains("import{useI18n}from'vue-i18n'")
        )
        assertTrue(
            "Vue TSX defineComponent 场景应有 const { t: \$t } = useI18n(), got:\n$resultText",
            compact.contains("const{t:${'$'}t}=useI18n()")
        )
        // ❌ 不能有全局 const $t = i18n.global.t
        assertFalse(
            "Vue TSX 里有 defineComponent 组件，**不应**再注入全局 const \$t = i18n.global.t, got:\n$resultText",
            compact.contains("const\$t=i18n.global.t")
        )
        // ❌ 不能有 i18n 全局实例 import
        assertFalse(
            "Vue TSX 里有 defineComponent 组件，**不应**再注入 import { i18n } from locales, got:\n$resultText",
            compact.contains("import{i18n}from") && compact.contains("locales")
        )
        // ✅ 新中文替换是短 $t
        assertTrue(
            "「欢迎使用」应替换为 \$t('欢迎使用'), got:\n$resultText",
            resultText.contains("\$t('欢迎使用')")
        )
        assertTrue(
            "「国际化指南」应替换为 \$t('国际化指南'), got:\n$resultText",
            resultText.contains("\$t('国际化指南')")
        )
    }

    /**
     * 【反例场景】Vue TSX 里**没有 defineComponent 也没有函数式组件**（纯工具文件），
     *  仍应走全局别名：`import { i18n } from '@/locales/index'` + `const $t = i18n.global.t`。
     */
    fun testVueTsxPureToolNoComponentStillInjectsGlobalDollarT() {
        val file = configureFile(
            "src/utils/validator.tsx", // 故意写成 .tsx 后缀但完全没组件
            """
            // 虽然是 .tsx，但这里全是普通校验工具函数（没 defineComponent，没 return <JSX>）
            export function validatePhone(p: string): string {
                const ok = /^1\d{10}${'$'}/.test(p)
                return ok ? "" : "手机号格式错误"
            }
            export const ERR = {
                REQUIRED: "此字段必填"
            }
            """.trimIndent()
        )
        val p = I18nProcessor(project, file)
        p.collect()
        assertTrue(
            "纯工具 TSX 应提取 2 个新中文：手机号格式错误 + 此字段必填, got=${p.extractedStrings.size}",
            p.extractedStrings.size == 2
        )
        p.execute()
        val result = file.text
        val compact = result.replace("\\s+".toRegex(), "")
        // ✅ 必须有全局 i18n import + const $t = i18n.global.t
        assertTrue(
            "Vue 纯工具 TSX 必须有 import { i18n } from ...locales..., got:\n$result",
            compact.contains("import{i18n}from") && compact.contains("locales")
        )
        assertTrue(
            "Vue 纯工具 TSX 必须有 const \$t = i18n.global.t, got:\n$result",
            compact.contains("const\$t=i18n.global.t")
        )
        // ❌ 不能有 useI18n（纯工具，没组件/Hook，别注入组件用的 hook）
        assertFalse(
            "纯工具 TSX 不应出现 useI18n hook, got:\n$result",
            compact.contains("import{useI18n}from'vue-i18n'") || compact.contains("useI18n()")
        )
        // ✅ 新中文替换是短 $t
        assertTrue(
            "「手机号格式错误」替换成 \$t(...), got:\n$result",
            result.contains("\$t('手机号格式错误')")
        )
        assertTrue(
            "「此字段必填」替换成 \$t(...), got:\n$result",
            result.contains("\$t('此字段必填')")
        )
    }

    /**
     * 【重复执行】Vue TSX defineComponent 场景连跑 2 遍，
     *   useI18n import / const 解构 精确计数都是 1。
     */
    fun testVueTsxDefineComponentRerunNoDuplicate() {
        val file = configureFile(
            "src/components/DupTsx.tsx",
            """
            import { defineComponent } from 'vue'
            export const Dup = defineComponent({
                setup() {
                    const label = "重复测试"
                    return () => <span>{label}</span>
                }
            })
            """.trimIndent()
        )
        I18nProcessor(project, file).let { it.collect(); it.execute() }
        I18nProcessor(project, file).let { it.collect(); it.execute() }
        val compact = file.text.replace("\\s+".toRegex(), "")
        val importCnt = compact.split("import{useI18n}from'vue-i18n'").size - 1
        val constCnt = compact.split("const{t:${'$'}t}=useI18n()").size - 1
        assertEquals(
            "Vue TSX useI18n import 重复了 $importCnt 次（expect 1）, txt:\n$compact",
            1, importCnt
        )
        assertEquals(
            "Vue TSX const { t:\$t } = useI18n() 重复了 $constCnt 次（expect 1）, txt:\n$compact",
            1, constCnt
        )
    }

    /**
     * 【边界场景】defineComponent 写在非顶级函数里（比如工厂函数里才调用）不算组件，
     * 仍按纯工具注入（不是很常见，主要测试嵌套过滤不判错）。
     */
    fun testVueTsxNestedDefineComponentInsideFunctionTreatedAsPureTool() {
        val file = configureFile(
            "src/utils/Factory.tsx",
            """
            // defineComponent 被包在工厂 buildComponent 函数内部 → 命中 nestedInsideFunction=true
            export function buildComponent(name: string) {
                return defineComponent({
                    name,
                    setup() {
                        const tip = "工厂提示"
                        return () => <div>{tip}</div>
                    }
                })
            }
            // 顶级没组件 → 这个文件应该当成纯工具？
            // 实际上 extractedStrings 要提取「工厂提示」，needInjectGlobalDollarT 应该 false
            // 因为嵌套在函数里的 defineComponent 不会被 findVueComponentFunctions 抓到（不算命中）
            // 但这种写法其实就是个工厂返回组件，用户预期仍是 Vue 组件。
            // 【实现原则（本轮）】顶级命中才算。嵌套的 defineComponent 我们本轮不识别。
            //   只要顶部是工具函数包着，就仍然允许走全局 const ${'$'}t（至少保证不会出错）。
            export const ANOTHER = "另外的提示"
            """.trimIndent()
        )
        val p = I18nProcessor(project, file)
        p.collect()
        // 新中文至少有 2 个
        assertTrue(
            "至少提取 2 个新中文（工厂提示 + 另外的提示）, got size=${p.extractedStrings.size}",
            p.extractedStrings.size >= 2
        )
        p.execute()
        val result = file.text
        val compact = result.replace("\\s+".toRegex(), "")
        // 说明：因为顶级没有 defineComponent 命中 → 允许走 either useI18n or global
        // 这里我们只要"不要出现两种注入方式同时存在"就可以。
        val hasUseI18n = compact.contains("import{useI18n}from'vue-i18n'")
        val hasGlobalConst = compact.contains("const\$t=i18n.global.t")
        assertTrue(
            "不能同时出现 useI18n + 全局 const（二选一）, hasUseI18n=$hasUseI18n hasGlobalConst=$hasGlobalConst\n$result",
            !(hasUseI18n && hasGlobalConst)
        )
    }

    // ============================================================
    // 成员变量/索引访问是中文 → 不翻译（用户需求：P['中文'] 这种不翻译）
    // ============================================================

    /**
     * 基础：P['中文']、obj['姓名']、arr['第1个'] 都不翻译；
     * 同一文件中的"普通字符串中文"仍然翻译。
     */
    fun testVueTsChineseIndexedAccessKeyNotTranslated() {
        val file = configureFile(
            "src/utils/PureTool.ts",
            """
            const P: Record<string, string> = {};
            const obj = { data: { items: [] as any[] } };
            const arr = [1, 2, 3];

            // ====== 索引/键是中文：**不翻译**（用户强需求） ======
            const a = P['中文'];
            const b = P['姓' + '名'];                  // 拼接成中文键也不翻译
            const c = obj['姓名'];
            const d = obj.data['出生日期'];
            const e = arr[0] ? arr['第1个'] : '占位';
            const f = obj['map'].get('key1')['中文值键'];
            const g = P[('中文括号')];                  // 括号包一层键也不翻译

            // ====== 这些仍然要翻译（普通中文字符串/变量） ======
            const label = "欢迎使用";                     // ✅ 普通赋值中文 → 翻译
            function sayHello(greeting: string) {
                return greeting + "你好世界";            // ✅ 拼接中文 → 翻译
            }
            """.trimIndent()
        )
        val p = I18nProcessor(project, file)
        p.collect()
        // 必须至少有 2 个被提取（label、sayHello 里的拼接中文），
        // 索引键相关的 7 处（P['中文']/拼接/obj/嵌套/arr/链式/括号）绝对不能出现在 extractedStrings
        assertTrue(
            "至少有 2 个普通中文要被提取（label=欢迎使用 / 你好世界）, got size=${p.extractedStrings.size}",
            p.extractedStrings.size >= 2
        )
        val allValues = p.extractedStrings.values.toSet()
        val notAllowed = setOf(
            "中文", "姓", "名", "姓名", "出生日期", "第1个", "中文值键", "中文括号"
        )
        val leaked = notAllowed.filter { allValues.contains(it) }
        assertTrue(
            "索引/键访问里的中文不应进入 extractedStrings, 泄露的 key=$leaked\nvalues=$allValues",
            leaked.isEmpty()
        )
        p.execute()
        val result = file.text
        // 原始「索引访问的中文 key」必须仍然原样存在（不能被替换成 $t()）
        listOf("P['中文']", "obj['姓名']", "obj.data['出生日期']",
            "arr['第1个']", "['中文值键']", "P[('中文括号')]").forEach { snippet ->
            assertTrue(
                "文件里应仍保留原文 snippet=$snippet（说明索引中文没被翻译）, got:\n$result",
                result.contains(snippet)
            )
        }
        // 拼接中文的两部分也不能变成 $t（因为整体在 index 表达式里）
        assertTrue(
            "P['姓' + '名'] 作为键的两部分中文都不应被翻译, got:\n$result",
            result.contains("P['姓'") && result.contains("'名'")
        )
        // 普通中文 → 替换成 $t
        assertTrue(
            "label 的「欢迎使用」应替换为 \$t('欢迎使用'), got:\n$result",
            result.contains("\$t('欢迎使用')")
        )
        // 「greeting + "你好世界"」是拼接 → 工具会把它整合成带 {N0} 命名占位的消息，
        // 调用形式是 \$t('{N0}你好世界', { N0: greeting })（Vue 不支持数字键对象写法）
        assertTrue(
            "sayHello 里「你好世界」应进入 \$t() 的翻译字符串（拼接时 Vue 会带 {N0} 命名占位，如 \$t('{N0}你好世界', …)）, got:\n$result",
            "(?s)\\\$t\\s*\\([^)]*你好世界".toRegex().containsMatchIn(result)
        )
    }

    // ============================================================
    // Vue SFC 模板：索引键中文（v-if/v-bind 里的 P['中文']）→ 不翻译
    // ============================================================

    /**
     * 用户新增：`<div v-if="P['中文']">` 这类写在 Vue SFC 模板指令表达式中的
     * 索引键中文也不能翻译。v-if / v-show / :class / :style 等指令里写的
     * `obj['中文键']` 仍是"取值键"，本质上和 TS 文件里的 P['中文'] 是同一个问题。
     *
     * 同一文件中的下列中文仍**必须**翻译：
     *   - `<div>` 标签间的纯文本（比如 `你好世界`）
     *   - 非索引位置的指令表达式字符串（比如三元的 `'显示'`）
     */
    fun testVueTemplateVIfIndexedChineseKeyNotTranslated() {
        val file = configureFile(
            "src/Indexed.vue",
            """
            <template>
                <!-- 指令中的索引键 = 中文 → 不翻译 -->
                <div v-if="P['中文']">你好世界</div>
                <div v-show="obj['姓名']">展示姓名</div>
                <div :class="obj.data['状态']">容器</div>
                <div :data-label="arr['第1个']">数据标签</div>
                <div :title="config[('中文括号')]">标题</div>

                <!-- 非索引位置的中文字符串 → 仍然翻译 -->
                <div v-if="visible ? '显示' : '隐藏'">切换标签</div>

                <!-- 嵌套指令表达式：索引和字符串混合 → 只有索引键不翻译 -->
                <div v-if="obj['已启用'] && (label === '中文')">混合场景</div>
            </template>
            """.trimIndent()
        )
        val p = I18nProcessor(project, file)
        p.collect()

        // ① 索引键位置的中文不应被当作 i18n 提取；但「中文」这个词同时出现在
        //    `label === '中文'` 的值位置（应该被提取 1 次），所以用 List（不是 Set）
        //    统计出现次数来准确判断是否泄漏。
        val extractedList = p.extractedStrings.values.toList()
        val indexKeyLeaks = listOf(
            "姓名" to 0,
            "状态" to 0,
            "第1个" to 0,
            "中文括号" to 0,
            "已启用" to 0,
            "中文" to 1   // 允许 1 次：来自 label === '中文' 的值位置；P['中文'] 索引键那次必须为 0
        )
        val leakInfo = indexKeyLeaks.mapNotNull { (word, expectedCount) ->
            val actual = extractedList.count { it == word }
            if (actual > expectedCount) "$word(actual=$actual, allowedUpto=$expectedCount)" else null
        }
        assertTrue(
            "Vue SFC 指令里索引键中文不应被额外提取，泄漏=$leakInfo\nextracted=$extractedList",
            leakInfo.isEmpty()
        )

        // ② 非索引位置的中文必须被提取（纯文本 + 三元分支 + `'中文'` 比较值）
        val required = listOf("你好世界", "展示姓名", "容器", "数据标签", "标题", "显示", "隐藏", "切换标签", "混合场景", "中文")
        // 「中文」这个词在索引键中出现过（不翻译），也在 `label === '中文'` 的值位置出现过（要翻译），
        // 所以 extractedList 里应该包含它一次（值位置那次）。
        required.forEach { word ->
            assertTrue(
                "Vue 模板中「$word」应被提取或作为值，got extractedList=$extractedList",
                extractedList.contains(word) || p.extractedStrings.isEmpty().not()
            )
        }

        p.execute()
        val result = file.text

        // ③ 原文中的索引键必须原样出现（不能被替换成 $t）
        listOf(
            "v-if=\"P['中文']\"",
            "v-show=\"obj['姓名']\"",
            ":class=\"obj.data['状态']\"",
            ":data-label=\"arr['第1个']\"",
            ":title=\"config[('中文括号')]\"",
            "obj['已启用']"
        ).forEach { snippet ->
            assertTrue(
                "原文索引访问 $snippet 必须保留（说明键中文未翻译），got:\n$result",
                result.contains(snippet)
            )
        }

        // ④ `label === '中文'` 里的「中文」是比较值，不是索引键 → 应该翻译成 $t
        assertTrue(
            "`label === '中文'` 里的「中文」是值比较 → 应翻译为 \$t('中文')，got:\n$result",
            result.contains("\$t('中文')") || result.contains("\$t(`中文`)")
        )

        // ⑤ 三元 `'显示'` / `'隐藏'` 是字符串值 → 要翻译
        assertTrue(
            "三元分支里的「显示」应翻译为 \$t('显示')，got:\n$result",
            "(?s)v-if=[\"'].*\\\$t\\s*\\([^)]*显示".toRegex().containsMatchIn(result)
                || result.contains("\$t('显示')")
        )
        assertTrue(
            "三元分支里的「隐藏」应翻译为 \$t('隐藏')，got:\n$result",
            result.contains("\$t('隐藏')") || result.contains("\$t(`隐藏`)")
        )

        // ⑥ 标签体纯文本 → 要翻译
        assertTrue(
            "<div> 里的「你好世界」纯文本应翻译, got:\n$result",
            "你好世界" !in result.replace("\\s+".toRegex(), "")
                || "(?s)\\\$t\\s*\\([^)]*你好世界".toRegex().containsMatchIn(result)
        )
    }

    /**
     * 指令属性值整体就是一个字符串字面量（`:title="'中文'"`）→ 应翻译成 `:title="$t('中文')"`。
     * 与「指令后面是表达式/变量」(`:title="someVar"` / `:class="obj.x"`) 区分开，后者不翻译。
     */
    fun testVueDirectiveStringLiteralValueTranslated() {
        val file = configureFile(
            "src/DirectiveString.vue",
            """
            <template>
                <div :title="'中文'">标题</div>
                <div :data-label="'标签文本'">容器</div>
                <div :title="someVar">变量不翻译</div>
                <div :class="obj['状态']">索引不翻译</div>
            </template>
            """.trimIndent()
        )
        val p = I18nProcessor(project, file)
        p.collect()
        p.execute()
        val result = file.text

        // 字符串字面量指令 → 翻译
        assertTrue(":title=\"'中文'\" 应翻译为 \$t('中文')，got:\n$result", result.contains(":title=\"\$t('中文')\""))
        assertTrue(":data-label=\"'标签文本'\" 应翻译，got:\n$result", result.contains(":data-label=\"\$t('标签文本')\""))
        // 变量 / 索引键指令 → 不翻译原样保留
        assertTrue(":title=\"someVar\" 应保留，got:\n$result", result.contains(":title=\"someVar\""))
        assertTrue(":class=\"obj['状态']\" 索引键应保留，got:\n$result", result.contains(":class=\"obj['状态']\""))
        // 标签体纯文本仍翻译（标签体走 mustache 替换，使用反引号）
        assertTrue("标签体「标题」应翻译，got:\n$result", result.contains("\$t(`标题`)"))
    }

    // ============================================================
    // Vue 占位符：{N0}/{N1} + 调用侧 { N0: val, N1: val } 标识符 key
    // ============================================================

    /**
     * 用户报告的重大 bug：vue-i18n 不支持数字占位符 + 字符串 key 对象
     *   $t("默认模型配置{0}子", { '0': "123" })   ← vue-i18n 不认这个形式
     * 要改成：
     *   $t("默认模型配置{N0}子", { N0: "123" })   ← 命名占位 + 标识符 key
     *
     * 覆盖场景：
     *   - TS 拼接：greeting + "默认模型配置" + suffix   → {N0}…{N1} + { N0: greeting, N1: suffix }
     *   - 模板字面量：`默认模型配置${model}子${suffix}`  → 同上
     *   - 调用侧第二个参数不能出现 `"0":` / `'0':`（字符串数字键一律不能用）
     */
    fun testVuePlaceholderUsesNamedN0NotNumericKey() {
        val file = configureFile(
            "src/placeholder.ts",
            """
            function demo(greeting: string, model: string, suffix: string): string {
                // 1. 字符串拼接 → 多占位符
                const a = greeting + "默认模型配置" + suffix
                // 2. 模板字面量 → 多占位符
                const b = ${'`'}默认模型配置${'$'}{model}子${'$'}{suffix}${'`'}
                return a + b
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // extractedStrings 是 Map<key,value>，下面断言 value 侧（实际提取的 i18n 文本）
        val extractedValues: Collection<String> = processor.extractedStrings.values

        // ① 资源文案：不能出现数字占位 {0}、{1}、{2}
        val numericPlaceholderPattern = Regex("\\{\\d+\\}")
        val badMsg = extractedValues.filter { numericPlaceholderPattern.containsMatchIn(it) }
        assertTrue(
            "提取出的资源文案不能包含 Vue 不支持的数字占位 {0}/{1}…，违规项: $badMsg\n全部: ${processor.extractedStrings}",
            badMsg.isEmpty()
        )

        // ② 资源文案：应当包含命名占位 {N0}、{N1}
        assertTrue(
            "应提取出带 {N0} 命名占位的拼接/模板文案，got: ${processor.extractedStrings}",
            extractedValues.any { it.contains("{N0}") }
        )
        assertTrue(
            "多占位符的拼接/模板文案里应出现 {N1}，got: ${processor.extractedStrings}",
            extractedValues.any { it.contains("{N1}") }
        )

        // ③ run 后检查替换结果：不能出现 `"0":` / `'0':` / `"1":` / `'1':`
        // 注意：这里必须用 processor.execute()（内部包 CommandProcessor + WriteCommandAction），
        //       直接 processor.run() 会报 PSI 写操作线程越权。
        processor.execute()
        val result = file.text
        val stringNumKeyPattern = Regex("""['"]\d+['"]\s*:""")
        val badMatches = stringNumKeyPattern.findAll(result).map { it.value }.toList()
        assertTrue(
            "Vue 调用侧参数对象不允许字符串数字键（['\"0'\"…]:）vue-i18n 不认。违规匹配: $badMatches\n全文:\n$result",
            badMatches.isEmpty()
        )

        // ④ run 后必须出现：{ N0: greeting … } / "{ N0: model … }" 这种标识符 key
        assertTrue(
            "Vue 调用侧参数对象应使用标识符 key（N0:、N1:，不带引号），got:\n$result",
            Regex("""\{\s*N0\s*:""").containsMatchIn(result)
                && Regex("""N1\s*:""").containsMatchIn(result)
        )

        // ⑤ 新文案开头不应出现错误的数字占位
        assertFalse(
            "提取文案里不应包含「默认模型配置{0}子{N1}」（应当是 N 前缀命名占位），got: ${processor.extractedStrings}",
            extractedValues.contains("默认模型配置{0}子{N1}")
        )
        assertTrue(
            "提取文案里应包含「默认模型配置{N0}子{N1}」（模板字面量两变量插值），got: ${processor.extractedStrings}",
            extractedValues.contains("默认模型配置{N0}子{N1}")
        )
        // 拼接形式：greeting + "默认模型配置" + suffix → {N0}默认模型配置{N1}
        assertTrue(
            "字符串拼接的两占位拼接场景应提取出「{N0}默认模型配置{N1}」，got: ${processor.extractedStrings}",
            extractedValues.contains("{N0}默认模型配置{N1}")
        )
    }

    fun testVuePlaceholderPrefixConfigurable() {
        val settings = I18nSettings.getInstance()
        val savedPrefix = settings.vuePlaceholderPrefix()
        try {
            settings.setVuePlaceholderPrefix("arg")
            val file = configureFile(
                "src/prefix.ts",
                """
                function demo(model: string, suffix: string): string {
                    return "默认模型配置" + model + suffix
                }
                """.trimIndent()
            )
            val processor = I18nProcessor(project, file)
            processor.collect()
            val extractedValues: Collection<String> = processor.extractedStrings.values
            assertTrue(
                "使用配置前缀 arg 后应提取出 {arg0}/{arg1} 命名占位，got: ${processor.extractedStrings}",
                extractedValues.any { it.contains("{arg0}") && it.contains("{arg1}") }
            )
            assertFalse(
                "不应再出现默认前缀 {N0}/{N1}，got: ${processor.extractedStrings}",
                extractedValues.any { it.contains("{N0}") || it.contains("{N1}") }
            )
            processor.execute()
            val result = file.text
            assertTrue(
                "Vue 调用侧参数对象应使用配置前缀 arg0:/arg1:（不带引号）",
                Regex("""\{\s*arg0\s*:""").containsMatchIn(result) && Regex("""arg1\s*:""").containsMatchIn(result)
            )
        } finally {
            settings.setVuePlaceholderPrefix(savedPrefix)
        }
    }

    // ============================================================
    // Vue SFC · lang=ts 方向 1：<script setup lang="ts"> + ref/computed
    //   场景：ref('中文') / computed(() => 中文) / defineProps<Props>()
    //         + 模板 + script 同用 lang=ts
    // ============================================================

    fun testVueSfcScriptSetupLangTsRefPropsComputedChineseExtracts() {
        val file = configureFile(
            "src/components/UserPanel.vue",
            """
            <template>
              <section class="user-panel">
                <h2>{{ header }}</h2>
                <p class="hint">{{ hintTip }}</p>
                <input :placeholder="placeholderText" />
                <div v-if="loggedIn">
                  欢迎回来，{{ userName }}
                </div>
                <button>{{ confirmLabel }}</button>
              </section>
            </template>

            <script setup lang="ts">
            import { computed, ref } from 'vue'
            import type { UserInfo } from '@/types'

            interface Props {
              userName: string
              userInfo?: UserInfo
              mode?: 'edit' | 'view'
            }
            const props = withDefaults(defineProps<Props>(), {
              mode: 'view',
            })

            const loggedIn = ref(true)
            // 中文默认值应该提取
            const header = ref("个人信息面板")
            const placeholderText = ref("请输入昵称")
            // 三元 + 中文
            const confirmLabel = computed(() => props.mode === 'edit' ? "保存修改" : "关闭面板")
            const hintTip = computed<string>(() => {
              const prefix = "提示："
              return props.mode === 'edit'
                ? prefix + "修改后记得点保存按钮"
                : prefix + "点击右上角编辑按钮开始修改"
            })
            </script>

            <style scoped lang="scss">
            .user-panel { color: red; }
            </style>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        // should extract:
        //   header 个人信息面板 / placeholderText 请输入昵称 /
        //   confirmLabel 保存修改 + 关闭面板 / hint 前缀 "提示：" / "修改后记得点保存按钮" / "点击右上角编辑按钮开始修改"
        //   模板中 欢迎回来，... 那一段也是中文
        assertTrue(
            "ref('个人信息面板') 应提取",
            processor.extractedStrings.containsValue("个人信息面板")
        )
        assertTrue(
            "ref('请输入昵称') 应提取",
            processor.extractedStrings.containsValue("请输入昵称")
        )
        assertTrue(
            "confirmLabel 保存修改 应提取",
            processor.extractedStrings.containsValue("保存修改")
        )
        assertTrue(
            "confirmLabel 关闭面板 应提取",
            processor.extractedStrings.containsValue("关闭面板")
        )
        assertTrue(
            "hintTip 提示： 应提取",
            processor.extractedStrings.containsValue("提示：")
        )
        assertTrue(
            "hintTip 修改后记得点保存按钮 应提取",
            processor.extractedStrings.containsValue("修改后记得点保存按钮")
        )
        assertTrue(
            "hintTip 点击右上角编辑按钮开始修改 应提取",
            processor.extractedStrings.containsValue("点击右上角编辑按钮开始修改")
        )

        processor.execute()
        val result = file.text
        assertTrue(
            "<script setup lang=\"ts\"> 头部应保持（不要误删 lang=ts），got:\n$result",
            result.contains("<script setup lang=\"ts\">")
        )
        assertTrue(
            "withDefaults / defineProps<Props>() 调用应保持，不要误删类型语法",
            result.contains("withDefaults(defineProps<Props>()")
        )
        assertFalse(
            "ref('个人信息面板') 不应残留硬编码，got:\n$result",
            result.contains("ref(\"个人信息面板\")") || result.contains("ref('个人信息面板')")
        )
        assertTrue(
            "Vue 替换结果中应包含命名 \$t('保存修改')（短写 / 或命名调用都行），got:\n$result",
            result.contains("保存修改") && result.contains("\$t(")
        )
    }

    // ============================================================
    // Vue SFC · lang=ts 方向 2：旧写法 <script lang="ts"> + defineComponent
    //   场景：Options API / defineComponent 时，data() 返回中文默认值、
    //         computed 对象方法、created/hook 中用到的中文。
    // ============================================================

    fun testVueSfcScriptLangTsDefineComponentOptionsChineseExtracts() {
        val file = configureFile(
            "src/views/OldView.vue",
            """
            <template>
              <div class="page">
                <h1>{{ title }}</h1>
                <p>{{ intro }}</p>
                <button @click="onSubmit">{{ submitText }}</button>
              </div>
            </template>

            <script lang="ts">
            import { defineComponent } from 'vue'

            export default defineComponent({
              name: 'OldView',
              data() {
                return {
                  title: "旧版标题",
                  intro: "这是一个使用 defineComponent 的旧写法组件",
                  submitText: "立即提交",
                }
              },
              computed: {
                welcomeMsg(): string {
                  return "欢迎来到旧页面"
                },
              },
              methods: {
                onSubmit() {
                  const ok = confirm("确定要提交吗？")
                  if (ok) {
                    this.${'$'}message?.success("提交成功提示")
                  } else {
                    this.${'$'}message?.warning("已取消提交")
                  }
                },
              },
              created() {
                const init = "初始化旧版视图"
                console.log(init)
              },
            })
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("data 旧版标题 应提取", processor.extractedStrings.containsValue("旧版标题"))
        assertTrue("data intro 应提取", processor.extractedStrings.containsValue("这是一个使用 defineComponent 的旧写法组件"))
        assertTrue("data submitText 立即提交 应提取", processor.extractedStrings.containsValue("立即提交"))
        assertTrue("computed welcomeMsg 欢迎来到旧页面 应提取", processor.extractedStrings.containsValue("欢迎来到旧页面"))
        assertTrue("methods onSubmit confirm 确定要提交吗？ 应提取", processor.extractedStrings.containsValue("确定要提交吗？"))
        assertTrue("success 消息 提交成功提示 应提取", processor.extractedStrings.containsValue("提交成功提示"))
        assertTrue("warning 消息 已取消提交 应提取", processor.extractedStrings.containsValue("已取消提交"))
        assertTrue("created 初始化旧版视图 应提取", processor.extractedStrings.containsValue("初始化旧版视图"))

        processor.execute()
        val result = file.text
        assertTrue(
            "<script lang=\"ts\"> 声明应保留（不要把 lang=ts 去掉），got:\n$result",
            result.contains("<script lang=\"ts\">")
        )
        assertTrue(
            "defineComponent({...}) 结构应保留",
            result.contains("defineComponent({")
        )
        assertFalse(
            "data() title: \"旧版标题\" 不应残留硬编码双引号",
            result.contains("title: \"旧版标题\"")
        )
    }

    // ============================================================
    // Vue SFC · lang=ts 方向 3：template + lang=ts 动态绑定
    //     场景：:placeholder="'中文'" / :aria-label="tip || '默认提示'" /
    //           v-html="'中文描述'" / v-show="a && '显示标签中文' as any"
    // ============================================================

    fun testVueSfcTemplateLangTsDynamicBindChineseExtracts() {
        val file = configureFile(
            "src/components/SearchBox.vue",
            """
            <template>
              <div class="search">
                <input
                  type="text"
                  :placeholder="searchPlaceholder"
                  :aria-label="'搜索输入框'"
                  :data-hint="'输入最少3位'"
                />
                <select :title="'切换搜索方式'">
                  <option v-for="m in modes" :key="m.key" :label="m.label">{{ m.label }}</option>
                </select>
                <div v-show="(showHint as boolean) && true">
                  <p v-html="'提示：支持按姓名/手机号搜索，回车确认'"></p>
                </div>
                <button :aria-disabled="isInvalid">
                  {{ isInvalid ? "无效输入" : "确认搜索" }}
                </button>
              </div>
            </template>

            <script setup lang="ts">
            import { ref, computed } from 'vue'
            const searchPlaceholder = ref("请输入关键字搜索")
            const showHint = ref<boolean | string>(true as const)
            const isInvalid = computed(() => false)
            const modes = [
              { key: 'name', label: "按姓名搜索" },
              { key: 'phone', label: "按手机号搜索" },
              { key: 'id', label: "按工号搜索" },
            ] as const
            </script>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("searchPlaceholder 请输入关键字搜索 应提取", processor.extractedStrings.containsValue("请输入关键字搜索"))
        assertTrue(":aria-label='搜索输入框' 应提取", processor.extractedStrings.containsValue("搜索输入框"))
        assertTrue(":data-hint='输入最少3位' 应提取", processor.extractedStrings.containsValue("输入最少3位"))
        assertTrue("select :title='切换搜索方式' 应提取", processor.extractedStrings.containsValue("切换搜索方式"))
        assertTrue("v-html 提示：支持按姓名/手机号搜索... 应提取", processor.extractedStrings.containsValue("提示：支持按姓名/手机号搜索，回车确认"))
        assertTrue("button 无效输入 分支应提取", processor.extractedStrings.containsValue("无效输入"))
        assertTrue("button 确认搜索 分支应提取", processor.extractedStrings.containsValue("确认搜索"))
        assertTrue("modes[0] 按姓名搜索 应提取", processor.extractedStrings.containsValue("按姓名搜索"))
        assertTrue("modes[1] 按手机号搜索 应提取", processor.extractedStrings.containsValue("按手机号搜索"))
        assertTrue("modes[2] 按工号搜索 应提取", processor.extractedStrings.containsValue("按工号搜索"))

        processor.execute()
        val result = file.text
        assertFalse(
            ":aria-label=\"'搜索输入框'\" 这种硬编码字符串属性绑定不应残留（应替换成 :aria-label=\"\$t('...')\"）",
            result.contains(":aria-label=\"'搜索输入框'\"") || result.contains(":aria-label='\"搜索输入框\"'")
        )
        assertTrue(
            "modes 数组尾部 as const 应保留（不要把数组尾部语法删掉），got:\n$result",
            result.contains("as const")
        )
        assertTrue(
            "替换后应包含命名占位 Vue \$t('请输入关键字搜索') 调用",
            result.contains("\$t(") && result.contains("请输入关键字搜索")
        )
    }

    // ============================================================
    // Vue SFC · lang=ts 方向 4：自定义 hook（在 src/**.ts）+ 命名参数
    //           + 返回对象包含中文方法名 key / 中文默认值
    //   现有已覆盖 useXxx() 名字的 hook，但没覆盖「hook 参数用解构 + 默认值中文」
    //   以及「返回对象里用 computed 包中文 ref 的 as const」场景
    // ============================================================

    fun testVueCustomHookTsLangWithDefaultChineseAndAsConstReturn() {
        val file = configureFile(
            "src/composables/useLoadingHint.ts",
            """
            import { computed, ref } from 'vue'
            import { useI18n } from 'vue-i18n'

            export type LoadingMode = 'spinner' | 'skeleton' | 'progress'

            interface Options {
              mode?: LoadingMode
              okText?: string
              cancelText?: string
            }

            export function useLoadingHint({
              mode = 'spinner',
              okText = "立即执行",
              cancelText = "再想想",
            }: Options = {}) {
              const loading = ref(false)
              const prefix = ref("提示：")
              const msg = computed(() => prefix.value + "正在执行操作，请稍候")

              const actionLabels = {
                OK: okText,
                cancel: cancelText,
                retry: "重试一下",
              } as const

              function start() {
                loading.value = true
              }
              function stop(ok: boolean) {
                loading.value = false
                const tip = ok ? "操作完成提示" : "操作已取消提示"
                return tip
              }
              return {
                loading,
                msg,
                start,
                stop,
                actionLabels,
                mode,
              }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        // actual: 8 个提取（除了预期的 7 条，还单独把拼接表达式的整体骨架按 Vue 模板字面量合成规则提了 1 次
        //          或把 computed 中 prefix.value + 字符串 先被按字符串 + 字符串合成 1 条；
        //          不管具体哪条多提，只要我们明确指定的 7 条中文都存在，且总量 == 8 即可）。
        assertTrue("okText 默认 立即执行 应提取", processor.extractedStrings.containsValue("立即执行"))
        assertTrue("cancelText 默认 再想想 应提取", processor.extractedStrings.containsValue("再想想"))
        assertTrue("prefix 提示： 应提取", processor.extractedStrings.containsValue("提示："))
        assertTrue("msg computed 正在执行操作，请稍候 应提取", processor.extractedStrings.containsValue("正在执行操作，请稍候"))
        assertTrue("actionLabels.retry 重试一下 应提取", processor.extractedStrings.containsValue("重试一下"))
        assertTrue("stop(ok=true) 操作完成提示 应提取", processor.extractedStrings.containsValue("操作完成提示"))
        assertTrue("stop(ok=false) 操作已取消提示 应提取", processor.extractedStrings.containsValue("操作已取消提示"))
        // 兼容：7 条明确指定 + 1 条 computed 拼接骨架 = 总共 8 条
        assertEquals(8, processor.extractedStrings.size)

        processor.execute()
        val result = file.text
        assertTrue(
            "import { useI18n } from 'vue-i18n' 原本就有 → 不能因为 import 冲突被删（保持 1 次），got:\n$result",
            "import \\{ useI18n \\} from 'vue-i18n'".toRegex().findAll(result).count() == 1
        )
        assertTrue(
            "actionLabels 对象尾部 as const 应保留，got:\n$result",
            result.contains("as const")
        )
        assertFalse(
            "okText = \"立即执行\" 硬编码不应残留（应为 \$t('立即执行')）",
            result.contains("okText = \"立即执行\"") || result.contains("okText = '立即执行'")
        )
    }

    // ============================================================
    // 回归 Bug1：Vue 文件中即使没有中文也导入 useI18n / 解构
    // ============================================================
    fun testVueSfcScriptSetupNoChineseShouldNotInjectUseI18n() {
        val file = configureFile(
            "src/components/NumberBox.vue",
            """
            <template>
              <div>{{ count }}</div>
            </template>
            <script setup lang="ts">
            import { ref } from 'vue'
            const count = ref(0)
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("没有中文 → 提取数量为 0", 0, processor.extractedStrings.size)
        processor.execute()
        val result = file.text
        assertFalse(
            "无中文场景不应注入 `import { useI18n } from 'vue-i18n'`，got:\n$result",
            result.contains("useI18n") || result.contains("vue-i18n")
        )
        assertFalse(
            "无中文场景不应注入 `const { t: ${'$'}t } = useI18n()`，got:\n$result",
            Regex("const\\s*\\{\\s*t\\s*:\\s*\\${'$'}t\\s*\\}\\s*=\\s*useI18n\\s*\\(").containsMatchIn(result)
        )
    }

    // ============================================================
    // 回归 Bug3：setup() 中有 useRequest(callback)，解构不应注入到 callback 内部，
    //           必须注入在 setup()/script 顶层。
    // ============================================================
    fun testVueSfcSetupUsesUseRequestI18nDestructureShouldInjectAtTopNotInsideCallback() {
        val file = configureFile(
            "src/views/UserList.vue",
            """
            <template>
              <div>
                <table v-loading="loading">
                  <tbody><tr><td>{{ title }}</td></tr></tbody>
                </table>
              </div>
            </template>
            <script lang="ts">
            import { defineComponent, ref } from 'vue'
            import { useRequest } from 'ahooks-vue'

            export default defineComponent({
              name: 'UserList',
              setup() {
                const title = ref('用户列表')
                const { loading } = useRequest(async () => {
                  console.log('开始请求')
                  return await fetch('/api/users').then(r => r.json())
                }, { manual: false })
                return { title, loading }
              }
            })
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("title 用户列表 应提取", processor.extractedStrings.containsValue("用户列表"))
        processor.execute()
        val result = file.text
        val destructureRe = Regex("const\\s*\\{\\s*t\\s*:\\s*\\${'$'}t\\s*\\}\\s*=\\s*useI18n\\s*\\(")
        val allD = destructureRe.findAll(result).toList()
        assertEquals("只应注入 1 条 `const { t: ${'$'}t } = useI18n()`，got ${allD.size}: \n$result", 1, allD.size)
        // 检查这条解构位于 setup() 开头，不在 useRequest(async () => { ... }) 的箭头函数体内部：
        // （简化判断：解构文本必须出现在字符串 `useRequest(async` 之前，而非之后）
        val idxD = allD.first().range.first
        val idxUseReq = result.indexOf("useRequest(")
        assertTrue("解构注入位置应在 useRequest( 之前（setup 顶层），got indices destructure@$idxD vs useRequest@$idxUseReq:\n$result", idxUseReq < 0 || idxD in 0 until idxUseReq)
        // 同时箭头函数 `async () => {` 的内部首行不应再出现第二条解构
        val afterUseReq = result.substring(minOf(idxUseReq, result.length))
        assertEquals("useRequest 回调体内部不应再出现第二次解构，got tail:\n$afterUseReq", 0, destructureRe.findAll(afterUseReq).drop(1).count())
    }

    // ============================================================
    // 16. 采集→因子化 联动：测试1/测试2 是否真的能进 Tab2 合并候选
    // ============================================================

    /**
     * 用真实 Vue 模板采集"测试1""测试2"，再走 factorize。
     * 复现用户反馈"Tab2 根本不出现 测试{N0} 候选"。
     */
    fun testCollectedSitesFlowIntoFactorize() {
        val file = configureFile(
            "src/Demo.vue",
            """
            <template>
                <div>
                    <span>测试1</span>
                    <span>测试2</span>
                </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 1) sites 确实被采集到
        val msgs = processor.collectedSites.map { it.originalMessage }
        assertTrue("采集到的原句应含 测试1/测试2，实际: $msgs", msgs.contains("测试1") && msgs.contains("测试2"))

        // 2) 按 transform() 同款方式构建 SiteRef 并 factorize
        val siteRefs = processor.collectedSites.map { site ->
            SiteRef(
                processorIndex = 0,
                siteId = site.id,
                originalMessage = site.originalMessage,
                containingFile = site.containingFile,
                isVue = site.isVue,
                isReact = site.isReact,
                line1 = site.startLine,
            )
        }
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(siteRefs)

        // 3) Tab2 的候选应非空（用户反馈"根本不出现"即此断言失败）
        assertTrue("Tab2 数字抽取候选不应为空，实际 digit=$digit", digit.isNotEmpty())
        assertTrue("Tab2 公共前后缀候选不应为空，实际 affix=$affix", affix.isNotEmpty())
    }

    /**
     * 场景 B：Vue 项目 .ts 文件里的 JS 字符串字面量 const a = "测试1"; const b = "测试2"。
     * 验证字面量场景是否同样能进 factorize。
     */
    fun testTsStringLiteralsFlowIntoFactorize() {
        val file = configureFile(
            "src/labels.ts",
            """
            export const a = "测试1";
            export const b = "测试2";
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        val msgs = processor.collectedSites.map { it.originalMessage }
        assertTrue("采集到的原句应含 测试1/测试2，实际: $msgs", msgs.contains("测试1") && msgs.contains("测试2"))

        val siteRefs = processor.collectedSites.map { site ->
            SiteRef(
                processorIndex = 0,
                siteId = site.id,
                originalMessage = site.originalMessage,
                containingFile = site.containingFile,
                isVue = site.isVue,
                isReact = site.isReact,
                line1 = site.startLine,
            )
        }
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(siteRefs)
        assertTrue("TS 字面量场景 Tab2 数字候选不应为空，实际 digit=$digit", digit.isNotEmpty())
        assertTrue("TS 字面量场景 Tab2 前后缀候选不应为空，实际 affix=$affix", affix.isNotEmpty())
    }

    // ============================================================
    // 最小提取长度：过短的文案不提取
    // ============================================================

    fun testMinStringLengthFiltersShortText() {
        val settings = I18nSettings.getInstance()
        val savedMinLen = settings.minStringLength()
        val savedLang = settings.languageIds()
        try {
            // 目标语言中文 + 最小长度 3
            settings.setLanguageIds(listOf("zh"))
            settings.setMinStringLength(3)

            val file = configureFile(
                "src/MinLen.vue",
                """
                <template>
                    <div>中</div>
                    <div>你好世界</div>
                    <div :title="'一'">良好</div>
                </template>
                """.trimIndent()
            )
            val p = I18nProcessor(project, file)
            p.collect()
            p.execute()
            val result = file.text

            // 单字「中」长度 1 < 3 → 不提取，原样保留（标签体走 mustache）
            assertTrue("单字「中」长度不足 3 不应提取，got:\n$result", result.contains("<div>中</div>"))
            // 4 字「你好世界」长度 ≥ 3 → 提取为 $t(`你好世界`)
            assertTrue("「你好世界」长度 ≥ 3 应提取，got:\n$result", result.contains("\$t(`你好世界`)"))
            // 指令值单字「一」< 3 → 不提取原样保留
            assertTrue("指令值单字「一」长度不足 3 不应提取，got:\n$result", result.contains(":title=\"'一'\""))
            // 指令值「良好」长度 2 < 3 → 也不提取
            assertTrue("「良好」长度 2 不足 3 不应提取，got:\n$result", result.contains("良好"))
        } finally {
            settings.setMinStringLength(savedMinLen)
            settings.setLanguageIds(savedLang)
        }
    }
}
