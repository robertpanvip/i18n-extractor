package com.pan.extractor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * React `i18n.t(...)` 全局调用 + locale 优先 / getI18n 回退 + 组件 useTranslation 注入
 * 的补充边界测试。
 *
 * 核心语义（与 ReactI18nProcessorTest 互补，聚焦"组件场景"与"去重/优先"交叉点）：
 *   1. 组件场景：不管顶部有没有全局导入，**必须**注入 useTranslation（hook 的 \$t 遮蔽全局 \$t）。
 *   2. locale 优先：locale 初始化文件导出了 i18n → 切 locale import，不回退 getI18n。
 *      locale 不可用 / 未导出 → 回退 `import { getI18n }` + `const \$t = getI18n().t`。
 *   3. 已有 i18n.t('...') 调用在回退场景下改写为 \$t('...')，避免 i18n 标识符悬空。
 */
class ReactI18nTCallScenarioTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "react-scenario-project",
              "dependencies": {
                "react": "^18.0.0",
                "react-dom": "^18.0.0",
                "react-i18next": "^13.0.0"
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
    // 1. 组件场景：回退 getI18n + 注入 useTranslation（核心语义）
    // ─────────────────────────────────────────────────────────────

    /**
     * 组件 + 已有 i18n.t + 无 locale → 同时注入：
     *   - import { getI18n } from 'react-i18next'  +  const \$t = getI18n().t（全局别名）
     *   - import { useTranslation } + 组件内 const { t: \$t } = useTranslation()
     *   - 已有 i18n.t("已存在") 改写为 \$t("已存在")
     */
    fun testComponentWithI18nTFallbackInjectsBothGlobalAndHook() {
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

        val c = compact(file)
        // 顶部全局别名（getI18n 回退）
        assertTrue("应注入 getI18n import", c.contains("import{getI18n}from'react-i18next'"))
        assertTrue("应注入全局 const \$t = getI18n().t", c.contains("const\$t=getI18n().t"))
        // 组件场景必须注入 useTranslation（不管顶部有没有全局导入）
        assertTrue("组件场景应注入 useTranslation import", file.text.contains("useTranslation"))
        assertTrue("组件内应注入 const { t: \$t } = useTranslation()", c.contains("const{t:\$t}=useTranslation()"))
        // 老调用改写
        assertTrue("i18n.t(\"已存在\") 应改写为 \$t(\"已存在\")", c.contains("\$t(\"已存在\")"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    /**
     * 组件 + i18n.t + locale 优先（导出了 i18n）→
     * 注入 locale import + useTranslation，**不**回退 getI18n。
     */
    fun testComponentWithI18nTAndLocaleExportUsesLocaleImport() {
        myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import i18n from 'i18next'
            import { initReactI18next } from 'react-i18next'

            i18n.use(initReactI18next).init({ resources: {}, lng: 'zh' })

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
        processor.execute()

        val c = compact(file)
        assertTrue("locale 优先应注入 import i18n from '@/locales'", c.contains("importi18nfrom'@/locales'"))
        assertFalse("locale 可用时不应回退 getI18n", c.contains("getI18n"))
        // locale 可用 → i18n.t 视为有效全局调用，保留原样（不 rewrite、不注入 useTranslation）
        assertTrue("i18n.t 应保留", c.contains("i18n.t(\"已存在\")"))
        assertFalse("locale 可用时不应注入 useTranslation", file.text.contains("useTranslation"))
    }

    /**
     * 组件 + i18n.t + locale 初始化文件存在但**未导出 i18n** → 回退 getI18n + useTranslation。
     */
    fun testComponentWithI18nTAndLocaleInitNoExportFallsBack() {
        myFixture.addFileToProject(
            "src/i18n/index.ts",
            """
            import i18n from 'i18next'
            import { initReactI18next } from 'react-i18next'

            i18n.use(initReactI18next).init({ resources: {}, lng: 'zh' })
            """.trimIndent()
        )

        val file = configureFile(
            "src/App.tsx",
            """
            export default function App() {
                return <button onClick={() => alert(i18n.t("已存在"))}>按钮</button>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val c = compact(file)
        assertFalse("未导出 i18n 不应切 locale", c.contains("@/i18n") || c.contains("@/locale"))
        assertTrue("应回退 getI18n import", c.contains("import{getI18n}from'react-i18next'"))
        assertTrue("应追加全局 const \$t = getI18n().t", c.contains("const\$t=getI18n().t"))
        assertTrue("组件场景应注入 useTranslation", file.text.contains("useTranslation"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 组件形态：多个组件 / 箭头组件 / 类组件 / 自定义 hook
    // ─────────────────────────────────────────────────────────────

    /**
     * 多个组件共存 + 各自 i18n.t → 每个组件都注入 useTranslation。
     */
    fun testMultipleComponentsWithI18nTEachGetUseTranslation() {
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
        processor.execute()

        val c = compact(file)
        val hookCount = c.split("const{t:\$t}=useTranslation()").size - 1
        assertEquals("两个组件都应注入 useTranslation, 实际 $hookCount 次", 2, hookCount)
        assertTrue("应回退 getI18n", c.contains("import{getI18n}from'react-i18next'"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    /**
     * 箭头函数组件 + i18n.t → 注入 useTranslation + 回退 getI18n。
     */
    fun testArrowComponentWithI18nTFallback() {
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
        processor.execute()

        val c = compact(file)
        assertTrue("箭头组件应注入 useTranslation", c.contains("const{t:\$t}=useTranslation()"))
        assertTrue("应回退 getI18n", c.contains("import{getI18n}from'react-i18next'"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    /**
     * 自定义 hook + i18n.t → 在 hook 函数体注入 useTranslation（hook 是合法调用点）。
     */
    fun testCustomHookWithI18nTFallback() {
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
        processor.execute()

        val c = compact(file)
        assertTrue("自定义 hook 应注入 useTranslation", c.contains("const{t:\$t}=useTranslation()"))
        assertTrue("应回退 getI18n", c.contains("import{getI18n}from'react-i18next'"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    /**
     * 组件 + 自定义 hook 共存 + 各自 i18n.t → 两处都注入 useTranslation。
     */
    fun testComponentAndHookBothGetUseTranslation() {
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
        processor.execute()

        val c = compact(file)
        val hookCount = c.split("const{t:\$t}=useTranslation()").size - 1
        assertEquals("组件和 hook 都应注入 useTranslation, 实际 $hookCount 次", 2, hookCount)
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 调用形态：JSX 属性 / 模板字符串 / 复数 tc / 嵌套
    // ─────────────────────────────────────────────────────────────

    /**
     * i18n.t 在 JSX 属性表达式里 → 改写为 \$t。
     */
    fun testI18nTInJsxAttributeRewrite() {
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
        processor.execute()

        val c = compact(file)
        assertTrue("JSX 属性里的 i18n.t 应改写为 \$t", c.contains("title={\$t(\"提示\")}"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    /**
     * i18n.t 使用反引号模板字符串 → 改写为 \$t，保留模板字符串。
     */
    fun testI18nTWithTemplateLiteralRewrite() {
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
        processor.execute()

        val c = compact(file)
        assertTrue("模板字符串 i18n.t 应改写为 \$t", c.contains("\$t(`模板文本`)"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    /**
     * 复数 i18n.tc('用户', 2) → 改写为 \$t('用户', 2)，保留数量参数。
     */
    fun testI18nTcPluralRewritePreservesCount() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return <div>{i18n.tc("用户", 2)}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val c = compact(file)
        assertTrue("i18n.tc 应改写为 \$t 并保留数量参数", c.contains("\$t(\"用户\",2)"))
        assertFalse("不应残留 i18n.tc(", file.text.contains("i18n.tc("))
    }

    /**
     * 嵌套 i18n.t(i18n.t(...)) → 外层改写为 \$t，内层调用也改写（各自独立 methodExpression）。
     */
    fun testNestedI18nTRewrite() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App() {
                return <div>{i18n.t(i18n.t("内层"))}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val c = compact(file)
        assertTrue("内层 i18n.t 应改为 \$t", c.contains("\$t(\"内层\")"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 去重：已有 useTranslation / getI18n 时不重复注入
    // ─────────────────────────────────────────────────────────────

    /**
     * 组件已有 useTranslation import + 调用，但缺 i18n 能力 → 只补 getI18n，不重复 useTranslation。
     */
    fun testExistingUseTranslationNoDuplicateWhenGetI18nNeededClean() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { useTranslation } from 'react-i18next'

            export default function App() {
                const { t: ${'$'}t } = useTranslation()
                return (
                    <div>
                        <h1>{${'$'}t("新标题")}</h1>
                        <button onClick={() => alert(i18n.t("已存在"))}>按钮</button>
                    </div>
                )
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val c = compact(file)
        // useTranslation 仅一次（import 一次 + 调用一次，调用只在此处）
        val callCount = c.split("useTranslation()").size - 1
        assertEquals("useTranslation() 调用应恰好一次", 1, callCount)
        // 缺 getI18n → 回退补上
        assertTrue("应回退补 getI18n import", c.contains("import{getI18n}from'react-i18next'"))
        // 已有 useTranslation import 时不应重复 import useTranslation
        val importCount = c.split("import{useTranslation}from'react-i18next'").size - 1
        assertEquals("useTranslation import 应恰好一次", 1, importCount)
        // 老 i18n.t 改写为 $t
        assertTrue("i18n.t(\"已存在\") 应改写为 \$t", c.contains("\$t(\"已存在\")"))
        assertFalse("不应残留 i18n.t(", file.text.contains("i18n.t("))
    }

    /**
     * 组件已完整具备 getI18n + useTranslation + const \$t 别名 → 不再重复注入任何东西。
     */
    fun testFullyConfiguredComponentNoDuplicate() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { getI18n } from 'react-i18next'
            import { useTranslation } from 'react-i18next'

            const ${'$'}t = getI18n().t

            export default function App() {
                const { t: ${'$'}t } = useTranslation()
                return <div>{${'$'}t("已有")}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val c = compact(file)
        assertEquals("getI18n import 应恰好一次", 1, c.split("import{getI18n}from'react-i18next'").size - 1)
        assertEquals("useTranslation import 应恰好一次", 1, c.split("import{useTranslation}from'react-i18next'").size - 1)
        assertEquals("useTranslation() 调用应恰好一次", 1, c.split("useTranslation()").size - 1)
        assertEquals("const \$t = getI18n().t 应恰好一次", 1, c.split("const\$t=getI18n().t").size - 1)
    }

    /**
     * 已有 `import { getI18n } from 'react-i18next'` → 已具备全局 i18n 能力。
     * 组件里的 i18n.t 被判定为"可用的全局调用"（tFunctionName=i18n.t），
     * 因此**不**走回退、**不**注入 useTranslation、**不**改写、**不**重复 getI18n。
     */
    fun testExistingGetI18nOnlyKeepsI18nTNoFallback() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import { getI18n } from 'react-i18next'

            export default function App() {
                return <div>{i18n.t("组件文本")}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val c = compact(file)
        assertEquals("getI18n import 应恰好一次", 1, c.split("import{getI18n}from'react-i18next'").size - 1)
        // 已有 getI18n → i18n.t 视为有效全局调用，保留原样
        assertTrue("i18n.t 应保留", c.contains("i18n.t(\"组件文本\")"))
        // 不补 useTranslation、不回退、不改写
        assertFalse("已有 getI18n 时不应注入 useTranslation", file.text.contains("useTranslation"))
        assertFalse("不应注入全局 const \$t = getI18n().t", c.contains("const\$t=getI18n().t"))
    }

    /**
     * 已有老 `import i18n from 'i18next'`（历史遗留）→ i18n 实例可用，
     * i18n.t 视为有效全局调用，**不**回退 getI18n、**不**注入 useTranslation、**不**改写。
     */
    fun testComponentWithLegacyI18nextImportNoGetI18nFallback() {
        val file = myFixture.configureByText(
            "App.tsx",
            """
            import i18n from 'i18next'

            export default function App() {
                return <div>{i18n.t("已有实例")}</div>
            }
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.execute()

        val c = compact(file)
        // 已有 i18n from i18next → hasI18nInstanceImported=true → 不回退 getI18n
        assertFalse("已有 i18n 实例不应回退 getI18n", c.contains("getI18n"))
        // i18n.t 视为有效全局调用，保留原样
        assertTrue("i18n.t 应保留", c.contains("i18n.t(\"已有实例\")"))
        // 不注入 useTranslation、不改写
        assertFalse("已有 i18n 实例时不应注入 useTranslation", file.text.contains("useTranslation"))
        // 老 i18n 实例导入保留
        assertTrue("老 import i18n from 'i18next' 应保留", c.contains("importi18nfrom'i18next'"))
    }
}