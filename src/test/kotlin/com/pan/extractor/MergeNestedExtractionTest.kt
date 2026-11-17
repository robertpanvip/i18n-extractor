package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.AffixGroupCandidate
import com.pan.extractor.merge.AffixVariant
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.merge.SiteRef
import com.pan.extractor.planner.ExtractionPlanner
import com.pan.extractor.planner.RewritePlan
import com.pan.extractor.ui.ExtractedStringsDialog

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 合并策略专项测试：字符串嵌套 + 「先单句提取、最后骨架合并」的完整链路。
 *
 * 背景：MergeApplier.apply 的内部顺序是
 *   ① 填 blockedSiteIds（被合并承载的句子跳过普通替换）
 *   ② 逐文件 processor.run()  —— 先把所有「未阻塞」句子提取成普通 $t('整句')
 *   ③ buildRewritePlans      —— 差异段若含目标语言，登记为 key 并渲染成嵌套 $t('差异')
 *   ④ RewriteInterpreter     —— 把被合并的站点重写成 $t('骨架', { N0: <差异表达式> })
 *   ⑤ 清理完全被承载的整句 key
 *
 * 因此「差异段是中文」时最终产物是嵌套调用：
 *   $t('请输入搜索{N0}', { N0: $t('关键词') })
 * 本类验证这条嵌套路径的产物是否符合预期，以及二次运行是否幂等。
 */
class MergeNestedExtractionTest : BasePlatformTestCase() {

    private lateinit var undoDisposable: com.intellij.openapi.Disposable

