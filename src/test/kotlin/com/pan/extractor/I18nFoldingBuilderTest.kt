package com.pan.extractor

import com.pan.extractor.ui.*

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
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

    /** 带折叠切换提示符的期望值，避免每个断言手动拼接。 */
    private fun hint(value: String) = value + I18nFoldingBuilder.TOGGLE_HINT

    override fun setUp() {
        super.setUp()
        originalFoldLang = I18nSettings.getInstance().foldDisplayLanguage()
        I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        // 项目根标记（findProjectRoot 依赖 package.json 定位根）
        myFixture.addFileToProject("package.json", """{"name":"root","dependencies":{"react":"^18"}}""")
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
        assertEquals("占位文本应为翻译值", hint("你好世界"), descriptors.first().placeholderText)
    }

    fun testFoldTAndChainedCall() {
        val descriptors = fold(
            """
            import { useTranslation } from 'react-i18next';
            const { t } = useTranslation();
            const a = t('你好世界');
            const b = i18n.t('hello');
            """.trimIndent()
        )
        assertEquals("应折叠 2 处", 2, descriptors.size)
        val placeholders = descriptors.map { it.placeholderText }.toSet()
        assertTrue("应含「你好世界」", placeholders.contains(hint("你好世界")))
        assertTrue("应含链式 t 的「你好」", placeholders.contains(hint("你好")))
    }

    fun testFoldNestedDottedKey() {
        val descriptors = fold("""const a = ${'$'}t('nested.greeting');""")
        assertEquals("应折叠 1 处", 1, descriptors.size)
        assertEquals("嵌套点号 key 应命中", hint("嵌套问候"), descriptors.first().placeholderText)
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
        assertTrue("应含「你好世界」", placeholders.contains(hint("你好世界")))
        assertTrue("应含「你好」", placeholders.contains(hint("你好")))
    }

    /**
     * 回归用例：React .tsx 中**反引号**模板 key `t(\`插件管理\`)`（非 Vue 注入）。
     * 此前 addFoldingDescriptor 用 isBacktickKeyCall 把宿主树反引号调用也跳过，
     * 而 raw 兜底正则只认 $t/i18n 前缀、对纯 `t(` 不命中 → 反引号 React 调用完全不折叠。
     */
    fun testFoldTsxBacktickKeyCall() {
        val file = configureFile(
            "src/App.tsx",
            """
            import {useTranslation} from 'react-i18next';
            export default function App() {
                const {t} = useTranslation();
                let a = t(`你好世界`);
                return <div>{ t(`hello`) }</div>;
            }
            """.trimIndent()
        )
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
        assertEquals("TSX 反引号 t() 应折叠 2 处", 2, descriptors.size)
        val placeholders = descriptors.map { it.placeholderText }.toSet()
        assertTrue("应含「你好世界」", placeholders.contains(hint("你好世界")))
        assertTrue("应含「你好」", placeholders.contains(hint("你好")))
    }

    /** 用户报告的问题复现：React 项目 key 含 {0} 占位符时应将 {0} 替换为实际参数值。 */
    fun testFoldTsxWithPlaceholderInKey() {
        // 额外添加含 {0} 占位符的翻译条目（React 格式，不带 N 前缀）
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                '你好Hello{0}': 'Hello{0}',
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
                    let a = t('你好Hello{0}', {"0": 2});
                    return <div>{ t('你好Hello{0}', { "0": '' }) }</div>;
                }
                """.trimIndent()
            )
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
            assertEquals("含 {0} 占位符的 key 应折叠 2 处", 2, descriptors.size)
            val placeholders = descriptors.map { it.placeholderText }.toSet()
            assertTrue("{\"0\": 2} 应替换为 Hello2", placeholders.contains(hint("Hello2")))
            assertTrue("{\"0\": ''} 应替换为 Hello", placeholders.contains(hint("Hello")))
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
            assertEquals("React {0} 应替换为 World", hint("Hello, World!"), descriptors.first().placeholderText)
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }

    /** Vue 项目：翻译值中 {{0}} 是花括号转义，应反转义为 {0} 且不做插值替换。 */
    fun testFoldVueDoubleBraceNotInterpolated() {
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                'hello': 'Hello{{0}}',
            }
            """.trimIndent()
        )
        I18nSettings.getInstance().setFoldDisplayLanguage("en")
        try {
            val file = configureFile(
                "src/App.vue",
                """
                <template>
                  <div>{{ ${'$'}t('hello', { "0": "World" }) }}</div>
                </template>
                """.trimIndent()
            )
            val inj = InjectedLanguageManager.getInstance(project)
            val host = PsiTreeUtil.collectElementsOfType(
                file, PsiLanguageInjectionHost::class.java
            ).firstOrNull { it.text.contains("${'$'}t") }!!
            val injected = inj.getInjectedPsiFiles(host)!!.first().first
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val descriptors = I18nFoldingBuilder().buildFoldRegions(injected, doc, false)
            assertEquals("应折叠 1 处", 1, descriptors.size)
            assertEquals("Vue 双花括号应反转义为单花括号，不替换参数", hint("Hello{0}"), descriptors.first().placeholderText)
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }

    /** React 项目：翻译值中 {{0}} 是占位符格式，应整体替换为参数值。 */
    fun testFoldReactDoubleBraceIsPlaceholder() {
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                'hello': 'Hello{{0}}',
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
            assertEquals("React {{0}} 应整体替换为参数值", hint("HelloWorld"), descriptors.first().placeholderText)
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }

    /**
     * 复现：因子化合并后产物为骨架 key + 嵌套差异调用，如
     *   zh.ts: {'请输入{N0}': '请输入', '搜索关键词': '搜索关键词'}
     *   Vue:   {{ ${'$'}t('请输入{N0}', { N0: ${'$'}t('搜索关键词') }) }}
     * 期望折叠后能看到完整文案「请输入搜索关键词」。
     */
    fun testFoldVueSkeletonWithNestedDiff() {
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
              '请输入{N0}': '请输入{N0}',
              '搜索关键词': '搜索关键词',
            }
            """.trimIndent()
        )
        I18nSettings.getInstance().setFoldDisplayLanguage("en")
        try {
            val file = configureFile(
                "src/App.vue",
                """
                <template>
                  <div>{{ ${'$'}t('请输入{N0}', { N0: ${'$'}t('搜索关键词') }) }}</div>
                </template>
                """.trimIndent()
            )
            val inj = InjectedLanguageManager.getInstance(project)
            val host = PsiTreeUtil.collectElementsOfType(
                file, PsiLanguageInjectionHost::class.java
            ).firstOrNull { it.text.contains("${'$'}t") }!!
            val injected = inj.getInjectedPsiFiles(host)!!.first().first
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val descriptors = I18nFoldingBuilder().buildFoldRegions(injected, doc, false)
            // 期望外层骨架折叠后看到「完整文案」请输入搜索关键词
            val outer = descriptors.firstOrNull { it.placeholderText?.contains("请输入") == true }
            System.out.println("==== REPRO fold placeholders = ${descriptors.map { it.placeholderText }}")
            assertEquals("骨架调用应折叠且占位为完整文案", hint("请输入搜索关键词"), outer?.placeholderText)
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
        assertEquals("占位文本应为翻译值", hint("你好世界"), descriptors.first().placeholderText)
    }

    /**
     * 用户报告：`{{ $t(\`模型自动分段\`) }}`（反引号 key 的 mustache）未被折叠，而属性绑定
     * `:title="$t('文档分段策略')"` 会折叠。根因：Vue 对反引号表达式注入的 PSI 不含
     * JSCallExpression（见 VueI18nStrategy.collectExistingTKeysFromTemplate 注释），
     * 宿主树扫描（collectJSCallExpressions）也扫不到。修复后应通过
     * 宿主原始文本兜底（[I18nFoldingBuilder.addRawFolds]）将这类调用折叠为译文。
     */
    fun testFoldVueBacktickMustacheRawTextFallback() {
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                '模型自动分段': '智能分片',
            }
            """.trimIndent()
        )
        I18nSettings.getInstance().setFoldDisplayLanguage("en")
        try {
            val file = configureFile(
                "src/Backtick.vue",
                """
                <template>
                  <div :class="styles.title">{{ ${'$'}t(`模型自动分段`) }}</div>
                </template>
                """.trimIndent()
            )
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            // 以 .vue 宿主文件为 root：生产环境折叠 builder 也会被触发在顶层文件上
            val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
            System.out.println("==== BACKTICK descriptors = ${descriptors.map { it.placeholderText }}")
            assertTrue(
                "反引号 mustache 应通过原始文本兜底折叠为译文",
                descriptors.any { it.placeholderText?.startsWith(hint("智能分片")) == true }
            )
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }

    /**
     * 诊断：复刻 I18nFoldToggleInlayProvider.addFoldToggleInlays 的收集逻辑
     * （在宿主文件顶层 PSI 里 collectElementsOfType 找 JSCallExpression，不含注入片段），
     * 对比 React .tsx 与 Vue .vue 谁能被该 inlay 逻辑扫到。
     */
    /** 诊断：Vue 属性绑定 :placeholder="$t('x')" 里的 $t 调用在宿主树与注入片段中分别能否被扫到。 */
    fun testDiagnosticVueAttributeBindingCallCoverage() {
        // setUp 已添加 src/locales/zh.ts（含 key 你好世界），直接复用
        val file = configureFile(
            "src/Attr.vue",
            """
            <template>
              <a-input :placeholder="${'$'}t('你好世界')" />
            </template>
            """.trimIndent()
        )
        // 1) 宿主顶层 PSI 扫描 JSCallExpression
        val hostCalls = PsiTreeUtil.collectElementsOfType(file, JSCallExpression::class.java)
            .filter { it.text.contains("你好") }
        System.out.println("==== DIAG VueAttr file.language = ${file.language} / id=${file.language?.id}")
        hostCalls.forEach { c ->
            System.out.println("==== DIAG VueAttr \$t lang(id) = ${c.language.id}, psiFile=${c.containingFile.name}, nodeElementType=${c.node.elementType}")
        }
        System.out.println("==== DIAG VueAttr host-top-level \$t calls = ${hostCalls.map { it.text }}")

        // 1b) 直接在宿主文件 root 上跑 buildFoldRegions，看是否产出折叠描述符
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val hostDescriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)
        System.out.println("==== DIAG VueAttr host-tree fold descriptors = ${hostDescriptors.map { d -> "range=${d.range} text=['" + doc.getText(d.range) + "']" }}")

        // 2) 注入片段
        val inj = InjectedLanguageManager.getInstance(project)
        val host = PsiTreeUtil.collectElementsOfType(file, PsiLanguageInjectionHost::class.java)
            .firstOrNull { it.text.contains("你好") }
        System.out.println("==== DIAG VueAttr host(attributeValue?) = ${host?.javaClass?.simpleName} :: ${host?.text}")
        val injected = host?.let { inj.getInjectedPsiFiles(it)?.firstOrNull()?.first }
        val injectedCalls = injected?.let {
            PsiTreeUtil.collectElementsOfType(it, JSCallExpression::class.java).filter { c -> c.text.contains("你好") }
        }
        System.out.println("==== DIAG VueAttr injected \$t calls = ${injectedCalls?.map { c -> c.text }}")

        // 3) 以注入片段为 root 尝试折叠
        if (injected != null) {
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val descriptors = I18nFoldingBuilder().buildFoldRegions(injected, doc, false)
            System.out.println("==== DIAG VueAttr fold descriptors(size) = ${descriptors.size}, ranges=${descriptors.map { it.range }}")
        }
        assertTrue("诊断无断言，仅打印", true)
    }

    /**
     * 用户报告：Vue 属性绑定 :placeholder="$t('x')" 点击 ↩ 会折叠"整个标签"而非仅 $t 调用中文文案。
     * 根因：.vue 根语言（Vue）未注册折叠，导致 $t 调用没有折叠区域。
     * 修复后应折叠出"仅覆盖 $t('你好世界') 调用"的区域，而非整段 `<a-input ...>`。
     */
    fun testFoldVueAttributeBindingProducesCallRegion() {
        val file = configureFile(
            "src/Attr.vue",
            """
            <template>
              <a-input :placeholder="${'$'}t('你好世界')" />
            </template>
            """.trimIndent()
        )
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        // 在 .vue 根语言宿主树上直接构建折叠区域（等同编辑器对 Vue 根语言的折叠调用）
        val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)

        // 折叠区域必须命中属性绑定里的 $t 调用
        val callDescriptors = descriptors.filter {
            doc.getText(it.range).trim().startsWith("\$t(")
        }
        assertTrue("应存在覆盖 \$t 调用的折叠区域", callDescriptors.isNotEmpty())

        // 折叠文本应恰好是调用本身，绝不包含整个标签
        val rangeText = doc.getText(callDescriptors.first().range).trim()
        assertTrue("折叠范围应仅是 \$t 调用，实际: $rangeText", rangeText.startsWith("\$t('你好世界')"))
        assertTrue("折叠范围不应包含整个标签", !rangeText.contains("<a-input"))
    }

    fun testDiagnosticInlayTopLevelCoverageVueVsReact() {
        // React .tsx
        val tsx = configureFile(
            "diag/App.tsx",
            """
            import {useTranslation} from 'react-i18next';
            export default function App() {
                const {t} = useTranslation();
                return <div>{ t('你好世界') }</div>;
            }
            """.trimIndent()
        )
        val tsxCalls = PsiTreeUtil.collectElementsOfType(tsx, JSCallExpression::class.java).size
        val tsxTranslationCalls = PsiTreeUtil.collectElementsOfType(tsx, JSCallExpression::class.java)
            .count { it.methodExpression?.text?.substringAfterLast('.') in setOf("t", "\${'$'}t", "tc", "\${'$'}tc") }
        System.out.println("==== DIAG tsx top-level JS calls = $tsxCalls, 其中 t()/\\${'$'}t() = $tsxTranslationCalls")

        // Vue .vue（模板 {{ $t() }} 为注入语言）
        val vue = configureFile(
            "diag/App.vue",
            """
            <template>
              <div>{{ ${'$'}t('你好世界') }}</div>
            </template>
            """.trimIndent()
        )
        val vueCalls = PsiTreeUtil.collectElementsOfType(vue, JSCallExpression::class.java).size
        val vueTranslationCalls = PsiTreeUtil.collectElementsOfType(vue, JSCallExpression::class.java)
            .count { it.methodExpression?.text?.substringAfterLast('.') in setOf("t", "\${'$'}t", "tc", "\${'$'}tc") }
        System.out.println("==== DIAG vue top-level JS calls = $vueCalls, 其中 t()/\\${'$'}t() = $vueTranslationCalls")

        assertTrue("tsx 顶层能扫到翻译调用", tsxTranslationCalls >= 1)
        System.out.println("==== DIAG 结论: tsx=$tsxTranslationCalls / vue 顶层=$vueTranslationCalls")
    }

    /**
     * 回归：Vue 中折叠曾出现 2 个预览、打断正常文字、点开才恢复。
     * 根因：mustache 注入出的 JSCallExpression.textRange 是**注入文件**坐标，被当作宿主
     * 文档坐标套用，产生错位垃圾区域（`{{ $t('模型自动分段') }}` 的折叠落在 `<template>` 标签里）。
     * 修复：宿主树的 JSCall 坐标即宿主坐标；模板表达式统一走原始文本兜底（宿主绝对坐标）。
     * 断言：折叠区域的数量与文本都精确落位，不允许任何错位。
     */
    fun testFoldVueTemplateCallRegionsAreHostAligned() {
        myFixture.addFileToProject(
            "src/locales/en.ts",
            """
            export default {
                '文档分段策略': 'Segmentation Strategy',
                '模型自动分段': 'Auto Split',
            }
            """.trimIndent()
        )
        I18nSettings.getInstance().setFoldDisplayLanguage("en")
        try {
            val file = configureFile(
                "src/Dupe.vue",
                """
                <template>
                  <div>
                    <span :title="${'$'}t('文档分段策略')">文</span>
                    <span>{{ ${'$'}t('模型自动分段') }}</span>
                  </div>
                </template>
                """.trimIndent()
            )
            val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
            val descriptors = I18nFoldingBuilder().buildFoldRegions(file, doc, false)

            val rangeTexts = descriptors.map { doc.getText(it.range).trim() }
            System.out.println("==== DIAG-VUE rangeTexts = $rangeTexts")
            // 必须恰好是两个调用各折叠一次，且折叠范围精确等于调用本身（不允许错位/多余区域）。
            assertEquals(setOf("${'$'}t('文档分段策略')", "${'$'}t('模型自动分段')"), rangeTexts.toSet())
            assertEquals("不应产生重复/错位的多余折叠区域", 2, descriptors.size)
        } finally {
            I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        }
    }
}