package com.pan.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 CommonPrefixSuffixFactorizer 的纯单元测试（不依赖 PSI/平台）。
 *
 * 目的：把代码走查时"猜测"的 bug 固化成可运行的回归用例。
 * 重点覆盖：
 *   1. 同一句文本"既入合并组、又作为独立站点存在"的前置条件（对应 transform() 第⑤步
 *      误删仍被引用 key 的隐患，见 AllI18nExtractorAction）；
 *   2. 公共前后缀合并的粗粒度行为（骨架不尽可能提取公共后缀）。
 */
class CommonPrefixSuffixFactorizerTest {

    private fun site(msg: String, idx: Int = 0): SiteRef = SiteRef(
        processorIndex = idx,
        siteId = "s$idx-$msg",
        originalMessage = msg,
        containingFile = null,
        isVue = true,
        isReact = false,
        line1 = 1,
    )

    private fun siteRefs(vararg msgs: String): List<SiteRef> = msgs.mapIndexed { i, m -> site(m, i) }

    /**
     * Bug 2 的前置条件：同一段文本既可以出现在某个"公共前后缀合并组"里，
     * 又可以在另一处作为独立站点（不被合并）出现。
     *
     * 当 transform() 第⑤步把 mergedOriginalMessages 里所有原句从资源中删除时，
     * 这个独立站点的 key 也会被误删，导致 $t('测试完了A') 悬空。
     * 这里验证：factorize 确实会同时产出"含 测试完了A 的合并组" AND 一个独立站点。
     */
    @Test
    fun testSameTextCanBeBothGroupedAndStandalone() {
        // 测试完了A 出现两次：一次与 测试完了B 共享前缀"测试完了"，一次独立。
        // 测试好了 与它们共享前缀"测试"。
        val sites = siteRefs(
            "测试完了A",   // 站点0
            "测试完了A",   // 站点1：与站点0 完全相同 → 进 exact-dup（不合并）
            "测试完了B",   // 站点2：与站点0 共享前缀 测试完了
            "测试好了",    // 站点3：与站点0 共享前缀 测试
        )
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)

        // 站点0 确实进入了一个合并组（公共前缀 ≥2 字）
        val grouped = affix.filter { c -> c.variants.any { v -> v.sites.any { it.originalMessage == "测试完了A" } } }
        assertTrue("站点0 应进入合并组，实际: $affix", grouped.isNotEmpty())

        // —— 前置条件成立：站点0 的文本既进了合并组，站点1 又作为完全相同的独立站点存在。
        //    若 transform 第⑤步按 mergedOriginalMessages 删除该文本 key，独立站点会悬空。
    }

    /**
     * 公共前后缀合并是粗粒度的：多个差异段共享的后缀不一定被提取到骨架里。
     * 例如 ["AB测试1XY","AB测试2XY","AB测试3XZ"]，后缀 "XY"（仅前两句共享）
     * 不会被放进骨架，导致 1XY/2XY 整个作为差异段。
     */
    @Test
    fun testCoarseFactoringDoesNotExtractPartialSuffix() {
        val sites = siteRefs("AB测试1XY", "AB测试2XY", "AB测试3XZ")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)

        val group = affix.firstOrNull()
        assertTrue("应存在合并组，实际: $affix", group != null)
        assertEquals("AB测试{N0}", group!!.skeleton)
        // 差异段应包含整个尾部（包括未共享的 XZ/XY），而不是只抽出数字
        val diffs = group.variants.map { it.diff }.toSet()
        assertTrue("差异段应包含 1XY/2XY/3XZ，实际: $diffs", diffs == setOf("1XY", "2XY", "3XZ"))
    }

    /**
     * 合并组必须至少包含 2 个不同站点；单站点不成组。
     */
    @Test
    fun testSingleSiteDoesNotMerge() {
        val sites = siteRefs("测试数据1")
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue("单站点不应产生合并建议，实际 affix=$affix digit=$digit", affix.isEmpty() && digit.isEmpty())
    }

    /**
     * ＞=2 字公共前后缀才合并；只有 1 个共享字符时不应合并。
     */
    @Test
    fun testShortAffixNotMerged() {
        // 前缀 测(1字) 后缀 啊(1字) → 总公共片段 2 字，但各自只有 1 字 → 不应按前后缀合并
        val sites = siteRefs("测1啊", "测2啊")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue("1 字前缀+1 字后缀不应合并，实际: $affix", affix.isEmpty())
    }

    /**
     * 回归：测试1/测试2 应被合并成 骨架 = 测试{N0}，数字 1/2 作为差异值。
     * 用户反馈"测试1、测试2 没被提取成 \$t('测试{N0}', {N0:1})"——
     * 该用例固化"合并算法确实会生成此候选"，若将来回归失败即说明被改坏。
     */
    @Test
    fun testSimilarTextWithDigitIsFactorized() {
        val sites = siteRefs("测试1", "测试2")
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(sites)

        // 数字抽取：骨架 = 测试{N0}，每站数字值 1 / 2
        val dg = digit.firstOrNull()
        assertTrue("应生成数字抽取候选，实际 digit=$digit", dg != null)
        assertEquals("测试{N0}", dg!!.skeleton)
        assertEquals(setOf("1", "2"), dg.perSites.map { it.digitValues.first() }.toSet())

        // 公共前后缀合并同样能产出 测试{N0}（前缀 测试）
        assertTrue("应存在公共前后缀候选，实际 affix=$affix", affix.any { it.skeleton == "测试{N0}" })
    }
}