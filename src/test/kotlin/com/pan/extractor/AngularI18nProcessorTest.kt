package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.project.Util
import com.pan.extractor.strategy.I18nFrameworkRegistry

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Angular（ngx-translate）i18n 提取端到端测试。
 *
 * ngx-translate 在模板用**管道**而非函数：`{{ '你好' | translate }}`。本测试聚焦
 * 依赖识别 + .html 模板文本/属性提取（Angular 的主流用法，产出自洽的管道表达式）。
 */
class AngularI18nProcessorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Angular 项目：@angular/core + @ngx-translate/core，不含 vue / solid / svelte
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "angular-test-project",
              "dependencies": {
                "@angular/core": "^17.0.0",
                "@ngx-translate/core": "^15.0.0"
              }
            }
            """.trimIndent()
        )
    }

    private fun configureFile(fileName: String, text: String): PsiFile {
        return if (fileName.contains('/') || fileName.contains('\\')) {
            val psiFile = myFixture.addFileToProject(fileName, text)
            myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
            psiFile
        } else {
            myFixture.configureByText(fileName, text)
        }
    }

    private fun compact(file: PsiFile): String = file.text.replace("\\s+".toRegex(), "")

    // ============================================================
    // 1. 框架识别：@angular/core + @ngx-translate/core → AngularI18nStrategy
    // ============================================================

    fun testAngularProjectDetected() {
        val file = myFixture.configureByText("App.ts", "export class App {}")
        assertTrue("isAngular should be true for an Angular project", Util.isAngular(file))
        assertEquals("ngx-translate", I18nFrameworkRegistry.detect(file).id)
        assertEquals("translate", I18nFrameworkRegistry.detect(file).tFunctionName)
    }

    fun testAngularStrategyPlaceholderShape() {
        val strategy = I18nFrameworkRegistry.detect(myFixture.configureByText("App.ts", "export class App {}"))
        assertEquals("{{0}}", strategy.placeholderFor(0))
        assertEquals("0", strategy.paramKey(0))
        assertTrue("paramKeyNeedsQuote should be true", strategy.paramKeyNeedsQuote)
        assertEquals("@ngx-translate/core", strategy.bootstrapDeps.single())
    }

    // ============================================================
    // 2. .html 模板文本提取 → {{ '你好' | translate }}
    // ============================================================

    fun testAngularTemplateTextExtract() {
        val file = configureFile(
            "src/app/app.component.html",
            "<div>你好</div>"
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val text = file.text
        assertTrue(
            "Template text should wrap as {{ `你好` | translate }} (backtick key per analyzer), got:\n$text",
            text.contains("{{ `你好` | translate }}")
        )
    }

    fun testAngularTemplateMultipleTextExtract() {
        val file = configureFile(
            "src/app/home.html",
            """
            <div>
              <h1>首页</h1>
              <p>欢迎回来</p>
            </div>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val text = file.text
        assertTrue("Should extract title", text.contains("{{ `首页` | translate }}"))
        assertTrue("Should extract paragraph", text.contains("{{ `欢迎回来` | translate }}"))
    }

    // ============================================================
    // 3. .html 模板属性提取 → title="{{ '提示' | translate }}"
    // ============================================================

    fun testAngularTemplateAttributeExtract() {
        val file = configureFile(
            "src/app/tooltip.html",
            """<div title="提示信息">hover me</div>"""
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val text = file.text
        assertTrue(
            "Attribute should wrap as title=\"{{ '提示信息' | translate }}\", got:\n$text",
            text.contains("title=\"{{ '提示信息' | translate }}\"") ||
                text.contains("title='{{ '提示信息' | translate }}'")
        )
    }

    // ============================================================
    // 4. 不缺 Vue/React/Svelte import，不产生 Vue `:` 前缀
    // ============================================================

    fun testAngularTemplateNeverInjectsVuePrefix() {
        val file = configureFile(
            "src/app/simple.html",
            """<div title="你好">文字</div>"""
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val text = file.text
        assertFalse("Should not use Vue ':' prefix, got:\n$text", text.contains(":title"))
        assertFalse("Should not wrap as Vue {{ \$t(...) }}, got:\n$text", text.contains("\$t("))
        assertFalse("Should not import TranslateService into .html, got:\n$text",
            text.contains("@ngx-translate/core"))
    }
}