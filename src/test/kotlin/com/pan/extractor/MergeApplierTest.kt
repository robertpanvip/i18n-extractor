package com.pan.extractor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * MergeApplier 应用层回归测试（评审 A3/A4/A9）。
 */
class MergeApplierTest : BasePlatformTestCase() {

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
        val site1: I18nProcessor.CollectedSite = sites.firstOrNull { it.originalMessage == "权限0755" }
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
}