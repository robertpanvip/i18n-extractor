package com.pan.extractor

import com.pan.extractor.merge.CommonPrefixSuffixFactorizer
import com.pan.extractor.merge.SiteRef

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

    /**
     * 用户反馈 Bug：你好hello / 你好hello2 应合并成「一个翻译」。
     * 其中 你好hello2 含数字 2，骨架为 你好hello{N0}；而 你好hello 恰好等于骨架去占位后的文本
     * （你好hello{N0} 去掉 {N0} → 你好hello），应作为「空数字」变体并入同一组，
     * 而不是各自独立成两条翻译。
     */
    @Test
    fun testPrefixOnlyWithDigitSuffixIsMerged() {
        val sites = siteRefs("你好hello", "你好hello2")
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(sites)

        val dg = digit.firstOrNull { it.skeleton == "你好hello{N0}" }
        assertTrue("应生成数字抽取候选，骨架=你好hello{N0}，实际 digit=$digit", dg != null)
        val perSiteTexts = dg!!.perSites.map { it.site.originalMessage to it.digitValues.first() }.toSet()
        assertTrue(
            "你好hello 应以空数字并入，你好hello2 以 2 并入，实际 $perSiteTexts",
            perSiteTexts == setOf("你好hello" to "", "你好hello2" to "2")
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 追加覆盖：占位提示 / 后缀-only / 数字边界 / 完全重复提示
    // ─────────────────────────────────────────────────────────────

    /**
     * 无数字骨架、无公共前后缀、但站点数 >=2 时，应生成一条 selected=false 的
     * "无可自动抽取"占位提示候选（factorize() 第 102-111 行），而不是空列表。
     */
    @Test
    fun testNoMergePlaceholderShowWhenSitesTwo() {
        val sites = siteRefs("苹果", "香蕉")
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue("无公共前后缀且无数字，应生成占位提示候选，实际 affix=$affix digit=$digit", digit.any { !it.selected && it.skeleton.contains("No auto-extractable digit skeleton") })
    }

    /**
     * 前后缀同时存在时应整体抽取。
     * "测试1团结"/"测试2团结"：前缀 测试(2字) + 后缀 团结(2字) → 骨架 测试{N0}团结。
     * 注意：算法按「首字符」分桶，跨首字符的纯后缀场景（如 桶A团结/箱B团结）不会合并，
     * 因此这里用同首字符输入验证后缀确实被提取进骨架。
     */
    @Test
    fun testSuffixOnlyGroupMerges() {
        val sites = siteRefs("测试1团结", "测试2团结")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val group = affix.firstOrNull { it.skeleton == "测试{N0}团结" }
        assertTrue("同首字符下后缀应被提取为骨架后缀，实际 affix=$affix", group != null)
        assertEquals("测试{N0}团结", group!!.skeleton)
    }

    /**
     * 数字抽取：小数（带小数点）应被识别为数字段，骨架保留其他汉字部分。
     */
    @Test
    fun testDigitGroupSupportsDecimal() {
        val sites = siteRefs("长度1.5米", "长度2.5米")
        val (_, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        val dg = digit.firstOrNull { it.skeleton == "长度{N0}米" }
        assertTrue("应生成小数数字抽取候选，实际 digit=$digit", dg != null)
        assertEquals(setOf("1.5", "2.5"), dg!!.perSites.map { it.digitValues.first() }.toSet())
        assertTrue("纯小数数字差异应标记 allNonChinese=true", dg.perSites.all { it.allNonChinese })
    }

    /**
     * 汉字不足 2 字的句子不参与数字抽取（"页1"/"页2" 只有 1 个汉字）。
     */
    @Test
    fun testDigitGroupRequiresMinHanCount() {
        val sites = siteRefs("页1", "页2")
        val (_, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        // 汉字不足 2 字不参与数字抽取；只允许出现 selected=false 的「无可抽取」占位提示
        assertTrue("汉字不足 2 字不应生成可应用的数字抽取，实际 digit=$digit", digit.none { it.selected })
    }

    /**
     * 数字段只在单句出现（entries.size < 2）时不生成抽取候选。
     */
    @Test
    fun testDigitGroupRequiresAtLeastTwoSites() {
        val sites = siteRefs("状态1")
        val (_, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue("单站点数字不应抽取，实际 digit=$digit", digit.isEmpty())
    }

    /**
     * 完全相同文本多次出现 → 生成一条 selected=false 的"同 key 合并去重"提示候选，
     * 且变体 diff 即原句本身。
     */
    @Test
    fun testExactDuplicateHintGroup() {
        val sites = siteRefs("重复文案", "重复文案")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val hint = affix.firstOrNull { it.id.startsWith("AG_EXACT_DUP_") }
        assertTrue("完全相同文本应生成提示候选，实际 affix=$affix", hint != null)
        assertTrue("提示候选默认不选中", !hint!!.selected)
        assertEquals("重复文案", hint.skeleton)
        assertEquals("重复文案", hint.variants.single().diff)
    }

    /**
     * 完全相同的多句文本：即使公共前后缀算法也能合并，exact-dup 提示与 affix 组
     * 应通过 distinctBy 去重（按 skeleton+diff 集合），不出现重复候选。
     */
    @Test
    fun testExactDuplicateHintDeduplicatedAgainstAffix() {
        val sites = siteRefs("重复文案", "重复文案")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val sameSkeleton = affix.filter { it.skeleton == "重复文案" }
        assertTrue("相同骨架候选应去重，实际数量=${sameSkeleton.size}", sameSkeleton.size <= 1)
    }

    /**
     * Bug 回归（问题 4）：完全相同文本的提示组必须标记 isExactDuplicate=true。
     * 应用阶段依据该标记跳过骨架重写，从而避免生成
     * $t('全选', { N0: $t('全选') }) 这类自引用调用。
     */
    @Test
    fun testExactDuplicateHintIsMarkedAsExactDuplicate() {
        val sites = siteRefs("全选", "全选")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val hint = affix.firstOrNull { it.id.startsWith("AG_EXACT_DUP_") }
        assertTrue("相同文本应生成提示候选", hint != null)
        assertTrue("提示候选应标记 isExactDuplicate=true", hint!!.isExactDuplicate)
    }

    /**
     * Bug 回归（问题 5）：当两句只共享很短的公共片段、差异是整句大部分内容时，
     * 不应生成无意义的合并组（否则会写成 $t('当前{N0}', {N0: $t('职位仅能选择60人')})）。
     *
     * "当前职位仅能选择60人" 与 "当前用户已离线" 只共享前缀 "当前"(2字/约18%)，
     * 共享片段占整句比例过低 → 必须被防呆逻辑拒绝。
     */
    @Test
    fun testMeaninglessAffixGroupRejected() {
        val sites = siteRefs("当前职位仅能选择60人", "当前用户已离线")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue(
            "仅共享 2 字前缀、差异为整句主体时不应产生 affix 组，实际: $affix",
            affix.none { it.skeleton.contains("当前{N0}") }
        )
    }

    /**
     * 对照：共享片段占比足够（/>1/3）时仍应正常生成合并组，防止防呆过度过滤。
     * "测试职位仅能选择60人" / "测试职位仅能选择30人" 共享前缀 "测试职位仅能选择"(8字)
     * 与后缀 "人"(1字)，差异是数字，应照常合并。
     */
    @Test
    fun testMeaningfulAffixGroupStillGenerated() {
        val sites = siteRefs("测试职务仅能选择60人", "测试职务仅能选择30人")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val group = affix.firstOrNull { it.skeleton.startsWith("测试职务仅能选择{N0}") }
        assertTrue(
            "共享片段占比足够时应照常生成 affix 组，实际: $affix",
            group != null
        )
        // 数字边界保护：60 和 30 是不同的完整数字，不应被拆分为 diff=6/3 + 后缀 "0人"，
        // 应正确生成骨架 "测试职务仅能选择{N0}人" 且 diff 为 "60"/"30"
        assertEquals("测试职务仅能选择{N0}人", group!!.skeleton)
        assertEquals(setOf("60", "30"), group.variants.map { it.diff }.toSet())
    }

    /**
     * 用户反馈 Bug 3 回归：限制30字符 / 限制200字符 应提炼为 "限制{N0}字符"，
     * 而不是 "限制{N0}0字符"。
     *
     * 根因：后缀扫描时，'0' 在两句中分别属于 "30" 和 "200" 两个不同的数字 token，
     * 但算法只按 char 匹配，把 '0' 计入了公共后缀，导致后缀 = "0字符"。
     * 修复：后缀扫描时若遇到数字字符，检查完整数字 token 是否相同，不同则停止扫描。
     */
    @Test
    fun testDigitBoundaryInSuffix() {
        val sites = siteRefs("限制30字符", "限制200字符")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val group = affix.firstOrNull { it.skeleton.startsWith("限制{N0}") }
        assertTrue("应生成 affix 组，实际: $affix", group != null)
        // 骨架应为 "限制{N0}字符"，不是 "限制{N0}0字符"
        assertEquals("限制{N0}字符", group!!.skeleton)
        assertEquals(setOf("30", "200"), group.variants.map { it.diff }.toSet())
    }

    /**
     * 性能回归：旧实现对每个候选逐条 `list.remove(sref)`（O(k) 线性扫描），而桶一直保持
     * 很大（每条只与它的配对伙伴共享长前缀，与其余几千条只共享首字符），因此每个 anchor
     * 都要扫描近全表并做一次 O(k) remove —— 累加起来把桶内算法从 O(k^2) 恶化成 O(k^3)，
     * 正是"单页转换卡到 20s+"的根因。
     *
     * 本用例构造「大量小分组 + 大表不缩小」的极端负载：2 万条消息、每对共享约 16 位随机
     * 十六进制前缀（组内伙伴可合并），但**组间彼此只共享首字符**（伪随机 hex 几乎不碰撞），
     * 逼着每个 anchor 各自形成 2 成员的组并反复在大表上做 remove。在 O(k^2) 实现下应于秒级
     * 完成；O(k^3) 复现则要卡几十秒。只断言上界（宽松阈值，避免 CI 抖动误报）。
     */
    @Test
    fun testLargeSingleBucketCompletesInTime() {
        val n = 20_000
        val sites = ArrayList<SiteRef>(n)
        // 固定乘子 → 伪随机 hex 稳定，且组间长前缀几乎不共享，组内伙伴完全共享。
        val mul = 0x9E3779B97F4A7C15UL
        for (i in 0 until n) {
            val group = (i / 2).toULong()
            val hex = (group * mul).toString(16)
            val side = if (i % 2 == 0) "甲" else "乙"
            val msg = "配$hex$side"   // 仅首字符 '配' 在所有消息间共享
            sites.add(site(msg, i))
        }

        val start = System.nanoTime()
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("affix 组数: ${affix.size}", affix.isNotEmpty())

        // 2 万条「大表 + 大量小分组」负载在 O(k^2) 下应落在秒级；给足余量避免 CI 抖动。
        assertTrue("factorize 2 万条同桶消息耗时 ${elapsedMs}ms，疑似退化为 O(k^3)，实际 affix=${affix.size}", elapsedMs < 15_000)
    }

    /**
     * 真实页面量级：单页提取出几千条、按首字分量数十桶、每桶几十条。桶小 + 邻居索引，
     * 应亚秒级完成 —— 这才是"单页转换"的真实负载（不再是 2 万条同首字符的合成极端）。
     */
    @Test
    fun testRealisticPageScaleCompletesInMillis() {
        val buckets = 60
        val perBucket = 80
        val sites = ArrayList<SiteRef>(buckets * perBucket)
        val prefixes = CharArray(buckets)
        for (b in 0 until buckets) prefixes[b] = (0x4E00 + b * 7).toChar()
        var idx = 0
        for (b in 0 until buckets) {
            val p = prefixes[b]
            for (j in 0 until perBucket) {
                val msg = "${p}请确认${(idx % 9) + 1}号操作后继续" // 桶内共享长前缀 请确认 + 数字差异
                sites.add(site(msg, idx++))
            }
        }

        val start = System.nanoTime()
        CommonPrefixSuffixFactorizer.factorize(sites)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        // 真实页面量级（近 5000 条）应在亚秒内完成；宽松阈值避免 CI 抖动。
        assertTrue("真实页面量级近 5000 条耗时 ${elapsedMs}ms，疑似退化", elapsedMs < 3_000)
    }

    /**
     * Bug 回归（问题 2/4）：Vue/Generic 占位符 {N0}/{0} 不应被合并算法拆分到前后缀中。
     *
     * 模板字符串提取后 originalMessage 已包含这些占位符（如 `最大值{0}` 来自 `最大值${num}`），
     * 合并算法不应将 `{0}` 拆分到前缀或后缀中，否则会产生 "最{N0}{N0}" 或 "超过{N{N0}}%的智能体"
     * 等错误骨架。
     *
     * 本用例验证：两条包含相同 Generic 占位符 {0} 的字符串，因占位符保护，
     * 后缀扫描应停在 {0} 边界处，不产生骨架合并。
     */
    @Test
    fun testGenericPlaceholderNotSplitInSuffix() {
        // "最大值{0}" 与 "大值{0}" 共享后缀 {0}，但 {0} 是占位符不应被拆分入后缀，
        // 因此后缀扫描应停在 {0} 边界，使 sLen=0，不满足合并阈值。
        val sites = siteRefs("最大值{0}", "大值{0}")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        // 不应产生将 {0} 拆入后缀的合并组
        val group = affix.firstOrNull { it.skeleton.contains("最大值{N0}") || it.skeleton.contains("大值{N0}") }
        assertTrue("Vue/Generic 占位符 {0} 不应被拆入后缀，实际: $affix", group == null)
    }

    /**
     * Bug 回归（问题 2/4）：Vue 占位符 {N0} 不应被合并算法拆分到前缀中。
     *
     * 本用例验证：两条字符串共享前缀 "超过{" 但 {N0} 是占位符不应被拆分入前缀，
     * 前缀扫描应停在 { 边界处。
     */
    @Test
    fun testVuePlaceholderNotSplitInPrefix() {
        // "超过{N0}%的智能体" 与 "超过{N1}%的智能体" 共享前缀 "超过{" + 后缀 "}%的智能体"，
        // 但 {N0} 和 {N1} 是占位符不应被拆分，前缀扫描遇到 { 应停止。
        val sites = siteRefs("超过{N0}%的智能体", "超过{N1}%的智能体")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        // 不应产生将 {N0}/{N1} 拆入前缀/后缀的合并组
        val group = affix.firstOrNull { it.skeleton.contains("超过{") }
        assertTrue("Vue 占位符 {N0}/{N1} 不应被拆入前缀/后缀，实际: $affix", group == null)
    }

    /**
     * Bug 回归（问题 2/4）：Generic 占位符 {0} 不应被拆分到前缀中。
     *
     * 本用例验证：两条包含 Generic 占位符 {0}/{1} 的字符串，前缀扫描应停在 { 边界处。
     */
    @Test
    fun testGenericPlaceholderNotSplitInPrefix() {
        // "超过{0}%的智能体" 与 "超过{1}%的智能体" 共享前缀 "超过{" + 后缀 "}%的智能体"，
        // 但 {0} 和 {1} 是占位符不应被拆分。
        val sites = siteRefs("超过{0}%的智能体", "超过{1}%的智能体")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        // 不应产生将 {0}/{1} 拆入前缀/后缀的合并组
        val group = affix.firstOrNull { it.skeleton.contains("超过{") }
        assertTrue("Generic 占位符 {0}/{1} 不应被拆入前缀/后缀，实际: $affix", group == null)
    }

    /**
     * 对照：不含占位符的字符串，正常共享前后缀时仍应产生合并组（防止保护过度）。
     * "超过A%的智能体" 与 "超过B%的智能体" 共享前缀 "超过" 和后缀 "%的智能体"，
     * 应正常合并为骨架 "超过{N0}%的智能体"。
     */
    @Test
    fun testNonPlaceholderStringsStillMerge() {
        val sites = siteRefs("超过A%的智能体", "超过B%的智能体")
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val group = affix.firstOrNull { it.skeleton == "超过{N0}%的智能体" }
        assertTrue("不含占位符的正常字符串应照常合并，实际: $affix", group != null)
        assertEquals(setOf("A", "B"), group!!.variants.map { it.diff }.toSet())
    }
}