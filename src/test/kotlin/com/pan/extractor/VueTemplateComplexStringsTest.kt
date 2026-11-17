package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.ui.ExtractedStringsDialog
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Vue 模板层复杂字符串提取回归测试。
 *
 * 覆盖复杂字符串（模板插值/拼接/嵌套/三元）放在 Vue 的各种模板位置：
 * 静态属性、v-bind 指令值、v-if/v-for 指令表达式、组件 prop、Mustache 插值。
 *
 * 重点回归：指令值以引号开头的拼接表达式（如 :label="'你好，' + name"）
 * 曾被属性路径误当纯文本整条提取，产生带代码的垃圾 key 并覆盖正确产物。
 * 现在应由 JS 表达式路径统一提取成占位符文案。
 */
class VueTemplateComplexStringsTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            { "name": "vue-test-project", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" } }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import { createI18n } from 'vue-i18n'
            export const i18n = createI18n({})
            export * from 'vue-i18n'
            """.trimIndent()
        )
    }

    private fun configureFile(text: String): PsiFile {
        val psiFile = myFixture.addFileToProject("src/Probe.vue", text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        return psiFile
    }

    /** 跑完整 collect → factorize → apply，返回 (改写后源码, 最终资源)。只应用 selected=true 的组（模拟真实 UI，跳过提示性占位组）。 */
    private fun runFullPipeline(text: String): Pair<String, Map<String, String>> {
        val file = configureFile(text)
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, digit) = MergeApplier.factorizeSites(listOf(processor))
        val plan = ExtractedStringsDialog.MergePlan(
            affix.filter { it.selected },
            digit.filter { it.selected }
        )
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val after = myFixture.findFileInTempDir("src/Probe.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        return after to (holder[0] ?: emptyMap())
    }

    private fun String.noWs(): String = this.replace(Regex("\\s+"), "")

    /**
     * 端到端：把复杂字符串放进 Vue 模板各位置，验证产物正确、无垃圾 key。
     */
    fun testComplexStringsInTemplateAllProduceCleanOutput() {
        val text = """
<template>
  <div class="page">
    <div title="欢迎使用系统" aria-label="关闭按钮">A</div>
    <span :title="`你好${'$'}{name}`">B</span>
    <span :label="'你好，' + name">C</span>
    <span :tip="'你好' + '世界'">D</span>
    <span :msg="`外层${'$'}{`内层${'$'}{name}`}文本`">E</span>
    <p v-if="status === '加载中'">F</p>
    <MyComp :header="`前缀${'$'}{name}后缀`" :footer="'结果：' + count" />
    <div>{{ `你好${'$'}{name}` }} / {{ '固定' + name }}</div>
    <li v-for="x in ['甲选项','乙选项']" :key="x">{{ x }}</li>
    <button :text="ok ? '操作成功' : '操作失败'">J</button>
  </div>
</template>
<script setup lang="ts">
const name = 'Tom'
const count = 5
const status = '加载中'
const ok = true
</script>
""".trimIndent()
        val (after, res) = runFullPipeline(text)
        val code = after.noWs()

        // A 静态属性：$t 包裹
        assertTrue("静态属性应提取: $after", code.contains("title=\"\$t('欢迎使用系统')\""))
        assertTrue("aria-label 应提取: $after", code.contains("aria-label=\"\$t('关闭按钮')\""))

        // B 模板插值：语义化占位符
        assertTrue(":title 模板插值: $after", code.contains(":title=\"\$t('你好{name}',{name:name})\""))

        // C/D/G 指令值拼接：应是语义化文案，绝不能出现带引号/加号的垃圾 key
        assertTrue("C 串+变量拼接应语义化: $after", code.contains(":label=\"\$t('你好，{name}',{name:name})\""))
        assertTrue("D 纯串拼接应内联: $after", code.contains(":tip=\"\$t('你好世界')\""))
        assertTrue("G footer 拼接应语义化: $after", code.contains(":footer=\"\$t('结果：{count}',{count:count})\""))

        // E 嵌套模板：嵌套 $t
        assertTrue("E 嵌套模板: $after", code.contains("外层{N0}文本") && code.contains("内层{name}"))

        // F v-if 指令表达式
        assertTrue("v-if 指令字符串: $after", code.contains("status===\$t('加载中')"))

        // H Mustache
        assertTrue("Mustache 模板插值: $after", code.contains("\$t('固定{name}',{name:name})"))

        // I v-for 数组字面量
        assertTrue("v-for 数组字符串: $after", code.contains("\$t('甲选项')") && code.contains("\$t('乙选项')"))

        // J 三元
        assertTrue("三元分支: $after", code.contains("操作{N0}"))

        // 核心断言：最终资源里不得出现任何"带 JS 代码"的垃圾 key
        val junkKeys = res.keys.filter { it.contains(" + ") || it.contains("'") || it.contains("\"") || it.contains("?") }
        assertTrue("资源不应含带代码的垃圾 key，实际: $junkKeys", junkKeys.isEmpty())
    }

    /**
     * 回归：指令值"整体就是一个纯字符串字面量"仍应走属性路径整条提取，
     * 不能被上面的复合表达式判定误伤。
     */
    fun testDirectiveSoleStringLiteralStillInlined() {
        // 两个文案刻意无公共前后缀，避免触发 factorize 合并组（那属于合并策略既定行为，
        // 与本回归无关），从而纯粹验证"指令值整体是字符串字面量"仍走属性路径整条提取。
        val text = """
<template>
  <div>
    <span :title="'欢迎光临'">X</span>
    <MyComp :label="'请稍候'" />
  </div>
</template>
<script setup lang="ts">
const x = 1
</script>
""".trimIndent()
        val (after, res) = runFullPipeline(text)
        val code = after.noWs()

        assertTrue("指令纯字面量应整条提取: $after", code.contains(":title=\"\$t('欢迎光临')\""))
        assertTrue("组件 prop 纯字面量: $after", code.contains(":label=\"\$t('请稍候')\""))
        assertTrue("key 应登记", res.containsKey("欢迎光临") && res.containsKey("请稍候"))
        assertEquals(2, res.size)
    }

    /**
     * 回归：非指令的静态中文属性（含撇号）仍正确转义，不受拼接判据影响。
     */
    fun testStaticAttributeWithApostropheStillValid() {
        val text = """
<template>
  <div>
    <span title="唐's 工具提示" aria-label="张三's 按钮">X</span>
  </div>
</template>
<script setup lang="ts">
const x = 1
</script>
""".trimIndent()
        val (after, res) = runFullPipeline(text)
        // 撇号应被转义为合法 JS
        assertTrue("撇号应转义，产物须合法: $after", after.contains("唐\\'s 工具提示"))
        assertFalse("不应产生 &quot; 转义", after.contains("&quot;"))
        assertTrue("key 应登记", res.containsKey("唐's 工具提示"))
    }
}
