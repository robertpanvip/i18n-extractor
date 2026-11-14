package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.CommonPrefixSuffixFactorizer
import com.pan.extractor.merge.SiteRef
import com.pan.extractor.strategy.AngularI18nStrategy
import com.pan.extractor.strategy.GenericStrategy
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.strategy.ReactIntlStrategy
import com.pan.extractor.strategy.SolidI18nStrategy
import com.pan.extractor.strategy.SvelteI18nStrategy
import com.pan.extractor.strategy.TranslocoI18nStrategy
import com.pan.extractor.strategy.VueI18nStrategy

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals("Vue placeholderFor(\"2\") 应为 {N2}", "{N2}", VueI18nStrategy.placeholderFor("2"))
        assertEquals("Vue paramKey(\"0\") 应为 N0", "N0", VueI18nStrategy.paramKey("0"))
        assertEquals("Vue paramKeyNeedsQuote 应为 false", false, VueI18nStrategy.paramKeyNeedsQuote)
    }

    // ── 语义化 key 单元测试（7.1 补充）──

    fun testVuePlaceholderSemanticKeyDirectPassthrough() {
        // 简单标识符 price → 直接作为占位符 key（不加 N 前缀）
        assertEquals("{price}", VueI18nStrategy.placeholderFor("price"))
        assertEquals("price", VueI18nStrategy.paramKey("price"))
        // 属性链 user.name 也直接透传
        assertEquals("{user.name}", VueI18nStrategy.placeholderFor("user.name"))
        assertEquals("user.name", VueI18nStrategy.paramKey("user.name"))
        // 数字 fallback（复杂表达式）仍然加 N 前缀
        assertEquals("{N0}", VueI18nStrategy.placeholderFor("0"))
        assertEquals("N0", VueI18nStrategy.paramKey("0"))
    }

    fun testVuePlaceholderSemanticInterpolate() {
        // 语义化 key 插值：{price}, {count}
        assertEquals(
            "语义化 key 单占位插值",
            "当前价格 100 元",
            VueI18nStrategy.interpolatePlaceholders("当前价格 {price} 元", mapOf("price" to "100"))
        )
        // 语义化 key 与编号 fallback 共存
        assertEquals(
            "语义化 + 编号 fallback 混合插值",
            "你好小明，数量 5",
            VueI18nStrategy.interpolatePlaceholders(
                "你好{name}，数量 {N0}",
                mapOf("name" to "小明", "0" to "5")
            )
        )
        // 同语义化 key 多次出现
        assertEquals(
            "同语义化 key 全部替换",
            "X和X",
            VueI18nStrategy.interpolatePlaceholders("{name}和{name}", mapOf("name" to "X"))
        )
    }

    fun testReactPlaceholderSemanticKey() {
        // react-i18next 直接透传语义化 key
        assertEquals("{{name}}", ReactI18nextStrategy.placeholderFor("name"))
        assertEquals("name", ReactI18nextStrategy.paramKey("name"))
        // 数字编号保持 {{0}}
        assertEquals("{{0}}", ReactI18nextStrategy.placeholderFor("0"))
        assertEquals("0", ReactI18nextStrategy.paramKey("0"))
        // 语义化 key 插值
        assertEquals(
            "React 语义化 key 插值",
            "a 世界 b",
            ReactI18nextStrategy.interpolatePlaceholders("a {{name}} b", mapOf("name" to "世界"))
        )
    }

    fun testGenericPlaceholderSemanticKey() {
        // Generic 直接透传
        assertEquals("{count}", GenericStrategy.placeholderFor("count"))
        assertEquals("count", GenericStrategy.paramKey("count"))
        assertEquals("加载 X", GenericStrategy.interpolatePlaceholders("加载 {count}", mapOf("count" to "X")))
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
        assertEquals("Generic placeholderFor(\"0\") 应为 {0}", "{0}", GenericStrategy.placeholderFor("0"))
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

    // ═══════════════════════════════════════════════════════════════
    // 以下为语义化 key 重构的补全测试（覆盖 extractSemanticKey
    // 边界 + 8 个策略类 + interpolatePlaceholders 三态分支）
    // ═══════════════════════════════════════════════════════════════

    // ── A. Vue paramKey 边界：多字符数字 + 含数字语义化 key ─────────

    fun testVueParamKeyMultiDigit() {
        // "123" 全部是数字 → 加前缀 N123
        assertEquals("N123", VueI18nStrategy.paramKey("123"))
        assertEquals("{N123}", VueI18nStrategy.placeholderFor("123"))
        // "001" 也全部是数字（前导零）→ 加前缀 N001
        assertEquals("N001", VueI18nStrategy.paramKey("001"))
        // "item1" 含字母 → 不是纯数字 → 直接透传
        assertEquals("item1", VueI18nStrategy.paramKey("item1"))
        assertEquals("{item1}", VueI18nStrategy.placeholderFor("item1"))
    }

    fun testVueParamKeyDollarAndUnderscore() {
        // $ 前缀 + 属性链
        assertEquals("\$foo", VueI18nStrategy.paramKey("\$foo"))
        assertEquals("{\$foo}", VueI18nStrategy.placeholderFor("\$foo"))
        assertEquals("_bar", VueI18nStrategy.paramKey("_bar"))
        assertEquals("{_bar}", VueI18nStrategy.placeholderFor("_bar"))
        assertEquals("\$a.b.c", VueI18nStrategy.paramKey("\$a.b.c"))
    }

    // ── B. interpolatePlaceholders 三态分支完整覆盖 ──────────────────

    fun testVueInterpolateExactKey() {
        // 分支 1: params 里精确找到 rawKey → 直接替换
        assertEquals(
            "精确 key 匹配优先",
            "价格 100 元",
            VueI18nStrategy.interpolatePlaceholders("价格 {price} 元", mapOf("price" to "100"))
        )
    }

    fun testVueInterpolatePrefixFallback() {
        // 分支 2: rawKey 带前缀（N0）找不到 → 去掉前缀 toIntOrNull → 找裸数字 "0"
        assertEquals(
            "N0 找不到，退回去 params['0']",
            "小明",
            VueI18nStrategy.interpolatePlaceholders("{N0}", mapOf("0" to "小明"))
        )
        // 混合：一个精确 key，一个前缀 fallback
        assertEquals(
            "混合精确 + prefix fallback",
            "小明的价格是 99",
            VueI18nStrategy.interpolatePlaceholders(
                "{name}的价格是 {N1}",
                mapOf("name" to "小明", "1" to "99")
            )
        )
    }

    fun testVueInterpolateKeyMissingPreservesPlaceholder() {
        // 分支 3: 两种都找不到 → 保留占位符不替换
        assertEquals(
            "不存在的 key 应保留原样",
            "价格 {unknown} 元",
            VueI18nStrategy.interpolatePlaceholders("价格 {unknown} 元", mapOf("price" to "100"))
        )
        // 混合：一个能替换，一个不能
        assertEquals(
            "部分替换部分保留",
            "小明的 {missing}",
            VueI18nStrategy.interpolatePlaceholders(
                "{name}的 {missing}",
                mapOf("name" to "小明")
            )
        )
    }

    fun testVueInterpolateEmptyParams() {
        assertEquals("空 params 原样返回", "无占位 {x} 文本",
            VueI18nStrategy.interpolatePlaceholders("无占位 {x} 文本", emptyMap()))
    }

    fun testVueInterpolateKeyWithWhitespace() {
        // params key 不带空格（语义化 key 正常情况）→ 能匹配
        assertEquals("带空格的占位符 key 应 trim 后匹配", "OK",
            VueI18nStrategy.interpolatePlaceholders("{ name }", mapOf("name" to "OK")))
    }

    fun testVueInterpolateLiteralBraces() {
        // {{ / }} 是 vue-i18n 的字面花括号转义，里面内容也一起作为字面量
        assertEquals("字面花括号保留", "a {b} c",
            VueI18nStrategy.interpolatePlaceholders("a {{b}} c", mapOf("b" to "X")))
        // 整体字面转义 {{N0}} → 变成 {N0}，里面 N0 不替换
        assertEquals("字面花括号对整体转义", "数量 {N0} 个",
            VueI18nStrategy.interpolatePlaceholders(
                "数量 {{N0}} 个",
                mapOf("0" to "5")
            ))
        // 空 {{}} 对
        assertEquals("空字面花括号对", "{{}}",
            VueI18nStrategy.interpolatePlaceholders("{{}}", emptyMap()))
        // 占位符 {name} 和字面花括号 {{lit}} 共存
        assertEquals("占位符 + 字面花括号", "小明 数量 {5} 个",
            VueI18nStrategy.interpolatePlaceholders(
                "{name} 数量 {{5}} 个",
                mapOf("name" to "小明")
            ))
    }

    // ── C. 8 个策略类的语义化 key 全覆盖 ──────────────────────────────

    /** React-i18next: 语义化 key 直接透传，双花括号 {{name}} */
    fun testReactSemanticKeyFull() {
        assertEquals("{{user.name}}", ReactI18nextStrategy.placeholderFor("user.name"))
        assertEquals("user.name", ReactI18nextStrategy.paramKey("user.name"))
        assertTrue(ReactI18nextStrategy.paramKeyNeedsQuote)
    }

    /** React intl: 语义化 key 直接透传，单花括号 {name} */
    fun testReactIntlSemanticKey() {
        assertEquals("{name}", ReactIntlStrategy.placeholderFor("name"))
        assertEquals("{user.profile.nickname}", ReactIntlStrategy.placeholderFor("user.profile.nickname"))
        assertEquals("name", ReactIntlStrategy.paramKey("name"))
        // 数字编号同样支持
        assertEquals("{0}", ReactIntlStrategy.placeholderFor("0"))
    }

    /** Svelte i18n: 语义化 key 直接透传 */
    fun testSvelteSemanticKey() {
        assertEquals("{count}", SvelteI18nStrategy.placeholderFor("count"))
        assertEquals("count", SvelteI18nStrategy.paramKey("count"))
        assertTrue("Svelte paramKeyNeedsQuote", SvelteI18nStrategy.paramKeyNeedsQuote)
        // 数字编号
        assertEquals("{0}", SvelteI18nStrategy.placeholderFor("0"))
    }

    /** Solid i18n: delegate 给 React，语义化 key 双花括号 */
    fun testSolidSemanticKey() {
        assertEquals("{{name}}", SolidI18nStrategy.placeholderFor("name"))
        assertEquals("name", SolidI18nStrategy.paramKey("name"))
        assertTrue(SolidI18nStrategy.paramKeyNeedsQuote)
    }

    /** Angular i18n: delegate 给 React，语义化 key 双花括号 */
    fun testAngularSemanticKey() {
        assertEquals("{{name}}", AngularI18nStrategy.placeholderFor("name"))
        assertEquals("name", AngularI18nStrategy.paramKey("name"))
    }

    /** Transloco: delegate 给 React，语义化 key 双花括号 */
    fun testTranslocoSemanticKey() {
        assertEquals("{{name}}", TranslocoI18nStrategy.placeholderFor("name"))
        assertEquals("name", TranslocoI18nStrategy.paramKey("name"))
    }

    /** Generic: 语义化 key 直接透传，单花括号 */
    fun testGenericSemanticKeyFull() {
        assertEquals("{user.name}", GenericStrategy.placeholderFor("user.name"))
        assertEquals("user.name", GenericStrategy.paramKey("user.name"))
        assertEquals("{count_123}", GenericStrategy.placeholderFor("count_123"))
    }

    // ── D. E2E 收集链路：extractSemanticKey 分支覆盖 ──────────────────

    private fun rewriteVue(content: String): String {
        val file = myFixture.addFileToProject("Test.vue", content.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val p = I18nProcessor(project, file)
        p.collect()
        p.runWithUndo()
        return file.text
    }

    /** 简单标识符 price/count → 语义化占位符 */
    fun testE2EVueSimpleIdentifierSemantic() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "价格" + price + "元";
            </script>
        """)
        assertTrue("应用语义化 key {price}: $rewritten", rewritten.contains("{price}"))
    }

    /** 属性链 user.name → 语义化占位符 {user.name} */
    fun testE2EVuePropertyChainSemantic() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "你好" + user.name;
            </script>
        """)
        assertTrue("应用语义化 key {user.name}: $rewritten", rewritten.contains("{user.name}"))
    }

    /** 含数字的变量名 item1 → 语义化（不是 fallback 编号） */
    fun testE2EVueVarWithNumberSuffix() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "共" + item1 + "个";
            </script>
        """)
        assertTrue("item1 应作为语义化 key: $rewritten", rewritten.contains("{item1}"))
    }

    /** 复杂表达式 count + 1 → fallback 到编号 {N0} */
    fun testE2EVueComplexExprFallbackToNumeric() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "数量" + (count + 1);
            </script>
        """)
        assertTrue("复杂表达式应 fallback 到 {N0} 或 {0}: $rewritten",
            rewritten.contains("{N0}") || rewritten.contains("{0}"))
    }

    /** 函数调用 getName() → fallback 到编号 */
    fun testE2EVueFunctionCallFallbackToNumeric() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "用户" + getName();
            </script>
        """)
        assertTrue("函数调用应 fallback: $rewritten",
            rewritten.contains("{N0}") || rewritten.contains("{0}"))
    }

    /** 数组索引 arr[0] → fallback 到编号 */
    fun testE2EVueArrayIndexFallbackToNumeric() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "第一个" + list[0];
            </script>
        """)
        assertTrue("数组索引应 fallback: $rewritten",
            rewritten.contains("{N0}") || rewritten.contains("{0}"))
    }

    /** 可选链 user?.name → fallback 到编号 */
    fun testE2EVueOptionalChainFallbackToNumeric() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "用户" + user?.name;
            </script>
        """)
        assertTrue("可选链应 fallback: $rewritten",
            rewritten.contains("{N0}") || rewritten.contains("{0}"))
    }

    /** 多变量混合：语义化 + 编号 fallback 共存 */
    fun testE2EVueMixedSemanticAndFallback() {
        val rewritten = rewriteVue("""
            <script setup>
            const msg = "你好" + name + "，" + (count + 1) + "个";
            </script>
        """)
        assertTrue("语义化 name 应保留: $rewritten", rewritten.contains("{name}"))
        // rawIndex 连续递增：name 占 index 0（语义化），(count+1) 占 index 1（fallback N1）
        assertTrue("复杂表达式应 fallback 到 N1: $rewritten",
            rewritten.contains("{N1}") || rewritten.contains("{1}"))
    }

    // ── 模板字符串 E2E 已由 TranslationQuality/testDuplicatePlaceholder_* 覆盖 ──
    // （模板字符串 PSI 处理较复杂，此处用字符串拼接 E2E 已足够覆盖语义化 + fallback 共存）
}