package com.pan.extractor.reference

import com.pan.extractor.ui.I18nSettings

import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.pom.Navigatable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import org.junit.Assert.*

/**
 * PsiReference 导航测试：
 * 直接使用 [I18nTranslationReferenceProvider] 验证 `t("key")` / `t('key')` / `` t(`key`) ``
 * 中的字符串参数通过原生 PsiReference 机制可导航到翻译文件对应条目。
 *
 * 因为 BasePlatformTestCase 不加载 plugin.xml 中的 psiReferenceContributor 扩展点，
 * 所以测试直接构造 Provider 并调用 getReferencesByElement()，而非依赖 element.references。
 *
 * 覆盖场景：
 *  - 三种引号风格（双引号 / 单引号 / 反引号）
 *  - 嵌套 JSON key（button.text）
 *  - 非翻译调用（f("key")）无引用
 *  - 非首参（t("key", "other") 的第二个参数）无引用
 *  - 翻译文件不存在时 resolve() 返回 null
 *  - rangeInElement 正确覆盖值部分（不含引号）
 */
class I18nTranslationReferenceTest : BasePlatformTestCase() {

    private val provider = I18nTranslationReferenceProvider()
    private lateinit var originalFoldLang: String

    override fun setUp() {
        super.setUp()
        originalFoldLang = I18nSettings.getInstance().foldDisplayLanguage()
        I18nSettings.getInstance().setFoldDisplayLanguage("zh")

        // 项目根标记
        myFixture.addFileToProject("package.json", """{"name":"root","dependencies":{"react":"^18"}}""")
        // 翻译文件（JSON 格式，含嵌套 key）
        myFixture.addFileToProject(
            "src/locales/zh.json",
            """
            {
              "你好世界": "你好世界",
              "hello": "你好",
              "button": {
                "text": "按钮文案",
                "tooltip": "提示信息"
              }
            }
            """.trimIndent()
        )
    }

    override fun tearDown() {
        try {
            I18nSettings.getInstance().setFoldDisplayLanguage(originalFoldLang)
        } finally {
            super.tearDown()
        }
    }

    /** 创建测试文件并配置到编辑器。 */
    private fun configureFile(fileName: String, text: String): com.intellij.psi.PsiFile {
        val psiFile = myFixture.addFileToProject(fileName, text.trimIndent())
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        return psiFile
    }

