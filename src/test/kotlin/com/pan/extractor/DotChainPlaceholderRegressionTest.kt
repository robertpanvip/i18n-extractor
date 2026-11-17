package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.ui.ExtractedStringsDialog
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 点号语义键（item.versionNum）两层 bug 复现：
 *  1) 提取层：Vue 参数对象裸键不允许点号（非法 JS），vue-i18n 具名插值也不支持点号名
 *  2) 合并层：公共前后缀把占位符拦腰切开 → 骨架 {{N0}.versionNum} 破碎 + 变量被引号字面量化
 */
class DotChainPlaceholderRegressionTest : BasePlatformTestCase() {

    private val d = '$'

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """{ "name": "t", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" } }"""
        )
    }

    private fun runApply(text: String, mode: String): Pair<PsiFile, MutableMap<String, String>> {
        val psiFile = myFixture.addFileToProject("src/Demo.vue", text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        val (affix, digit) = com.pan.extractor.merge.MergeApplier.factorizeSites(listOf(processor))
        val plan = when (mode) {
            "affix" -> ExtractedStringsDialog.MergePlan(affix, emptyList())
            "digit" -> ExtractedStringsDialog.MergePlan(emptyList(), digit)
            else -> ExtractedStringsDialog.MergePlan(affix, digit)
        }
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = com.pan.extractor.merge.MergeApplier.apply(listOf(processor), extracted, plan)
        }
        return psiFile to (holder[0] ?: LinkedHashMap())
    }

    // ── 层1：提取 ──
    fun testExtractDotChainParamMustFallBackToNumberedPlaceholder() {
        val psiFile = myFixture.addFileToProject(
            "Demo.vue",
            """
            <template><div id="root"></div></template>
            <script setup lang="ts">
            const items = [{ versionNum: 1 }]
            items.forEach((item) => {
              const label = ${'"'}第${'"'} + item.versionNum + ${'"'}版${'"'}
              console.log(label)
            })
            </script>
            """.trimIndent()
        ) as PsiFile
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        processor.runWithUndo()
        val result = psiFile.text
        assertFalse(
            "参数对象不允许点号裸键（非法 JS），got:\n$result",
            result.contains("${d}t(") && Regex("""\{\s*[\w$]+\.[\w$]+\s*:""").containsMatchIn(result)
        )
        assertTrue(
            "应回退编号占位 N0 且参数值为变量表达式，got:\n$result",
            result.contains("N0: item.versionNum")
        )
    }

    // ── 层2：合并建议 ──
    fun testAffixMergeMustNotCutPlaceholderAcrossBoundary() {
        val (file, _) = runApply(
            """
            <template><div id="root"></div></template>
            <script setup lang="ts">
            const items = [{ versionNum: 1 }]
            const users = [{ versionNum: 2 }]
            const opts = [
              { label: ${'"'}第${'"'} + item.versionNum + ${'"'}版${'"'}, value: 1 },
              { label: ${'"'}第${'"'} + user.versionNum + ${'"'}版${'"'}, value: 2 },
            ]
            </script>
            """.trimIndent(),
            "affix"
        )
        val result = file.text
        assertFalse(
            "骨架不得出现破碎嵌套占位符，got:\n$result",
            result.contains("{{N0}") || result.contains("{N0}.")
        )
        assertFalse(
            "占位符内变量不得被引号字面量化，got:\n$result",
            result.contains("N0: 'item'") || result.contains("N0: 'user'")
        )
    }
}
