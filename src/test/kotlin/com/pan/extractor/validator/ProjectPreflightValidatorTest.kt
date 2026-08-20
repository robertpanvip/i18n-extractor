package com.pan.extractor.validator

import com.pan.extractor.model.ExtractionSite
import com.pan.extractor.planner.ImportPlan
import com.pan.extractor.planner.ResourcePlan
import com.pan.extractor.planner.RewriteKind
import com.pan.extractor.planner.RewritePlan
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * P0 A 组：统一 Apply 前 preflight（PROJECT_ANALYSIS §6 / §16）。
 *
 * 断言 [ProjectPreflightValidator] 能捕捉四类「写入前必须失败」的问题，且正常计划
 * 全部通过（isValid）：
 *  - RewritePlan：processor 索引越界 / site 缺失 / 目标 pointer 为 null / 目标 PSI 已失效；
 *  - Import：目标文件不可解析 / 多计划写入同一目标冲突；
 *  - Resource：目标文件不可解析；
 *  - [ProjectPreflightValidator.requireValidWithActualFiles] 发现任一问题时抛异常（零写入语义）。
 */
class ProjectPreflightValidatorTest : BasePlatformTestCase() {

    private fun rewrite(siteId: String, processorIndex: Int = 0, target: com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiElement>? = null) =
        RewritePlan(siteId = siteId, kind = RewriteKind.JS_LITERAL, processorIndex = processorIndex, newExpression = "'k'", target = target)

    private fun site(id: String, pointer: com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiElement>?) =
        ExtractionSite(id = id, originalMessage = "原文", replaceRoot = pointer)

    // ── happy path ────────────────────────────────────────────────

    fun testValidPlansPass() {
        val psi = myFixture.configureByText("a.ts", "const x = 1;\n")
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(psi.firstChild)
        val rp = rewrite("s1", target = ptr)
        val sites = listOf(site("s1", ptr))
        val importFiles = mapOf(
            ImportPlan(fileName = "a.ts", imports = listOf("import {t} from 'x'")) to psi.virtualFile
        )
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rp),
            sites = sites,
            processorCount = 1,
            importFiles = importFiles,
            resourceFiles = emptyMap(),
        )
        assertTrue("正常计划应通过 preflight，实际 issue=${result.issues}", result.isValid)
    }

    // ── A1：RewritePlan ───────────────────────────────────────────

    fun testProcessorIndexOutOfRangeFails() {
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rewrite("s1", processorIndex = 5)),
            sites = listOf(site("s1", null)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue(result.issues.any { it.code == "REWRITE_PROCESSOR_IDX" })
    }

    fun testSiteMissingFails() {
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rewrite("ghost")),
            sites = emptyList(),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("缺失 site 应报 REWRITE_SITE_MISSING，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "REWRITE_SITE_MISSING" })
    }

    fun testTargetNullFails() {
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rewrite("s1")),
            sites = listOf(site("s1", null)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("无 target 应报 REWRITE_TARGET_NULL，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "REWRITE_TARGET_NULL" })
    }

    fun testBurnedPointerFails() {
        val psi = myFixture.configureByText("burn.ts", "const live = 1;\n")
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(psi.firstChild)
        WriteCommandAction.runWriteCommandAction(project) { psi.delete() }
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rewrite("s1", target = ptr)),
            sites = listOf(site("s1", ptr)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("目标 PSI 已失效应报 REWRITE_TARGET_INVALID，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "REWRITE_TARGET_INVALID" })
    }

    // ── A2：Import ────────────────────────────────────────────────

    fun testImportUnresolvedFails() {
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = mapOf(ImportPlan(fileName = "nosuch.ts") to null),
            resourceFiles = emptyMap(),
        )
        assertTrue("import 目标不可解析应报 IMPORT_TARGET_UNRESOLVED，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "IMPORT_TARGET_UNRESOLVED" })
    }

    fun testImportDuplicateTargetFails() {
        val psi = myFixture.configureByText("dup.ts", "const a = 1;\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = mapOf(
                ImportPlan(fileName = "a.ts") to psi.virtualFile,
                ImportPlan(fileName = "b.ts") to psi.virtualFile,
            ),
            resourceFiles = emptyMap(),
        )
        assertTrue("两 import 写同一文件应报 IMPORT_TARGET_DUPLICATE，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "IMPORT_TARGET_DUPLICATE" })
    }

    // ── A3：Resource ──────────────────────────────────────────────

    fun testResourceUnresolvedFails() {
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = emptyMap(),
            resourceFiles = mapOf(ResourcePlan(targetPath = "locales/zh.json") to null),
        )
        assertTrue("resource 目标不可解析应报 RESOURCE_TARGET_UNRESOLVED，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "RESOURCE_TARGET_UNRESOLVED" })
    }

    // ── 零写入：requireValidWithActualFiles ───────────────────────

    fun testRequireValidThrowsOnAnyIssue() {
        try {
            ProjectPreflightValidator.requireValidWithActualFiles(
                rewrites = listOf(rewrite("ghost")),
                sites = emptyList(),
                processorCount = 1,
                importFiles = emptyMap(),
                resourceFiles = emptyMap(),
            )
            fail("存在失效计划时应抛异常（零写入）")
        } catch (e: IllegalStateException) {
            assertTrue("异常信息应说明 preflight 失败，实际=${e.message}",
                e.message.orEmpty().contains("preflight") || e.message.orEmpty().contains("未写入"))
        }
    }

    fun testRequireValidPassesWhenClean() {
        val psi = myFixture.configureByText("clean.ts", "const c = 2;\n")
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(psi.firstChild)
        ProjectPreflightValidator.requireValidWithActualFiles(
            rewrites = listOf(rewrite("s1", target = ptr)),
            sites = listOf(site("s1", ptr)),
            processorCount = 1,
            importFiles = mapOf(ImportPlan(fileName = "clean.ts") to psi.virtualFile),
            resourceFiles = mapOf(ResourcePlan(targetPath = "locales/zh.ts") to psi.virtualFile),
        )
        assertTrue("全量有效计划不应抛异常", true)
    }

    // ── 辅助断言：isValid 语义 ────────────────────────────────────

    fun testInvalidResultNotValid() {
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rewrite("gone")),
            sites = emptyList(),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertFalse("有 issue 时 isValid 应为 false", result.isValid)
    }
}