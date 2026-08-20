package com.pan.extractor

import com.pan.extractor.project.I18nPsiTools

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 4.4 — Harden ImportInjector（补充）：
 * 验证 [I18nPsiTools.hasImportedSpecifier] 对更多 import 形态的正确判定，防止重复注入。
 *
 * 覆盖：
 *  - type import：`import { type t }` 视为 t 已导入
 *  - 相对路径：`./i18n` / `../i18n` 识别 named import
 *  - 多行 alias：多行 `t as translate` 识别原 t 已导入
 *  - moduleName 不含 index 尾缀、源码含 `/index` → removeSuffix('/index') 后匹配
 *  - 双引号模块路径
 */
class I18nImportInjectorMoreTest : BasePlatformTestCase() {

    /** 向项目添加一个 .ts 文件，返回其中唯一的 import declaration（无则报错）。 */
    private fun onlyImport(source: String): ES6ImportDeclaration {
        val psi = myFixture.addFileToProject("src/imports.ts", source.trimIndent())
        myFixture.configureFromExistingVirtualFile(psi.virtualFile)
        val imports = PsiTreeUtil.findChildrenOfType(psi, ES6ImportDeclaration::class.java)
        assertEquals("应恰好生成 1 条 import，源码:\n$source", 1, imports.size)
        return imports.first()
    }

    private fun hasSpec(decl: ES6ImportDeclaration, module: String, wanted: String): Boolean =
        I18nPsiTools.hasImportedSpecifier(decl, module, wanted)

    // ── 1. type import：`import { type t }` 视为 t 已导入 ─────────────

    fun testTypeImportOfFunctionNameRecognized() {
        val decl = onlyImport("import { type t } from 'vue-i18n';")
        assertTrue("`import { type t }` 应识别 t 已导入（防重复注入）", hasSpec(decl, "vue-i18n", "t"))
    }

    // ── 2. 相对路径：`./i18n` / `../i18n` 识别 named import ───────────

    fun testRelativeSingleDotPath() {
        val decl = onlyImport("import { useI18n } from './i18n';")
        assertTrue("./i18n 相对路径应识别 useI18n", hasSpec(decl, "./i18n", "useI18n"))
    }

    fun testRelativeParentDotPath() {
        val decl = onlyImport("import { useI18n } from '../i18n';")
        assertTrue("../i18n 相对路径应识别 useI18n", hasSpec(decl, "../i18n", "useI18n"))
    }

    // ── 3. 多行 alias：`t as translate` 识别原 t 已导入 ────────────────

    fun testMultilineAliasRecognizesOriginalName() {
        val decl = onlyImport(
            """
            import {
              t as translate,
            } from 'vue-i18n';
            """.trimIndent()
        )
        assertTrue("多行 `t as translate` 应识别原 t 已导入", hasSpec(decl, "vue-i18n", "t"))
    }

    // ── 4. moduleName 不含 index 尾缀、源码含 `/index` → removeSuffix('/index') 匹配 ──

    fun testModuleWithoutIndexSuffixMatchesSourceWithIndex() {
        val decl = onlyImport("import { t } from '@/locales/index';")
        assertTrue("moduleName '@/locales' 与源码 '@/locales/index' 应匹配", hasSpec(decl, "@/locales", "t"))
    }

    // ── 5. 双引号模块路径 ──────────────────────────────────────────

    fun testDoubleQuotedModulePath() {
        val decl = onlyImport("import { t } from \"./i18n\";")
        assertTrue("双引号路径 \"./i18n\" 应识别 t", hasSpec(decl, "./i18n", "t"))
    }
}