package com.pan.extractor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Solid.js i18n 提取端到端测试。
 *
 * 覆盖 Solid 组件（PascalCase 函数返回 JSX）文本提取、`useI18n` 数组解构注入、
 * `@solid-primitives/i18n` import 位置 / 去重，以及纯工具 TS 的全局 `$t` 别名注入。
 *
 * 核心语义（与 [ReactI18nProcessorTest] 对称，差异点已标注）：
 *   1. 组件 / 自定义 hook：注入 `import { useI18n } from '@solid-primitives/i18n'`
 *      + 函数体首行 `const [t, { locale }] = useI18n();`（数组解构，与 React 对象解构不同）。
 *   2. 纯工具 TS（无组件无 hook）：注入 `const { t: $t } = createAppI18n();` 全局别名
 *      （从 i18n 工厂文件导入 createAppI18n；找不到工厂文件时回退 `const [$t] = useI18n()`）。
 *   3. Solid 不应注入 react-i18next 的 useTranslation / getI18n。
 */
class SolidI18nProcessorTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // 创建 Solid 项目的 package.json（solid-js + @solid-primitives/i18n 依赖，无 vue 依赖）
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "solid-test-project",
              "dependencies": {
                "solid-js": "^1.8.0",
                "@solid-primitives/i18n": "^2.0.0"
              }
            }
            """.trimIndent()
        )
    }

    /**
     * 与 [ReactI18nProcessorTest] 一致：支持带路径（src/xxx.tsx）文件名的配置。
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

    private fun compact(file: PsiFile): String = file.text.replace("\\s+".toRegex(), "")

    // ============================================================
    // 1. JSX 文本提取（Solid 组件语法形态与 React 一致）
    // ============================================================

    /**
     * Solid 函数组件 JSX 文本提取。
     */
    fun testSolidJsxExtract() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
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
     * Solid 函数组件 JSX 属性字符串提取。
     */
    fun testSolidJsxAttributeStringExtract() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
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
     * Solid 函数组件嵌套元素文本提取。
     */
    fun testSolidJsxNestedElements() {
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
    // 2. Solid 组件函数 - useI18n 数组解构注入（核心差异点）
    // ============================================================

    /**
     * Solid 函数组件中注入 useI18n hook（数组解构 `const [t, { locale }] = useI18n()`）。
     *
     * 这是 Solid 与 React 的核心差异：React 用对象解构 `const { t } = useTranslation()`，
     * Solid 用数组解构 `const [t, { locale }] = useI18n()`。
     */
    fun testSolidFunctionComponentHookInjection() {
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
        val c = compact(file)
        assertTrue(
            "Should import useI18n from @solid-primitives/i18n, got:\n$resultText",
            c.contains("import{useI18n}from'@solid-primitives/i18n'")
        )
        assertTrue(
            "Should inject array destructuring const [t, { locale }] = useI18n(), got:\n$resultText",
            c.contains("const[t,{locale}]=useI18n()")
        )
    }

    /**
     * Solid 箭头函数组件中注入 useI18n hook。
     */
    fun testSolidArrowFunctionComponentHookInjection() {
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

        val c = compact(file)
        assertTrue(
            "Arrow function should inject useI18n, got:\n${file.text}",
            c.contains("const[t,{locale}]=useI18n()")
        )
    }

    /**
     * 已有 useI18n 调用时不重复注入（数组解构去重）。
     */
    fun testSolidExistingUseI18nNotDuplicated() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { useI18n } from '@solid-primitives/i18n';

            export default function App() {
                const [t] = useI18n();
                return <div>{t('你好')}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val resultText = file.text
        // useI18n() 调用应只出现一次（已有的那次），不重复注入
        val callCount = resultText.split("useI18n()").size - 1
        assertEquals(
            "useI18n() call should appear exactly once (existing one), got $callCount times:\n$resultText",
            1, callCount
        )
        // import 也应只出现一次
        val importCount = resultText.split("@solid-primitives/i18n").size - 1
        assertEquals(
            "@solid-primitives/i18n import should appear exactly once, got $importCount times",
            1, importCount
        )
    }

    // ============================================================
    // 3. @solid-primitives/i18n import 注入位置 / 去重
    // ============================================================

    /**
     * 无 import 时 useI18n import 应加到文件最开头。
     */
    fun testSolidImportAddedAtTop() {
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
        val importIndex = resultText.indexOf("@solid-primitives/i18n")
        val exportIndex = resultText.indexOf("export default")
        assertTrue(
            "import should exist, got:\n$resultText",
            importIndex >= 0
        )
        assertTrue(
            "import should come before export, import at $importIndex, export at $exportIndex",
            importIndex >= 0 && exportIndex >= 0 && importIndex < exportIndex
        )
    }

    /**
     * 已有其他 import 时 useI18n import 加在最前面。
     */
    fun testSolidImportAddedBeforeOtherImports() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { render } from 'solid-js/web';

            export default function App() {
                return <div>你好</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val lines = file.text.lines()
        val solidI18nIndex = lines.indexOfFirst { it.contains("@solid-primitives/i18n") }
        val solidJsIndex = lines.indexOfFirst { it.contains("from 'solid-js/web'") }
        assertTrue("@solid-primitives/i18n import should exist", solidI18nIndex >= 0)
        assertTrue(
            "@solid-primitives/i18n import should come before solid-js/web import, got:\n${file.text}",
            solidI18nIndex < solidJsIndex
        )
    }

    /**
     * 已有 @solid-primitives/i18n import 时不重复添加。
     */
    fun testSolidExistingImportNotDuplicated() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { useI18n } from '@solid-primitives/i18n';

            export default function App() {
                const [t] = useI18n();
                return <div>你好</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val count = file.text.split("@solid-primitives/i18n").size - 1
        assertEquals(
            "@solid-primitives/i18n should appear exactly once, got $count times:\n${file.text}",
            1, count
        )
    }

    // ============================================================
    // 4. 多组件场景：每个组件都注入 useI18n
    // ============================================================

    /**
     * 多个 Solid 组件时每个组件都注入 useI18n。
     */
    fun testSolidMultipleComponentsAllInjected() {
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

        val c = compact(file)
        val hookCount = c.split("const[t,{locale}]=useI18n()").size - 1
        assertEquals(
            "Three components should each inject useI18n, got $hookCount times:\n${file.text}",
            3, hookCount
        )
    }

    // ============================================================
    // 5. 纯工具 TS：全局 $t 别名注入
    // ============================================================

    /**
     * Solid 纯工具 TS（无组件无 hook）：注入 i18n 工厂导入 + 全局 `$t` 别名。
     *
     * 场景：utils.ts 中只有普通函数，没有 PascalCase 组件函数也没有 use* hook。
     * collect 阶段 detectGlobalDollarTNeeded 返回 true → 注入 `const { t: $t } = createAppI18n()`。
     *
     * 这里使用 src/locales/index.ts 提供 createAppI18n 工厂文件，验证能从工厂文件导入。
     */
    fun testSolidPureUtilFileInjectsGlobalDollarT() {
        myFixture.addFileToProject(
            "src/i18n/index.ts",
            """
            import { useI18n } from '@solid-primitives/i18n';

            const dict = { zh: {} };

            export function createAppI18n() {
                const [t, { locale }] = useI18n(dict, () => 'zh');
                return { t, locale };
            }
            """.trimIndent()
        )

        val file = configureFile(
            "src/utils/format.ts",
            """
            export function greet(name: string) {
                return "您好" + ", " + name
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        // 纯工具文件应注入 $t 别名（具体形态由工厂文件决定，createAppI18n 命中 → 对象解构）
        assertTrue(
            "Pure util should inject global \$t alias from createAppI18n, got:\n${file.text}",
            c.contains("const{t:\$t}=createAppI18n()")
        )
        // 应从 i18n 工厂文件导入 createAppI18n
        assertTrue(
            "Should import createAppI18n from i18n factory file, got:\n${file.text}",
            c.contains("import{createAppI18n}from")
        )
    }

    /**
     * Solid 纯工具 TS（无工厂文件）：回退到 `import { useI18n } from '@solid-primitives/i18n'`
     * + `const [$t] = useI18n();`（无 dict 调用会失败，但至少不悬空 $t）。
     */
    fun testSolidPureUtilFileFallbackWhenNoFactory() {
        val file = configureFile(
            "src/utils/empty.ts",
            """
            export function label() {
                return "标签"
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        // 找不到工厂文件 → 回退 useI18n 数组解构
        assertTrue(
            "Pure util without factory should fallback to const [\$t] = useI18n(), got:\n${file.text}",
            c.contains("const[\$t]=useI18n()")
        )
        assertTrue(
            "Should import useI18n from @solid-primitives/i18n, got:\n${file.text}",
            c.contains("import{useI18n}from'@solid-primitives/i18n'")
        )
    }

    // ============================================================
    // 6. 关键回归：Solid 不应注入 react-i18next
    // ============================================================

    /**
     * 回归测试：Solid 项目**绝不**注入 react-i18next 的 useTranslation / getI18n。
     *
     * 这是 Solid.js 支持的核心修复点：之前 Solid 走 React 分支会错误注入 react-i18next。
     */
    fun testSolidNeverInjectsReactI18next() {
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

        val c = compact(file)
        assertFalse(
            "Solid should NOT import react-i18next, got:\n${file.text}",
            c.contains("react-i18next")
        )
        assertFalse(
            "Solid should NOT inject useTranslation, got:\n${file.text}",
            c.contains("useTranslation")
        )
        assertFalse(
            "Solid should NOT inject getI18n, got:\n${file.text}",
            c.contains("getI18n")
        )
    }
}
