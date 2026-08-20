package com.pan.extractor

import com.pan.extractor.core.I18nProcessor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * Solid.js `i18n.t(...)` / `$t(...)` 老调用场景 + 组件 useI18n 注入的边界测试。
 *
 * 与 [ReactI18nTCallScenarioTest] 对称，覆盖 Solid 专属语义：
 *   1. 组件场景：**必须**注入 useI18n（hook 的 t 遮蔽全局 t），不管有没有 i18n.t 老调用。
 *   2. Solid 文件统一用短 `t`（[SolidI18nStrategy.tFunctionName] == "t"）：
 *      组件 / 自定义 hook 注入 `const [t, { locale }] = useI18n()`；
 *      纯工具文件（无组件无 hook）注入 `const { t: $t } = createAppI18n()`。
 *   3. 老 `i18n.t('...')` / `$t('...')` 调用一律保留，不再改写。
 *   4. Solid 不应回退 react-i18next 的 getI18n（这是修复前的 bug）。
 */
class SolidI18nTCallScenarioTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "solid-scenario-project",
              "dependencies": {
                "solid-js": "^1.8.0",
                "@solid-primitives/i18n": "^2.0.0"
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

    // ─────────────────────────────────────────────────────────────
    // 1. 组件场景：注入 useI18n（核心语义）
    // ─────────────────────────────────────────────────────────────

    /**
     * 组件 + 已有 i18n.t 老调用 + 无 i18n 工厂文件 → 组件场景：
     *   - import { useI18n } from '@solid-primitives/i18n' + 组件内 const [t, { locale }] = useI18n()
     *   - 老 i18n.t("已存在") 保留（不改写）
     *   - 不回退 getI18n（Solid 的修复点）
     */
    fun testComponentWithI18nTInjectsUseI18n() {
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

        val c = compact(file)
        // 组件场景必须注入 useI18n
        assertTrue(
            "Component should import useI18n from @solid-primitives/i18n, got:\n${file.text}",
            c.contains("import{useI18n}from'@solid-primitives/i18n'")
        )
        assertTrue(
            "Component body should inject const [t, { locale }] = useI18n(), got:\n${file.text}",
            c.contains("const[t,{locale}]=useI18n()")
        )
        // 老 i18n.t 调用保留（不改写）
        assertTrue("i18n.t(\"已存在\") 应保留, got:\n${file.text}", file.text.contains("i18n.t("))
        // 新提取写短 t（SolidI18nStrategy.tFunctionName == "t"）
        assertTrue("新提取应写 t(`新标题`), got:\n${file.text}", c.contains("t(`新标题`)"))
        // 关键回归：Solid 不应回退 react-i18next 的 getI18n
        assertFalse("Solid should NOT fallback to react-i18next getI18n", c.contains("getI18n"))
        assertFalse("Solid should NOT import react-i18next", c.contains("react-i18next"))
    }

    /**
     * 组件 + i18n.t + 有 i18n 工厂文件（导出 createAppI18n）→
     * 组件场景仍注入 useI18n（不切工厂导入，工厂只用于纯工具文件）。
     */
    fun testComponentWithI18nTAndFactoryStillUsesUseI18n() {
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

        val c = compact(file)
        // 组件场景仍注入 useI18n（不切工厂导入 createAppI18n）
        assertTrue(
            "Component should still inject useI18n even with factory file, got:\n${file.text}",
            c.contains("const[t,{locale}]=useI18n()")
        )
        assertFalse(
            "Component should NOT import createAppI18n (factory is for pure util only), got:\n${file.text}",
            c.contains("import{createAppI18n}from")
        )
        // 老 i18n.t 保留
        assertTrue("i18n.t 应保留", c.contains("i18n.t(\"已存在\")"))
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 组件形态：多组件 / 箭头组件 / 自定义 hook
    // ─────────────────────────────────────────────────────────────

    /**
     * 多个组件共存 + 各自 i18n.t → 每个组件都注入 useI18n，老 i18n.t 保留。
     */
    fun testMultipleComponentsWithI18nTEachGetUseI18n() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export function Header() {
                return <h1>{i18n.t("标题")}<span>头部备注</span></h1>
            }

            export function Footer() {
                return <footer>{i18n.t("页脚")}<span>底部备注</span></footer>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        val hookCount = c.split("const[t,{locale}]=useI18n()").size - 1
        assertEquals(
            "两个组件都应注入 useI18n, 实际 $hookCount 次:\n${file.text}",
            2, hookCount
        )
        assertFalse("Solid 不应回退 getI18n", c.contains("getI18n"))
        assertTrue("老 i18n.t 应保留", file.text.contains("i18n.t("))
    }

    /**
     * 箭头函数组件 + i18n.t → 注入 useI18n，老 i18n.t 保留。
     */
    fun testArrowComponentWithI18nT() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            const App = () => {
                return <div>{i18n.t("你好")}<p>箭头文案</p></div>
            }
            export default App
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        assertTrue(
            "箭头组件应注入 useI18n, got:\n${file.text}",
            c.contains("const[t,{locale}]=useI18n()")
        )
        assertFalse("Solid 不应回退 getI18n", c.contains("getI18n"))
        assertTrue("老 i18n.t 应保留", file.text.contains("i18n.t("))
    }

    /**
     * 自定义 hook + i18n.t → 在 hook 函数体注入 useI18n（hook 是合法调用点）。
     */
    fun testCustomHookWithI18nT() {
        val file = myFixture.configureByText(
            "useGreeting.ts",
            """
            export function useGreeting(name: string) {
                const suffix = "您好"
                return i18n.t("欢迎") + ", " + name + suffix
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        assertTrue(
            "自定义 hook 应注入 useI18n, got:\n${file.text}",
            c.contains("const[t,{locale}]=useI18n()")
        )
        assertFalse("Solid 不应回退 getI18n", c.contains("getI18n"))
        assertTrue("老 i18n.t 应保留", file.text.contains("i18n.t("))
    }

    /**
     * 组件 + 自定义 hook 共存 + 各自 i18n.t → 两处都注入 useI18n。
     */
    fun testComponentAndHookBothGetUseI18n() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export function useTitle() {
                return i18n.t("标题")
            }

            export default function App() {
                return <div>{i18n.t("内容")}<p>共存文案</p></div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        val hookCount = c.split("const[t,{locale}]=useI18n()").size - 1
        assertEquals(
            "组件和 hook 都应注入 useI18n, 实际 $hookCount 次:\n${file.text}",
            2, hookCount
        )
        assertTrue("老 i18n.t 应保留", file.text.contains("i18n.t("))
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 调用形态：JSX 属性 / 模板字符串
    // ─────────────────────────────────────────────────────────────

    /**
     * i18n.t 在 JSX 属性表达式里 → 老调用保留（不改写为 $t）。
     */
    fun testI18nTInJsxAttributePreserved() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return <button title={i18n.t("提示")}>按钮</button>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        assertTrue(
            "JSX 属性里的 i18n.t 应保留, got:\n${file.text}",
            c.contains("title={i18n.t(\"提示\")}")
        )
        assertFalse("不应改写为 \$t", c.contains("title={\$t(\"提示\")}"))
    }

    /**
     * i18n.t 使用反引号模板字符串 → 老调用保留。
     */
    fun testI18nTWithTemplateLiteralPreserved() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return <div>{i18n.t(`模板文本`)}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        assertTrue(
            "模板字符串里的 i18n.t 应保留, got:\n${file.text}",
            c.contains("i18n.t(`模板文本`)")
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 纯工具 TS：i18n.t 老调用 + 工厂文件 → 全局 $t 别名
    // ─────────────────────────────────────────────────────────────

    /**
     * 纯工具 TS（无组件无 hook）+ i18n.t 老调用 + 有 i18n 工厂文件 →
     * 注入 `import { createAppI18n } from '...'` + `const { t: $t } = createAppI18n()`，
     * 老 i18n.t 保留（不改写）。
     */
    fun testPureUtilWithI18nTInjectsGlobalDollarT() {
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
                return i18n.t("欢迎") + ", " + name
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val c = compact(file)
        // 纯工具文件应注入 createAppI18n 工厂导入 + $t 别名
        assertTrue(
            "Pure util should import createAppI18n from factory, got:\n${file.text}",
            c.contains("import{createAppI18n}from")
        )
        assertTrue(
            "Pure util should inject const { t: \$t } = createAppI18n(), got:\n${file.text}",
            c.contains("const{t:\$t}=createAppI18n()")
        )
        // 老 i18n.t 调用保留（不改写为 $t）
        assertTrue("老 i18n.t 应保留, got:\n${file.text}", c.contains("i18n.t(\"欢迎\")"))
        // 关键回归：不回退 react-i18next
        assertFalse("Solid 不应回退 getI18n", c.contains("getI18n"))
        assertFalse("Solid 不应导入 react-i18next", c.contains("react-i18next"))
    }
}
