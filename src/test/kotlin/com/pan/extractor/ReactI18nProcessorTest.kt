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
     * 【用户指定新规则】：全部都用 $t 减少复杂度。
     *   顶部注入两行（来自 react-i18next 官方 getI18n API）：
     *       import { getI18n } from 'react-i18next';
     *       const $t = getI18n().t;
     *   替换结果仍是短写法 $t('key')，与 React Hook / Vue 内部完全一致，
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
        processor.execute()

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
        // ★ 替换结果必须是短写法 $t('总共') 等，**不**是 i18n.t(...)
        assertTrue(
            "替换必须是短写法 \$t('总共') / \$t('提示') / \$t('说明文本'), got:\n$resultText",
            resultText.contains("\$t(")
        )
        assertFalse(
            "不应再出现冗长 i18n.t(...) 调用, got:\n$resultText",
            resultText.contains("i18n.t(")
        )
        // ★ 必须追加 const $t = getI18n().t;
        assertTrue(
            "必须追加 const \$t = getI18n().t; 全局别名, got:\n$resultText",
            resultText.replace("\\s+".toRegex(), "").contains("const\$t=getI18n().t")
        )

        // —— 连跑两遍不重复注入（问题 4 React 版本回归）——
        val processor2 = I18nProcessor(project, file)
        processor2.collect()
        processor2.execute()
        val textAfterTwice = file.text.replace("\\s+".toRegex(), "")
        val getI18nCnt = textAfterTwice.split("import{getI18n}from'react-i18next'").size - 1
        val constCnt = textAfterTwice.split("const\$t=getI18n().t").size - 1
        assertEquals(
            "React getI18n import 重复出现 $getI18nCnt 次（expect 1）, txt:\n$textAfterTwice",
            1, getI18nCnt
        )
        assertEquals(
            "React const \$t 别名重复出现 $constCnt 次（expect 1）, txt:\n$textAfterTwice",
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
        processor.execute()

        val resultText = file.text
        val compact = resultText.replace("\\s+".toRegex(), "")
        assertEquals(
            "无中文 React 纯工具 TS 文件：extractedStrings 应为空, got: ${processor.extractedStrings}",
            0, processor.extractedStrings.size
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
        processor.execute()

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
        // ★ 关键断言 3：还要追加 const $t = getI18n().t
        assertTrue(
            "还要追加 const \$t = getI18n().t 全局别名, got:\n$resultText",
            compact.contains("const\$t=getI18n().t")
        )
        // ★ 关键断言 4：替换是短 $t（不是老 i18n.t）
        assertTrue(
            "替换应该是短 \$t('提示'), got:\n$resultText",
            resultText.contains("\$t('提示')")
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

    // ============================================================
    // 问题 3：已写在 t()/i18n.t() 内的中文没被提取到 existingStrings
    // ============================================================

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
        val values = processor.existingStrings.values.toSet()
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
     *        - 追加 const $t = getI18n().t
     *   ④ 新中文替换是短 $t('新提示')；老 i18n.t('老调用中文') 保留
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
            "existingStrings 应收录「老调用中文」, got=${processor.existingStrings.values}",
            processor.existingStrings.values.contains("老调用中文")
        )
        assertTrue(
            "应该有 ≥ 1 个新提取（至少包含「新提示」）, got size=${processor.extractedStrings.size}",
            processor.extractedStrings.size >= 1
        )
        processor.execute()
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
        // ③ 追加 const $t = getI18n().t
        assertTrue(
            "新模式必须追加 const \$t = getI18n().t, got:\n$resultText",
            compact.contains("const\$t=getI18n().t")
        )
        // ④ 新中文替换是短 $t
        assertTrue(
            "新中文「新提示」应替换为 \$t('新提示'), got:\n$resultText",
            resultText.contains("\$t('新提示')")
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
        assertEquals("应提取 1 个新中文（品牌好）, got=${p1.extractedStrings.size}", 1, p1.extractedStrings.size)
        p1.execute()
        // 再跑一遍：模拟用户第二次点 Extract
        val p2 = I18nProcessor(project, file)
        p2.collect()
        p2.execute()

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
        // 新提取替换仍为短 $t
        assertTrue(
            "「品牌好」应替换为 \$t('品牌好'), got:\n${file.text}",
            file.text.contains("\$t('品牌好')")
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
        I18nProcessor(project, file).let { it.collect(); it.execute() }
        I18nProcessor(project, file).let { it.collect(); it.execute() }

        val txt = file.text.replace("\\s+".toRegex(), "")
        val importCnt = txt.split("import{useTranslation}from'react-i18next'").size - 1
        val constCnt = txt.split("const{t:\$t}=useTranslation()").size - 1
        assertEquals(
            "useTranslation import 重复了 $importCnt 次（expect 1）, txt:\n$txt",
            1, importCnt
        )
        assertEquals(
            "const { t: \$t } = useTranslation() 重复了 $constCnt 次（expect 1）, txt:\n$txt",
            1, constCnt
        )
    }

    /**
     * 【React 纯 TS · 部分残缺场景】：
     *   顶部用户手删了 const 别名，只剩 `import { getI18n } from 'react-i18next'`；
     *   文件里既有新硬编码中文要提取。
     *
     * 预期：只补 `const $t = getI18n().t`，**不能**再重复追加 import { getI18n }。
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
        assertEquals("应提取 1 个新中文（残缺提示）, got=${p.extractedStrings.size}", 1, p.extractedStrings.size)
        p.execute()

        val txt = file.text
        val compact = txt.replace("\\s+".toRegex(), "")
        val getI18nImportCnt = compact.split("import{getI18n}from'react-i18next'").size - 1
        assertEquals(
            "import { getI18n } from 'react-i18next' 已经有了，不应重复追加；实际出现 $getI18nImportCnt 次, txt:\n$compact",
            1, getI18nImportCnt
        )
        val constCnt = compact.split("const\$t=getI18n().t").size - 1
        assertEquals(
            "必须补齐 const \$t = getI18n().t 别名（expect 1）, 实际 $constCnt 次, txt:\n$compact",
            1, constCnt
        )
        assertTrue(
            "「残缺提示」替换成短 \$t('残缺提示'), got:\n$txt",
            txt.contains("\$t('残缺提示')")
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
        val values = p.existingStrings.values.toSet()
        assertTrue(
            "React tc/\$tc/i18n.tc 内中文必须进 existingStrings,\nexpect=$expected\ngot=$values",
            values.containsAll(expected)
        )
    }
}
