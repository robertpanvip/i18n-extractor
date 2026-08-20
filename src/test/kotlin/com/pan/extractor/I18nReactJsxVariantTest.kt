package com.pan.extractor

import com.pan.extractor.core.I18nProcessor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 5.5 — Import/Rewrite 组合：React JSX / TSX 扩展形态。
 *
 * 现有 React 用例全部使用 `.tsx`，本文件补齐 `.jsx` / `.tsx` 两种扩展名，
 * 验证「注入 useTranslation hook import」在两种扩展名下都只注入一次：
 *  - import 恰好 1 条 `react-i18next`
 *  - `useTranslation()` 调用恰好 1 次（import 中不带 ()，不计入）
 *  - 硬编码中文被提取
 *
 * 触发路径：函数组件内 JSX 硬编码文本 → hasExtractedStrings=true 且无既有 hook →
 * [I18nImportInjector] 注入 `import { useTranslation } from 'react-i18next'` + `const { t } = useTranslation()`。
 */
class I18nReactJsxVariantTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "react-jsx-variant",
              "dependencies": {
                "react": "^18.0.0",
                "react-dom": "^18.0.0",
                "react-i18next": "^13.0.0"
              }
            }
            """.trimIndent()
        )
    }

    private fun extract(fileName: String): PsiFile {
        val file = myFixture.addFileToProject(
            fileName,
            """
            export default function App() {
                return <div>你好</div>
            }
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()
        return file
    }

    /** 断言该文件恰好 1 条 useTranslation import + 1 次 hook 调用。 */
    private fun assertSingleHook(name: String, file: PsiFile) {
        val compact = file.text.replace("\\s+".toRegex(), "")
        val matches = Regex("import\\{useTranslation\\}from'react-i18next'").findAll(compact).toList()
        assertEquals("$name 应恰好 1 条 useTranslation import, got:\n${file.text}", 1, matches.size)
        val calls = compact.split("useTranslation()").size - 1
        assertEquals("$name 应恰好 1 次 useTranslation() 调用", 1, calls)
    }

    fun testJsxVariantSingleImport() {
        assertSingleHook("App.jsx", extract("src/App.jsx"))
    }

    fun testTsxVariantSingleImport() {
        assertSingleHook("App.tsx", extract("src/App.tsx"))
    }

    fun testJsxAndTsxFilesBothSingleImport() {
        assertSingleHook("A.jsx", extract("src/A.jsx"))
        assertSingleHook("B.tsx", extract("src/B.tsx"))
    }
}