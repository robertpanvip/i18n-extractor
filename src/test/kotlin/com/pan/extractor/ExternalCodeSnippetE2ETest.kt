package com.pan.extractor

import com.pan.extractor.core.I18nProcessor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 基于真实开源项目代码的 E2E 测试。
 *
 * 从以下项目中收集了典型 bug 模式的代码片段：
 *   Vue: ant-design-vue, element-plus, vue-vben-admin, arco-design-vue, vant, naive-ui
 *   React: ant-design, material-ui, mantine
 *
 * 这些片段直接作为测试输入，验证插件对复杂模式的处理是否正确。
 */
class ExternalCodeSnippetE2ETest : BasePlatformTestCase() {

    private val d = '$' // dollar sign helper for template escaping

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "e2e-test-project",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0",
                "react": "^18.0.0",
                "react-dom": "^18.0.0",
                "react-i18next": "^13.0.0"
              }
            }
            """.trimIndent()
        )
    }

    private fun processVue(text: String): String {
        val psiFile = myFixture.addFileToProject("Test.vue", text) as PsiFile
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        processor.runWithUndo()
        return psiFile.text
    }

    private fun processReact(text: String): String {
        val psiFile = myFixture.addFileToProject("Test.tsx", text) as PsiFile
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        processor.runWithUndo()
        return psiFile.text
    }

    // ───────────────────────────────────────────────
    // Bug 模式 1: Vue mustache 模板字符串拼接三元 + 变量
    // 来源: ant-design-vue/site/src/components/Contributors/index.vue:58
    //   :title="`${d}{isZn ? '文档贡献者：' : 'Contributor: '}${d}{item.login}`"
    // ───────────────────────────────────────────────

    fun testVueAttributeTemplateConcatTernaryVar() {
        val vue = """
        <template>
          <div>
            <a-tooltip :title="`${d}{isZn ? '文档贡献者：' : 'Contributor: '}${d}{item.login}`">
              <a :href="item.url">{{ item.login }}</a>
            </a-tooltip>
          </div>
        </template>
        <script setup lang="ts">
          const isZn = true
          const item = { login: 'test', url: '#' }
        </script>
        """.trimIndent()

        val result = processVue(vue)

        assertFalse(
            "不应出现嵌套占位符 {N{N0}} 等",
            Regex("""\{N\{N\d+\}\}""").containsMatchIn(result)
        )
        assertFalse(
            "不应出现参数值为字面量 0",
            Regex("""\{\s*N\d+\s*:\s*0\s*\}""").containsMatchIn(result)
        )
        assertTrue(
            "应提取中文 '文档贡献者：'，got:\n$result",
            result.contains("$d t('文档贡献者") || result.contains("${d}t('文档贡献者")
        )
    }

    // ───────────────────────────────────────────────
    // Bug 模式 2: Vue mustache 三元表达式含中文 + 冒号拼接
    // ───────────────────────────────────────────────

    fun testVueMustacheTernaryChinesePlusColon() {
        val vue = """
        <template>
          <span>
            {{ isZn ? '最后更新' : 'Last updated' }} : {{ dayjs(lastCommitTime).format('YYYY/MM/DD') }}
          </span>
        </template>
        <script setup lang="ts">
          const isZn = true
          const lastCommitTime = 0
          const dayjs = (t: any) => ({ format: () => "" })
        </script>
        """.trimIndent()

        val result = processVue(vue)

        assertFalse(
            "不应出现嵌套占位符",
            Regex("""\{N\{N\d+\}\}""").containsMatchIn(result)
        )
        assertTrue(
            "应提取中文 '最后更新'，got:\n$result",
            result.contains("$d t('最后更新'") || result.contains("${d}t('最后更新'")
        )
    }

    // ───────────────────────────────────────────────
    // Bug 模式 3: Vue mustache 模板字符串 + || 链式
    // ───────────────────────────────────────────────

    fun testVueMustacheTemplateWithOrChain() {
        val vue = """
        <template>
          <div>
            {{ isZhCN ? `${d}{m.title} ${d}{m.subtitle || ''}` : m.enTitle || m.title }}
          </div>
        </template>
        <script setup lang="ts">
          const isZhCN = true
          const m = { title: '标题', subtitle: '副标题', enTitle: 'Title' }
        </script>
        """.trimIndent()

        val result = processVue(vue)

        assertFalse(
            "不应出现嵌套占位符",
            Regex("""\{N\{N\d+\}\}""").containsMatchIn(result)
        )
        assertFalse(
            "不应出现参数值为字面量 0",
            Regex("""\{\s*N\d+\s*:\s*0\s*\}""").containsMatchIn(result)
        )
    }

    // ───────────────────────────────────────────────
    // Bug 模式 4: Vue mustache 字符串拼接变量（之前的 bug）
    //   {{ "第" + record.versionNum + "版" }}
    // ───────────────────────────────────────────────

    fun testVueMustacheStringConcatWithVariable() {
        val vue = """
        <template>
          <span>{{ "第" + record.versionNum + "版" }}</span>
        </template>
        <script setup lang="ts">
          const record = { versionNum: 1 }
        </script>
        """.trimIndent()

        val result = processVue(vue)

        assertFalse(
            "不应出现嵌套占位符 {N{N0}}，got:\n$result",
            Regex("""\{N\{N\d+\}\}""").containsMatchIn(result)
        )
        assertFalse(
            "参数值不应是字面量 0，got:\n$result",
            Regex("""\{\s*N\d+\s*:\s*0\s*\}""").containsMatchIn(result)
        )
        assertTrue(
            "应生成正确的命名占位 {N0}，got:\n$result",
            result.contains("{N0}")
        )
        assertTrue(
            "参数值应是变量表达式 record.versionNum，got:\n$result",
            result.contains("N0: record.versionNum")
        )
    }

    // ───────────────────────────────────────────────
    // Bug 模式 5: Vue 脚本内三元表达式含中文字符串拼接变量
    // ───────────────────────────────────────────────

    fun testVueScriptTernaryStringConcat() {
        val vue = """
        <template><div>{{ msg }}</div></template>
        <script setup lang="ts">
          const count = 5
          const isOK = true
          const msg = isOK ? '成功' + count + '条' : '失败'
        </script>
        """.trimIndent()

        val result = processVue(vue)

        assertFalse(
            "不应出现嵌套占位符",
            Regex("""\{N\{N\d+\}\}""").containsMatchIn(result)
        )
        assertFalse(
            "不应出现参数值为字面量 0",
            Regex("""\{\s*N\d+\s*:\s*0\s*\}""").containsMatchIn(result)
        )
    }

    // ───────────────────────────────────────────────
    // Bug 模式 6: React JSX 中文文本 + 三元
    // ───────────────────────────────────────────────

    fun testReactJSXChineseWithTernary() {
        val react = """
        import React from 'react'
        export default function FormItem({ hasError }) {
          return (
            <div>
              {hasError ? '校验失败' : '校验通过'}
              <span>共 {count} 项</span>
            </div>
          )
        }
        """.trimIndent()

        val result = processReact(react)

        // React 用数字占位符 {{0}}
        assertFalse(
            "不应出现嵌套占位符 {{{",
            Regex("""\{\{\{""").containsMatchIn(result)
        )
    }

    // ───────────────────────────────────────────────
    // Bug 模式 7: Vue 脚本内模板字符串多变量插值
    // ───────────────────────────────────────────────

    fun testVueScriptTemplateMultiInterpolation() {
        val vue = """
        <template><div>{{ title }}</div></template>
        <script setup lang="ts">
          const title = '标题'
          const version = '1.0'
          const full = `${d}{title} - ant-design-vue@${d}{version}`
        </script>
        """.trimIndent()

        val result = processVue(vue)

        assertFalse(
            "不应出现嵌套占位符",
            Regex("""\{N\{N\d+\}\}""").containsMatchIn(result)
        )
        assertFalse(
            "不应出现参数值为字面量 0",
            Regex("""\{\s*N\d+\s*:\s*0\s*\}""").containsMatchIn(result)
        )
    }

    // ───────────────────────────────────────────────
    // Bug 模式 8: Vue mustache 已有 $t() 调用不重复提取
    // ───────────────────────────────────────────────

    fun testVueMustacheExistingTCallNoDoubleExtract() {
        val vue = """
        <template>
          <span>{{ ${d}t('已有翻译') }}</span>
          <span>新中文</span>
        </template>
        """.trimIndent()

        val result = processVue(vue)

        assertFalse(
            "不应出现嵌套的 \$t(\$t(...)) 调用",
            Regex("""\\\${d}t\\\${d}t\(""").containsMatchIn(result) ||
                result.contains("\${d}t(\${d}t(")
        )
    }

    // ───────────────────────────────────────────────
    // 基础场景回归
    // ───────────────────────────────────────────────

    private val nestedPlaceholderRegex = Regex("""\{N\{N\d+\}\}""")
    private val zeroParamRegex = Regex("""\{\s*N\d+\s*:\s*0\s*\}""")
    private val nestedReactRegex = Regex("""\{\{\{""")

    fun testVueAttributeSimpleString() {
        val vue = """
        <template>
          <a-tooltip title="简单的中文提示">
            <span>悬停提示</span>
          </a-tooltip>
        </template>
        """.trimIndent()
        val result = processVue(vue)
        assertFalse(nestedPlaceholderRegex.containsMatchIn(result))
        assertFalse(zeroParamRegex.containsMatchIn(result))
    }

    fun testVueSimpleConcat() {
        val vue = """
        <template>
          <span>{{ '第' + page + '页' }}</span>
        </template>
        <script setup lang="ts">const page = 1</script>
        """.trimIndent()
        val result = processVue(vue)
        assertFalse(nestedPlaceholderRegex.containsMatchIn(result))
        assertFalse(zeroParamRegex.containsMatchIn(result))
    }

    fun testVueNumberConcat() {
        val vue = """
        <template>
          <span>共 {{ n }} 个</span>
        </template>
        <script setup lang="ts">const n = 10</script>
        """.trimIndent()
        val result = processVue(vue)
        assertFalse(nestedPlaceholderRegex.containsMatchIn(result))
        assertFalse(zeroParamRegex.containsMatchIn(result))
    }

    fun testVueVIfChinese() {
        val vue = """
        <template>
          <div v-if="show">显示中文字符</div>
        </template>
        <script setup lang="ts">const show = true</script>
        """.trimIndent()
        val result = processVue(vue)
        assertFalse(nestedPlaceholderRegex.containsMatchIn(result))
    }

    fun testReactSimpleJSX() {
        val react = """
        import React from 'react'
        export default function App() {
          return <div>简单的中文文本</div>
        }
        """.trimIndent()
        val result = processReact(react)
        assertFalse(nestedReactRegex.containsMatchIn(result))
    }
}
