package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.ui.*

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现用户反馈的 Vue 两个 bug：
 *  1. placeholder 属性因子化后生成 &quot;$t(...)&quot; 多余的转义引号
 *  2. 生成的最终资源里，原始整句 key 与因子化骨架 key 重复
 */
class BugReproVueAttributeTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "vue-test-project",
              "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" }
            }
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

    private fun configureFile(fileName: String, text: String): PsiFile {
        val psiFile = myFixture.addFileToProject(fileName, text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        return psiFile
    }

    private fun String.noWs(): String = this.replace(Regex("\\s+"), "")

    fun testReproPlaceholderFactorsWithNestedDiff() {
        // 一期目标：仅复现，打印实际输出
        val text = """
            <template>
                <div>
                    <Input placeholder="请输入搜索关键词" />
                    <span>请输入用户名</span>
                </div>
            </template>
        """.trimIndent()
        val file = configureFile("src/Demo.vue", text)
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, digit) = MergeApplier.factorizeSites(listOf(processor))
        println("=== affix groups ===")
        for (g in affix) {
            println("skeleton=${g.skeleton} skeletonKey=${g.skeletonKey} variants=${g.variants.map { it.diff to it.sites.map { s -> s.originalMessage } }}")
        }

        val group = affix.firstOrNull { it.skeleton.contains("请输入") }
        if (group == null) {
            println("!! 未生成包含 请输入 的 affix 组，改用全部组")
        }
        val plan = ExtractedStringsDialog.MergePlan(
            listOfNotNull(group),
            digit
        )
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        println("=== extracted before apply ===")
        extracted.forEach { (k, v) -> println("$k -> $v") }
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultRes = holder[0] ?: emptyMap()
        val resultText = myFixture.findFileInTempDir("src/Demo.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        println("=== file text after ===")
        println(resultText)
        println("=== final resource keys ===")
        resultRes.forEach { (k, v) -> println("$k -> $v") }

        // Bug 1：不应出现 &quot; 转义
        assertFalse("不应生成 &quot; 转义引号，实际:\n$resultText", resultText.contains("&quot;"))

        // Bug 2：资源里原始的整句 key 不应与骨架重复，骨架 key 应存在
        val hasSkeleton = resultRes.keys.any { it.contains("{N") }
        assertTrue("骨架 key 应存在于最终资源，keys=${resultRes.keys}", hasSkeleton)
        assertFalse("原始整句 key 不应保留（已被因子化承载），keys=${resultRes.keys}", resultRes.containsKey("请输入搜索关键词"))
    }
}