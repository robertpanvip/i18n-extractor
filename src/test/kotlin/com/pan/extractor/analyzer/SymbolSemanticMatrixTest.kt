package com.pan.extractor.analyzer

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals

/**
 * PROJECT_ANALYSIS §7 —— Translation Call 语义矩阵。
 *
 * 直接针对 [TranslationAnalyzer.statusOf]/[SymbolAnalyzer.analyze] 断言三态分类，
 * 覆盖：
 *  - t / \$t / tc / \$tc 四形；
 *  - useI18n / useTranslation 解构产物；
 *  - re-export / barrel import（`@{/}...` 再从框架 re-export）；
 *  - namespace import（`import * as i18n from 'vue-i18n'`）；
 *  - 跨文件 i18n instance resolve（`import i18n from '../locales/i18n'`）；
 *  - 本地 shadow / 非 i18n import。
 */
class SymbolSemanticMatrixTest : BasePlatformTestCase() {

    private fun configureFile(path: String, text: String): PsiFile =
        myFixture.addFileToProject(path, text.trimIndent())

    /** 取 [methodText] 对应的调用并做三态判定；有且仅有一个匹配调用才合法。 */
    private fun statusOf(file: PsiFile, methodText: String): TranslationCallStatus {
        val call = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
            .first { it.methodExpression?.text == methodText }
        return TranslationAnalyzer.statusOf(call)
    }

    // ── 1. t / $t / tc / $tc 四形 ─────────────────────────────────

    fun testDirectFrameworkDollarTIsTranslation() {
        val file = configureFile(
            "src/A.vue",
            """
            <script setup lang="ts">
            import { useI18n } from 'vue-i18n';
            const { t: ${'$'}t } = useI18n();
            const a = ${'$'}t('已翻译')
            const b = ${'$'}tc('已翻译')
            </script>
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "${'$'}t"))
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "${'$'}tc"))
    }

    fun testDestructuredHookProductIsTranslation() {
        val file = configureFile(
            "src/B.ts",
            """
            import { useTranslation } from 'react-i18next';
            const { t, tc } = useTranslation();
            const a = t('已翻译')
            const b = tc('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "tc"))
    }

    // ── 2. re-export / barrel import ──────────────────────────────

    fun testNamedReExportFromFrameworkIsTranslation() {
        // barrel：从 vue-i18n re-export t/tc
        configureFile(
            "src/i18n.ts",
            """
            export { t, tc } from 'vue-i18n'
            """.trimIndent()
        )
        val file = configureFile(
            "src/UseBarrel.ts",
            """
            import { t, tc } from '@/i18n'
            const a = t('已翻译')
            const b = tc('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "tc"))
    }

    fun testStarReExportFromFrameworkIsTranslation() {
        configureFile(
            "src/i18nextBarrel.ts",
            """
            export * from 'react-i18next'
            """.trimIndent()
        )
        val file = configureFile(
            "src/UseStar.ts",
            """
            import { t } from '@/i18nextBarrel'
            const a = t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
    }

    // ── 3. namespace import ───────────────────────────────────────

    fun testNamespaceImportFromFrameworkIsTranslation() {
        val file = configureFile(
            "src/NS.ts",
            """
            import * as i18n from 'vue-i18n'
            const a = i18n.t('已翻译')
            const b = i18n.tc('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "i18n.t"))
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "i18n.tc"))
    }

    fun testNamespaceImportDifferentAliasIsTranslation() {
        val file = configureFile(
            "src/NSAlias.ts",
            """
            import * as L10n from 'react-i18next'
            const a = L10n.t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "L10n.t"))
    }

    // ── 4. 跨文件 i18n instance resolve ───────────────────────────

    fun testCrossFileI18nInstanceIsTranslation() {
        configureFile(
            "src/locales/i18n.ts",
            """
            import { createI18n } from 'vue-i18n'
            export const i18n = createI18n({ legacy: false })
            """.trimIndent()
        )
        val file = configureFile(
            "src/UseInstance.ts",
            """
            import { i18n } from '@/locales/i18n'
            const a = i18n.t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "i18n.t"))
    }

    // ── 4.5 useTranslation / useI18n 多种 destructuring 形式 ─────

    fun testUseI18nWithLegacyObjectArg() {
        val file = configureFile(
            "src/DestructureObjArg.ts",
            """
            import { useI18n } from 'vue-i18n'
            const { t, tc } = useI18n({ legacy: false, globalInjection: true })
            const a = t('已翻译')
            const b = tc('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "tc"))
    }

    fun testUseTranslationWithNamespaceArg() {
        val file = configureFile(
            "src/DestructureNsArg.ts",
            """
            import { useTranslation } from 'react-i18next'
            const { t } = useTranslation('mySrcNamespace')
            const a = t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
    }

    fun testRenamedDestructuredBindingViaAlias() {
        // `const { t: tx } = useI18n()` 后用别名 tx 调用 → 翻译
        val file = configureFile(
            "src/DestructureAlias.ts",
            """
            import { useI18n } from 'vue-i18n'
            const { t: tx } = useI18n()
            const a = tx('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "tx"))
    }

    fun testHookValueChainAccess() {
        // `const t = useTranslation().t` 值链访问 → 翻译
        val file = configureFile(
            "src/HookValueChain.ts",
            """
            import { useTranslation } from 'react-i18next'
            const t = useTranslation().t
            const a = t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
    }

    fun testDestructuredInstanceThenChainedCall() {
        // `const { i18n } = useI18n()` 解构出实例，再 i18n.t() → 翻译
        val file = configureFile(
            "src/DestructureInstance.ts",
            """
            import { useI18n } from 'vue-i18n'
            const { i18n } = useI18n({ legacy: false })
            const a = i18n.t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "i18n.t"))
    }

    // ── 5. 保守 / 非翻译 ──────────────────────────────────────────

    fun testLocalShadowIsNotTranslation() {
        val file = configureFile(
            "src/Shadow.ts",
            """
            const t = (x: string) => x.toUpperCase()
            const a = t('硬编码')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.NON_TRANSLATION, statusOf(file, "t"))
    }

    fun testNonI18nModuleImportIsNotTranslation() {
        val file = configureFile(
            "src/NonI18n.ts",
            """
            import { t } from './utils/format'
            const a = t('硬编码')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.NON_TRANSLATION, statusOf(file, "t"))
    }
}