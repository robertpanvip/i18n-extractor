package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.merge.CommonPrefixSuffixFactorizer
import com.pan.extractor.merge.MergeApplier
import com.pan.extractor.merge.SiteRef
import com.pan.extractor.ui.ExtractedStringsDialog
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MergeApplier 审计修复（2026-11）回归：
 *  P1-1 骨架重写解析失败（null replacement）不得登记资源 key（源码/资源一致性）
 *  P1-2 骨架重写失败必须站点级隔离（不得穿透 apply 中止整个合并）
 *  P2-3 因子化器丢弃组不得先消费站点（后续合法组仍应形成）
 *  P3-1 dropExistingKeysOut 集成缝隙覆盖
 */
class MergeApplierAuditFixTest : BasePlatformTestCase() {

    private val d = '$'
    private lateinit var undoDisposable: com.intellij.openapi.Disposable

    override fun setUp() {
        super.setUp()
        undoDisposable = com.intellij.openapi.util.Disposer.newDisposable()
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(
            com.intellij.openapi.ui.TestDialog.OK, undoDisposable
        )
        myFixture.addFileToProject(
            "package.json",
            """{ "name": "t", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" } }"""
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
        if (::undoDisposable.isInitialized) com.intellij.openapi.util.Disposer.dispose(undoDisposable)
        super.tearDown()
    }

    private fun runApply(
        text: String,
        mode: String,
        drop: MutableSet<String>? = null,
    ): Pair<PsiFile, MutableMap<String, String>> {
        val psiFile = myFixture.addFileToProject("src/Demo.vue", text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        val (affix, digit) = MergeApplier.factorizeSites(listOf(processor))
        val plan = when (mode) {
            "affix" -> ExtractedStringsDialog.MergePlan(affix, emptyList())
            "digit" -> ExtractedStringsDialog.MergePlan(emptyList(), digit)
            else -> ExtractedStringsDialog.MergePlan(affix, digit)
        }
        val extracted = LinkedHashMap(processor.analyzer.extractedStrings)
        val holder = arrayOfNulls<MutableMap<String, String>>(1)
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            holder[0] = MergeApplier.apply(listOf(processor), extracted, plan, dropExistingKeysOut = drop)
        }
        return psiFile to (holder[0] ?: LinkedHashMap())
    }

    // ── P1-1：骨架以反斜杠结尾 → $t 表达式解析失败（null）→ 不得登记资源 key ──
    fun testNullReplacementMustNotRegisterResourceKey() {
        val psiFile = myFixture.addFileToProject(
            "src/NullRepl.vue",
            """
            <template><div id="x"></div></template>
            <script setup lang="ts">
            const a = "测试文案"
            </script>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        val proc = I18nProcessor(project, psiFile)
        proc.collect()
        val site = proc.analyzer.collectedSites.first()
        val root = site.replaceRootPointer?.element ?: error("no root")
        val bs = '\\'
        val skeleton = "路径{N0}$bs"
        val before = psiFile.text
        val final = LinkedHashMap<String, String>()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            MergeApplier.rewriteSiteToSkeleton(
                rootPsi = root, site = site,
                skeletonValue = skeleton, skeletonKey = skeleton,
                paramPairs = listOf("N0" to "'A'"),
                proc = proc, finalExtracted = final,
            )
        }
        assertTrue("解析失败(null replacement)不得登记资源 key，got: $final", final.isEmpty())
        assertEquals("解析失败时源码不应被改写", before, psiFile.text)
    }

    // ── P1-2：骨架重写失败必须站点级隔离（不抛穿 apply、不登记） ──
    fun testSkeletonRewriteFailureMustBeIsolatedPerSite() {
        val psiFile = myFixture.addFileToProject(
            "src/Iso.vue",
            """
            <template><div id="x"></div></template>
            <script setup lang="ts">
            const a = "测试文案"
            </script>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        val proc = I18nProcessor(project, psiFile)
        proc.collect()
        val site = proc.analyzer.collectedSites.first()
        val root = site.replaceRootPointer?.element ?: error("no root")
        val final = LinkedHashMap<String, String>()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            MergeApplier.rewriteSiteToSkeleton(
                rootPsi = root,
                site = site,
                skeletonValue = "骨架{N0}",
                skeletonKey = "骨架{N0}",
                paramPairs = listOf("BAD" to "x"),
                proc = proc,
                finalExtracted = final,
            )
        }
        assertTrue("失败站点不得登记资源 key: $final", final.isEmpty())
    }

    // ── P2-3：占位符组被丢弃后，其余站点仍应能组成合法组 ──
    fun testDiscardedPlaceholderGroupMustNotConsumeSites() {
        val msgs = listOf("超过{N0}%的智能体", "超过{N1}%的智能体", "超过A%的智能体", "超过B%的智能体")
        val sites = msgs.mapIndexed { i, m -> SiteRef(0, "s$i", m, null, true, false, i) }
        val (affix, _) = CommonPrefixSuffixFactorizer.factorize(sites)
        val group = affix.firstOrNull { it.variants.map { v -> v.diff }.toSet() == setOf("A", "B") }
        assertTrue("占位符组被丢弃后 A/B 仍应成组，实际: $affix", group != null)
        assertEquals("超过{N0}%的智能体", group!!.skeleton)
    }

    // ── P3-1：dropExistingKeysOut 应收集被完全承载的原句（集成缝隙覆盖） ──
    fun testDropExistingKeysOutCollectsFullyConsumedMessages() {
        val drop = mutableSetOf<String>()
        val (_, final) = runApply(
            """
            <template>
                <div>
                    <span>权限0755</span>
                    <span>权限0756</span>
                </div>
            </template>
            """.trimIndent(),
            "digit",
            drop
        )
        assertEquals(setOf("权限0755", "权限0756"), drop)
        assertTrue("骨架 key 应保留在 finalExtracted", final.keys.any { it.contains("权限{N0}") })
    }
}
