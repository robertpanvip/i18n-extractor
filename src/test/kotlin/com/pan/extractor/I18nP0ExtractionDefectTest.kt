package com.pan.extractor

import com.pan.extractor.ui.*

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

/**
 * 线上 bug（GitHub issues #35 / #36 / #37 / #38）回归测试。
 *
 *  - #35 `EnglishExtractor.accepts()` 未过滤 ATTRIBUTE → 启用英文后 `class="main container"` 被误提取改写
 *  - #36 属性值替换对单引号零转义 → 含撇号文案生成语法非法代码
 *  - #37 `generateKey` 未消毒 → key 含 `.`/`@`/`|` 时 vue-i18n 解析为嵌套路径导致翻译丢失
 *  - #38 `isConfirmedI18nGlobalChainCall` 按变量名而非引用解析 → 本地 `const i18n = {...}` 被漏提
 */
class I18nP0ExtractionDefectTest : BasePlatformTestCase() {

    private lateinit var originalIds: Set<String>

    override fun setUp() {
        super.setUp()
        // I18nSettings 是应用级单例、测试间共享；保存并在 tearDown 恢复，
        // 每测默认回到「仅中文」，避免 #35 的 setLanguageIds(["en"]) 泄漏到其它用例。
        originalIds = I18nSettings.getInstance().languageIds().toSet()
        I18nSettings.getInstance().setLanguageIds(listOf("zh"))
        myFixture.addFileToProject(
            "package.json",
            """{"name": "defect-test", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" }}""".trimIndent()
        )
    }

    override fun tearDown() {
        try {
            I18nSettings.getInstance().setLanguageIds(originalIds)
        } finally {
            super.tearDown()
        }
    }

    private fun configureFile(fileName: String, text: String): PsiFile {
        val psiFile = myFixture.addFileToProject(fileName, text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        return psiFile
    }

    // ── #35：启用英文后 ATTRIBUTE 上下文（class="main container"）不应被误提取 ──
    fun testClassAttributeWithLatinNotExtracted() {
        I18nSettings.getInstance().setLanguageIds(listOf("en"))
        val file = configureFile(
            "src/ClassAttr.vue",
            """
            <template>
                <div class="main container">Hello world</div>
            </template>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertFalse(
            "class=\"main container\" 属非文案属性，不应被提取；实际 extracted=${processor.extractedStrings}",
            processor.extractedStrings.containsValue("main container")
        )
        // TEXT 节点的英文整句仍应正常提取
        assertTrue("文本节点英文句子仍应提取", processor.extractedStrings.containsValue("Hello world"))
    }

    // ── #38：本地 const i18n = { t: … } 不是真实 i18n 实例，参数中文应被提取 ──
    fun testLocalObjectI18NReceiverNotTreatedAsInstance() {
        val file = configureFile(
            "src/LocalI18nVar.vue",
            """
            <template><div/></template>
            <script setup lang="ts">
            const i18n = { t: (s: string) => s.toUpperCase() }
            console.log(i18n.t('这是一段要被提取的本地对象中文'))
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "本地对象 i18n.t() 的参数应被提取，而非误判为已翻译；实际 extracted=${processor.extractedStrings}",
            processor.extractedStrings.containsValue("这是一段要被提取的本地对象中文")
        )
    }

    // ── #37：generateKey 应对保留分隔符（. @ |）消毒，避免 vue-i18n 嵌套路径误解析 ──
    fun testKeyContainingDotIsSanitized() {
        val file = configureFile("src/KeyDot.vue", "<template><div>{{ msg }}</div></template>")
        val element: com.intellij.psi.PsiElement = file
        assertEquals("句末句号应被清除以避免嵌套路径 key", "Hello world", I18nPsiTools.generateKey("Hello world.", element))
        assertEquals("@ 应被替换", "Q A", I18nPsiTools.generateKey("Q@A", element))
        assertEquals("| 应被替换", "A B", I18nPsiTools.generateKey("A|B", element))
        // 内部点须保留：编号列表/小数是真实文案内容，不能按路径分隔符整体替换
        // （见 I18nProcessorTest.testObjectPropertyMultilineStringWithEscapedNewline）
        assertEquals("3.14 的内部小数点在 key 中应原样保留（它是文案而非嵌套路径）", "请输入 3.14 以上的值", I18nPsiTools.generateKey("请输入 3.14 以上的值", element))
        assertEquals("3.14. 的句末点仍应被清除", "请输入 3.14", I18nPsiTools.generateKey("请输入 3.14. ", element))
        // 普通中文 key 不受影响
        assertEquals("中文 key 保持原样", "保存", I18nPsiTools.generateKey("保存", element))
    }

    // ── #36：属性值含单引号时生成合法代码（$t('…') 内转义撇号）────
    fun testAttributeWithApostropheProducesValidCode() {
        val file = configureFile(
            "src/ApostropheAttr.vue",
            """
            <template>
                <div title="唐's 工具提示">hover</div>
            </template>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "中文属性（含撇号）应被提取；实际 extracted=${processor.extractedStrings}",
            processor.extractedStrings.containsValue("唐's 工具提示")
        )
        processor.runWithUndo()
        val result = file.text
        // 期望转义撇号：:title="$t('唐\'s 工具提示')"
        val escapedForm = ":title=\"${processor.tFunctionName}('唐\\'s 工具提示')\""
        assertTrue("属性改写应转义撇号，生成合法代码；实际:\n$result", result.contains(escapedForm))
        // 不得再出现未转义的 $t('唐's …（撇号直接闭合字符串导致语法破坏）
        assertFalse("不得产生未转义 \$t('唐's …)；实际:\n$result", result.contains("\$t('唐's"))
    }
}