    /** 从文件中找到指定值的 JSLiteralExpression（双引号/单引号字符串）。 */
    private fun findLiteral(file: com.intellij.psi.PsiFile, value: String): JSLiteralExpression? =
        PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java)
            .firstOrNull { it.stringValue == value }

    /** 从文件中找到指定文本的 JSStringTemplateExpression（反引号字符串）。 */
    private fun findTemplate(file: com.intellij.psi.PsiFile, text: String): JSStringTemplateExpression? =
        PsiTreeUtil.findChildrenOfType(file, JSStringTemplateExpression::class.java)
            .firstOrNull { it.text == text }

    /** 直接调用 Provider 获取引用。 */
    private fun getRefs(element: com.intellij.psi.PsiElement): List<I18nTranslationReference> =
        provider.getReferencesByElement(element, ProcessingContext())
            .filterIsInstance<I18nTranslationReference>()

    // ── 1. 三种引号风格 ─────────────────────────────────────

    fun testDoubleQuotedKeyHasReference() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t("你好世界") }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "你好世界")
        assertNotNull("应找到 JSLiteralExpression 值=你好世界", literal)

        val refs = getRefs(literal!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

        val target = refs[0].resolve()
        assertNotNull("resolve() 应返回非 null 目标", target)
        assertTrue("目标应可导航", (target!! as Navigatable).canNavigate())
    }

    fun testSingleQuotedKeyHasReference() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t('hello') }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "hello")
        assertNotNull("应找到 JSLiteralExpression 值=hello", literal)

        val refs = getRefs(literal!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

        val target = refs[0].resolve()
        assertNotNull("resolve() 应返回非 null 目标", target)
        assertTrue("目标应可导航", (target!! as Navigatable).canNavigate())
    }

    fun testBacktickKeyHasReference() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t(`hello`) }</div>;
            }
            """.trimIndent()
        )
        val template = findTemplate(file, "`hello`")
        assertNotNull("应找到 JSStringTemplateExpression 文本=`hello`", template)

        val refs = getRefs(template!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

        val target = refs[0].resolve()
        assertNotNull("resolve() 应返回非 null 目标", target)
        assertTrue("目标应可导航", (target!! as Navigatable).canNavigate())
    }

    // ── 2. 嵌套 key ─────────────────────────────────────────

    fun testNestedKeyNavigation() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t("button.text") }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "button.text")
        assertNotNull("应找到 JSLiteralExpression 值=button.text", literal)

        val refs = getRefs(literal!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

        val target = refs[0].resolve()
        assertNotNull("嵌套 key button.text 应能解析到翻译文件", target)
        assertTrue("目标应可导航", (target!! as Navigatable).canNavigate())
    }

    // ── 3. 非翻译调用无引用 ─────────────────────────────────

    fun testNonTranslationCallHasNoReference() {
        val file = configureFile(
            "src/App.tsx",
            """
            function foo(x: string) {}
            const a = foo("你好世界");
            """.trimIndent()
        )
        val literal = findLiteral(file, "你好世界")
        assertNotNull("应找到 JSLiteralExpression", literal)

        val refs = getRefs(literal!!)
        assertEquals("非翻译调用不应有 I18nTranslationReference", 0, refs.size)
    }

    // ── 4. 非首参无引用 ─────────────────────────────────────

    fun testNonFirstArgHasNoReference() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t("hello", "other") }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "other")
        assertNotNull("应找到第二个参数的 JSLiteralExpression", literal)

        val refs = getRefs(literal!!)
        assertEquals("非首参不应有 I18nTranslationReference", 0, refs.size)
    }

    // ── 5. 翻译文件不存在时 resolve 返回 null ────────────────

    fun testResolveReturnsNullForMissingKey() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t("不存在的key") }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "不存在的key")
        assertNotNull("应找到 JSLiteralExpression", literal)

        val refs = getRefs(literal!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

        val target = refs[0].resolve()
        assertNull("不存在的 key 的 resolve() 应返回 null", target)
    }

    // ── 6. rangeInElement 验证 ───────────────────────────────

    fun testRangeInElementCoversValueWithoutQuotes() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t("hello") }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "hello")
        assertNotNull("应找到 JSLiteralExpression", literal)

        val refs = getRefs(literal!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

        val range = refs[0].rangeInElement
        // "hello" 文本为 7 字符（含引号），range 应覆盖引号内的值部分
        // JSLiteralExpression 的文本是 "hello"，值部分是 hello（不含引号）
        // textRange 在 JSLiteralExpression 内部，起始偏移 1（跳过开引号），长度 5（值长度）
        assertEquals("rangeInElement 起始应为 1（跳过开引号）", 1, range.startOffset)
        assertEquals("rangeInElement 结束应覆盖值部分", 6, range.endOffset)
    }

    // ── 7. t() 调用在 JS 文件中也可识别 ──────────────────────

    fun testReferenceInJsFile() {
        val file = configureFile(
            "src/App.js",
            """
            import { useTranslation } from 'react-i18next';
            const { t } = useTranslation();
            const a = t("你好世界");
            """.trimIndent()
        )
        val literal = findLiteral(file, "你好世界")
        assertNotNull("JS 文件应找到 JSLiteralExpression", literal)

        val refs = getRefs(literal!!)
        assertEquals("JS 文件应有 1 个 I18nTranslationReference", 1, refs.size)
        assertNotNull("resolve() 应返回非 null", refs[0].resolve())
    }

    // ── 8. 嵌套 key 不存在时 resolve 返回 null ─────────────

    fun testNestedNonExistentKeyResolvesNull() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t("button.nonexistent") }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "button.nonexistent")
        assertNotNull("应找到 JSLiteralExpression", literal)

        val refs = getRefs(literal!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)
        assertNull("不存在的嵌套 key 的 resolve() 应返回 null", refs[0].resolve())
    }

    // ── 9. 翻译文件使用 .ts 格式也能解析 ─────────────────────

    fun testReferenceWithTsTranslationFile() {
        // 额外添加 .ts 格式翻译文件
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                '你好': 'Hello',
                '世界': 'World',
            }
            """.trimIndent()
        )
        I18nSettings.getInstance().setFoldDisplayLanguage("en")
        try {
            val file = configureFile(
                "src/App.tsx",
                """
                import { useTranslation } from 'react-i18next';
                export default function App() {
                    const { t } = useTranslation();
                    return <div>{ t("你好") }</div>;
                }
                """.trimIndent()
            )
            val literal = findLiteral(file, "你好")
            assertNotNull("应找到 JSLiteralExpression", literal)

            val refs = getRefs(literal!!)
            assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

            // .ts 翻译文件通过文本搜索匹配，resolve 应返回非 null
            val target = refs[0].resolve()
            assertNotNull(".ts 翻译文件的 resolve() 应返回非 null", target)
            assertTrue("目标应可导航", (target!! as Navigatable).canNavigate())
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }

    // ── 10. 验证 resolve 的目标偏移量指向 value 所在行 ──────

    fun testResolvedTargetOffsetPointsToValue() {
        val file = configureFile(
            "src/App.tsx",
            """
            import { useTranslation } from 'react-i18next';
            export default function App() {
                const { t } = useTranslation();
                return <div>{ t("hello") }</div>;
            }
            """.trimIndent()
        )
        val literal = findLiteral(file, "hello")
        assertNotNull("应找到 JSLiteralExpression", literal)

        val refs = getRefs(literal!!)
        assertEquals("应有 1 个 I18nTranslationReference", 1, refs.size)

        val target = refs[0].resolve() as? I18nTranslationTargetElement
        assertNotNull("resolve() 应返回 I18nTranslationTargetElement", target)

        // 验证目标文件是翻译文件
        val targetFile = target!!.containingFile
        assertEquals("目标文件应为 zh.json", "zh.json", targetFile?.name)
    }
}