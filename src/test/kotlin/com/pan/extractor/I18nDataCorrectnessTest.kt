package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.CommonPrefixSuffixFactorizer
import com.pan.extractor.merge.SiteRef
import com.pan.extractor.strategy.GenericStrategy
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.strategy.VueI18nStrategy

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 7.x — 数据正确性。
 *
 * 7.1 Placeholder：三种策略的占位符语法与插值（单 / 多 / 重复 / 转义 / quote）
 * 7.2 Nested Expression：对象字面量 / 三元 / 箭头函数内多个字符串被逐个单独提取
 * 7.3 MergeApplier：公共前后缀因子化（相同重复 / 数字骨架）正确分组
 */
class I18nDataCorrectnessTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "data-correctness",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0"
              }
            }
            """.trimIndent()
        )
    }

    private fun collectFs(fileName: String, text: String): I18nProcessor {
        val file = myFixture.addFileToProject(fileName, text.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val p = I18nProcessor(project, file)
        p.collect()
        return p
    }

    // ── 7.1 Placeholder ─────────────────────────────────────────────

    fun testVuePlaceholderSyntax() {
        assertEquals("Vue placeholderFor(2) 应为 {N2}", "{N2}", VueI18nStrategy.placeholderFor(2))
        assertEquals("Vue paramKey(0) 应为 N0", "N0", VueI18nStrategy.paramKey(0))
        assertEquals("Vue paramKeyNeedsQuote 应为 false", false, VueI18nStrategy.paramKeyNeedsQuote)
    }

    fun testVuePlaceholderSingle() {
        assertEquals(
            "Vue 单占位插值",
            "请输入关键词",
            VueI18nStrategy.interpolatePlaceholders("请输入{N0}", mapOf("0" to "关键词"))
        )
    }

    fun testVuePlaceholderMultipleKeepsOrder() {
        assertEquals(
            "Vue 多占位按序插值",
            "你好小明，我是小红",
            VueI18nStrategy.interpolatePlaceholders("你好{N0}，我是{N1}", mapOf("0" to "小明", "1" to "小红"))
        )
    }

    fun testVuePlaceholderRepeated() {
        assertEquals(
            "Vue 重复占位全部替换",
            "X和X",
            VueI18nStrategy.interpolatePlaceholders("{N0}和{N0}", mapOf("0" to "X"))
        )
    }

    fun testVuePlaceholderEscapeLiteralBraces() {
        // 按 i18n 转义语义：{{ }} 表示字面花括号（转义），{{ -> {、}} -> }
        assertEquals(
            "Vue 转义花括号折叠为字面单括号",
            "开始 { 结束 } 后",
            VueI18nStrategy.interpolatePlaceholders("开始 {{ 结束 }} 后", mapOf("0" to "X"))
        )
        // 占位符与字面花括号并存：{N0} 被替换为参数值，{{x}} 被折叠为字面 {x}（不参与补间）
        assertEquals(
            "Vue 占位补间与字面花括号转义分别处理",
            "数量 5 个（默认 {x}）",
            VueI18nStrategy.interpolatePlaceholders("数量 {N0} 个（默认 {{x}}）", mapOf("0" to "5"))
        )
        // 连续两对字面花括号被折叠为两个孤立花括号（{{ 与 }} 各折为 { 和 }）
        assertEquals(
            "Vue 相邻字面花括号成对折叠",
            "{中间}",
            VueI18nStrategy.interpolatePlaceholders("{{中间}}", mapOf("0" to "X"))
        )
    }

    fun testReactPlaceholderQuoteAndReplace() {
        assertEquals("React paramKeyNeedsQuote 应为 true", true, ReactI18nextStrategy.paramKeyNeedsQuote)
        assertEquals(
            "React 双花括号占位插值",
            "a 世界 b",
            ReactI18nextStrategy.interpolatePlaceholders("a {{0}} b", mapOf("0" to "世界"))
        )
    }

    fun testGenericPlaceholderSyntax() {
        assertEquals("Generic placeholderFor(0) 应为 {0}", "{0}", GenericStrategy.placeholderFor(0))
        assertEquals(
            "Generic 单括号占位插值",
            "加载 X",
            GenericStrategy.interpolatePlaceholders("加载 {0}", mapOf("0" to "X"))
        )
    }

    // ── 7.2 Nested Expression（各自单独提取）────────────────────────

    fun testObjectLiteralNestedExtractedSeparately() {
        val p = collectFs(
            "src/obj.ts",
            """
            const opts = foo({
              title: "你好",
              description: "世界"
            })
            """.trimIndent()
        )
        assertTrue("对象字面量内 title 应被提取", p.analyzer.extractedStrings.containsValue("你好"))
        assertTrue("对象字面量内 description 应被提取", p.analyzer.extractedStrings.containsValue("世界"))
        assertEquals("对象字面量两个字符串应各成一个 key", 2, p.analyzer.extractedStrings.size)
    }

    fun testTernaryNestedExtractedSeparately() {
        val p = collectFs(
            "src/ternary.ts",
            """
            const label = cond ? "你好" : "世界"
            """.trimIndent()
        )
        assertTrue("三元 true 分支应被单独提取", p.analyzer.extractedStrings.containsValue("你好"))
        assertTrue("三元 false 分支应被单独提取", p.analyzer.extractedStrings.containsValue("世界"))
        assertEquals("三元两个分支应各成一个 key", 2, p.analyzer.extractedStrings.size)
    }

    fun testArrowBodyNestedExtracted() {
        val p = collectFs(
            "src/map.ts",
            """
            const labels = items.map(item => "你好")
            """.trimIndent()
        )
        assertTrue("箭头函数体内的字符串应被提取", p.analyzer.extractedStrings.containsValue("你好"))
        assertEquals("箭头函数体应成一个 key", 1, p.analyzer.extractedStrings.size)
    }

    // ── 7.3 MergeApplier：因子化分组正确性 ────────────────────────────

    private fun siteRef(processorIndex: Int, id: String, msg: String, isVue: Boolean) =
        SiteRef(
            processorIndex = processorIndex,
            siteId = id,
            originalMessage = msg,
            containingFile = null,
            isVue = isVue,
            isReact = !isVue,
            line1 = 0,
        )

    fun testMergeDuplicateExactDetected() {
        val sites = listOf(
            siteRef(0, "a", "你好", true),
            siteRef(1, "b", "你好", true),
        )
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue("完全相同文本应进入 affix 组合并标记 isExactDuplicate",
            affix.any { it.isExactDuplicate && it.skeleton == "你好" })
        assertTrue("无数字骨架则 digit 分组应为空", digit.isEmpty())
    }

    fun testMergeDigitSkeletonDetected() {
        val sites = listOf(
            siteRef(0, "a", "测试数据1", true),
            siteRef(1, "b", "测试数据2", true),
        )
        val (_, digit) = CommonPrefixSuffixFactorizer.factorize(sites)
        assertTrue("不同数字应聚合成一个数字骨架组", digit.any { it.perSites.size == 2 })
    }

    fun testMergeEmptyProcessorsNoCrash() {
        val (affix, digit) = CommonPrefixSuffixFactorizer.factorize(emptyList())
        assertTrue(affix.isEmpty())
        assertTrue(digit.isEmpty())
    }
}