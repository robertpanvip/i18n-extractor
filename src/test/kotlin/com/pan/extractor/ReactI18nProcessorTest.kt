package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * React / JSX i18n 提取测试
 *
 * 覆盖 JSX 文本、属性、组件函数 useTranslation 注入、import 位置等 React 专属场景。
 */
class ReactI18nProcessorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // 创建 React 项目的 package.json（包含 react 依赖，无 vue 依赖）
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "react-test-project",
              "dependencies": {
                "react": "^18.0.0",
                "react-dom": "^18.0.0",
                "react-i18next": "^13.0.0"
              }
            }
            """.trimIndent()
        )
    }

    // ============================================================
    // 1. JSX 文本提取
    // ============================================================

    /**
     * 测试 JSX 基本文本提取
     */
    fun testReactJsxExtract() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App(){

                return (
                    <div>
                        你好
                    </div>
                )

            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(1, processor.extractedStrings.size)
    }

    /**
     * 测试 JSX 属性中的字符串提取
     */
    fun testReactJsxAttributeStringExtract() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App(){
                return <div title="提示信息">hover me</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(
            "extractedStrings should contain '提示信息', got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("提示信息")
        )
    }

    /**
     * 测试 JSX 嵌套元素文本
     */
    fun testReactJsxNestedElements() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return (
                    <div>
                        <h1>标题</h1>
                        <p>段落内容</p>
                        <span>更多文本</span>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertEquals(3, processor.extractedStrings.size)
        assertTrue(processor.extractedStrings.containsValue("标题"))
        assertTrue(processor.extractedStrings.containsValue("段落内容"))
        assertTrue(processor.extractedStrings.containsValue("更多文本"))
    }

    // ============================================================
    // 2. React 组件函数 - useTranslation 注入
    // ============================================================

    /**
     * 测试函数组件中注入 useTranslation hook
     */
    fun testReactFunctionComponentHookInjection() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return <div>你好</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        assertTrue(
            "Should contain useTranslation hook, got:\n$resultText",
            resultText.contains("useTranslation()") && resultText.contains("\$t")
        )
    }

    /**
     * 测试箭头函数组件中注入 useTranslation hook
     */
    fun testReactArrowFunctionComponentHookInjection() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            const App = () => {
                return <div>你好</div>
            }
            export default App
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        assertTrue(
            "Arrow function should contain useTranslation hook, got:\n$resultText",
            resultText.contains("useTranslation()") && resultText.contains("\$t")
        )
    }

    /**
     * 测试已有 useTranslation 时不重复注入
     */
    fun testReactExistingUseTranslationNotDuplicated() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { useTranslation } from 'react-i18next';

            export default function App() {
                const { t: ${'$'}t } = useTranslation();
                return <div>{${'$'}t('你好')}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        // 注意：import 中的 useTranslation 不带 ()，只有调用带 ()
        // 所以 count = 1 表示只有一个 useTranslation() 调用，没有重复注入
        val count = resultText.split("useTranslation()").size - 1
        assertEquals("useTranslation() call should appear exactly once, got $count", 1, count)
    }

    // ============================================================
    // 3. react-i18next import 注入
    // ============================================================

    /**
     * 测试无 import 时 import 应加到文件最开头
     */
    fun testReactImportAddedAtTop() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return <div>你好</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        val importLine = resultText.lines().firstOrNull { it.contains("react-i18next") }
        assertTrue(
            "import react-i18next should exist, got:\n$resultText",
            importLine != null
        )
        // import 应该在 export 之前（用 react-i18next 关键字定位，避免空格差异）
        val importIndex = resultText.indexOf("react-i18next")
        val exportIndex = resultText.indexOf("export default")
        assertTrue(
            "import should come before export, import at $importIndex, export at $exportIndex",
            importIndex >= 0 && exportIndex >= 0 && importIndex < exportIndex
        )
    }

    /**
     * 测试已有其他 import 时 react-i18next import 加在最前面
     */
    fun testReactImportAddedBeforeOtherImports() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import React from 'react';
            import { useState } from 'react';

            export default function App() {
                return <div>你好</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        val lines = resultText.lines()
        val reactI18Index = lines.indexOfFirst { it.contains("react-i18next") }
        val reactIndex = lines.indexOfFirst { it.contains("from 'react'") }

        assertTrue("react-i18next import should exist", reactI18Index >= 0)
        assertTrue(
            "react-i18next import should come before react import, got:\n$resultText",
            reactI18Index < reactIndex
        )
    }

    /**
     * 测试已有 react-i18next import 时不重复添加
     */
    fun testReactExistingImportNotDuplicated() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            import React from 'react';

            export default function App() {
                return <div>你好</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        val count = resultText.split("react-i18next").size - 1
        assertEquals("react-i18next should appear exactly once, got $count", 1, count)
    }

    // ============================================================
    // 4. 多组件场景
    // ============================================================

    /**
     * 测试多个组件时每个组件都注入 useTranslation
     */
    fun testReactMultipleComponentsAllInjected() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            function Header() {
                return <header>头部</header>
            }

            function Footer() {
                return <footer>底部</footer>
            }

            export default function App() {
                return (
                    <div>
                        <Header />
                        <Footer />
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val resultText = file.text
        val count = resultText.split("useTranslation()").size - 1
        // 3 个组件各一个 useTranslation() 调用（import 中不带 ()，不计入）
        assertEquals(
            "Each component should have useTranslation, expected 3 calls, got $count",
            3, count
        )
    }

    // ============================================================
    // 5. JSX 中已有 $t() 跳过
    // ============================================================

    /**
     * 测试 JSX 中已有 $t() 的文本不应提取
     */
    fun testReactExistingTInJsxShouldSkip() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { useTranslation } from 'react-i18next';

            export default function App() {
                const { t: ${'$'}t } = useTranslation();
                return <div>{${'$'}t('你好')}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertFalse(
            "extractedStrings should not contain '你好' for existing ${'$'}t(), got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好")
        )
        assertTrue(
            "existingStrings should contain '你好', got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("你好")
        )
    }

    // ============================================================
    // 6. JSX 注释跳过
    // ============================================================

    /**
     * 测试 JSX 注释中的中文应跳过
     */
    fun testReactJsxCommentShouldSkip() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return (
                    <div>
                        {/* 这是 JSX 注释，不应提取 */}
                        <span>真实文本</span>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        assertTrue(processor.extractedStrings.containsValue("真实文本"))
        assertFalse(processor.extractedStrings.containsValue("这是 JSX 注释，不应提取"))
    }

    // ============================================================
    // 7. 通过 package.json 依赖判断（非 .tsx 后缀文件）
    // ============================================================

    /**
     * 测试 React 项目中普通 .ts 文件的模板字面量插值应使用 React 的双括号格式 {{key}}
     * 因为 package.json 中包含 react 依赖，即使不是 .tsx 后缀也应判定为 React
     */
    fun testReactProjectTsFileUsesDoubleBrace() {
        val file = myFixture.configureByText(
            "utils.ts",
            """
            const name = "World"
            const msg = `你好${'$'}{name}`
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 模板字面量变量插值应使用 React 的双括号格式 {{0}}
        assertTrue(
            "React 项目中 .ts 文件的模板字面量插值应使用双括号格式 {{0}}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{{0}}")
        )
        assertFalse(
            "React 项目中 .ts 文件的模板字面量插值不应使用单括号格式 {0}, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("你好{0}")
        )
    }

    /**
     * 测试 React 项目中 isReact 判断应为 true（即使文件是普通 .ts 后缀）
     */
    fun testReactProjectTsFileIsReactShouldBeTrue() {
        val file = myFixture.configureByText(
            "helper.ts",
            """
            export function formatMessage(name: string) {
                return `你好${'$'}{name}`
            }
            """.trimIndent()
        )

        val element = file.firstChild
        val isReact = com.pan.extractor.Util.isReact(element)

        assertTrue(
            "React 项目中的 .ts 文件 isReact 应为 true，因为 package.json 中包含 react 依赖",
            isReact
        )
    }
}
