package com.pan.extractor.planner

import com.pan.extractor.model.ExtractionSite
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

/**
 * P0 A 组 A4：统一 ChangePlan —— 把 Code + Import + Resource 汇成一个 [ProjectChangePlanner.ChangePlan]，
 * 写入前统一 preflight，失败零写入（PROJECT_ANALYSIS §6 / §16）。
 *
 * 断言：
 *  - [ProjectChangePlanner.plan] 能把普通 rewrite + 失效 pointer 组装进同一计划；
 *  - [ProjectChangePlanner.ChangePlan.preflightOrThrow] 在任一类失效时抛异常（零写入）；
 *  - 全量有效计划通过 preflight（不抛）。
 */
class ProjectChangePlannerTest : BasePlatformTestCase() {

    private fun rewritePlan(siteId: String, target: com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiElement>? = null) =
        RewritePlan(siteId = siteId, kind = RewriteKind.JS_LITERAL, processorIndex = 0, newExpression = "'k'", target = target)

    private fun site(id: String, pointer: com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiElement>?) =
        ExtractionSite(id = id, originalMessage = "原文", replaceRoot = pointer)

    // ── 全量有效 → preflight 通过 ─────────────────────────────────

    fun testValidChangePlanPassesPreflight() {
        val psi = myFixture.configureByText("plan.ts", "const ok = 1;\n")
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(psi.firstChild)
        val cp = ProjectChangePlanner.plan(
            processors = emptyList(),
            sites = listOf(site("s1", ptr)),
            rewrites = listOf(rewritePlan("s1", ptr)),
            processorCount = 1,
            entryVf = psi.virtualFile,
            finalExtracted = mapOf("k" to "v"),
            dropKeys = emptySet(),
        )
        assertTrue("全量有效计划应通过 preflight，实际=${cp.preflight().issues.map { it.code }}", cp.preflight().isValid)
    }

    // ── 失效 pointer → 零写入（抛异常）─────────────────────────────

    fun testBurnedPointerFailsPreflight() {
        val psi = myFixture.configureByText("burn_plan.ts", "const live = 2;\n")
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(psi.firstChild)
        WriteCommandAction.runWriteCommandAction(project) { psi.delete() }
        val cp = ProjectChangePlanner.plan(
            processors = emptyList(),
            sites = listOf(site("s1", ptr)),
            rewrites = listOf(rewritePlan("s1", ptr)),
            processorCount = 1,
            entryVf = psi.virtualFile,
            finalExtracted = mapOf("k" to "v"),
            dropKeys = emptySet(),
        )
        try {
            cp.preflightOrThrow()
            fail("失效 pointer 应先抛异常（零写入）")
        } catch (e: IllegalStateException) {
            assertTrue("异常应表述 preflight 失败，实际=${e.message}",
                e.message.orEmpty().contains("preflight") || e.message.orEmpty().contains("未写入"))
        }
    }

    // ── 空/合法计划不抛 ───────────────────────────────────────────

    fun testEmptyChangePlanPasses() {
        val cp = ProjectChangePlanner.plan(
            processors = emptyList(),
            sites = emptyList(),
            rewrites = emptyList(),
            processorCount = 0,
            entryVf = null,
            finalExtracted = emptyMap(),
            dropKeys = emptySet(),
        )
        assertTrue("空计划应通过 preflight", cp.preflight().isValid)
    }
}