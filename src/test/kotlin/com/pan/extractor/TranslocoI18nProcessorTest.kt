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
 * Transloco（@jsverse/transloco）i18n 提取端到端测试。
 *
 * 与 ngx-translate 同形态：模板用**管道** `{{ '你好' | transloco }}`。本测试聚焦依赖识别 +
 * .html 模板文本/属性提取（与 Angular 主流用法一致）。
 */
class TranslocoI18nProcessorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Transloco 项目：@angular/core + @jsverse/transloco，不含 vue / solid / svelte
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "transloco-test-project",
              "dependencies": {
                "@angular/core": "^17.0.0",
                "@jsverse/transloco": "^8.0.0"
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

    // ============================================================
    // 1. 框架识别：@angular/core + @jsverse/transloco → TranslocoI18nStrategy
    // ============================================================

    fun testTranslocoProjectDetected() {
        val file = myFixture.configureByText("App.ts", "export class App {}")
        assertTrue("isTransloco should be true for a Transloco project", Util.isTransloco(file))
        assertEquals("transloco", I18nFrameworkRegistry.detect(file).id)
        assertEquals("transloco", I18nFrameworkRegistry.detect(file).tFunctionName)
    }

    fun testTranslocoStrategyPlaceholderShape() {
        val strategy = I18nFrameworkRegistry.detect(myFixture.configureByText("App.ts", "export class App {}"))
        assertEquals("{{0}}", strategy.placeholderFor(0))
        assertEquals("0", strategy.paramKey(0))
        assertTrue("paramKeyNeedsQuote should be true", strategy.paramKeyNeedsQuote)
        assertEquals("@jsverse/transloco", strategy.bootstrapDeps.single())
    }

    // ============================================================
    // 2. .html 模板文本提取 → {{ `你好` | transloco }}
    // ============================================================

    fun testTranslocoTemplateTextExtract() {
        val file = configureFile(
            "src/app/app.component.html",
            "<div>你好</div>"
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val text = file.text
        assertTrue(
            "Template text should wrap as {{ `你好` | transloco }} (backtick key per analyzer), got:\n$text",
            text.contains("{{ `你好` | transloco }}")
        )
    }

    fun testTranslocoTemplateMultipleTextExtract() {
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
        assertTrue("Should extract title", text.contains("{{ `首页` | transloco }}"))
        assertTrue("Should extract paragraph", text.contains("{{ `欢迎回来` | transloco }}"))
    }

    // ============================================================
    // 3. .html 模板属性提取 → title="{{ '提示信息' | transloco }}"
    // ============================================================

    fun testTranslocoTemplateAttributeExtract() {
        val file = configureFile(
            "src/app/tooltip.html",
            """<div title="提示信息">hover me</div>"""
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val text = file.text
        assertTrue(
            "Attribute should wrap as title=\"{{ '提示信息' | transloco }}\", got:\n$text",
            text.contains("title=\"{{ '提示信息' | transloco }}\"") ||
                text.contains("title='{{ '提示信息' | transloco }}'")
        )
    }

    // ============================================================
    // 4. 不注入 Vue/React 前缀、.html 不注入 TranslocoService import
    // ============================================================

    fun testTranslocoTemplateNeverInjectsVuePrefix() {
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
        assertFalse("Should not import TranslocoService into .html, got:\n$text",
            text.contains("TranslocoService"))
    }
}