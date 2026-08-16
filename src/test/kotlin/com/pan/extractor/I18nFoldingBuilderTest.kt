package com.pan.extractor

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * $t() 折叠测试：
 *  - 折叠展示指定语言（默认 zh）的翻译值
 *  - 仅命中翻译资源中存在的 key
 *  - 嵌套点号 key / 链式 t() / Vue-模板插值调用均能折叠
 */
class I18nFoldingBuilderTest : BasePlatformTestCase() {

    private lateinit var originalFoldLang: String

    override fun setUp() {
        super.setUp()
        originalFoldLang = I18nSettings.getInstance().foldDisplayLanguage()
        I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        // 项目根标记（findProjectRoot 依赖 package.json 定位根）
        myFixture.addFileToProject("package.json", """{"name":"root"}""")
        // 中文 locale 入口（含嵌套 key 与普通 key）
        myFixture.addFileToProject(
            "src/locales/zh.ts",
            """
            export default {
              '你好世界': '你好世界',
              'hello': '你好',
              nested: {
                'greeting': '嵌套问候'
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

    private fun configureFile(fileName: String, text: String): PsiFile {
        val psiFile = myFixture.addFileToProject(fileName, text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        return psiFile
    }

    private fun fold(text: String): List<com.intellij.lang.folding.FoldingDescriptor> {
        val file = configureFile("src/App.ts", text)
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        return I18nFoldingBuilder().buildFoldRegions(file, doc, false).toList()
    }

    fun testFoldShowsTranslationValueForDirectTCall() {
        val descriptors = fold("""const a = ${'$'}t('你好世界');""")
        assertEquals("应折叠 1 处", 1, descriptors.size)
        assertEquals("占位文本应为翻译值", "你好世界", descriptors.first().placeholderText)
    }

    fun testFoldTAndChainedCall() {
        val descriptors = fold(
            """
            const a = t('你好世界');
            const b = i18n.t('hello');
            """.trimIndent()
        )
        assertEquals("应折叠 2 处", 2, descriptors.size)
        val placeholders = descriptors.map { it.placeholderText }.toSet()
        assertTrue("应含「你好世界」", placeholders.contains("你好世界"))
        assertTrue("应含链式 t 的「你好」", placeholders.contains("你好"))
    }

    fun testFoldNestedDottedKey() {
        val descriptors = fold("""const a = ${'$'}t('nested.greeting');""")
        assertEquals("应折叠 1 处", 1, descriptors.size)
        assertEquals("嵌套点号 key 应命中", "嵌套问候", descriptors.first().placeholderText)
    }

    fun testUnknownKeyNotFolded() {
        val descriptors = fold("""const a = ${'$'}t('不存在的key');""")
        assertEquals("不存在的 key 不应折叠", 0, descriptors.size)
    }

    fun testNonTranslationCallNotFolded() {
        val descriptors = fold("""const a = foo('你好世界');""")
        assertEquals("非翻译调用不应折叠", 0, descriptors.size)
    }

    fun testFoldRangeSpansWholeCall() {
        val file = configureFile("src/Range.ts", """const a = ${'$'}t('你好世界');""")
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
        val d = descriptors.first()
        val text = doc.getText(d.range)
        assertTrue("折叠范围应覆盖整个调用，实际: $text", text.contains("\$t"))
        assertTrue("折叠范围应含 key，实际: $text", text.contains("你好世界"))
    }

    fun testFoldTsxDirectCall() {
        val file = configureFile(
            "src/App.tsx",
            """
            import {useTranslation} from 'react-i18next';
            export default function App() {
                const {t} = useTranslation();
                let a = t('你好世界');
                return <div>{ t('hello') }</div>;
            }
            """.trimIndent()
        )
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
        assertEquals("TSX 文件应折叠 2 处 t() 调用", 2, descriptors.size)
        val placeholders = descriptors.map { it.placeholderText }.toSet()
        assertTrue("应含「你好世界」", placeholders.contains("你好世界"))
        assertTrue("应含「你好」", placeholders.contains("你好"))
    }

    /** 用户报告的问题复现：key 含 {N0} 占位符时应将 {N0} 替换为实际参数值。 */
    fun testFoldTsxWithPlaceholderInKey() {
        // 额外添加含 {N0} 占位符的翻译条目
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                '你好Hello{N0}': 'Hello{N0}',
            }
            """.trimIndent()
        )
        I18nSettings.getInstance().setFoldDisplayLanguage("en")
        try {
            val file = configureFile(
                "src/App.tsx",
                """
                import {useTranslation} from 'react-i18next';
                export default function App() {
                    const {t} = useTranslation();
                    let a = t('你好Hello{N0}', {"0": 2});
                    return <div>{ t('你好Hello{N0}', { "0": '' }) }</div>;
                }
                """.trimIndent()
            )
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
            assertEquals("含 {N0} 占位符的 key 应折叠 2 处", 2, descriptors.size)
            val placeholders = descriptors.map { it.placeholderText }.toSet()
            assertTrue("{\"0\": 2} 应替换为 Hello2", placeholders.contains("Hello2"))
            assertTrue("{\"0\": ''} 应替换为 Hello", placeholders.contains("Hello"))
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }

    /** React 项目使用 {0}/{1} 格式（不带 N 前缀），验证插值正常。 */
    fun testFoldTsxReactStylePlaceholder() {
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                'hello': 'Hello, {0}!',
            }
            """.trimIndent()
        )
        I18nSettings.getInstance().setFoldDisplayLanguage("en")
        try {
            val file = configureFile(
                "src/App.tsx",
                """
                import {useTranslation} from 'react-i18next';
                export default function App() {
                    const {t} = useTranslation();
                    return <div>{ t('hello', { "0": "World" }) }</div>;
                }
                """.trimIndent()
            )
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
            assertEquals("应折叠 1 处", 1, descriptors.size)
            assertEquals("React {0} 应替换为 World", "Hello, World!", descriptors.first().placeholderText)
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }

    fun testFoldVueTemplateInterpolation() {
        val file = configureFile(
            "src/App.vue",
            """
            <template>
              <div>{{ ${'$'}t('你好世界') }}</div>
            </template>
            """.trimIndent()
        )
        // Vue 模板插值会被注入为 VueJS PSI，折叠实际作用于注入片段。
        val inj = InjectedLanguageManager.getInstance(project)
        val host = PsiTreeUtil.collectElementsOfType(
            file, PsiLanguageInjectionHost::class.java
        ).firstOrNull { it.text.contains("${'$'}t") }!!
        val injected = inj.getInjectedPsiFiles(host)!!.first().first
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val descriptors = I18nFoldingBuilder().buildFoldRegions(injected, doc, false)
        assertTrue("Vue 模板插值应折叠", descriptors.isNotEmpty())
        assertEquals("占位文本应为翻译值", "你好世界", descriptors.first().placeholderText)
    }
}