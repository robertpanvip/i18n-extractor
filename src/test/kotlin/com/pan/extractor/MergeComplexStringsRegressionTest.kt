package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.AffixGroupCandidate
import com.pan.extractor.merge.CommonPrefixSuffixFactorizer
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.merge.SiteRef
import com.pan.extractor.ui.ExtractedStringsDialog

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 复杂字符串形态 × 合并策略的回归测试。
 *
 * 覆盖三个已修复的缺陷（均由「模板字符串 / 字符串拼接 + 合并」交叉触发）：
 *  1. 同一句文本分别出现在模板字面量与字符串拼接里时，一处被拆成骨架嵌套、
 *     另一处保持整句 —— 同一字符串两种产物；
 *  2. 字符串拼接（`'中文' + 变量`）既提取整句、又把内层字面量单独提取一次
 *     —— 产生重复 key；
 *  3. 纯标点差异（`你好世界` / `你好，`）也能组成 你好{N0} 组
 *     —— 固定短语被无意义拆成「前缀 + 标点」。
 */
class MergeComplexStringsRegressionTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
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

    private fun site(msg: String, idx: Int = 0): SiteRef = SiteRef(
        processorIndex = idx,
        siteId = "s$idx-$msg",
        originalMessage = msg,
        containingFile = null,
        isVue = true,
        isReact = false,
        line1 = 1,
    )

    private fun configureVue(name: String, text: String): PsiFile =
        myFixture.addFileToProject(name, text)

    private fun applyMerge(file: PsiFile, selectedAffix: List<AffixGroupCandidate>):
        MutableMap<String, String> {
        val processor = I18nProcessor(project, file).also { it.collect() }
        val plan = ExtractedStringsDialog.MergePlan(selectedAffix, emptyList())
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        var res: MutableMap<String, String> = linkedMapOf()
        WriteCommandAction.runWriteCommandAction(project) {
            res = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        return res
    }

    private fun textOf(file: PsiFile): String =
        PsiManager.getInstance(project).findFile(file.virtualFile)?.text ?: ""

    // ─────────────────────────────────────────────────────────────
    // Bug 1：同一文本（模板 vs 拼接）必须得到同一种产物
    // ─────────────────────────────────────────────────────────────

    /** 纯算法层：完全相同的文本，其所有站点必须落在同一变体里（同命运）。 */
    fun testIdenticalTextSitesShareOneVariant() {
        val sites = listOf(site("你好世界", 0), site("你好世界", 1), site("你好地球", 2))
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)

        val group = affix.firstOrNull { it.skeleton == "你好{N0}" }
        assertNotNull("应生成 你好{N0} 组，实际: ${affix.map { it.skeleton }}", group)
        val shijie = group!!.variants.firstOrNull { it.diff == "世界" }
        assertNotNull("差异段应含 世界，实际: ${group.variants.map { it.diff }}", shijie)
        assertEquals(
            "文本相同（你好世界）的两个站点必须被同一变体承载，否则会一处嵌套一处整句",
            2,
            shijie!!.sites.size
        )
        assertEquals(2, shijie.sites.map { it.siteId }.distinct().size)
    }

    /** 端到端：模板字面量与字符串拼接里的同一句文本，重写产物必须一致。 */
    fun testIdenticalTextFromTemplateAndConcatRewrittenAlike() {
        val file = configureVue(
            "src/TplConcat.vue",
            """
            <template><div><span>占位</span></div></template>
            <script setup lang="ts">
            const a = `你好世界`
            const b = '你好' + '世界'
            const c = '你好地球'
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file).also { it.collect() }

        // 采集层：模板站点与拼接站点的 originalMessage 完全相同（都是 你好世界）
        val sameTextSites = processor.analyzer.collectedSites.count { it.originalMessage == "你好世界" }
        assertEquals("模板 + 拼接应有 2 个 你好世界 站点", 2, sameTextSites)
        // 只登记 1 个 你好世界 key（拼接没有被拆成额外 key）
        assertEquals(
            "你好世界 应只提取 1 次，实际: ${processor.analyzer.extractedStrings}",
            1,
            processor.analyzer.extractedStrings.values.count { it == "你好世界" }
        )

        val (affix, _) = MergeApplier.factorizeSites(listOf(processor))
        val group = affix.firstOrNull { it.skeleton == "你好{N0}" }
            ?: throw IllegalStateException("应生成 你好{N0} 组，实际: ${affix.map { it.skeleton }}")

        val res = applyMerge(file, listOf(group))
        val result = textOf(file)

        // 3 处（模板 A、拼接 B、邻居 C）都走同一骨架，不再有整句 $t('你好世界')
        assertEquals(
            "3 个站点都应重写为 你好{N0} 骨架，实际:\n$result",
            3,
            Regex("你好\\{N0\\}").findAll(result).count()
        )
        assertFalse(
            "同一文本不得一处嵌套、一处整句，实际:\n$result",
            result.contains("\$t('你好世界')") || result.contains("\$t(`你好世界`)")
        )
        assertTrue("骨架 key 应写入资源", res.containsKey("你好{N0}"))
        assertFalse("被完全承载的整句 key 应被清理", res.containsKey("你好世界"))
    }

    // ─────────────────────────────────────────────────────────────
    // Bug 2：字符串拼接不得重复提取内层字面量
    // ─────────────────────────────────────────────────────────────

    /** `'中文' + 变量`：整条拼接只提取 1 次，且不再单独产出纯字面量 key。 */
    fun testConcatWithVariableExtractedOnce() {
        val file = configureVue(
            "src/Concat.vue",
            """
            <template><div><span>占位</span></div></template>
            <script setup lang="ts">
            const name = 'Tom'
            const msg = '你好，' + name
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file).also { it.collect() }
        val values = processor.analyzer.extractedStrings.values.toList()

        assertTrue("整条拼接应提取为 你好，{name}，实际: $values", values.contains("你好，{name}"))
        assertFalse(
            "内层字面量 '你好，' 不应再被单独提取（重复 key），实际: $values",
            values.contains("你好，")
        )
        assertEquals("应只提取 1 条（含 占位），实际: $values", 2, values.size)
    }

    /** 多个字面量拼接（`'你好' + '世界'`）同样只提取合并后的 1 条。 */
    fun testPureConcatExtractedOnce() {
        val file = configureVue(
            "src/Concat2.vue",
            """
            <template><div><span>占位</span></div></template>
            <script setup lang="ts">
            const msg = '你好' + '世界'
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file).also { it.collect() }
        val values = processor.analyzer.extractedStrings.values.toList()
        assertTrue("应提取 你好世界，实际: $values", values.contains("你好世界"))
        assertEquals("拼接应只提取 1 条（含 占位），实际: $values", 2, values.size)
    }

    // ─────────────────────────────────────────────────────────────
    // Bug 3：纯标点差异不构成合并组
    // ─────────────────────────────────────────────────────────────

    /** `你好世界` / `你好，`：差异只剩标点 → 不得组成 你好{N0}，固定短语不应被拆。 */
    fun testPunctuationOnlyDiffDoesNotFormGroup() {
        val sites = listOf(site("你好世界", 0), site("你好，", 1))
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)

        val realGroups = affix.filter { !it.isExactDuplicate }
        assertTrue(
            "纯标点差异不应生成合并组（会把 你好世界 拆成 你好 + 世界），实际: ${realGroups.map { it.skeleton }}",
            realGroups.none { it.skeleton.startsWith("你好{N0}") }
        )
    }

    /** 差异段含汉字时仍正常合并（对照组，确保防呆没把合法合并一起拒掉）。 */
    fun testSemanticDiffStillMerges() {
        val sites = listOf(site("你好世界", 0), site("你好地球", 1))
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val group = affix.firstOrNull { it.skeleton == "你好{N0}" }
        assertNotNull("语义差异（世界/地球）应正常合并，实际: ${affix.map { it.skeleton }}", group)
        assertEquals(setOf("世界", "地球"), group!!.variants.map { it.diff }.toSet())
    }
}
