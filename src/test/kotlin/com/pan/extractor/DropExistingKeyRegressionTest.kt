package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.merge.CommonPrefixSuffixFactorizer
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.merge.SiteRef
import com.pan.extractor.ui.ExtractedStringsDialog
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 回归：因子化合并不得因「过度合并」误删入口翻译文件里已有的 key。
 *
 * 缺陷链："发布成功"(diff 成功=2字) 与 "发布的版本："(diff 的版本：=4字)
 * 仅共享短前缀 "发布"(2字)，被合成 发布{N0}；两整句被判「完全消费」进入
 * dropExistingKeys，导致 zh-CN.ts 里已有的 `发布成功:'发布成功'` 被删除（数据丢失）。
 *
 * 修复：因子化守卫新增「任一差异段长度不得大于公共前后缀总长」，此类伪模板不成组。
 */
class DropExistingKeyRegressionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """{"name":"vue-test","dependencies":{"vue":"^3.0.0","vue-i18n":"^9.0.0"}}""".trimIndent()
        )
        myFixture.addFileToProject(
            "src/locales/index.ts",
            "import { createI18n } from 'vue-i18n'\nexport const i18n = createI18n({})\nexport * from 'vue-i18n'"
        )
    }

    private fun site(msg: String, idx: Int): SiteRef = SiteRef(
        processorIndex = idx, siteId = "s$idx", originalMessage = msg,
        containingFile = null, isVue = true, isReact = false, line1 = 1,
    )

    /** 算法层：标签型差异（"发布的版本：" 冒号结尾）不得把整句卷进伪模板组。 */
    fun testLabelDiffIsNotGrouped() {
        val sites = listOf(
            site("发布成功", 0), site("发布的版本：", 1),
            site("请选择发布的版本", 2), site("选择发布的版本", 3),
        )
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue(
            "标签型差异不应组成 发布{N0} 伪模板，实际: ${affix.map { it.skeleton }}",
            affix.none { it.skeleton.contains("发布{N0}") }
        )
    }

    /** 算法层：既有合法组合（数字/短词/长差异但无句末标点）不受新守卫影响。 */
    fun testLegitGroupsStillFactored() {
        val sites = listOf(
            site("测试1", 0), site("测试2", 1),            // 数字差异，骨架 测试{N0}
            site("欢迎张三回来", 2), site("欢迎李四回来", 3), // 短词差异 + 后缀，骨架 欢迎{N0}回来
            site("请输入搜索关键词", 4), site("请输入用户名", 5), // 长差异但无句末标点，骨架 请输入{N0}
        )
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue("数字组应保留 测试{N0}，实际: ${digit.map { it.skeleton }}",
            digit.any { it.skeleton == "测试{N0}" })
        assertTrue("短词+后缀组合法保留 欢迎{N0}回来，实际: ${affix.map { it.skeleton }}",
            affix.any { it.skeleton == "欢迎{N0}回来" })
        assertTrue("长差异无句末标点仍应合并 请输入{N0}，实际: ${affix.map { it.skeleton }}",
            affix.any { it.skeleton == "请输入{N0}" })
    }

    /** 端到端：用户已有的 key 在「全选合并」后仍保留，不被 dropExistingKeys 删除。 */
    fun testExistingKeySurvivesFullMergeApply() {
        val resourcePath = "src/locales/zh-CN-survive.ts"
        val beforeRes = "export default {\n  发布成功: '发布成功',\n}"
        myFixture.addFileToProject(resourcePath, beforeRes)

        val vue = """
<script setup lang="ts">
import { message } from 'ant-design-vue';
message.success('发布成功');
</script>
<template>
  <a-button>发布</a-button>
  <span>发布的版本：</span>
  <span>选择发布的版本</span>
  <span>请选择发布的版本</span>
</template>
""".trimIndent()
        val file = myFixture.addFileToProject("src/Survive.vue", vue)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val processor = I18nProcessor(project, file).also { it.collect() }
        val (affixAll, digitAll) = MergeApplier.factorizeSites(listOf(processor))
        // 真实 UI 只应用 selected=true 的组
        val plan = ExtractedStringsDialog.MergePlan(
            affixAll.filter { it.selected }, digitAll.filter { it.selected }
        )
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        val dropKeys = LinkedHashSet<String>()
        val finalRes = WriteCommandAction.runWriteCommandAction<MutableMap<String, String>>(project) {
            MergeApplier.apply(listOf(processor), extracted, plan, null, null, dropKeys)
        }

        assertTrue("发布成功 不应进 dropExistingKeys，实际: $dropKeys", "发布成功" !in dropKeys)
        assertTrue("finalRes 应保留 发布成功 key，实际: ${finalRes.keys}", finalRes.containsKey("发布成功"))

        val entryVf = myFixture.findFileInTempDir(resourcePath)!!
        val newRes = TsFileEditor.regenerateTsFileWithNewJson(project, entryVf, finalRes, dropKeys)
        assertNotNull("资源应能重新生成", newRes)
        assertTrue("写回后资源必须保留已有的 发布成功 key，实际:\n$newRes",
            newRes!!.contains("发布成功"))
    }

    /** 端到端：真正合法的合并仍然生效（骨架 + 差异），不受守卫影响。 */
    fun testLegitMergeStillApplies() {
        val vue = """<template><div><span>测试1</span><span>测试2</span></div></template>"""
        val file = myFixture.addFileToProject("src/Legit.vue", vue)
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val processor = I18nProcessor(project, file).also { it.collect() }
        val (affixAll, digitAll) = MergeApplier.factorizeSites(listOf(processor))
        val plan = ExtractedStringsDialog.MergePlan(
            affixAll.filter { it.selected }, digitAll.filter { it.selected }
        )
        val hasDigitMerge = digitAll.any { it.selected && it.skeleton == "测试{N0}" }
        assertTrue("合法的数字合并组仍应存在，实际: ${digitAll.map { it.skeleton to it.selected }}", hasDigitMerge)
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        val finalRes = WriteCommandAction.runWriteCommandAction<MutableMap<String, String>>(project) {
            MergeApplier.apply(listOf(processor), extracted, plan)
        }
        assertTrue("合法合并应产出骨架 key 测试{N0}，实际: ${finalRes.keys}",
            finalRes.containsKey("测试{N0}"))
    }
}
