package com.pan.extractor.analyzer

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals

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

    fun testMultiLevelBarrelReExportIsTranslation() {
        // P1（§26-翻译·re-export）：跨文件多级 barrel 递归 A→B→vue-i18n
        configureFile(
            "src/i18n-core.ts",
            "export { t, tc } from 'vue-i18n'\n"
        )
        configureFile(
            "src/i18n-mid.ts",
            "export { t as midT, tc } from '@/i18n-core'\n"
        )
        val file = configureFile(
            "src/UseMulti.ts",
            """
            import { midT, tc } from '@/i18n-mid'
            const a = midT('已翻译')
            const b = tc('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "midT"))
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "tc"))
    }

    fun testNamespaceImportOfBarrelReExportIsTranslation() {
        // P1：`import * as ns from '@/i18n'`（i18n 内部 re-export 框架）→ i18n 实例 → TRANSLATION
        configureFile(
            "src/i18n-ns.ts",
            """
            export { i18n, t } from 'vue-i18n'
            export default i18n
            """.trimIndent()
        )
        val file = configureFile(
            "src/UseNsBarrel.ts",
            """
            import * as L10n from '@/i18n-ns'
            const a = L10n.t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "L10n.t"))
    }

    fun testCrossFileInstanceViaBarrelThenChainIsTranslation() {
        // P1：barrel 再导出 createI18n 实例，另一端 .t() 链式调用
        configureFile(
            "src/locales/index.ts",
            """
            import { createI18n } from 'vue-i18n'
            export const i18n = createI18n({ legacy: false })
            """.trimIndent()
        )
        val file = configureFile(
            "src/UseAliasChain.ts",
            """
            import { i18n as l10n } from '@/locales/index'
            const a = l10n.t('已翻译')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "l10n.t"))
    }

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

    // ── 6. P0 增量：本地 shadow / 孤儿调用绝不可误判为「翻译」（§5.3 / §18）──

    fun testLocallyDefinedFunctionTIsNonTranslation() {
        // function t(x){return x} 是本地定义，非框架 t → NON_TRANSLATION
        val file = configureFile(
            "src/LocalShadow.ts",
            """
            function t(x: string) { return x.toUpperCase() }
            const a = t('x')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.NON_TRANSLATION, statusOf(file, "t"))
    }

    fun testLocalConstShadowTIsNeverTranslation() {
        // const t = somethingElse 右侧无法证明来源 → 至少绝不判为 TRANSLATION（保守避免高风险改写）
        val file = configureFile(
            "src/ConstShadow.ts",
            """
            const t = somethingElse
            const a = t('x')
            """.trimIndent()
        )
        assertNotEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
    }

    fun testPlainObjectMethodTIsNeverTranslation() {
        // 普通对象方法 o.t() 无从证明来源 → 至少绝不判为 TRANSLATION（避免高风险改写）
        val file = configureFile(
            "src/PlainObject.ts",
            """
            const o = { t(x: string) { return x } }
            const a = o.t('x')
            """.trimIndent()
        )
        assertNotEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "o.t"))
    }

    fun testOrphanBareTWithoutOriginIsNotHighRisk() {
        // 孤儿 t('x')：无 import / 无本地定义 / 无可解析出处 → 必须保守（非 TRANSLATION）
        val file = configureFile(
            "src/OrphanBareT.ts",
            "const a = t('x')\n"
        )
        assertNotEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
    }

    fun testHookTShadowedByLocalFunctionIsNotTranslation() {
        // P1 Negative：`const { t } = useI18n()` 之后同一作用域出现本地同名 `function t`，
        // 调用命中「本地同名声明」→ 绝不能误判 TRANSLATION（shadow 优先级高于框架解构回退）
        val file = configureFile(
            "src/HookShadowed.ts",
            """
            import { useI18n } from 'vue-i18n'
            const { t } = useI18n()
            function t(x: string) { return x.toUpperCase() }
            const a = t('硬编码')
            """.trimIndent()
        )
        assertNotEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "t"))
    }

    fun testOrdinaryUtilsModuleTIsNonTranslation() {
        // P1 Negative：`import { t } from '@/utils'`，utils 不 re-export 框架 → NON_TRANSLATION
        configureFile(
            "src/utils.ts",
            "export function t(x: string) { return x.trim() }\n"
        )
        val file = configureFile(
            "src/UseUtils.ts",
            """
            import { t } from '@/utils'
            const a = t('硬编码')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.NON_TRANSLATION, statusOf(file, "t"))
    }

    // ── React Intl / 本地实例 shadow（Negative）────────────────────

    /** Negative：本地 `function formatMessage` 不得因名字被当作 react-intl（见 TranslationAnalyzer 来源证明）。 */
    fun testLocalFunctionNamedFormatMessageIsNotTranslation() {
        val file = configureFile(
            "src/LocalFormatMessage.ts",
            """
            function formatMessage(options: any) { return options.id }
            const a = formatMessage({ id: '中文' })
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.NON_TRANSLATION, statusOf(file, "formatMessage"))
    }

    /** Negative：本地 `function defineMessages` 同样不得当作 react-intl。 */
    fun testLocalFunctionNamedDefineMessagesIsNotTranslation() {
        val file = configureFile(
            "src/LocalDefineMessages.ts",
            """
            function defineMessages(obj: any) { return obj }
            const a = defineMessages({ k: { defaultMessage: '中文' } })
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.NON_TRANSLATION, statusOf(file, "defineMessages"))
    }

    /** Negative：`const i18n = { t: ... }` 本地对象字面量实例 → LOCAL_SHADOW，绝非翻译。 */
    fun testLocalObjectLiteralInstanceTItsNotTranslation() {
        val file = configureFile(
            "src/ObjLiteralShadow.ts",
            """
            const i18n = { t: (x: string) => x }
            const a = i18n.t('中文')
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.NON_TRANSLATION, statusOf(file, "i18n.t"))
    }

    /** Negative：`const i18n = createSomethingElse()` 未知工厂产物 → 不得误判为翻译（保守）。 */
    fun testUnknownFactoryInstanceTItsNotTranslation() {
        val file = configureFile(
            "src/UnknownFactoryShadow.ts",
            """
            const i18n = createSomethingElse()
            const a = i18n.t('中文')
            """.trimIndent()
        )
        assertNotEquals(TranslationCallStatus.TRANSLATION, statusOf(file, "i18n.t"))
    }

    // ── 8. 作用域可见性（Scope）───────────────────────────────────

    /** 按第一个字符串实参定位调用并判定三态（同名 t 多处调用时用参数区分）。 */
    private fun statusOfCallWithArg(file: PsiFile, argText: String): TranslationCallStatus {
        val call = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
            .first { c -> c.arguments.firstOrNull()?.text == argText }
        return TranslationAnalyzer.statusOf(call)
    }

    /**
     * 【作用域污染回归】模块顶层 `t('等于')`（t 未定义）不得被组件内
     * `const { t } = useTranslation()` 证明为翻译 —— hook 解构是**函数作用域内**的
     * 绑定，对顶层调用不可见。顶层 t 按无证据处理 → UNKNOWN（保守跳过，零误改）。
     * 组件内 `t('权限名称')` 仍应正常判 TRANSLATION。
     */
    fun testTopLevelTNotProvenByComponentHookDestructure() {
        val file = configureFile(
            "src/AddEditAuthModal.tsx",
            """
            import { useTranslation } from 'react-i18next';
            const conditionList = [
              { label: t('等于'), value: "=" },
            ];
            const AddEditAuthModal = () => {
              const {t} = useTranslation();
              const v = t('权限名称')
              return v;
            }
            export default AddEditAuthModal
            """.trimIndent()
        )
        // 顶层 t('等于')：t 未定义，组件内 hook 的 t 对顶层不可见 → UNKNOWN
        assertEquals(TranslationCallStatus.UNKNOWN, statusOfCallWithArg(file, "'等于'"))
        // 组件内 t('权限名称')：hook 解构同作用域可见 → TRANSLATION
        assertEquals(TranslationCallStatus.TRANSLATION, statusOfCallWithArg(file, "'权限名称'"))
    }

    /**
     * 【作用域正向】模块顶层的 `const { t } = useTranslation()`（hook 解构本身在顶层）
     * 对文件内任意位置的 t 调用均可见 → TRANSLATION（保留既有行为，防回归）。
     */
    fun testModuleLevelHookDestructureVisibleEverywhere() {
        val file = configureFile(
            "src/TopHook.ts",
            """
            import { useTranslation } from 'react-i18next';
            const { t } = useTranslation();
            const conditionList = [
              { label: t('等于'), value: "=" },
            ];
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOfCallWithArg(file, "'等于'"))
    }

    /**
     * 【混合文件终态形态】顶层 `const t = i18n.t`（i18n 默认导入自 locale barrel，
     * 内部 `export default i18n`）+ 组件内 `const { t } = useTranslation()`：
     *  - 顶层 `t('等于')` → 模块级别名作用域可见 → TRANSLATION；
     *  - 组件内 `t('权限名称')` → 最近作用域声明优先（hook 解构遮蔽模块别名）→ TRANSLATION。
     */
    fun testTopLevelIAliasAndComponentHookBothTranslation() {
        configureFile(
            "src/locales/i18n.ts",
            """
            import i18n from 'i18next'
            import { initReactI18next } from 'react-i18next'
            i18n.use(initReactI18next).init({ resources: {}, lng: 'zh' })
            export default i18n
            """.trimIndent()
        )
        val file = configureFile(
            "src/AddEditAuthModal.tsx",
            """
            import { useTranslation } from 'react-i18next';
            import i18n from '@/locales/i18n.ts'
            const t = i18n.t
            const conditionList = [
              { label: t('等于'), value: "=" },
            ];

            /** 权限的新增/编辑 */
            const AddEditAuthModal = () => {
              const { t } = useTranslation();
              const v = t('权限名称')
              return v;
            }

            export default AddEditAuthModal
            """.trimIndent()
        )
        assertEquals(TranslationCallStatus.TRANSLATION, statusOfCallWithArg(file, "'等于'"))
        assertEquals(TranslationCallStatus.TRANSLATION, statusOfCallWithArg(file, "'权限名称'"))
    }
}