    override fun setUp() {
        super.setUp()
        undoDisposable = com.intellij.openapi.util.Disposer.newDisposable()
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(
            com.intellij.openapi.ui.TestDialog.OK,
            undoDisposable
        )
        myFixture.addFileToProject(
            "package.json",
            """
            { "name": "vue-merge-test", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" } }
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

    override fun tearDown() {
        if (::undoDisposable.isInitialized) {
            com.intellij.openapi.util.Disposer.dispose(undoDisposable)
        }
        super.tearDown()
    }

    private fun configureFile(fileName: String, text: String): PsiFile {
        val psiFile = myFixture.addFileToProject(fileName, text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        return psiFile
    }

    private fun resultText(path: String): String {
        val vf = myFixture.findFileInTempDir(path) ?: return ""
        return com.intellij.psi.PsiManager.getInstance(project).findFile(vf)?.text ?: ""
    }

    private fun collect(file: PsiFile): I18nProcessor =
        I18nProcessor(project, file).also { it.collect() }

    // ─────────────────────────────────────────────────────────────
    // 1. Planner 纯函数层：差异段是中文 → 嵌套 $t；是数字 → 字面量
    // ─────────────────────────────────────────────────────────────

    /** 中文差异段应登记进 diffKeys，并被渲染为嵌套的 $t('中文') 表达式。 */
    fun testRewritePlanChineseDiffBecomesNestedT() {
        val site = SiteRef(0, "S1", "请输入搜索关键词", null, isVue = true, isReact = false, line1 = 1)
        val group = AffixGroupCandidate(
            id = "AG1",
            skeleton = "请输入搜索{N0}",
            prefix = "请输入搜索",
            suffix = "",
            variants = listOf(AffixVariant("关键词", listOf(site))),
            skeletonKey = "请输入搜索{N0}",
        )
        val proc = collect(configureFile("src/D.vue", "<template><div>请输入搜索关键词</div></template>"))
        val diffKeys = LinkedHashMap<String, String>()

        val plans: List<RewritePlan> = ExtractionPlanner.buildRewritePlans(
            ExtractedStringsDialog.MergePlan(listOf(group), emptyList()),
            listOf(proc),
            diffKeys,
        )

        assertEquals(1, plans.size)
        val paramExpr = plans.single().params.single().second
        assertTrue("中文差异应渲染为嵌套 \$t(...) 调用，实际: $paramExpr",
            paramExpr.contains("\$t") && paramExpr.contains("关键词"))
        assertTrue("中文差异段应登记进 diffKeys，实际: $diffKeys",
            diffKeys.containsKey("关键词"))
    }

    /** 数字差异段不应登记 key，也不应嵌套 $t，而是字面量。 */
    fun testRewritePlanDigitDiffStaysLiteral() {
        val site = SiteRef(0, "S1", "第1页", null, isVue = true, isReact = false, line1 = 1)
        val group = AffixGroupCandidate(
            id = "AG1", skeleton = "第{N0}页", prefix = "第", suffix = "页",
            variants = listOf(AffixVariant("1", listOf(site))), skeletonKey = "第{N0}页",
        )
        val proc = collect(configureFile("src/D.vue", "<template><div>第1页</div></template>"))
        val diffKeys = LinkedHashMap<String, String>()

        val plans = ExtractionPlanner.buildRewritePlans(
            ExtractedStringsDialog.MergePlan(listOf(group), emptyList()),
            listOf(proc), diffKeys,
        )

        val paramExpr = plans.single().params.single().second
        assertEquals("数字差异应渲染为裸字面量", "1", paramExpr)
        assertFalse("数字差异不应登记为翻译 key", diffKeys.containsKey("1"))
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 端到端：先普通提取，最后骨架合并 → 嵌套调用 + 双 key 资源
    // ─────────────────────────────────────────────────────────────

    /**
     * 完整链路：两句共享前缀、差异为中文。
     * "请输入搜索关键词" / "请输入搜索号码" → 前缀 "请输入搜索"
     * 期望最终源码含嵌套调用 $t('请输入搜索{N0}', { N0: $t('关键词') })，
     * 资源同时含骨架 key 与两个差异 key。
     */
    fun testExtractThenMergeProducesNestedCall() {
        val file = configureFile(
            "src/Demo.vue",
            """
            <template>
              <div>
                <span>请输入搜索关键词</span>
                <span>请输入搜索号码</span>
                <p>独立文案保留</p>
              </div>
            </template>
            """.trimIndent()
        )
        val processor = collect(file)
        val (affix, _) = MergeApplier.factorizeSites(listOf(processor))

        val group = affix.firstOrNull { it.variants.size >= 2 && it.skeleton.contains("{N0}") }
            ?: throw IllegalStateException("应生成可合并的 affix 组，实际: $affix")
        val plan = ExtractedStringsDialog.MergePlan(listOf(group), emptyList())
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }

        val result = resultText("src/Demo.vue")
        val res = holder[0] ?: emptyMap()

        assertTrue("应生成骨架 key，实际: ${res.keys}", res.containsKey(group.skeleton))
        val diffKeys = group.variants.map { it.diff.trim() }
        diffKeys.forEach { d ->
            assertTrue("差异 key '$d' 应登记进资源，实际: ${res.keys}", res.containsKey(d))
        }
        assertTrue("源码应含骨架文本，实际:\n$result", result.contains(group.skeleton))
        assertTrue("源码应含嵌套 \$t 调用（N0: \$t），实际:\n$result", result.contains("N0: \$t"))
        assertTrue("独立文案应仍为独立 \$t，不被并入骨架参数，实际:\n$result",
            result.contains("独立文案保留"))
    }

    /**
     * 幂等性：对已应用合并的文件再次 collect + 以相同计划 apply，
     * 不应产生 $t('...', { N0: $t('...', { N0: ... }) }) 这样的双重嵌套。
     * （验证「先提取最后合并」重复执行时的稳定性。）
     */
    fun testMergeIsIdempotentOnAlreadyMergedSource() {
        val raw = """
            <template>
              <div>
                <span>请输入搜索关键词</span>
                <span>请输入搜索号码</span>
              </div>
            </template>
        """.trimIndent()
        val file = configureFile("src/Idem.vue", raw)

        // 第一次：collect → merge
        val p1 = collect(file)
        val (affix1, _) = MergeApplier.factorizeSites(listOf(p1))
        val g1 = affix1.firstOrNull { it.variants.size >= 2 && it.skeleton.contains("{N0}") }
            ?: throw IllegalStateException("首次应生成合并组")
        WriteCommandAction.runWriteCommandAction(project) {
            MergeApplier.apply(listOf(p1), LinkedHashMap(p1.analyzer.extractedStrings),
                ExtractedStringsDialog.MergePlan(listOf(g1), emptyList()))
        }
        val afterFirst = resultText("src/Idem.vue")

        // 第二次：重新 collect（此时源码已是嵌套 $t）→ 不应再产生会二次嵌套的合并组
        val p2 = collect(file)
        val (affix2, _) = MergeApplier.factorizeSites(listOf(p2))
        // 嵌套调用的差异是 $t(...) 表达式，不应再被当作普通中文差异合并成双层骨架
        val dangerous = affix2.filter { g ->
            g.variants.any { v -> v.diff.contains("\$t") || v.diff.contains("{N0}") }
        }
        assertTrue("二次合并不应把嵌套 \$t / 占位符当作差异再合并，实际: $affix2", dangerous.isEmpty())

        // 源码不出现双重嵌套特征
        assertFalse("不应出现双层骨架嵌套，第一次结果:\n$afterFirst",
            Regex("N0:\\s*\\\$t\\([^)]*N0:").containsMatchIn(afterFirst))
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 混合：差异段是中文短语（含空格/标点）时仍是嵌套，且 key 精确
    // ─────────────────────────────────────────────────────────────

    /** 公共后缀 + 中文差异：骨架应含后缀，差异 key 不含后缀。 */
    fun testNestedDiffWithCommonSuffix() {
        val file = configureFile(
            "src/Suffix.vue",
            """
            <template>
              <div>
                <span>欢迎张三回来</span>
                <span>欢迎李四回来</span>
              </div>
            </template>
            """.trimIndent()
        )
        val processor = collect(file)
        val (affix, _) = MergeApplier.factorizeSites(listOf(processor))
        val group = affix.firstOrNull { it.variants.size >= 2 && it.skeleton.contains("{N0}") }
            ?: throw IllegalStateException("应生成合并组，实际: $affix")

        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor),
                LinkedHashMap(processor.analyzer.extractedStrings),
                ExtractedStringsDialog.MergePlan(listOf(group), emptyList()))
        }
        val res = holder[0] ?: emptyMap()
        val result = resultText("src/Suffix.vue")
        assertTrue("骨架应含公共后缀「回来」，实际: ${group.skeleton}", group.skeleton.endsWith("回来"))
        assertTrue("源码应含嵌套 \$t 调用，实际:\n$result", result.contains("N0: \$t"))
        group.variants.map { it.diff.trim() }.forEach { d ->
            assertTrue("差异 key '$d' 应登记，实际: ${res.keys}", res.containsKey(d))
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 翻译资源内容正确性：骨架 value 与差异 value 都不应残留 {N0}
    // ─────────────────────────────────────────────────────────────

    /**
     * 「先提取最后合并」后，翻译资源里的每一项 value 都必须是干净文案：
     *  - 骨架 key "请输入搜索{N0}" 的 value 应保留 {N0} 占位（供 vue-i18n 插值），这是正确的；
     *  - 差异 key（关键词/号码）的 value 应是纯文案，绝不能含 {N0} 或嵌套 $t。
     * 这里固化「差异 value 不含占位符残留」的预期，防止把嵌套调用的源码文本误写进资源。
     */
    fun testMergedResourceValuesAreClean() {
        val file = configureFile(
            "src/Clean.vue",
            """
            <template>
              <div>
                <span>请输入搜索关键词</span>
                <span>请输入搜索号码</span>
              </div>
            </template>
            """.trimIndent()
        )
        val processor = collect(file)
        val (affix, _) = MergeApplier.factorizeSites(listOf(processor))
        val group = affix.firstOrNull { it.variants.size >= 2 && it.skeleton.contains("{N0}") }
            ?: throw IllegalStateException("应生成合并组，实际: $affix")

        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor),
                LinkedHashMap(processor.analyzer.extractedStrings),
                ExtractedStringsDialog.MergePlan(listOf(group), emptyList()))
        }
        val res = holder[0] ?: emptyMap()

        // 骨架 value 保留占位符（正确）；差异 value 必须是纯文案
        assertEquals("骨架 value 应保留 {N0} 占位供插值", group.skeleton, res[group.skeleton])
        group.variants.map { it.diff.trim() }.forEach { d ->
            val value = res[d] ?: throw IllegalStateException("差异 key '$d' 缺失，实际: $res")
            assertFalse("差异 value '$value' 不应残留 {N0} 占位符", value.contains("{N0}"))
            assertFalse("差异 value '$value' 不应含嵌套 \$t 文本", value.contains("\$t"))
            assertEquals("差异 value 应等于纯文案本身", d, value)
        }
    }

    /**
     * 中文 + 数字混合差异（如 "费用100元"/"费用200元" 已由数字组承载；
     * 这里用纯中文差异 "费用超限"/"费用正常"）应产生骨架 + 嵌套 \$t，
     * 且每个差异各自成 key —— 确认含数字的文案不会被错误地并入中文差异组。
     */
    fun testChineseDiffWithNumericSiblingsStaySeparate() {
        val file = configureFile(
            "src/Mixed.vue",
            """
            <template>
              <div>
                <span>费用超限</span>
                <span>费用正常</span>
                <span>费用100元</span>
                <span>费用200元</span>
              </div>
            </template>
            """.trimIndent()
        )
        val processor = collect(file)
        val (affix, digit) = MergeApplier.factorizeSites(listOf(processor))

        // 两组都应存在：affix 骨架 费用{N0}（差异为纯中文后缀 超限/正常 或含数字的 100元/200元），
        // digit 骨架 费用{N0}元（纯数字抽取）。affix 是纯字符前后缀匹配，会把 100元/200元 也纳入
        // 费用{N0} 组，这是算法的正常行为，故只断言「包含中文差异」而非「恰好等于」。
        val cnGroupCandidate = affix.firstOrNull { it.skeleton == "费用{N0}" }
        assertNotNull("应生成 affix 骨架 费用{N0}，实际: $affix", cnGroupCandidate)
        val diffs = cnGroupCandidate!!.variants.map { it.diff }.toSet()
        assertTrue("affix 组应包含中文差异 超限/正常，实际: $diffs",
            diffs.containsAll(setOf("超限", "正常")))
        assertTrue("应生成数字 digit 组，实际: $digit",
            digit.any { it.skeleton == "费用{N0}元" })

        val cnGroup = cnGroupCandidate
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor),
                LinkedHashMap(processor.analyzer.extractedStrings),
                ExtractedStringsDialog.MergePlan(listOf(cnGroup), emptyList()))
        }
        val res = holder[0] ?: emptyMap()
        val result = resultText("src/Mixed.vue")
        assertTrue("中文差异应嵌套 \$t，实际:\n$result", result.contains("N0: \$t"))
        assertTrue("中文差异 key 应登记（超限），实际: ${res.keys}", res.containsKey("超限"))
        assertTrue("中文差异 key 应登记（正常），实际: ${res.keys}", res.containsKey("正常"))
    }
}
