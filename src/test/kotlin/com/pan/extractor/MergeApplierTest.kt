package com.pan.extractor

import com.pan.extractor.ui.*

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * MergeApplier 应用层回归测试（评审 A3/A4/A9）。
 */
class MergeApplierTest : BasePlatformTestCase() {

    private lateinit var undoDisposable: com.intellij.openapi.Disposable

    override fun tearDown() {
        if (::undoDisposable.isInitialized) {
            com.intellij.openapi.util.Disposer.dispose(undoDisposable)
        }
        super.tearDown()
    }

    override fun setUp() {
        super.setUp()
        // 跨文档 Undo 会弹“Undo …?”确认对话框，headless 下需自动确认（TestDialog.OK）
        undoDisposable = com.intellij.openapi.util.Disposer.newDisposable()
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(
            com.intellij.openapi.ui.TestDialog.OK,
            undoDisposable
        )
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

    /** collect → factorize → 按 mode 选择候选 → apply，返回 apply 后的最终资源。 */
    private fun runApply(text: String, mode: String = "all"): Pair<PsiFile, MutableMap<String, String>> {
        val file = configureFile("src/Demo.vue", text)
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, digit) = MergeApplier.factorizeSites(listOf(processor))
        val plan = when (mode) {
            "affix" -> ExtractedStringsDialog.MergePlan(affix, emptyList())
            "digit" -> ExtractedStringsDialog.MergePlan(emptyList(), digit)
            else -> ExtractedStringsDialog.MergePlan(affix, digit)
        }
        val extracted = LinkedHashMap(processor.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        return file to (holder[0] ?: LinkedHashMap())
    }

    private fun String.containsIgnoringWs(other: String): Boolean {
        return this.replace("\\s+".toRegex(), "").contains(other.replace("\\s+".toRegex(), ""))
    }

    // ─────────────────────────────────────────────────────────
    // A3：数字前导零应被当作字符串字面量写入参数
    // ─────────────────────────────────────────────────────────

    /**
     * "权限0755"/"权限0756" → 数字抽取骨架 "权限{N0}"，参数应为 N0: '0755'（带引号）。
     * 若裸写 N0: 0755，strict 模式下是 octal 语法错误。
     * 只选数字组，避免公共前后缀组（会把前导零并进骨架前缀）干扰。
     */
    fun testLeadingZeroDigitShouldBeQuotedInParams() {
        val (file, final) = runApply(
            """
            <template>
                <div>
                    <span>权限0755</span>
                    <span>权限0756</span>
                </div>
            </template>
            """.trimIndent(),
            mode = "digit"
        )
        val result = file.text
        // 期望带引号：$t('权限{N0}', { N0: '0755' })
        assertTrue(
            "前导零 0755 应作为字符串字面量（带引号）写入参数，实际:\n$result",
            result.contains("N0: '0755'") || result.contains("N0: \"0755\"")
        )
    }

    // ─────────────────────────────────────────────────────────
    // A4：清理阶段不应误删未被合并承载的独立同名 key
    // ─────────────────────────────────────────────────────────

    /**
     * 手工构造「部分合并」：文本 "权限0755" 既被某个合并组承载（site1），又有独立站点（site3）
     * 不被合并。清理必须按「站点」粒度判定——只有当某文本的所有命中站点都被合并承载时才删除，
     * 否则独立站点的 key "权限0755" 会被误删。
     */
    fun testCleanupMustNotDeleteStandaloneKey() {
        val raw = """
            <template>
                <div>
                    <span>权限0755</span>
                    <span>权限0756</span>
                    <span>权限0755</span>
                </div>
            </template>
        """.trimIndent()
        val file = configureFile("src/Demo.vue", raw)
        val processor = I18nProcessor(project, file)
        processor.collect()

        // 手工构造：只让 site1 进入数字组，site2/site3 保持独立
        val sites = processor.collectedSites.toList()
        val site1: com.pan.extractor.model.ExtractionSite = sites.firstOrNull { it.originalMessage == "权限0755" }
            ?: throw IllegalStateException("未找到 site1")
        val site1Ref = SiteRef(
            processorIndex = 0,
            siteId = site1.id,
            originalMessage = site1.originalMessage,
            containingFile = site1.containingFile,
            isVue = site1.isVue,
            isReact = site1.isReact,
            line1 = site1.startLine,
        )
        val digitGroup = DigitGroupCandidate(
            id = "DG_TEST",
            skeleton = "权限{N0}",
            digits = listOf(DigitSlot(0)),
            perSites = listOf(DigitPerSite(site1Ref, listOf("0755"), true)),
            selected = true,
            skeletonKey = "权限{N0}",
        )
        val plan = ExtractedStringsDialog.MergePlan(emptyList(), listOf(digitGroup))
        val extracted = LinkedHashMap(processor.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultRes = holder[0] ?: LinkedHashMap()
        // 独立站点 site3 的 key "权限0755" 不在合并组里，应保留
        assertTrue(
            "独立同名站点 '权限0755' 的 key 不应被清理误删，keys=${resultRes.keys}",
            resultRes.containsKey("权限0755")
        )
    }

    // ─────────────────────────────────────────────────────────
    // A9：属性型站点骨架 key 应写入翻译资源
    // ─────────────────────────────────────────────────────────

    /**
     * 属性（v-bind/普通属性）站点走骨架重写后，skeleton key 必须出现在最终资源里。
     * 复现 XmlAttributeValue 分支提前 return 导致骨架 key 丢失的问题。
     */
    fun testAttributeSkeletonKeyWrittenToFinalExtracted() {
        val file = configureFile(
            "src/Demo.vue",
            """
            <template>
                <div>
                    <span title="状态1">状态1</span>
                    <span title="状态2">状态2</span>
                </div>
            </template>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, digit) = MergeApplier.factorizeSites(listOf(processor))
        val plan = ExtractedStringsDialog.MergePlan(affix, digit)
        val extracted = LinkedHashMap(processor.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultRes = holder[0] ?: emptyMap()
        // 属性站点产生的骨架 key（如 "状态{N0}"）必须存在于最终资源
        val skeletonCandidates = listOf("状态{N0}")
        val containsSkeleton = skeletonCandidates.any { resultRes.containsKey(it) }
        assertTrue(
            "属性站点骨架 key 应写入最终资源，实际 keys=${resultRes.keys}",
            containsSkeleton
        )
    }

    // ─────────────────────────────────────────────────────────
    // 追加覆盖：跨文件合并 / affix+digit 组合 / skeletonKey 编辑
    // ─────────────────────────────────────────────────────────

    /** 跨文件构建两个 processor，collect 后 factorize，返回 processor 列表。 */
    private fun crossFileProcessors(vararg fileTexts: Pair<String, String>): List<I18nProcessor> =
        fileTexts.map { (name, text) ->
            val file = configureFile(name, text)
            I18nProcessor(project, file).also { it.collect() }
        }

    /**
     * 跨文件合并：文件 A 和文件 B 各处贡献 site，merged 骨架应同时写入两个文件。
     * "测试完成A"/"测试完成B"(A文件) + "测试完成C"(B文件) → 前缀 "测试完成" → 骨架 测试完成{N0}。
     */
    fun testApplyMergesAcrossFiles() {
        val processors = crossFileProcessors(
            "src/A.vue" to "<template><div><span>测试完成A</span><span>测试完成B</span></div></template>",
            "src/B.vue" to "<template><div><span>测试完成C</span></div></template>",
        )
        val (affix, _) = MergeApplier.factorizeSites(processors)
        val skeletonGroup = affix.firstOrNull { it.skeleton == "测试完成{N0}" }
        assertNotNull("跨文件应生成 测试完成{N0} 骨架组", skeletonGroup)

        val plan = ExtractedStringsDialog.MergePlan(listOf(skeletonGroup!!), emptyList())
        val extracted = LinkedHashMap<String, String>()
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(processors, extracted, plan)
        }
        val resultRes = holder[0] ?: emptyMap()
        assertTrue("跨文件骨架 key 应写入最终资源", resultRes.containsKey("测试完成{N0}"))

        // 两个文件里的原始句都应被替换为带 {N0} 的 $t 调用（读 PSI 内存文本，反映已应用的改动）
        val textA = myFixture.findFileInTempDir("src/A.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        val textB = myFixture.findFileInTempDir("src/B.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        // 只匹配骨架文本，避免正则里出现 $t 触发 Kotlin 字符串插值
        assertTrue("A 文件应被重写为骨架调用，实际:\n$textA", textA.contains("测试完成{N0}"))
        assertTrue("B 文件应被重写为骨架调用，实际:\n$textB", textB.contains("测试完成{N0}"))
    }

    /**
     * affix + digit 两组同时选中：最终资源同时包含两组骨架 key。
     * affix: "名称可用"/"名称占用" → 名称{N0}；digit: "序号1"/"序号2" → 序号{N0}。
     */
    fun testApplyCombinesAffixAndDigit() {
        val file = configureFile(
            "src/Demo.vue",
            """
            <template>
                <div>
                    <span>名称可用</span>
                    <span>名称占用</span>
                    <span>序号1</span>
                    <span>序号2</span>
                </div>
            </template>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, digit) = MergeApplier.factorizeSites(listOf(processor))
        val affixGroup = affix.firstOrNull { it.skeleton == "名称{N0}用" }
        val digitGroup = digit.firstOrNull { it.skeleton == "序号{N0}" }
        assertNotNull("应生成 名称{N0}用 affix 组", affixGroup)
        assertNotNull("应生成 序号{N0} digit 组", digitGroup)

        val plan = ExtractedStringsDialog.MergePlan(listOf(affixGroup!!), listOf(digitGroup!!))
        val extracted = LinkedHashMap(processor.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultRes = holder[0] ?: emptyMap()
        assertTrue("affix 骨架 key 应存在", resultRes.containsKey("名称{N0}用"))
        assertTrue("digit 骨架 key 应存在", resultRes.containsKey("序号{N0}"))
    }

    /**
     * 用户编辑了骨架 key（skeletonKey 非默认）：最终资源应写入自定义 key，
     * 且 $t 调用使用该自定义 key。
     */
    fun testApplyUsesEditedSkeletonKey() {
        val file = configureFile(
            "src/Demo.vue",
            """
            <template>
                <div>
                    <span>名称可用</span>
                    <span>名称占用</span>
                </div>
            </template>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, _) = MergeApplier.factorizeSites(listOf(processor))
        val affixGroup = affix.firstOrNull { it.skeleton == "名称{N0}用" } ?: throw IllegalStateException("未找到 affix 组")
        affixGroup.skeletonKey = "custom.merged.label"   // 用户编辑

        val plan = ExtractedStringsDialog.MergePlan(listOf(affixGroup), emptyList())
        val extracted = LinkedHashMap(processor.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultRes = holder[0] ?: emptyMap()
        assertTrue("自定义骨架 key 应写入最终资源，keys=${resultRes.keys}", resultRes.containsKey("custom.merged.label"))
        val resultText = myFixture.findFileInTempDir("src/Demo.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        assertTrue("\$t 调用应使用自定义 key，实际:\n$resultText", resultText.contains("custom.merged.label"))
    }

    // ─────────────────────────────────────────────────────────
    // 问题 4 回归：完全相同文本不要生成自引用 $t('全选', { N0: $t('全选') })
    // ─────────────────────────────────────────────────────────

    /**
     * "全选" 出现两次时，factorize 产出的是 isExactDuplicate=true 的提示组。
     * 即便误勾选，apply 也必须跳过骨架重写：两句应各自替换为普通 $t('全选')，
     * 不能变成 $t('全选', { N0: $t('全选') })。
     */
    fun testExactDuplicateHintDoesNotProduceSelfRefNestedCall() {
        val file = configureFile(
            "src/Demo.vue",
            "<template><div><span>全选</span><span>全选</span></div></template>"
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, _) = MergeApplier.factorizeSites(listOf(processor))
        val hint = affix.firstOrNull { it.id.startsWith("AG_EXACT_DUP_") }
            ?: throw IllegalStateException("未生成 exact-dup 提示组")
        assertTrue("提示组应标记 isExactDuplicate", hint.isExactDuplicate)

        // 模拟用户误勾选了提示组
        val plan = ExtractedStringsDialog.MergePlan(listOf(hint), emptyList())
        val extracted = LinkedHashMap(processor.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultRes = holder[0] ?: emptyMap()
        // 最终资源里不应出现 "N0" 自引用占位 key
        assertFalse("exact-dup 不应生成 N0 占位 key，keys=${resultRes.keys}", resultRes.containsKey("N0"))
        assertTrue("原句 key '全选' 应保留，keys=${resultRes.keys}", resultRes.containsKey("全选"))

        val resultText = myFixture.findFileInTempDir("src/Demo.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        // 普通单句替换会按项目习惯选择引号（本例 t 表达式用反引号 `全选`），
        // 因此两种引号形式都算正确；核心是要"存在 $t(全选) 调用"且"无 N0 自引用"。
        val hasPlainT = resultText.contains("\$t(`全选`)") || resultText.contains("\$t('全选')")
        assertTrue("应替换为普通 \$t('全选')，实际:\n$resultText", hasPlainT)
        assertFalse("不应出现自引用 N0: \$t(...)，实际:\n$resultText", resultText.contains("N0: \$t"))
    }

    // ─────────────────────────────────────────────────────────
    // 7.3 边缘：合并重写不应破坏相邻模板注释 / 周围空白格式
    // ─────────────────────────────────────────────────────────

    /**
     * 骨架重写只替换命中站点对应的文本节点，相邻 HTML 注释（<!-- … -->）是独立
     * PSI 节点，不应被误删或吞并。
     */
    fun testMergeRewritePreservesAdjacentComment() {
        val file = configureFile(
            "src/Demo.vue",
            """
            <template>
                <div>
                    <!-- 这是关于状态的说明 -->
                    <span>状态1</span>
                    <span>状态2</span>
                </div>
            </template>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (_, digit) = MergeApplier.factorizeSites(listOf(processor))
        val digitGroup = digit.firstOrNull { it.skeleton == "状态{N0}" }
            ?: throw IllegalStateException("未生成 状态{N0} 数字组")
        val plan = ExtractedStringsDialog.MergePlan(emptyList(), listOf(digitGroup))
        val extracted = LinkedHashMap(processor.extractedStrings)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultText = myFixture.findFileInTempDir("src/Demo.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        assertTrue("相邻注释应保留，实际:\n$resultText", resultText.contains("<!-- 这是关于状态的说明 -->"))
        assertTrue("骨架调用应写入，实际:\n$resultText", resultText.contains("状态{N0}"))
    }

    /**
     * 骨架重写保持在站点对应的文本节点内，不吞并相邻文本 / 折叠换行与缩进。
     * 要求替换后整体仍保留原有结构（span 各占一行缩进对齐）。
     */
    fun testMergeRewritePreservesSurroundingFormatting() {
        val raw = """
            <template>
                <div>
                    <span>更新成功</span>
                    <span>更新完成</span>
                    <p>相邻段落保持</p>
                </div>
            </template>
        """.trimIndent()
        val file = configureFile("src/Demo.vue", raw)
        val processor = I18nProcessor(project, file)
        processor.collect()
        val (affix, _) = MergeApplier.factorizeSites(listOf(processor))
        // "更新成功"/"更新完成" 无公共后缀（功|完 不同），骨架为 更新{N0}
        val affixGroup = affix.firstOrNull { it.skeleton == "更新{N0}" }
            ?: throw IllegalStateException("未生成 更新{N0} affix 组, got=${affix.map { it.skeleton }}")
        val plan = ExtractedStringsDialog.MergePlan(listOf(affixGroup), emptyList())
        val extracted = LinkedHashMap(processor.extractedStrings)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            MergeApplier.apply(listOf(processor), extracted, plan)
        }
        val resultText = myFixture.findFileInTempDir("src/Demo.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        // 结构完整性：两个 <span> 与 <p> 仍各自保留独立元素（格式/行结构未被合并重写破坏）。
        // 说明：<p> 内的文本也会被独立提取为 $t('相邻段落保持')，但必须是独立的 $t 调用，
        // 不能被吞并进骨架 {N0} 参数里。
        assertTrue("应保留 <span> 元素，实际:\n$resultText", resultText.contains("<span>"))
        assertTrue("<p> 文本应独立成 \$t 调用而非并入骨架，实际:\n$resultText",
            resultText.contains("\$t(`相邻段落保持`)") || resultText.contains("\$t('相邻段落保持')"))
        // <p> 元素仍然是自包含的（没有把相邻 span 或骨架参数混进来）
        assertTrue("应保留 <p> 元素闭合，实际:\n$resultText", resultText.contains("</p>"))
        assertTrue("骨架调用应写入，实际:\n$resultText", resultText.contains("更新{N0}"))
        // 骨架重写的两个 span 保持各自一行（换行缩进未被折叠）
        val spanCount = Regex("<span>").findAll(resultText).count()
        assertEquals("两个 <span> 元素应保留，实际:\n$resultText", 2, spanCount)
    }

    // ─────────────────────────────────────────────────────────
    // P0 多文件修改原子性：任何待改写 site 失效时，apply 必须在
    // 写入任何文件之前整体中止（validateAllModifiableSites），不留半完成状态。
    // ─────────────────────────────────────────────────────────
    fun testApplyThrowsBeforeAnyWriteWhenSiteInvalid() {
        // 两个文件各贡献一个 site，用于交叉改写的两个文件都应保持原样
        val processors = crossFileProcessors(
            "src/A.vue" to "<template><div><span>完成A</span></div></template>",
            "src/B.vue" to "<template><div><span>完成B</span></div></template>",
        )
        val (affix, _) = MergeApplier.factorizeSites(processors)
        val skeletonGroup = affix.firstOrNull { it.skeleton == "完成{N0}" }
        assertNotNull("跨文件应生成 完成{N0} 骨架组", skeletonGroup)
        val plan = ExtractedStringsDialog.MergePlan(listOf(skeletonGroup!!), emptyList())

        // 破坏其中一处 site 的替换目标，使其引用失效（选 B.vue 的站点，确保"未受影响"的 A.vue 全程不被触碰）。
        // 为避免"选中站点恰好属于待断言完整性的文件"导致该文件被误删，确定性地取 processorIndex==1（B.vue）的 site。
        val targetSite = skeletonGroup!!.variants.flatMap { it.sites }
            .first { it.processorIndex == 1 }
        assertTrue("应能定位到 B.vue 的待改写站点", targetSite.processorIndex == 1)
        val targetProc = processors[targetSite.processorIndex]
        val targetCollected = targetProc.collectedSites.first { it.id == targetSite.siteId }
        val targetElement = targetCollected.replaceRootPointer?.element!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            targetElement.delete()
        }

        val extracted = LinkedHashMap<String, String>()
        var caught: IllegalStateException? = null
        try {
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                MergeApplier.apply(processors, extracted, plan)
            }
        } catch (t: IllegalStateException) {
            caught = t
        }
        assertNotNull("apply 应因站点失效抛出 IllegalStateException", caught)
        assertTrue("异常说明应包含奇异化站点描述，实际: ${caught?.message}",
            (caught?.message ?: "").contains("已失效"))

        // 另一文件不得被改动（原子：验证失败在写入任何文件之前）
        val intactText = myFixture.findFileInTempDir("src/A.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        assertTrue("未受影响文件不得被重写，实际:\n$intactText",
            intactText.contains("完成A") && !intactText.contains("完成{N0}"))
    }

    // ─────────────────────────────────────────────────────────
    // P0 多文件原子性（正向）：单命令一次改写多个文件代码 + 产出翻译资源，
    // 同批修改只需一次 Undo 即可整体回滚（共享同一 command → 单次回退原子组）。
    // 与 testMultiFileUndoRedoRoundTrip（两次独立命令各回退一次）形成互补。
    // ─────────────────────────────────────────────────────────
    fun testSingleCommandMultiFileCodeAndResourceAtomicUndo() {
        val beforeA = "<template><div><span>完成A</span></div></template>"
        val beforeB = "<template><div><span>完成B</span></div></template>"
        val processors = crossFileProcessors(
            "src/AtomA.vue" to beforeA,
            "src/AtomB.vue" to beforeB,
        )
        val (affix, _) = MergeApplier.factorizeSites(processors)
        val skeletonGroup = affix.firstOrNull { it.skeleton == "完成{N0}" }
        assertNotNull("跨文件应生成 完成{N0} 骨架组", skeletonGroup)
        val plan = ExtractedStringsDialog.MergePlan(listOf(skeletonGroup!!), emptyList())

        val extracted = LinkedHashMap<String, String>()
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        // 单 command 内同时改写两个代码文件，并产出最终翻译资源（JSON 内容来源）。
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(processors, extracted, plan)
        }
        val finalRes = holder[0] ?: emptyMap()
        assertTrue("单命令内两文件代码 + 资源应一并产出：资源需含骨架 key，keys=${finalRes.keys}",
            finalRes.containsKey("完成{N0}"))

        val textA = myFixture.findFileInTempDir("src/AtomA.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        val textB = myFixture.findFileInTempDir("src/AtomB.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        assertTrue("A 代码应被改写，实际:\n$textA", textA.contains("完成{N0}"))
        assertTrue("B 代码应被改写，实际:\n$textB", textB.contains("完成{N0}"))

        // 同一 command 的跨文件改动，只需一次 Undo 即整体回滚（不留 A 已改/B 未改）。
        com.intellij.openapi.command.CommandProcessor.getInstance().runUndoTransparentAction {
            myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val textAUndo = myFixture.findFileInTempDir("src/AtomA.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        val textBUndo = myFixture.findFileInTempDir("src/AtomB.vue")?.let {
            com.intellij.psi.PsiManager.getInstance(project).findFile(it)?.text
        } ?: ""
        assertTrue("一次 Undo 后 A 应整体回滚，实际:\n$textAUndo", textAUndo.contains("完成A") && !textAUndo.contains("完成{N0}"))
        assertTrue("一次 Undo 后 B 应整体回滚，实际:\n$textBUndo", textBUndo.contains("完成B") && !textBUndo.contains("完成{N0}"))
    }

    // ─────────────────────────────────────────────────────────
    // P0 §20：code + import + resource 完整 Undo / Redo
    // 单 command 内同时改写代码（含 import 注入）并把翻译资源落盘到入口 zh.ts，
    // 一次 Undo 应整体回滚（代码回原文、资源回空入口），一次 Redo 应整体恢复。
    // ─────────────────────────────────────────────────────────
    fun testSingleCommandCodeImportResourceUndoRedo() {
        val resourcePath = "src/locales/zh.ts"
        val beforeResource = "export default {}"
        myFixture.addFileToProject(resourcePath, beforeResource)

        val beforeCode = "<template><div><span>资源中文</span></div></template>"
        val file = configureFile("src/ResUndo.vue", beforeCode)

        val processors = listOf(I18nProcessor(project, file))
        processors.forEach { it.collect() }
        val extracted = LinkedHashMap<String, String>()
        processors.forEach { extracted.putAll(it.extractedStrings) }
        assertTrue("应提取到待资源化的中文，got: $extracted", extracted.containsValue("资源中文"))

        val plan = ExtractedStringsDialog.MergePlan(emptyList(), emptyList())
        val entryVf = myFixture.findFileInTempDir(resourcePath)
        assertNotNull("资源入口文件应存在", entryVf)

        // 单 command：代码改写 + import 注入 + 资源落盘，三者共享同一 undo 组
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val finalRes = MergeApplier.apply(processors, extracted, plan)
            val newResource = com.pan.extractor.TsFileEditor.regenerateTsFileWithNewJson(project, entryVf!!, finalRes)
            assertNotNull("资源应能重新生成，finalRes=$finalRes", newResource)
            entryVf.setBinaryContent(newResource!!.toByteArray())
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()

        val codeAfter = file.text
        assertTrue("代码应改写为 \$t 调用，实际:\n$codeAfter", codeAfter.contains("t("))
        val resAfter = fileText(resourcePath)
        assertTrue("资源应写入新文案，实际:\n$resAfter", resAfter.contains("资源中文"))
        assertTrue("资源应保持 export default 入口形态，实际:\n$resAfter", resAfter.contains("export default"))

        // Undo → 代码 + import + 资源全部回滚
        com.intellij.openapi.command.CommandProcessor.getInstance().runUndoTransparentAction {
            myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_UNDO)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("Undo 后代码应回到原文，实际:\n${file.text}", beforeCode, file.text)
        val resUndo = fileText(resourcePath)
        assertTrue("Undo 后资源应回滚（不含新文案），实际:\n$resUndo", !resUndo.contains("资源中文"))
        assertEquals("Undo 后资源应还原为空入口，实际:\n$resUndo", "export default {}", normalizeWs(resUndo))

        // Redo → 全部恢复
        com.intellij.openapi.command.CommandProcessor.getInstance().runUndoTransparentAction {
            myFixture.performEditorAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_REDO)
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        val resRedo = fileText(resourcePath)
        assertTrue("Redo 后资源应恢复新文案，实际:\n$resRedo", resRedo.contains("资源中文"))
        assertTrue("Redo 后代码应恢复 \$t 调用，实际:\n${file.text}", file.text.contains("t("))
    }

    private fun fileText(path: String): String {
        val vf = myFixture.findFileInTempDir(path) ?: return ""
        val f = com.intellij.psi.PsiManager.getInstance(project).findFile(vf) ?: return ""
        return f.text
    }

    private fun normalizeWs(s: String) = s.replace("\\s+".toRegex(), " ").trim()

    // ─────────────────────────────────────────────────────────
    // Phase 4：Planner 纯函数 —— ExtractionPlanner.computeBlockedSiteIds
    // 不接触 PSI、不写文件，可在任意 fixture（此处空 fixture 即可）上测。
    // ─────────────────────────────────────────────────────────
    fun testComputeBlockedSiteIdsCollectsMergeSites() {
        val a = SiteRef(0, "S1", "全选", null, false, false, 1)
        val b = SiteRef(0, "S2", "全选", null, false, false, 2)
        // 公共前后缀组：命中 a、b → 两个都进阻塞
        val affix = AffixGroupCandidate(
            id = "AG_1", skeleton = "全选{N0}", prefix = "全选", suffix = "", isExactDuplicate = false,
            variants = listOf(AffixVariant("菜单", listOf(a))),
            skeletonKey = "全选{N0}",
        )
        // 数字组：命中 c
        val cSite = SiteRef(1, "S3", "权限0755", null, false, false, 3)
        val digit = DigitGroupCandidate(
            id = "DG_1", skeleton = "权限{N0}", digits = listOf(DigitSlot(0)),
            perSites = listOf(DigitPerSite(cSite, listOf("0755"), true)),
            selected = true, skeletonKey = "权限{N0}",
        )
        val blocked = com.pan.extractor.planner.ExtractionPlanner
            .computeBlockedSiteIds(ExtractedStringsDialog.MergePlan(listOf(affix), listOf(digit)))
        assertTrue("合并承载的 affix site 应进阻塞集合，实际:$blocked", blocked.contains("S1"))
        assertTrue("合并承载的 digit site 应进阻塞集合，实际:$blocked", blocked.contains("S3"))
    }

    fun testComputeBlockedSiteIdsExcludesExactDuplicate() {
        val dup = SiteRef(0, "S9", "全选", null, false, false, 9)
        // 完全相同文本的提示组（isExactDuplicate=true）：不进阻塞，避免自引用
        val exact = AffixGroupCandidate(
            id = "AG_EXACT_DUP_1", skeleton = "全选", prefix = "全选", suffix = "", isExactDuplicate = true,
            variants = listOf(AffixVariant("全选", listOf(dup))),
            skeletonKey = "全选",
        )
        val blocked = com.pan.extractor.planner.ExtractionPlanner
            .computeBlockedSiteIds(ExtractedStringsDialog.MergePlan(listOf(exact), emptyList()))
        assertTrue("完全相同文本提示组不应进阻塞集合，实际:$blocked", "S9" !in blocked)
    }

    fun testComputeFullyConsumedMessagesOnlyFullyBlockedSentences() {
        val msgToSites = mapOf(
            "请选择" to setOf("S1", "S2"),   // 全部被合并 → 冗余，应删除
            "正在加载" to setOf("S3", "S4"), // 仅 S3 被合并 → 保留（有独立站点）
            "权限不足" to setOf("S5"),        // 全部被合并 → 冗余，应删除
        )
        val blocked = setOf("S1", "S2", "S3", "S5")
        val consumed = com.pan.extractor.planner.ExtractionPlanner
            .computeFullyConsumedMessages(msgToSites, blocked)
        assertEquals("完全承载的整句应被删除，实际:$consumed", setOf("请选择", "权限不足"), consumed)
    }

    fun testResourceApplierBuildPlanMapsFields() {
        myFixture.addFileToProject("src/locales/zh.ts", "export default {}")
        val vf = com.intellij.psi.PsiManager.getInstance(project).findFile(
            myFixture.findFileInTempDir("src/locales/zh.ts")
        )?.virtualFile ?: error("缺 zh.ts vf")
        val plan = com.pan.extractor.resource.ResourceApplier.buildPlan(
            vf, mapOf("key" to "值"), setOf("旧句"),
        )
        assertTrue("targetPath 应指向入口文件，实际:${plan.targetPath}", plan.targetPath.endsWith("src/locales/zh.ts"))
        assertEquals("format 应由扩展名映射为 ts", "ts", plan.format)
        assertEquals("entries 应透传", "值", plan.entries["key"])
        assertTrue("dropKeys 应透传", plan.dropKeys.contains("旧句"))
    }
}