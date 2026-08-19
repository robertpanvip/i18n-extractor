package com.pan.extractor

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.util.PsiTreeUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 4.4 — Harden ImportInjector：
 * 验证 [I18nPsiTools.hasImportedSpecifier] 对各种 import 形态的正确判定，
 * 防止重复注入 / 误改写（如 `import { t as translate }` 不被破坏）。
 */
class I18nImportInjectorHardenTest : BasePlatformTestCase() {

    /** 向项目添加一个 .ts 文件，返回其中唯一的 import declaration（无则报错）。 */
    private fun onlyImport(source: String): ES6ImportDeclaration {
        val psi = myFixture.addFileToProject("src/imports.ts", source.trimIndent())
        myFixture.configureFromExistingVirtualFile(psi.virtualFile)
        val imports = PsiTreeUtil.findChildrenOfType(psi, ES6ImportDeclaration::class.java)
        assertEquals("应恰好生成 1 条 import，源码:\n$source", 1, imports.size)
        return imports.first()
    }

    // ── named import ─────────────────────────────────────────────

    fun testNamedImportRecognized() {
        val decl = onlyImport("import { useI18n } from 'vue-i18n';")
        assertTrue("named import { useI18n } 应被识别", hasSpec(decl, "vue-i18n", "useI18n"))
    }

    fun testNamedImportAmongOthers() {
        val decl = onlyImport("import { a, useI18n, b } from 'vue-i18n';")
        assertTrue("多 specifier 中的 useI18n 应被识别", hasSpec(decl, "vue-i18n", "useI18n"))
    }

    fun testNamedImportDoesNotMatchOtherName() {
        // 只导入了 useI18n / t，目标 wantedName=translate 时不应误判
        val decl = onlyImport("import { useI18n, t } from 'vue-i18n';")
        assertFalse("未导入 translate，不应识别为已存在", hasSpec(decl, "vue-i18n", "translate"))
    }

    // ── import alias：`{ t as translate }` 保护 ─────────────────────

    fun testNamedImportAlias() {
        val decl = onlyImport("import { t as translate } from './i18n';")
        assertTrue("`t as translate` 别名绑定应识别原 t 已导入（防止再注入 t）", hasSpec(decl, "./i18n", "t"))
    }

    fun testNamedImportAliasTargetNotMatched() {
        val decl = onlyImport("import { t as translate } from './i18n';")
        assertFalse("别名下的 translate 不应被误当成 useI18n 已存在", hasSpec(decl, "./i18n", "useI18n"))
    }

    // ── default import ───────────────────────────────────────────

    fun testDefaultImportRecognized() {
        val decl = onlyImport("import i18n from 'i18next';")
        assertTrue("default import i18n 应被识别", hasSpec(decl, "i18next", "i18n"))
    }

    fun testDefaultImportWithName() {
        val decl = onlyImport("import customer from './i18n';")
        assertFalse("default import customer (非目标) 不应误判为 t", hasSpec(decl, "./i18n", "t"))
        assertTrue("default import customer 应被其自身名字识别", hasSpec(decl, "./i18n", "customer"))
    }

    // ── namespace import ─────────────────────────────────────────

    fun testNamespaceImportRecognized() {
        val decl = onlyImport("import * as i18n from 'vue-i18n';")
        // namespace 别名 i18n 恰好等于 wantedName → 视为已导入
        assertTrue("namespace import `* as i18n` 绑定自由名 i18n，wantedName=i18n 应识别", hasSpec(decl, "vue-i18n", "i18n"))
        // namespace 别名 ≠ wantedName 时并不把该自由名带入作用域，不应视为已导入
        // （P0：旧实现一律 return true，`import * as vue from 'vue-i18n'` 也被误判为 useI18n 已导入、
        //   跳过注入，生成的裸 useI18n() 运行时未定义。）
        assertFalse("namespace 别名 i18n ≠ wantedName=useI18n，不应识别", hasSpec(decl, "vue-i18n", "useI18n"))
        assertFalse("namespace 别名 i18n ≠ wantedName=anything，不应识别", hasSpec(decl, "vue-i18n", "anything"))
    }

    // ── multiline import ─────────────────────────────────────────

    fun testMultilineNamedImport() {
        val decl = onlyImport(
            """
            import {
              useI18n,
              t,
            } from 'vue-i18n';
            """.trimIndent()
        )
        assertTrue("多行 named import 应识别 useI18n", hasSpec(decl, "vue-i18n", "useI18n"))
    }

    fun testMultilineImportAlias() {
        val decl = onlyImport(
            """
            import {
              t as translate,
              n,
            } from './i18n';
            """.trimIndent()
        )
        assertTrue("多行 import 中 `t as translate` 应识别 t 已导入", hasSpec(decl, "./i18n", "t"))
    }

    // ── type import ──────────────────────────────────────────────

    fun testTypeImportNamed() {
        val decl = onlyImport("import type { I18n } from 'vue-i18n';")
        assertFalse("type import 中的 I18n 不应与 t 混淆", hasSpec(decl, "vue-i18n", "t"))
        assertTrue("type import 中 I18n 应被识别为自己", hasSpec(decl, "vue-i18n", "I18n"))
    }

    // ── side-effect import（仅导入副作用，无 specifier）────────────

