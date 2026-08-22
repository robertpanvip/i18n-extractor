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

    // ── A1b：替换后语法有效性 ────────────────────────────────────

    fun testRewriteResultUnbalancedFails() {
        val psi = myFixture.configureByText("r.ts", "const x = 'hi';\n")
        val el = psi.findElementAt("const x = ".length)!!
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(el)
        val rp = RewritePlan(
            siteId = "s1", kind = RewriteKind.JS_LITERAL, processorIndex = 0,
            newExpression = "\$t('k'", // 缺失右括号 → 替换后括号不平衡
            target = ptr,
        )
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rp),
            sites = listOf(site("s1", ptr)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("替换后括号不平衡应报 REWRITE_RESULT_UNBALANCED，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "REWRITE_RESULT_UNBALANCED" })
    }

    fun testRewriteResultBalancedPasses() {
        val psi = myFixture.configureByText("ok.ts", "const x = 'hi';\n")
        val el = psi.findElementAt("const x = ".length)!!
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(el)
        val rp = RewritePlan(
            siteId = "s1", kind = RewriteKind.JS_LITERAL, processorIndex = 0,
            newExpression = "\$t('k')", // 完整调用 → 替换后结构平衡
            target = ptr,
        )
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rp),
            sites = listOf(site("s1", ptr)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("完整替换应通过，实际=${result.issues.map { it.code }}", result.isValid)
    }

    // 回归：newExpression 本身平衡、但文件里有注释含孤引号（如 `// it's`、`/* can't */`、
    // `<!-- it's -->`）时，旧实现把注释里的单引号当成未闭合字符串，导致整文件误判不平衡，
    // 连累同文件所有 rewrite 报 REWRITE_RESULT_UNBALANCED。注释内容应被整体忽略。
    fun testRewriteResultBalancedDespiteLineCommentWithQuote() {
        val psi = myFixture.configureByText("c.ts", "// it's fine\nconst x = 'hi';\n")
        val el = psi.findElementAt("// it's fine\nconst x = ".length)!!
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(el)
        val rp = RewritePlan(
            siteId = "s1", kind = RewriteKind.JS_LITERAL, processorIndex = 0,
            newExpression = "t('hi')", target = ptr,
        )
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rp),
            sites = listOf(site("s1", ptr)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("行注释含孤引号不应干扰平衡判定，实际=${result.issues.map { it.code }}", result.isValid)
    }

    fun testRewriteResultBalancedDespiteBlockCommentWithOrphanBrace() {
        val psi = myFixture.configureByText("b.ts", "/* just a } brace */\nconst y = 'k';\n")
        val el = psi.findElementAt("/* just a } brace */\nconst y = ".length)!!
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(el)
        val rp = RewritePlan(
            siteId = "s2", kind = RewriteKind.JS_LITERAL, processorIndex = 0,
            newExpression = "t('k')", target = ptr,
        )
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rp),
            sites = listOf(site("s2", ptr)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("块注释内孤括号不应干扰平衡判定，实际=${result.issues.map { it.code }}", result.isValid)
    }

    fun testRewriteResultBalancedDespiteHtmlCommentWithQuote() {
        val psi = myFixture.configureByText("d.tsx", "<!-- it's a note -->\nconst z = 'v';\n")
        val el = psi.findElementAt("<!-- it's a note -->\nconst z = ".length)!!
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(el)
        val rp = RewritePlan(
            siteId = "s3", kind = RewriteKind.JS_LITERAL, processorIndex = 0,
            newExpression = "t('v')", target = ptr,
        )
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rp),
            sites = listOf(site("s3", ptr)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("模板注释含孤引号不应干扰平衡判定，实际=${result.issues.map { it.code }}", result.isValid)
    }

    // 回归：真实源码中正则字面量（如 split /['",、]+/）含引号，会让朴素扫描器误判整文件不平衡。
    // 若因此对每个 rewrite 都报错，会连累同文件所有改写误报 REWRITE_RESULT_UNBALANCED。
    // 增量判定：原文件本身已被扫描器判为不平衡 → 跳过本次改写，不再误报。
    fun testRewriteResultBalancedDespiteRegexWithQuoteInFile() {
        val psi = myFixture.configureByText("re.ts", "const parts = s.split(/['\"]+/, 10);\nconst x = 'hi';\n")
        val el = psi.findElementAt("const parts = s.split(/['\"]+/, 10);\nconst x = ".length)!!
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(el)
        val rp = RewritePlan(
            siteId = "s4", kind = RewriteKind.JS_LITERAL, processorIndex = 0,
            newExpression = "t('hi')", target = ptr,
        )
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = listOf(rp),
            sites = listOf(site("s4", ptr)),
            processorCount = 1,
            importFiles = emptyMap(),
            resourceFiles = emptyMap(),
        )
        assertTrue("含正则字面量的文件不应因朴素扫描误报，实际=${result.issues.map { it.code }}", result.isValid)
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

    // ── A2b：Import 语义级 ───────────────────────────────────────

    fun testImportBindingConflictAgainstDifferentSourceFails() {
        val psi = myFixture.configureByText("bind.ts", "import { t } from 'x';\nconst a = 1;\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = mapOf(
                ImportPlan(fileName = "bind.ts", imports = listOf("import { t } from 'y';")) to psi.virtualFile,
            ),
            resourceFiles = emptyMap(),
        )
        assertTrue("同名绑定 t 已绑 x，再绑 y 应报 IMPORT_BINDING_CONFLICT，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "IMPORT_BINDING_CONFLICT" })
    }

    fun testImportSpecifierDuplicateAgainstSameSourceFails() {
        val psi = myFixture.configureByText("dup.ts", "import { useI18n } from 'vue-i18n';\nconst a = 1;\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = mapOf(
                ImportPlan(fileName = "dup.ts", imports = listOf("import { useI18n } from 'vue-i18n';")) to psi.virtualFile,
            ),
            resourceFiles = emptyMap(),
        )
        assertTrue("同 specifier 从同 source 重复注入应报 IMPORT_SPECIFIER_DUPLICATE，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "IMPORT_SPECIFIER_DUPLICATE" })
    }

    fun testImportDeduplicatedSameSourcePasses() {
        // 已存在同模块同绑定 → 若 plan 也登记了同名同源，视为重复注入（应由计划侧去重）
        val psi = myFixture.configureByText("dedup.ts", "import { i18n } from '@/locales';\nconst a = 1;\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = mapOf(
                ImportPlan(fileName = "dedup.ts") to psi.virtualFile, // plan 不含重复 import
            ),
            resourceFiles = emptyMap(),
        )
        assertTrue("计划未注入重复 import 时应通过，实际=${result.issues.map { it.code }}", result.isValid)
    }

    // ── A3b：Resource 语义级 ─────────────────────────────────────

    fun testResourceJsonNotParseableFails() {
        val psi = myFixture.configureByText("zh.json", "{ broken\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = emptyMap(),
            resourceFiles = mapOf(
                ResourcePlan(targetPath = "zh.json", entries = emptyMap(), format = "json") to psi.virtualFile,
            ),
        )
        assertTrue("JSON 不可解析应报 RESOURCE_NOT_PARSEABLE，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "RESOURCE_NOT_PARSEABLE" })
    }

    fun testResourceJsonNestedPathConflictFails() {
        val psi = myFixture.configureByText("zh.json", "{\"a\": \"scalar\"}\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = emptyMap(),
            resourceFiles = mapOf(
                ResourcePlan(targetPath = "zh.json", entries = mapOf("a.b" to "v"), format = "json") to psi.virtualFile,
            ),
        )
        assertTrue("嵌套 key a.b 的祖先 a 是标量应报 RESOURCE_NESTED_PATH_CONFLICT，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "RESOURCE_NESTED_PATH_CONFLICT" })
    }

    fun testResourceEntryDropConflictFails() {
        val psi = myFixture.configureByText("zh.json", "{}\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = emptyMap(),
            resourceFiles = mapOf(
                ResourcePlan(targetPath = "zh.json", entries = mapOf("k" to "v"), dropKeys = setOf("k"), format = "json") to psi.virtualFile,
            ),
        )
        assertTrue("key 同时在 entries 与 dropKeys 应报 RESOURCE_ENTRY_AND_DROP_CONFLICT，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "RESOURCE_ENTRY_AND_DROP_CONFLICT" })
    }

    fun testResourceTsObjectMissingFails() {
        val psi = myFixture.configureByText("loc.ts", "const x = 1;\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = emptyMap(),
            resourceFiles = mapOf(
                ResourcePlan(targetPath = "loc.ts", format = "ts") to psi.virtualFile,
            ),
        )
        assertTrue("TS 无导出对象应报 RESOURCE_OBJECT_MISSING，实际=${result.issues.map { it.code }}",
            result.issues.any { it.code == "RESOURCE_OBJECT_MISSING" })
    }

    fun testResourceObjectMissingIsWarningNotBlocking() {
        // 目标 TS/JS 无可解析导出对象 → 报警告但不阻断整批写入（写回层会优雅回退剪贴板）。
        val psi = myFixture.configureByText("loc.ts", "const x = 1;\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = emptyMap(),
            resourceFiles = mapOf(
                ResourcePlan(targetPath = "loc.ts", format = "ts") to psi.virtualFile,
            ),
        )
        assertTrue("仅资源不可解析不应阻断写入，实际=${result.issues.map { it.code }}", result.isValid)
        assertTrue("RESOURCE_OBJECT_MISSING 应为非阻断警告",
            result.issues.filter { it.code == "RESOURCE_OBJECT_MISSING" }.all { !it.blocking })
        // 若该 issue 是唯一问题，requireValidWithActualFiles 不应抛异常（不 abort 整批改写）。
        try {
            ProjectPreflightValidator.requireValidWithActualFiles(
                rewrites = emptyList(),
                sites = emptyList(),
                processorCount = 0,
                importFiles = emptyMap(),
                resourceFiles = mapOf(
                    ResourcePlan(targetPath = "loc.ts", format = "ts") to psi.virtualFile,
                ),
            )
        } catch (e: IllegalStateException) {
            fail("仅有资源不可解析时不应抛异常阻断整批写入，实际=$e")
        }
    }

    fun testResourceTsValidObjectPasses() {
        val psi = myFixture.configureByText("loc.ts", "export default {\n  greeting: 'hi'\n}\n")
        val result = ProjectPreflightValidator.preflightValidate(
            rewrites = emptyList(),
            sites = emptyList(),
            processorCount = 0,
            importFiles = emptyMap(),
            resourceFiles = mapOf(
                ResourcePlan(targetPath = "loc.ts", entries = mapOf("app.ok" to "ok"), format = "ts") to psi.virtualFile,
            ),
        )
        assertTrue("合法 TS 对象且无冲突应通过，实际=${result.issues.map { it.code }}", result.isValid)
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
        val source = myFixture.configureByText("clean.ts", "const c = 2;\n")
        val json = myFixture.configureByText("zh.json", "{}\n")
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(source.firstChild)
        ProjectPreflightValidator.requireValidWithActualFiles(
            rewrites = listOf(rewrite("s1", target = ptr)),
            sites = listOf(site("s1", ptr)),
            processorCount = 1,
            importFiles = mapOf(ImportPlan(fileName = "clean.ts") to source.virtualFile),
            resourceFiles = mapOf(ResourcePlan(targetPath = "zh.json") to json.virtualFile),
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