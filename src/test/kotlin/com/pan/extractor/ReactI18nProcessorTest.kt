package com.pan.extractor

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
            "'已存在' 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("已存在")
        )
        assertTrue(
            "'按钮' 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("按钮")
        )
        // "新标题" 应被提取
        assertTrue(
            "'新标题' 应被提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("新标题")
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
            "'hook文本' (via \$t) 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("hook文本")
        )
        assertTrue(
            "'全局文本' (via i18n.t) 应在 existingStrings 中, got: ${processor.existingStrings}",
            processor.existingStrings.containsValue("全局文本")
        )
        // 不应重复提取
        assertFalse(
            "'hook文本' 不应被重复提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("hook文本")
        )
        assertFalse(
            "'全局文本' 不应被重复提取, got: ${processor.extractedStrings}",
            processor.extractedStrings.containsValue("全局文本")
        )
    }

    // ============================================================
    // 9. i18n.t 全局实例导入注入
    // ============================================================

    /**
     * 测试使用 i18n.t 但缺少 i18n 实例导入时，应自动注入默认导入。
     * React 默认注入默认导入：import i18n from 'i18next'
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
        processor.execute()

        val resultText = file.text
        // 应注入 i18n 实例的默认导入
        assertTrue(
            "应注入 import i18n from 'i18next', got:\n$resultText",
            resultText.contains("import i18n from 'i18next'")
        )
        // 不应注入 useTranslation（已使用全局 i18n）
        assertFalse(
            "不应注入 useTranslation, got:\n$resultText",
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
        processor.execute()

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
        processor.execute()

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
        processor.execute()

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
     * 相反，应当注入 i18next 全局实例（`import i18n from 'i18next'`），
     * 并把中文替换为 `i18n.t('key')` 调用（而非未定义的 `$t('key')`）。
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
        processor.execute()

        val resultText = file.text
        assertFalse(
            "纯工具 TS 文件不应注入 useTranslation import, got:\n$resultText",
            resultText.contains("useTranslation")
        )
        assertFalse(
            "纯工具 TS 文件不应注入 react-i18next import, got:\n$resultText",
            resultText.contains("react-i18next")
        )
        // PR #12 审查新增断言：普通函数有中文时，顶部必须导入全局 i18n from 'i18next'
        assertTrue(
            "纯工具 TS 文件有中文提取时，必须注入 import i18n from 'i18next', got:\n$resultText",
            resultText.contains("import i18n from ") && resultText.contains("i18next")
        )
        // PR #12 审查新增断言：替换必须使用 i18n.t('key')（而非未定义的 \$t('key')）
        assertTrue(
            "替换结果必须使用 i18n.t 调用，got:\n$resultText",
            resultText.contains("i18n.t(")
        )
        assertFalse(
            "纯工具 TS 文件不应出现未定义的 \$t() 调用，got:\n$resultText",
            resultText.contains("\$t(")
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
        processor.execute()

        val resultText = file.text
        assertTrue(
            "自定义 hook TS 文件应注入 react-i18next import, got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "")
                .contains("import{useTranslation}from'react-i18next'")
        )
        assertTrue(
            "自定义 hook 体内应注入 useTranslation() 调用, got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "")
                .contains("const{t:\$t}=useTranslation()")
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
        processor.execute()

        assertEquals(
            "locale 命名的文件不应提取任何字符串, got: ${processor.extractedStrings}",
            0,
            processor.extractedStrings.size
        )
        assertEquals(
            "locale 命名的文件不应读取 existingStrings, got: ${processor.existingStrings}",
            0,
            processor.existingStrings.size
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
        processor.execute()

        assertEquals(
            "locales/ 目录下的文件不应提取字符串, got: ${processor.extractedStrings}",
            0,
            processor.extractedStrings.size
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
                processor.existingStrings.size to processor.extractedStrings.size
            }
        assertEquals("existingStrings 应为空", 0, existingSize)
        assertEquals("extractedStrings 应包含两个中文", 2, extractedSize)
    }
}