    fun testSideEffectImportNotMatched() {
        val decl = onlyImport("import 'vue-i18n';")
        assertFalse("side-effect import 不应被识别为已导入 useI18n", hasSpec(decl, "vue-i18n", "useI18n"))
    }

    // ── CRLF ─────────────────────────────────────────────────────

    fun testCrlfImportRecognized() {
        val decl = onlyImport("import { useI18n } from 'vue-i18n';\r\n")
        assertTrue("CRLF 行尾不应影响判定", hasSpec(decl, "vue-i18n", "useI18n"))
    }

    fun testCrlfMultiline() {
        val decl = onlyImport("import {\r\n  useI18n,\r\n} from 'vue-i18n';\r\n")
        assertTrue("CRLF 多行 import 应识别 useI18n", hasSpec(decl, "vue-i18n", "useI18n"))
    }

    // ── 相对路径 & alias 路径 ─────────────────────────────────────

    fun testRelativePathWithIndexSuffix() {
        val decl = onlyImport("import { useI18n } from './locales/index';")
        assertTrue("./locales/index 相对路径应识别 useI18n", hasSpec(decl, "./locales", "useI18n"))
    }

    fun testDifferentModuleNotMatched() {
        val decl = onlyImport("import { useI18n } from './other';")
        assertFalse("./other 不是目标模块 vue-i18n", hasSpec(decl, "vue-i18n", "useI18n"))
    }

    // ── 保持原有 alias 不变 / 不产生 duplicate ─────────────────────

    fun testExistingAliasNotClobbered() {
        // 已存在 `t as translate` 时，若目标 wantedName 是 translate，应识别为已导入
        val decl = onlyImport("import { t as translate } from './i18n';")
        assertTrue("别名 translate 自身也应被视为已存在 t（避免二次注入破坏别名）", hasSpec(decl, "./i18n", "translate"))
    }

    // ── Windows path / import path alias / window 反斜杠路径 ─────────

    fun testAtAliasModulePath() {
        val decl = onlyImport("import { useI18n } from '@/locales';")
        assertTrue("@/locales 路径别名应识别 useI18n", hasSpec(decl, "@/locales", "useI18n"))
    }

    fun testAtAliasWithIndexSuffix() {
        val decl = onlyImport("import { t } from '@/locales/index';")
        assertTrue("@/locales/index 路径别名（含 /index 尾缀）应识别 t", hasSpec(decl, "@/locales", "t"))
    }

    private fun hasSpec(decl: ES6ImportDeclaration, module: String, wanted: String): Boolean =
        I18nPsiTools.hasImportedSpecifier(decl, module, wanted)

    // ── Import/Symbol Collision（P1 §8）：$t 别名语义锁定 ──────────────
    // BUG：#35 修复后用户手写 `const { t } = useI18n()`（只绑定 t，未绑定 $t）时，
    // 若插件误以为 $t 已存在而跳过注入，生成的 key 会运行时 $t 未定义。
    // scopeHasDestructuredCall 必须只在解构确实绑定了 $t 时才判定已处理。

    private fun scopeHasDollarT(source: String): Boolean =
        I18nPsiTools.scopeHasDestructuredCall(
            myFixture.addFileToProject("src/hook.ts", source.trimIndent()),
            callee = "useI18n",
            destructureNameFrom = "t",
            destructureAlias = "\$t"
        )

    fun testScopeUserDestructuresOnlyTShouldStillInjectDollarT() {
        // 用户手写 const { t } = useI18n()：只绑定 t → $t 未定义 → 必须判定为"未处理"（继续注入 $t）
        assertFalse("仅绑定 t 时不应视为 \$t 已存在", scopeHasDollarT("const { t } = useI18n()"))
    }

    fun testScopeUserDestructuresTcOnlyNotDollarT() {
        assertFalse("仅绑定 tc 时不应视为 \$t 已存在", scopeHasDollarT("const { tc } = useI18n()"))
    }

    fun testScopeCanonicalFormRecognized() {
        assertTrue("插件规范形式 const { t: \$t } 应判定为已处理", scopeHasDollarT("const { t: \$t } = useI18n()"))
    }

    fun testScopeAliasAmongOthersRecognized() {
        assertTrue("const { t: \$t, n } 中 \$t 已绑定应判定为已处理", scopeHasDollarT("const { t: \$t, n } = useI18n()"))
    }

    fun testScopeShortDollarTFormRecognized() {
        assertTrue("const { \$t } = useI18n() 直接绑定 \$t 应判定为已处理", scopeHasDollarT("const { \$t } = useI18n()"))
    }

    fun testScopeChainedReceiverRecognized() {
        assertTrue("链式接收者 i18n.useI18n() 中绑定 \$t 应判定为已处理", scopeHasDollarT("const { t: \$t } = i18n.useI18n()"))
    }

    fun testScopeChainedReceiverNotBindingDollarT() {
        assertFalse("链式接收者仅绑定 t（无 \$t）不应判定为已处理", scopeHasDollarT("const { t } = i18n.useI18n()"))
    }

    // 非 useI18n 调用即使解构相同也不该命中（callee 必须匹配）
    fun testScopeDifferentCalleeIgnored() {
        assertFalse("非 useI18n 调用不应命中", scopeHasDollarT("const { t: \$t } = other()"))
    }
}