package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.project.Util

import com.intellij.psi.PsiFile
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

    /**
     * §架构验证：用户选「react-intl」时，只需 ReactIntlStrategy 一个策略文件即可生效——
     * 检测（I18nFrameworkRegistry）→ 调用表达式（CallExpressionStrategy.buildCallExpression）
     * → 注入计划（buildImportPlan）全链路应生成 react-intl 调用形态，且不破坏默认 i18next。
     */
    fun testReactIntlLibraryGeneratesFormatMessage() {
        val settings = com.pan.extractor.ui.I18nSettings.getInstance()
        val original = settings.reactLibrary()
        try {
            settings.setReactLibrary(com.pan.extractor.ui.ReactLibrary.REACT_INTL)
            val file = myFixture.configureByText(
                "App.tsx",
                """
                import React from 'react';
                export default function App() {
                    return <div title="提示信息">你好</div>
                }
                """.trimIndent()
            )

            val processor = I18nProcessor(project, file)
            processor.collect()
            processor.runWithUndo()

            val resultText = file.text
            val compact = resultText.replace("\\s+".toRegex(), "")
            assertTrue(
                "react-intl 应注入 useIntl 并解构 formatMessage, got:\n$resultText",
                compact.contains("import{useIntl}from'react-intl'") &&
                    compact.contains("const{formatMessage}=useIntl()")
            )
            assertTrue(
                "react-intl 调用形态应为 formatMessage({ id: 'key' }), got:\n$resultText",
                compact.contains("formatMessage({id:")
            )
            assertFalse(
                "react-intl 不应出现 react-i18next 的 t('key') 形态, got:\n$resultText",
                compact.contains("const{t}=useTranslation()")
            )
        } finally {
            settings.setReactLibrary(original)
        }
    }

    /**
     * 与 VueI18nProcessorTest 一致：支持带路径（src/xxx.ts）文件名的配置。
     * configureByText 不接受含 "/" 文件名会抛 Invalid file name，因此先
     * addFileToProject 再 configureFromExistingVirtualFile。
     */
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

        assertEquals(1, processor.analyzer.extractedStrings.size)
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
            "extractedStrings should contain '提示信息', got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("提示信息")
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

        assertEquals(3, processor.analyzer.extractedStrings.size)
        assertTrue(processor.analyzer.extractedStrings.containsValue("标题"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("段落内容"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("更多文本"))
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
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertTrue(
            "Should contain useTranslation hook, got:\n$resultText",
            compact.contains("useTranslation()") && compact.contains("const{t}=useTranslation()")
        )
    }

    /**
     * BUG 回归：选 react-i18next 后，即使组件文件（如 app.tsx）没有任何待提取中文文案，
     * 也应对每个 React 组件 / hook 文件完成接线（注入 useTranslation），否则 app.tsx 不会变化。
     */
    fun testReactEmptyComponentFileStillInjectsUseTranslation() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import React from 'react';
            export default function App() {
                return <div><span>no-chinese-here</span></div>;
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertTrue(
            "空文案组件文件也应注入 useTranslation 完成接线, got:\n$resultText",
            compact.contains("import{useTranslation}from'react-i18next'") &&
                compact.contains("const{t}=useTranslation()")
        )
    }

    /**
     * BUG 回归（react-intl）：组件文件没有待提取文案时，仍应注入 useIntl + 解构 formatMessage
     * 完成接线，否则 app.tsx 不会变化。
     */
    fun testReactIntlEmptyComponentFileStillInjectsUseIntl() {
        val settings = com.pan.extractor.ui.I18nSettings.getInstance()
        val original = settings.reactLibrary()
        try {
            settings.setReactLibrary(com.pan.extractor.ui.ReactLibrary.REACT_INTL)
            val file = myFixture.configureByText(
                "App.tsx",
                """
                import React from 'react';
                export default function App() {
                    return <div><span>no-chinese-here</span></div>;
                }
                """.trimIndent()
            )

            val processor = I18nProcessor(project, file)
            processor.collect()
            processor.runWithUndo()

            val resultText = file.text
            val compact = resultText.replace("\\s+".toRegex(), "")
            assertTrue(
                "空文案组件文件也应注入 useIntl 完成接线, got:\n$resultText",
                compact.contains("import{useIntl}from'react-intl'") &&
                    compact.contains("const{formatMessage}=useIntl()")
            )
        } finally {
            settings.setReactLibrary(original)
        }
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
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertTrue(
            "Arrow function should contain useTranslation hook, got:\n$resultText",
            compact.contains("useTranslation()") && compact.contains("const{t}=useTranslation()")
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
        processor.runWithUndo()

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
        processor.runWithUndo()

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
        processor.runWithUndo()

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
        processor.runWithUndo()

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
        processor.runWithUndo()

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
            "extractedStrings should not contain '你好' for existing ${'$'}t(), got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("你好")
        )
        assertTrue(
            "existingStrings should contain '你好', got: ${processor.analyzer.existingStrings}",
            processor.analyzer.existingStrings.containsValue("你好")
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

        assertTrue(processor.analyzer.extractedStrings.containsValue("真实文本"))
        assertFalse(processor.analyzer.extractedStrings.containsValue("这是 JSX 注释，不应提取"))
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
            "React 项目中 .ts 文件的模板字面量插值应使用双括号格式 {{0}}, got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("你好{{0}}")
        )
        assertFalse(
            "React 项目中 .ts 文件的模板字面量插值不应使用单括号格式 {0}, got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("你好{0}")
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
        val isReact = com.pan.extractor.project.Util.isReact(element)

        assertTrue(
            "React 项目中的 .ts 文件 isReact 应为 true，因为 package.json 中包含 react 依赖",
            isReact
        )
    }

    // ============================================================
    // 8. i18n.t 全局调用支持（React i18next）
    // ============================================================

    /**
     * 测试 React 文件中使用 i18n.t 时，新提取的字符串应使用 i18n.t
     * 且不应注入 useTranslation hook
     */
    fun testReactI18nTGlobalDetection() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import i18n from './i18n'

            export default function App() {
                const handleClick = () => {
                    alert(i18n.t("已存在"))
                }
                return (
                    <div>
                        <h1>新标题</h1>
                        <button onClick={handleClick}>{i18n.t("按钮")}</button>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // "已存在" 和 "按钮" 应在 existingStrings 中
        assertTrue(
            "'已存在' 应在 existingStrings 中, got: ${processor.analyzer.existingStrings}",
            processor.analyzer.existingStrings.containsValue("已存在")
        )
        assertTrue(
            "'按钮' 应在 existingStrings 中, got: ${processor.analyzer.existingStrings}",
            processor.analyzer.existingStrings.containsValue("按钮")
        )
        // "新标题" 应被提取
        assertTrue(
            "'新标题' 应被提取, got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("新标题")
        )
    }

    /**
     * 测试 React 中 i18n.t 和 $t 共存
     */
    fun testReactI18nTCoexistWithUseTranslation() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { useTranslation } from 'react-i18next'
            import i18n from './i18n'

            export default function App() {
                const { t: ${'$'}t } = useTranslation()
                return (
                    <div>
                        <span>{${'$'}t("hook文本")}</span>
                        <span>{i18n.t("全局文本")}</span>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 两种形式都应识别为已翻译
        assertTrue(
            "'hook文本' (via \$t) 应在 existingStrings 中, got: ${processor.analyzer.existingStrings}",
            processor.analyzer.existingStrings.containsValue("hook文本")
        )
        assertTrue(
            "'全局文本' (via i18n.t) 应在 existingStrings 中, got: ${processor.analyzer.existingStrings}",
            processor.analyzer.existingStrings.containsValue("全局文本")
        )
        // 不应重复提取
        assertFalse(
            "'hook文本' 不应被重复提取, got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("hook文本")
        )
        assertFalse(
            "'全局文本' 不应被重复提取, got: ${processor.analyzer.extractedStrings}",
            processor.analyzer.extractedStrings.containsValue("全局文本")
        )
    }

    // ============================================================
    // 9. i18n.t 全局实例导入注入
    // ============================================================

    /**
     * 测试使用 i18n.t 但缺少 i18n 实例导入时，组件场景注入 useTranslation，
     * 老 i18n.t("已存在") 保留（React 不再回退 getI18n 改写为 $t）。
     */
    fun testReactI18nTInjectImportWhenMissing() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                const handleClick = () => {
                    alert(i18n.t("已存在"))
                }
                return (
                    <div>
                        <h1>新标题</h1>
                        <button onClick={handleClick}>按钮</button>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        // 组件场景不回退 getI18n
        assertFalse(
            "组件场景不应注入 import { getI18n } from 'react-i18next', got:\n$resultText",
            compact.contains("import{getI18n}from'react-i18next'")
        )
        // 组件场景不注入 const t = getI18n().t
        assertFalse(
            "组件场景不应注入 const t = getI18n().t, got:\n$resultText",
            compact.contains("constt=getI18n().t")
        )
        // 老 i18n.t("已存在") 保留（不改写）
        assertTrue(
            "老 i18n.t(\"已存在\") 应保留, got:\n$resultText",
            resultText.contains("i18n.t(")
        )
        // 不再硬编码旧的 import i18n from 'i18next'
        assertFalse(
            "不应再注入 import i18n from 'i18next', got:\n$resultText",
            resultText.contains("from 'i18next'") || resultText.contains("""from "i18next"""")
        )
        // 组件场景必须注入 useTranslation（不管顶部有没有全局导入）
        assertTrue(
            "组件场景应注入 useTranslation, got:\n$resultText",
            resultText.contains("useTranslation")
        )
    }

    // ============================================================
    // 9.1 新语义：locale 优先、失败回退 getI18n
    //     （locale 初始化文件导出了 i18n → import i18n from '@/locales'；
    //       未导出 i18n / 无初始化文件 → 回退 getI18n）
    // ============================================================

    /**
     * locale 初始化文件导出了 i18n，组件场景 + i18n.t 但缺导入 →
     * 组件场景注入 useTranslation，老 i18n.t 保留，不切 locale import、不回退 getI18n。
     */
    fun testReactI18nTWithLocaleExportUsesLocaleImport() {
        // locale 初始化文件：React 初始化 + 导出了 i18n
        myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import i18n from 'i18next'
            import { initReactI18next } from 'react-i18next'

            i18n.use(initReactI18next).init({
              resources: {},
              lng: 'zh',
            })

            export default i18n
            """.trimIndent()
        )

        val file = configureFile(
            "src/App.tsx",
            """
            export default function App() {
                return (
                    <div>
                        <h1>新标题</h1>
                        <button onClick={() => alert(i18n.t("已存在"))}>按钮</button>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        // 组件场景不切 locale import
        assertFalse(
            "组件场景不应注入 import i18n from '@/locales', got:\n$resultText",
            compact.contains("importi18nfrom'@/locales'")
        )
        // 不落回 getI18n
        assertFalse(
            "组件场景不应回退 getI18n, got:\n$resultText",
            compact.contains("getI18n")
        )
        // 老 i18n.t 保留（不改写）
        assertTrue(
            "老 i18n.t 应保留, got:\n$resultText",
            resultText.contains("i18n.t(")
        )
        // 组件场景注入 useTranslation
        assertTrue(
            "组件场景应注入 useTranslation, got:\n$resultText",
            resultText.contains("useTranslation")
        )
    }

    /**
     * locale 初始化文件导出了 i18n，纯工具 TS 文件（t 语义）→
     * 注入 `import i18n from '@/locales'` + `const t = i18n.t;`，不回退 getI18n。
     */
    fun testReactPureTsWithLocaleExportUsesLocaleImport() {
        myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import i18n from 'i18next'
            import { initReactI18next } from 'react-i18next'

            i18n.use(initReactI18next).init({
              resources: {},
              lng: 'zh',
            })

            export default i18n
            """.trimIndent()
        )

        val file = configureFile(
            "src/utils/format.ts",
            """
            export function formatTip(type: string) {
                const label = "提示"
                return type + ": " + label
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertTrue(
            "纯工具 TS 应注入 import i18n from '@/locales', got:\n$resultText",
            compact.contains("importi18nfrom'@/locales'")
        )
        assertTrue(
            "纯工具 TS 应追加 const t = i18n.t（用 locale i18n，而非 getI18n）, got:\n$resultText",
            compact.contains("constt=i18n.t")
        )
        assertFalse(
            "locale 可用时纯工具 TS 不应回退 getI18n, got:\n$resultText",
            compact.contains("getI18n")
        )
    }

    /**
     * locale 初始化文件**存在但未导出 i18n** → 组件场景仍只注入 useTranslation，
     * 老 i18n.t 保留，不回退 getI18n。
     */
    fun testReactI18nTWithLocaleInitNoExportFallsBackToGetI18n() {
        // locale 初始化文件：React 初始化了，但**没有导出 i18n**
        myFixture.addFileToProject(
            "src/i18n/index.ts",
            """
            import i18n from 'i18next'
            import { initReactI18next } from 'react-i18next'

            i18n.use(initReactI18next).init({
              resources: {},
              lng: 'zh',
            })
            """.trimIndent()
        )

        val file = configureFile(
            "src/App.tsx",
            """
            export default function App() {
                return (
                    <div>
                        <h1>新标题</h1>
                        <button onClick={() => alert(i18n.t("已存在"))}>按钮</button>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        // 初始化文件未导出 i18n → 不切 locale
        assertFalse(
            "未导出 i18n 的初始化文件不应被用作 locale 导入, got:\n$resultText",
            compact.contains("@/i18n") || compact.contains("@/locale") || compact.contains("@/locales")
        )
        // 组件场景不回退 getI18n
        assertFalse(
            "组件场景不应回退 import { getI18n } from 'react-i18next', got:\n$resultText",
            compact.contains("import{getI18n}from'react-i18next'")
        )
        assertFalse(
            "组件场景不应追加 const t = getI18n().t, got:\n$resultText",
            compact.contains("constt=getI18n().t")
        )
        // 老 i18n.t("已存在") 保留（不改写）
        assertTrue(
            "老 i18n.t(\"已存在\") 应保留, got:\n$resultText",
            resultText.contains("i18n.t(")
        )
        // 组件场景必须注入 useTranslation（不管顶部有没有全局导入）
        assertTrue(
            "组件场景应注入 useTranslation, got:\n$resultText",
            resultText.contains("useTranslation")
        )
    }

    /**
     * 测试已有 i18n 默认导入时不重复注入
     */
    fun testReactI18nTNotDuplicateDefaultImport() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import i18n from './i18n'

            export default function App() {
                const handleClick = () => {
                    alert(i18n.t("已存在"))
                }
                return (
                    <div>
                        <h1>新标题</h1>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        assertFalse(
            "已有默认导入时不应再注入, got:\n$resultText",
            resultText.contains("from 'i18next'")
        )
        assertTrue(
            "原有 import i18n from './i18n' 应保留, got:\n$resultText",
            resultText.contains("import i18n from './i18n'")
        )
    }

    /**
     * 测试已有 i18n 命名导入时不重复注入
     */
    fun testReactI18nTNotDuplicateNamedImport() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { i18n } from './i18n'

            export default function App() {
                return <h1>{i18n.t("标题")}</h1>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        assertFalse(
            "已有命名导入时不应再注入默认导入, got:\n$resultText",
            resultText.contains("from 'i18next'")
        )
        assertTrue(
            "原有 import { i18n } from './i18n' 应保留, got:\n$resultText",
            resultText.contains("import { i18n } from './i18n'")
        )
    }

    /**
     * 测试已有 namespace 导入时不重复注入
     */
    fun testReactI18nTNotDuplicateNamespaceImport() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import * as i18n from './i18n'

            export default function App() {
                return <h1>{i18n.t("标题")}</h1>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        assertFalse(
            "已有 namespace 导入时不应再注入, got:\n$resultText",
            resultText.contains("from 'i18next'")
        )
    }

    // ============================================================
    // 10. Bug 1: 无组件/无 Hook 的 React TS 文件不应注入 useTranslation
    // ============================================================

    /**
     * 纯工具 TS 文件（既没有函数组件，也没有 use 开头自定义 hook），
     * 虽然属于 React 项目、文件中有硬编码中文，但不得注入 `import { useTranslation }`。
     * 否则会违反 Hooks 规则：Hook 只能在组件/自定义 Hook 里调用。
     *
     * 【用户指定新规则】：React 纯工具文件统一用短 t。
     *   顶部注入两行（来自 react-i18next 官方 getI18n API）：
     *       import { getI18n } from 'react-i18next';
     *       const t = getI18n().t;
     *   替换结果仍是短写法 t('key')，与 React Hook / 组件内部完全一致，
     *   **不再**写冗长 i18n.t('key') 调用。
     */
    fun testReactTsNoComponentNoHookShouldNotInjectUseTranslation() {
        val file = configureFile(
            "src/utils/format.ts",
            """
            // 普通工具函数，不返回 JSX，也不是 use 开头 hook
            export function formatNumber(n: number) {
                const label = "总共"
                return label + ": " + n
            }

            export const MSG = {
                title: "提示",
                description: "说明文本"
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        assertFalse(
            "纯工具 TS 文件不应注入 useTranslation import, got:\n$resultText",
            resultText.contains("useTranslation")
        )
        // ★ 用户新规则：react-i18next 关键字仍然出现在顶部（因为从它 import { getI18n }）
        assertTrue(
            "纯工具 TS 文件必须从 react-i18next import getI18n, got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "").let {
                it.contains("import{getI18n}from'react-i18next'")
            }
        )
        // ★ 不能再出现旧导入 i18n from i18next
        assertFalse(
            "纯工具 TS 文件不应再写旧的 import i18n from 'i18next', got:\n$resultText",
            resultText.contains("from 'i18next'") || resultText.contains("""from "i18next"""")
        )
        // ★ 替换结果必须是短写法 t('总共') 等，**不**是 i18n.t(...)
        assertTrue(
            "替换必须是短写法 t('总共') / t('提示') / t('说明文本'), got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "").contains("t('")
        )
        assertFalse(
            "不应再出现冗长 i18n.t(...) 调用, got:\n$resultText",
            resultText.contains("i18n.t(")
        )
        // ★ 必须追加 const t = getI18n().t;
        assertTrue(
            "必须追加 const t = getI18n().t; 全局别名, got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "").contains("constt=getI18n().t")
        )

        // —— 连跑两遍不重复注入（问题 4 React 版本回归）——
        val processor2 = I18nProcessor(project, file)
        processor2.collect()
        processor2.runWithUndo()
        val textAfterTwice = file.text.replace("\\s+".toRegex(), "")
        val getI18nCnt = textAfterTwice.split("import{getI18n}from'react-i18next'").size - 1
        val constCnt = textAfterTwice.split("constt=getI18n().t").size - 1
        assertEquals(
            "React getI18n import 重复出现 $getI18nCnt 次（expect 1）, txt:\n$textAfterTwice",
            1, getI18nCnt
        )
        assertEquals(
            "React const t 别名重复出现 $constCnt 次（expect 1）, txt:\n$textAfterTwice",
            1, constCnt
        )
    }

    /**
     * 即便 TS 文件里有"自定义 Hook"（use 开头），也允许 useTranslation 注入。
     * 这是 Bug 1 的反例：只有既没组件也没 hook 时才跳过。
     */
    fun testReactTsWithCustomHookShouldStillInjectUseTranslation() {
        val file = configureFile(
            "src/hooks/useAuth.ts",
            """
            export function useAuth() {
                const hint = "登录成功"
                return { hint }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        assertTrue(
            "自定义 hook TS 文件应注入 react-i18next import, got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "")
                .contains("import{useTranslation}from'react-i18next'")
        )
        assertTrue(
            "自定义 hook 体内应注入 useTranslation() 调用, got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "")
                .contains("const{t}=useTranslation()")
        )
    }

    // ============================================================
    // 10.1 Bug 1+. 用户报告"问题 1：没有中文的文件也导入了全局导入"
    // ============================================================

    /**
     * 纯工具 TS 文件，**完全没有中文**，也没有任何 t/i18n.t/$t 调用 →
     * 绝不应该注入任何全局 i18n 导入（哪怕它同时满足「React 项目 + 无组件 + 无 Hook」的预判条件）。
     *
     * 前一版 bug：needInjectReactGlobalDollarT 被单独 OR 进 needGlobalI18nImport，
     * 导致预判命中时无论文件里是否有中文，顶部都会塞进两行导入。
     */
    fun testReactEmptyToolTsFileNoChineseShouldNotInjectAnything() {
        val file = configureFile(
            "src/utils/number.ts",
            """
            // 完全没有中文，也没有 i18n 调用的普通工具函数
            export function formatNumber(n: number): string {
                return Intl.NumberFormat("en-US").format(n)
            }

            export const MATH = {
                PI: 3.14159,
                E: 2.71828
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertEquals(
            "无中文 React 纯工具 TS 文件：extractedStrings 应为空, got: ${processor.analyzer.extractedStrings}",
            0, processor.analyzer.extractedStrings.size
        )
        assertFalse(
            "无中文文件不应出现任何 i18n 全局导入（import getI18n / i18next 都不行）, got:\n$resultText",
            compact.contains("react-i18next") || compact.contains("from'i18next'")
        )
        assertFalse(
            "无中文文件不应出现 const \$t 别名定义, got:\n$resultText",
            compact.contains("const\$t=getI18n().t") || compact.contains("const\$t=i18n.global.t")
        )
        assertFalse(
            "无中文文件不应出现 useTranslation, got:\n$resultText",
            compact.contains("useTranslation")
        )
    }

    /**
     * （Vue 对称场景见 VueI18nProcessorTest.testVueEmptyToolTsFileNoChineseShouldNotInjectAnything）
     */

    // ============================================================
    // 10.2 Bug 2+. 用户报告"问题 2：有的地方只导入了 const $t = getI18n().t"
    //      （没有对应 import { getI18n } from 'react-i18next' → 运行时 getI18n is not defined）
    // ============================================================

    /**
     * 【典型触发路径】
     *   1) 文件顶部已存在**老模式**的 `import i18n from 'i18next'`（可能是历史遗留）
     *   2) 但这个文件同时属于"React 项目 + 无组件 + 无 Hook" → 预判 injectReactGlobalDollarT=true
     *   3) 文件里有新的硬编码中文（非已有 i18n.t 调用），所以 extractedStrings 非空，需要走新模式
     *
     * 老代码的 bug：hasI18nInstanceImported 把「老 i18n from i18next」也算成导入已存在，
     * importAlreadySatisfied=true → importText=null，但是 dollarTText 仍然追加
     * `const $t = getI18n().t` → **只出现 const，不出现 getI18n import**，运行时报
     * ReferenceError: getI18n is not defined。
     *
     * 修复后：React injectReactGlobalDollarT=true 模式必须「严格存在 getI18n 命名导入」
     * 才叫 requiredImportAlreadyPresent，老 i18n 导入顶用不上；
     * importText 是否生成只看 requiredImportAlreadyPresent，不再跨模式短路。
     */
    fun testReactOldI18nImportedButNeedGetI18nShouldStillInjectBoth() {
        val file = configureFile(
            "src/utils/legacy.ts",
            """
            // 历史遗留：已经 import 老模式的 i18n from i18next（但还没写任何调用）
            import i18n from 'i18next'

            export function formatTip(type: string) {
                // 有硬编码中文：需要新提取 → 命中 injectReactGlobalDollarT 新模式
                const label = "提示"
                return type + ": " + label
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        // ★ 关键断言 1：老 i18n from i18next 必须保留（不破坏历史）
        assertTrue(
            "老 import i18n from 'i18next' 必须保留, got:\n$resultText",
            compact.contains("importi18nfrom'i18next'")
        )
        // ★ 关键断言 2：新模式必须**额外**追加 import { getI18n } from 'react-i18next'
        assertTrue(
            "新模式必须额外追加 import { getI18n } from 'react-i18next'（不能因老 i18n 已导入就跳过）, got:\n$resultText",
            compact.contains("import{getI18n}from'react-i18next'")
        )
        // ★ 关键断言 3：还要追加 const t = getI18n().t
        assertTrue(
            "还要追加 const t = getI18n().t 全局别名, got:\n$resultText",
            compact.contains("constt=getI18n().t")
        )
        // ★ 关键断言 4：替换是短 t（不是老 i18n.t / $t）
        assertTrue(
            "替换应该是短 t('提示'), got:\n$resultText",
            resultText.contains("t('提示')")
        )
        assertFalse(
            "替换不应再用老 i18n.t('提示'), got:\n$resultText",
            resultText.contains("i18n.t(")
        )
    }

    // ============================================================
    // 11. Bug 2: 翻译资源文件（语言包）跳过提取与注入
    // ============================================================

    /**
     * 文件基名本身就是 locale code（如 en-US.ts、zh_CN.ts），应判定为翻译资源文件，
     * 不提取中文，也不注入任何 import。
     */
    fun testTranslationResourceByLocaleNameShouldSkip() {
        val file = configureFile(
            "src/zh-CN.ts",
            """
            export default {
                common: {
                    confirm: "确定",
                    cancel: "取消"
                }
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        assertEquals(
            "locale 命名的文件不应提取任何字符串, got: ${processor.analyzer.extractedStrings}",
            0,
            processor.analyzer.extractedStrings.size
        )
        assertEquals(
            "locale 命名的文件不应读取 existingStrings, got: ${processor.analyzer.existingStrings}",
            0,
            processor.analyzer.existingStrings.size
        )
        val resultText = file.text
        assertFalse(
            "locale 文件不应注入任何 import, got:\n$resultText",
            resultText.contains("import { useTranslation }") ||
                resultText.contains("import i18n from")
        )
    }

    /**
     * 文件位于 locales / i18n 目录下，即便名不是 locale code，也应按目录判定跳过
     * （典型：src/locales/index.ts 是语言包聚合文件）
     */
    fun testTranslationResourceByDirectoryShouldSkip() {
        val file = configureFile(
            "src/locales/index.ts",
            """
            import zh from './zh-CN'
            import en from './en-US'

            export const resources = {
                zh: { translation: zh },
                en: { translation: en }
            }

            const label = "中文标签"
            export { label }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        assertEquals(
            "locales/ 目录下的文件不应提取字符串, got: ${processor.analyzer.extractedStrings}",
            0,
            processor.analyzer.extractedStrings.size
        )
        val resultText = file.text
        assertFalse(
            "locales/ 目录下的文件不应被注入 import/hook, got:\n$resultText",
            resultText.contains("useTranslation") || resultText.contains("i18next")
        )
    }

    // ============================================================
    // 12. 线程安全：确保 processor.collect() 包 runReadAction 后在非 EDT 线程
    //     不抛 "Read access is allowed from inside read-action only"。
    //     （BasePlatformTestCase 默认读锁持有，但我们显式模拟真实 Action 调用方的习惯，
    //     通过 Application.executeOnPooledThread 在未持有读锁的线程调用 collect。）
    // ============================================================

    /**
     * 这个用例主要防止 PR 审查中反馈的 ReadAccess 违规：
     *    RuntimeExceptionWithAttachments: Read access is allowed from inside read-action only
     *    Current thread: ApplicationImpl pooled thread N
     * 真实环境中 I18nExtractorAction / AllI18nExtractorAction 会在 pooled thread
     * (ProgressManager / ActionUpdateThread.BGT) 下调用 PsiManager.findFile + collect()。
     * 这里直接显式在一个不持有读锁的线程上 collect，确保我们的 Action 层加了 runReadAction
     * 同时 Processor 本身即便被误用（外部没加读锁）也不会出现"替换 lambda 生成的代码
     * 引用错误变量"的副作用——至少 collect 阶段要能跑完。
     */
    fun testCollectOnReadActionThreadDoesNotThrow() {
        val file = configureFile(
            "src/utils/strings.ts",
            """
            export const OK = "成功"
            export const CANCEL = "取消"
            """.trimIndent()
        )

        // 显式要求持有读锁跑 collect（模拟 Action 层已经加了 runReadAction）
        val (existingSize, extractedSize) = com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .runReadAction<Pair<Int, Int>> {
                val processor = I18nProcessor(project, file)
                processor.collect()
                processor.analyzer.existingStrings.size to processor.analyzer.extractedStrings.size
            }
        assertEquals("existingStrings 应为空", 0, existingSize)
        assertEquals("extractedStrings 应包含两个中文", 2, extractedSize)
    }

    // ============================================================
    // 问题 3：已写在 t()/i18n.t() 内的中文没被提取到 existingStrings
    // ============================================================

    /**
     * 【用户反馈 Bug】React 中已翻译的 t() 调用（如 t('你好hello') / t('你好hello2')）
     * 必须被识别进 existingStrings，从而在最终 JSON 中被“复用”，否则 JSON 会是空的。
     * 该场景同时验证：中英混合 + 数字结尾的已翻译 key 都能被收录，且不会重复提取。
     */
    fun testReactExistingTChineseEnglishDigitCollected() {
        val file = configureFile(
            "src/mix.tsx",
            """
            import { useTranslation } from 'react-i18next';

            function App() {
                const { t } = useTranslation();
                return (
                    <div>
                        <span>{t('你好hello')}</span>
                        <span>{t('你好hello2')}</span>
                    </div>
                );
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val values = processor.analyzer.existingStrings.values.toSet()
        assertTrue(
            "已翻译的 t('你好hello') / t('你好hello2') 必须进 existingStrings, got=$values",
            values.containsAll(setOf("你好hello", "你好hello2"))
        )
        // 已翻译调用不应被当作新文本再次提取
        assertTrue(
            "已翻译调用不应进入 extractedStrings, got=${processor.analyzer.extractedStrings.values}",
            processor.analyzer.extractedStrings.values.none { it == "你好hello" || it == "你好hello2" }
        )
    }

    /**
     * 问题 3（React）：文件已经写了 `i18n.t('删除')` 或 `t('新增')` 这类调用，
     * 其参数字符串的中文也必须进入 existingStrings（最终对话框里出现，写回语言包）。
     * 之前只识别了简单引用名 $t/t，漏掉了链式 i18n.t / i18n.global.t 分支。
     */
    fun testReactExistingI18nTCallArgsCollected() {
        val file = configureFile(
            "src/existingMix.ts",
            """
            import i18n from 'i18next';
            import { useTranslation } from 'react-i18next';

            function App() {
                const { t } = useTranslation();
                return {
                    a: t('成功'),
                    b: i18n.t('取消'),
                };
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val values = processor.analyzer.existingStrings.values.toSet()
        val expected = setOf("成功", "取消")
        assertTrue(
            "React: t() / i18n.t() 内的中文必须进 existingStrings, \nexpect=$expected\ngot=$values",
            values.containsAll(expected)
        )
    }

    // ============================================================
    // 矩阵补全 (React 版)：5 条
    // ============================================================

    /**
     * 【React 纯 TS · 混合场景】：
     *   - 顶部已老 `import i18n from 'i18next'` + 文件里已写 `i18n.t('老调用中文')`
     *   - 同时还有**新硬编码中文**需要提取（比如 `新提示`）
     *
     * 预期：
     *   ① existingStrings 收录「老调用中文」（问题 3 语义）
     *   ② extractedStrings 收录「新提示」≥ 1 个
     *   ③ 因为有新提取 → 命中 React 新模式 getI18n：
     *        - 老 import i18n from i18next 保留（不破坏）
     *        - 额外追加 import { getI18n } from 'react-i18next'
     *        - 追加 const t = getI18n().t
     *   ④ 新中文替换是短 t('新提示')；老 i18n.t('老调用中文') 保留
     */
    fun testReactPureTsMixExistingI18nTAndNewChineseInjectsBothImports() {
        val file = configureFile(
            "src/utils/legacy-mix.ts",
            """
            import i18n from 'i18next'

            export function mixLabel(type: string): string {
                // 已写老 i18n.t 调用
                const base = i18n.t('老调用中文')
                // 新硬编码中文
                const suffix = "新提示"
                return base + " | " + suffix
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "existingStrings 应收录「老调用中文」, got=${processor.analyzer.existingStrings.values}",
            processor.analyzer.existingStrings.values.contains("老调用中文")
        )
        assertTrue(
            "应该有 ≥ 1 个新提取（至少包含「新提示」）, got size=${processor.analyzer.extractedStrings.size}",
            processor.analyzer.extractedStrings.size >= 1
        )
        processor.runWithUndo()
        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        // ① 老 import 保留
        assertTrue(
            "老 import i18n from 'i18next' 应保留, got:\n$resultText",
            compact.contains("importi18nfrom'i18next'")
        )
        // ② 新模式必须额外追加 getI18n import
        assertTrue(
            "新模式必须追加 import { getI18n } from 'react-i18next', got:\n$resultText",
            compact.contains("import{getI18n}from'react-i18next'")
        )
        // ③ 追加 const t = getI18n().t
        assertTrue(
            "新模式必须追加 const t = getI18n().t, got:\n$resultText",
            compact.contains("constt=getI18n().t")
        )
        // ④ 新中文替换是短 t
        assertTrue(
            "新中文「新提示」应替换为 t('新提示'), got:\n$resultText",
            resultText.contains("t('新提示')")
        )
        // ⑤ 老 i18n.t('老调用中文') 保留
        assertTrue(
            "老 i18n.t('老调用中文') 仍保留, got:\n$resultText",
            resultText.contains("i18n.t(")
        )
    }

    /**
     * 【React 纯 TS · 去重场景】：
     *   顶部已经是完整的 `import { getI18n } from 'react-i18next'` + `const $t = getI18n().t`
     *   → 再跑 1 次 processor 不应重复追加任何 import / const。
     */
    fun testReactPureTsGetI18nAndConstAlreadyExistsNoDuplicate() {
        val file = configureFile(
            "src/utils/brand-new.ts",
            """
            import { getI18n } from 'react-i18next'
            const ${'$'}t = getI18n().t

            export function brandTip() {
                // 新硬编码中文，要走已有别名
                return "品牌好"
            }
            """.trimIndent()
        )
        val p1 = I18nProcessor(project, file)
        p1.collect()
        assertEquals("应提取 1 个新中文（品牌好）, got=${p1.analyzer.extractedStrings.size}", 1, p1.analyzer.extractedStrings.size)
        p1.runWithUndo()
        // 再跑一遍：模拟用户第二次点 Extract
        val p2 = I18nProcessor(project, file)
        p2.collect()
        p2.runWithUndo()

        val txt = file.text.replace("\\s+".toRegex(), "")
        val getI18nImportCnt = txt.split("import{getI18n}from'react-i18next'").size - 1
        val constCnt = txt.split("const\$t=getI18n().t").size - 1
        assertEquals(
            "import { getI18n } from 'react-i18next' 重复了 $getI18nImportCnt 次（expect 1）, txt:\n$txt",
            1, getI18nImportCnt
        )
        assertEquals(
            "const \$t = getI18n().t 重复了 $constCnt 次（expect 1）, txt:\n$txt",
            1, constCnt
        )
        // 新提取替换仍为短 t
        assertTrue(
            "「品牌好」应替换为 t('品牌好'), got:\n${file.text}",
            file.text.contains("t('品牌好')")
        )
    }

    /**
     * 【React useTranslation · 重复执行计数升级】
     *   已有 testReactExistingUseTranslationNotDuplicated 只断言「不要出现重复字符串」，
     *   这里补上精确计数：import / 解构 分别只能出现 1 次。
     */
    fun testReactUseTranslationReRunCountExactlyOnce() {
        val file = configureFile(
            "src/App.tsx",
            """
            export function Hello() {
                return <h1>欢迎你</h1>
            }
            """.trimIndent()
        )
        // 连续跑 2 遍
        I18nProcessor(project, file).let { it.collect(); it.runWithUndo() }
        I18nProcessor(project, file).let { it.collect(); it.runWithUndo() }

        val txt = file.text.replace("\\s+".toRegex(), "")
        val importCnt = txt.split("import{useTranslation}from'react-i18next'").size - 1
        val constCnt = txt.split("const{t}=useTranslation()").size - 1
        assertEquals(
            "useTranslation import 重复了 $importCnt 次（expect 1）, txt:\n$txt",
            1, importCnt
        )
        assertEquals(
            "const { t } = useTranslation() 重复了 $constCnt 次（expect 1）, txt:\n$txt",
            1, constCnt
        )
    }

    /**
     * §C2：React Hook 注入后 `t` 解构函数行为的完整生命周期。
     * 第一遍注入 useTranslation / 解构 t 并把 JSX 文案改写为 `{t('中文')}`；
     * 第二遍（同一 PsiFile reparse）重新 collect 应识别为已翻译（extractedStrings 为空）、
     * 不再重复注入 import/const；再 apply 一次源码与第一遍完全一致（幂等）。
     */
    fun testReactUseTranslationHookLifecycleIdempotent() {
        val file = configureFile(
            "src/HookLifecycle.tsx",
            """
            export function Hello() {
                return <h1>欢迎回来</h1>
            }
            """.trimIndent()
        )

        // ── 第一遍：注入 hook + 改写 JSX 文案 ──
        I18nProcessor(project, file).let { it.collect(); it.runWithUndo() }
        val first = file.text
        val compact1 = first.replace("\\s+".toRegex(), "")
        assertTrue(
            "应注入 useTranslation 并解构 t，got:\n$first",
            compact1.contains("import{useTranslation}from'react-i18next'") &&
                compact1.contains("const{t}=useTranslation()")
        )
        // JSX 文本节点按统一反引号模板形态改写：`{ t(`欢迎回来`) }`
        val jsxCall = "{t(`欢迎回来`)}"
        assertTrue(
            "JSX 文案应改写为 { t(`欢迎回来`) }（反引号模板），got:\n$first",
            compact1.contains(jsxCall)
        )

        // ── 第二遍：重新 collect——已翻译不再提取、不再重复注入 ──
        val p2 = I18nProcessor(project, file)
        p2.collect()
        assertTrue(
            "第二遍不应再提取 '欢迎回来'，got: ${p2.analyzer.extractedStrings}",
            p2.analyzer.extractedStrings.isEmpty()
        )
        p2.runWithUndo()
        val second = file.text
        val compact2 = second.replace("\\s+".toRegex(), "")
        assertEquals(
            "二次 apply 后源码应与第一次 apply 后一致（幂等），\n一次:\n$first\n\n二次:\n$second",
            first, second
        )
        assertEquals(
            "JSX 文案的 { t(`欢迎回来`) } 应恰好出现一次，got:\n$second",
            1, compact2.split(jsxCall).size - 1
        )
    }

    /**
     * 【React 纯 TS · 部分残缺场景】：
     *   顶部用户手删了 const 别名，只剩 `import { getI18n } from 'react-i18next'`；
     *   文件里既有新硬编码中文要提取。
     *
     * 预期：只补 `const t = getI18n().t`，**不能**再重复追加 import { getI18n }。
     */
    fun testReactPureTsGetI18nImportedButConstMissingOnlyInjectsConst() {
        val file = configureFile(
            "src/utils/partial.ts",
            """
            // 用户写了 getI18n import 但手删了 const 别名（只剩一半）
            import { getI18n } from 'react-i18next'

            export function showTip() {
                // 新硬编码中文：需要提取 → 应该发现 const 缺失，只补 const，不再补 import
                return "残缺提示"
            }
            """.trimIndent()
        )
        val p = I18nProcessor(project, file)
        p.collect()
        assertEquals("应提取 1 个新中文（残缺提示）, got=${p.analyzer.extractedStrings.size}", 1, p.analyzer.extractedStrings.size)
        p.runWithUndo()

        val txt = file.text
        val compact = txt.replace("\\s+".toRegex(), "")
        val getI18nImportCnt = compact.split("import{getI18n}from'react-i18next'").size - 1
        assertEquals(
            "import { getI18n } from 'react-i18next' 已经有了，不应重复追加；实际出现 $getI18nImportCnt 次, txt:\n$compact",
            1, getI18nImportCnt
        )
        val constCnt = compact.split("constt=getI18n().t").size - 1
        assertEquals(
            "必须补齐 const t = getI18n().t 别名（expect 1）, 实际 $constCnt 次, txt:\n$compact",
            1, constCnt
        )
        assertTrue(
            "「残缺提示」替换成短 t('残缺提示'), got:\n$txt",
            txt.contains("t('残缺提示')")
        )
    }

    /**
     * 【React 问题 3 扩展：复数函数 tc / ${'$'}tc / i18n.tc】
     *  已写调用 `${'$'}tc('项目', 2)` / `tc('项目', 2)` / `i18n.tc('项目', 2)` 里的中文
     *  也必须进 existingStrings。
     */
    fun testReactTcAndDollarTcCallArgsCollectedToExistingStrings() {
        val file = configureFile(
            "src/tc-mix.tsx",
            """
            import { useTranslation } from 'react-i18next'
            import i18n from 'i18next'

            export function Report() {
                const { t, tc } = useTranslation()
                return (
                    <div>
                      {tc('记录', 3)}
                      {${'$'}tc('文件', 5)}
                      {i18n.tc('用户', 10)}
                    </div>
                )
            }
            """.trimIndent()
        )
        val p = I18nProcessor(project, file)
        p.collect()
        val expected = setOf("记录", "文件", "用户")
        val values = p.analyzer.existingStrings.values.toSet()
        assertTrue(
            "React tc/\$tc/i18n.tc 内中文必须进 existingStrings,\nexpect=$expected\ngot=$values",
            values.containsAll(expected)
        )
    }

    // ============================================================
    // React TSX 方向 1：属性（props）中的中文 + 显式 TypeScript 类型 props
    //           —— `<Button title="提示" />` 这种 `title="提示"` 场景之前只断言
    //              extracted 中有值，但没跑 execute 替换；这里补完整链路，
    //              并且额外覆盖显式类型 Props 的场景（`type Props = { title: string }`）
    // ============================================================

    fun testReactTsxPropStringChineseFullReplaceWithTypeProps() {
        val file = configureFile(
            "src/ConfirmButton.tsx",
            """
            import React from 'react'

            type Props = {
              size?: 'sm' | 'md' | 'lg'
              title: string
            }

            function ConfirmButton(props: Props) {
              return (
                <button
                  title="确认提示"
                  aria-label="确认操作"
                  data-ok-text="确定"
                >
                  {props.title}
                </button>
              )
            }

            export default function App() {
              return (
                <div>
                  <ConfirmButton title="确认" />
                  <ConfirmButton title="取消确认" />
                </div>
              )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        // title="确认提示" / aria-label="确认操作" / data-ok-text="确定"
        // 注意：<ConfirmButton title="确认" /> / title="取消确认" → 它们的值也是中文硬编码 prop
        assertTrue(
            "button 静态属性 title='确认提示' 应提取，got=${processor.analyzer.extractedStrings.values}",
            processor.analyzer.extractedStrings.containsValue("确认提示")
        )
        assertTrue(
            "aria-label='确认操作' 应提取",
            processor.analyzer.extractedStrings.containsValue("确认操作")
        )
        assertTrue(
            "data-ok-text='确定' 应提取",
            processor.analyzer.extractedStrings.containsValue("确定")
        )
        assertTrue(
            "ConfirmButton title='确认' / title='取消确认' 两个 JSX 字符串 props 都应提取",
            processor.analyzer.extractedStrings.containsValue("确认") && processor.analyzer.extractedStrings.containsValue("取消确认")
        )

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "替换后属性中应包含 t(...)（React 格式），got:\n$result",
            result.contains("t(")
        )
        // React 属性替换后应把 `title="确认提示"` 这种字符串 → `title={t('确认提示')}` / title={t("...")}
        assertFalse(
            "button title='确认提示' 静态字符串不应残留，got:\n$result",
            result.contains("title=\"确认提示\"") || result.contains("title='确认提示'")
        )
        assertFalse(
            "ConfirmButton title=\"确认\" 不应残留硬编码双引号，got:\n$result",
            result.contains("title=\"确认\"") || result.contains("title='确认'")
        )
    }

    // ============================================================
    // React TSX 方向 2：泛型组件 `<T extends ...>`
    //     场景： `<Select<string> label="选择国家" value={...} />`
    //     如果泛型语法没正确识别，会导致 JSX attribute 中文漏提 +
    //     children 里中文漏提。
    // ============================================================

    fun testReactTsxGenericComponentLabelAndChildrenChineseExtracts() {
        val file = configureFile(
            "src/GenericSelect.tsx",
            """
            import React from 'react'

            function App() {
              return (
                <div>
                  <select<string>
                    placeholder="请选择国家"
                    label="国家地区"
                  >
                    <option value="cn">中国</option>
                    <option value="us">美国</option>
                    <option value="jp">日本</option>
                  </select<string>>
                  <List<number> header="数字列表" footer="列表结束">
                    <div>第一项描述</div>
                    <div>第二项描述</div>
                  </List<number>>
                </div>
              )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "泛型 select 的 placeholder 请选择国家 应提取",
            processor.analyzer.extractedStrings.containsValue("请选择国家")
        )
        assertTrue(
            "泛型 select 的 label 国家地区 应提取",
            processor.analyzer.extractedStrings.containsValue("国家地区")
        )
        assertTrue("option 中文 中国 应提取", processor.analyzer.extractedStrings.containsValue("中国"))
        assertTrue("option 中文 美国 应提取", processor.analyzer.extractedStrings.containsValue("美国"))
        assertTrue("option 中文 日本 应提取", processor.analyzer.extractedStrings.containsValue("日本"))
        assertTrue("泛型 List header 数字列表 应提取", processor.analyzer.extractedStrings.containsValue("数字列表"))
        assertTrue("泛型 List footer 列表结束 应提取", processor.analyzer.extractedStrings.containsValue("列表结束"))
        assertTrue("List children 第一项描述 应提取", processor.analyzer.extractedStrings.containsValue("第一项描述"))
        assertTrue("List children 第二项描述 应提取", processor.analyzer.extractedStrings.containsValue("第二项描述"))
        assertEquals(9, processor.analyzer.extractedStrings.size)

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "泛型语法 `select<string>` 应保留（不要把它当字符串删了），got:\n$result",
            result.contains("select<string>") && result.contains("List<number>")
        )
        assertFalse(
            "placeholder=\"请选择国家\" 不应残留硬编码，got:\n$result",
            result.contains("placeholder=\"请选择国家\"")
        )
        assertFalse(
            "<option>中国</option> 不应残留硬编码中文文本，got:\n$result",
            result.contains(">中国<")
        )
    }

    // ============================================================
    // React TSX 方向 3：spread attrs 中使用的中文字符串 +
    //           as const 数组作为 JSX children（`{[ 'A','B'] as const}`）
    //           —— spread 在 TSX 中常用来传 object，object value 的中文也要抽
    // ============================================================

    fun testReactTsxSpreadObjectPlusAsConstArrayChildrenExtracts() {
        val file = configureFile(
            "src/Mix.tsx",
            """
            import React from 'react'

            const commonProps = {
              tip: "鼠标悬停提示",
              ariaLabel: "可点击控件",
            }

            function App() {
              const tabs = ["首页", "发现页", "我的"] as const
              return (
                <div>
                  <Button {...commonProps} label="主按钮" />
                  <ul>
                    {tabs}
                  </ul>
                </div>
              )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("commonProps.tip 鼠标悬停提示 应提取", processor.analyzer.extractedStrings.containsValue("鼠标悬停提示"))
        assertTrue("commonProps.ariaLabel 可点击控件 应提取", processor.analyzer.extractedStrings.containsValue("可点击控件"))
        assertTrue("tabs[0] 首页 应提取", processor.analyzer.extractedStrings.containsValue("首页"))
        assertTrue("tabs[1] 发现页 应提取", processor.analyzer.extractedStrings.containsValue("发现页"))
        assertTrue("tabs[2] 我的 应提取", processor.analyzer.extractedStrings.containsValue("我的"))
        assertTrue("Button label=\"主按钮\" 应提取", processor.analyzer.extractedStrings.containsValue("主按钮"))
        assertEquals(6, processor.analyzer.extractedStrings.size)

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "tabs 尾部 as const 应保留（不要把数组尾删了），got:\n$result",
            result.contains("as const")
        )
        assertTrue(
            "spread {...commonProps} 语法应保留，got:\n$result",
            result.contains("{...commonProps}")
        )
        assertFalse(
            "commonProps.tip 不应残留硬编码 \"鼠标悬停提示\"，got:\n$result",
            result.contains("tip: \"鼠标悬停提示\"")
        )
        // NOTE：如果替换形式是 t("首页") → result.contains("\"首页\"") 会 TRUE，所以判断是
        //       「如果命中字符串字面量，就必须紧邻包在 t( 调用里」，而不是字面量 0 出现。
        assertFalse(
            "tabs 数组中 '首页' 不应残留裸硬编码（应包进 \$t(\"首页\") 形式），got:\n$result",
            (result.contains("\"首页\"") && !result.contains("t(\"首页\"")) ||
                (result.contains("'首页'") && !result.contains("t('首页')"))
        )
        assertFalse(
            "tabs 数组中 '发现页' 不应残留裸硬编码，got:\n$result",
            (result.contains("\"发现页\"") && !result.contains("t(\"发现页\"")) ||
                (result.contains("'发现页'") && !result.contains("t('发现页')"))
        )
        assertFalse(
            "tabs 数组中 '我的' 不应残留裸硬编码，got:\n$result",
            (result.contains("\"我的\"") && !result.contains("t(\"我的\"")) ||
                (result.contains("'我的'") && !result.contains("t('我的')"))
        )
        assertFalse(
            "Button label=\"主按钮\" 不应残留裸硬编码，got:\n$result",
            (result.contains("\"主按钮\"") && !result.contains("t(\"主按钮\"")) ||
                (result.contains("'主按钮'") && !result.contains("t('主按钮')"))
        )
    }

    // ============================================================
    // React TSX 方向 4：纯 TS 工具文件（非组件）里用 declare namespace 声明类型
    //           + 实际运行时对象里的中文字符串——类型层不能扫、只扫 runtime 层
    // ============================================================

    fun testReactTsToolNamespaceDeclarationShouldNotAffectRuntimeChineseExtract() {
        val file = configureFile(
            "src/utils/error.ts",
            """
            import { getI18n } from 'react-i18next'

            declare global {
              namespace NodeJS {
                interface ProcessEnv {
                  readonly NODE_ENV: "development" | "production"
                }
              }
            }

            export const ERRORS = {
              E001: "用户名或密码错误",
              E002: "验证码已过期",
              E003: "用户未登录",
            } as const

            export function msgOf(code: keyof typeof ERRORS) {
              return ERRORS[code]
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(processor.analyzer.extractedStrings.containsValue("用户名或密码错误"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("验证码已过期"))
        assertTrue(processor.analyzer.extractedStrings.containsValue("用户未登录"))
        assertEquals(3, processor.analyzer.extractedStrings.size)

        processor.runWithUndo()
        val result = file.text
        assertTrue(
            "declare global / namespace NodeJS 声明应保留（不要删除 TS 类型层），got:\n$result",
            result.contains("declare global") && result.contains("namespace NodeJS")
        )
        assertTrue(
            "ERRORS 末尾 as const 应保留",
            result.contains("as const")
        )
        assertFalse(
            "ERRORS.E001 硬编码 \"用户名或密码错误\" 不应残留",
            result.contains("E001: \"用户名或密码错误\"")
        )
    }

    // ============================================================
    // P1（§26-React Intl）：react-intl 结构化形态黑盒 —— 不重复提取 / 不二次 $t 包装
    // ============================================================

    /** 切到 react-intl 并跑 collect + undo，返回改写后的文件文本。 */
    private fun runReactIntlExtract(text: String): Pair<String, com.pan.extractor.analyzer.I18nAnalyzer> {
        val settings = com.pan.extractor.ui.I18nSettings.getInstance()
        val original = settings.reactLibrary()
        try {
            settings.setReactLibrary(com.pan.extractor.ui.ReactLibrary.REACT_INTL)
            val file = configureFile("src/IntlComp.tsx", text)
            val processor = I18nProcessor(project, file)
            processor.collect()
            processor.runWithUndo()
            return file.text to processor.analyzer
        } finally {
            settings.setReactLibrary(original)
        }
    }

    /** B2：formatMessage({ id: '已翻译' }) 的 id 已属既有翻译，识别入 existingStrings 且不再二次包装。 */
    fun testReactIntlFormatMessageObjectIdIsAlreadyTranslated() {
        val (resultText, analyzer) = runReactIntlExtract(
            """
            import React from 'react';
            import { useIntl } from 'react-intl';
            export default function App() {
                const { formatMessage } = useIntl();
                return <div>{formatMessage({ id: '已翻译' })}</div>;
            }
            """.trimIndent()
        )
        // id 值识别为已有翻译 key
        assertTrue(
            "formatMessage 的 id 应进入 existingStrings",
            analyzer.existingStrings.containsKey("已翻译")
        )
        // 不应二次包一层 formatMessage（源访问保持原状）
        val c = resultText.replace("\\s+".toRegex(), "")
        assertTrue(
            "formatMessage({ id: '已翻译' }) 应保持原状，got:\n$resultText",
            c.contains("{formatMessage({id:'已翻译'})}")
        )
        assertFalse(
            "不应出现 formatMessage 嵌套 formatMessage 的双重包装，got:\n$resultText",
            c.contains("formatMessage({id:formatMessage")
        )
    }

    /** B1：defineMessages 包裹的消息描述符（defaultMessage）已被结构化，不再重复提取。 */
    fun testReactIntlDefineMessagesDefaultMessageIsStructured() {
        val (resultText, analyzer) = runReactIntlExtract(
            """
            import { defineMessages } from 'react-intl'
            const messages = defineMessages({
                greeting: { id: 'app.greeting', defaultMessage: '你好世界' }
            })
            """.trimIndent()
        )
        assertTrue(
            "defaultMessage 的值被结构化识别（不进 extractedStrings 待包装集合）",
            !analyzer.extractedStrings.values.contains("你好世界")
        )
        assertTrue(
            "defaultMessage: '你好世界' 应保持原状、不被二次包装，got:\n$resultText",
            resultText.contains("defaultMessage: '你好世界'")
        )
        assertFalse(
            "不应把默认文案再包成 formatMessage，got:\n$resultText",
            resultText.contains("formatMessage({ id: '你好世界' ")
        )
    }

    /** B3：<FormattedMessage defaultMessage="你好" /> 已是结构化消息，不重复提取。 */
    fun testReactIntlFormattedMessageDefaultMessageIsStructured() {
        val (resultText, _) = runReactIntlExtract(
            """
            import React from 'react';
            import { FormattedMessage } from 'react-intl';
            export default function Comp() {
                return <FormattedMessage id="app.hello" defaultMessage="你好" />;
            }
            """.trimIndent()
        )
        assertTrue(
            "FormattedMessage 的 defaultMessage 应保持原状，got:\n$resultText",
            resultText.contains("defaultMessage=\"你好\"")
        )
        assertFalse(
            "不应把 defaultMessage 再包成 formatMessage，got:\n$resultText",
            resultText.contains("formatMessage({ id: '你好' ")
        )
    }

    /** Negative：本地 `function formatMessage` 不得因名字被当作 react-intl 结构化资源而跳过提取。 */
    fun testReactIntlLocalFormatMessageShadowNotStructured() {
        val (_, analyzer) = runReactIntlExtract(
            """
            function formatMessage(options: any) {
                return options.id;
            }
            export function localFn() {
                return formatMessage({ id: '普通文本' });
            }
            """.trimIndent()
        )
        assertTrue(
            "本地 formatMessage 的 { id: '普通文本' } 是普通硬编码，应进 extractedStrings，got=${analyzer.extractedStrings}",
            analyzer.extractedStrings.values.contains("普通文本")
        )
        assertFalse(
            "本地 formatMessage 不得被当作 react-intl 结构化而进 existingStrings，got=${analyzer.existingStrings}",
            analyzer.existingStrings.containsKey("普通文本")
        )
    }

    /** Negative：本地 `function defineMessages` 同样不得被当作 react-intl 结构化资源而跳过提取。 */
    fun testReactIntlLocalDefineMessagesShadowNotStructured() {
        val (_, analyzer) = runReactIntlExtract(
            """
            function defineMessages(obj: any) {
                return obj;
            }
            const msgs = defineMessages({ greeting: { defaultMessage: '普通问候' } });
            """.trimIndent()
        )
        assertTrue(
            "本地 defineMessages 的 defaultMessage 是普通硬编码，应进 extractedStrings，got=${analyzer.extractedStrings}",
            analyzer.extractedStrings.values.contains("普通问候")
        )
        assertFalse(
            "本地 defineMessages 不得被当作 react-intl 结构化而进 existingStrings，got=${analyzer.existingStrings}",
            analyzer.existingStrings.containsKey("普通问候")
        )
    }
}
