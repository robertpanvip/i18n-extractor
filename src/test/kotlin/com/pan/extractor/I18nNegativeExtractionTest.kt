package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 4.3 — Negative Extraction Test。
 *
 * 以下内容提取器应当产生 **0 extraction**（不进入 extractedStrings）：
 * - 字符串字面量：`const text = "$t('hello')"`（是普通字符串，不是真调用）
 * - 行注释 / 块注释 / Vue 注释：`// $t('hello')`、`/* $t('hello') */`、`<!-- $t('hello') -->`
 * - 模板字符串插值之外的文字：`` const x = `text $t('hello')` ``
 * - 函数引用（未调用）：`const fn = $t`
 * - 普通变量（不识别的名字）
 * - 非 translation function 的 `.t()`：`foo.t('hello')`、`obj.t('x')`
 * - JSX / Vue attribute 里的普通文本
 *
 * 断言统一：extractedStrings 为空（已翻译 / 非文案 / 纯英文都不算待提取）。
 */
class I18nNegativeExtractionTest : BasePlatformTestCase() {

    private fun makeProcessor(fileName: String, text: String): I18nProcessor {
        val psi = myFixture.addFileToProject(fileName, text.trimIndent())
        myFixture.configureFromExistingVirtualFile(psi.virtualFile)
        val processor = I18nProcessor(project, psi)
        processor.collect()
        return processor
    }

    private fun assertZeroExtractions(processor: I18nProcessor, msg: String) {
        assertTrue("期望 0 提取 —— $msg，实际=${processor.extractedStrings}", processor.extractedStrings.isEmpty())
    }

    // ── 字符串字面量：非调用形态的 $t 字样 ─────────────────────────

    fun testStringLiteralDollarTText() {
        val p = makeProcessor("src/neg1.ts", """const text = "${'$'}t('hello')";""")
        assertZeroExtractions(p, "普通字符串里的 \$t('hello') 不是真调用")
    }

    fun testStringLiteralSingleQuotedTText() {
        val p = makeProcessor("src/neg2.ts", """const text = '${'$'}t("world")';""")
        assertZeroExtractions(p, "单引号字符串里的 \$t(\"world\")")
    }

    fun testRegexDollarTInString() {
        val p = makeProcessor("src/neg3.ts", """const re = "${'$'}t(name)";""")
        assertZeroExtractions(p, "字符串内的 \$t(name) 无引号参数不是调用")
    }

    // ── 注释：不应从注释中提取 ───────────────────────────────────

    fun testDoubleSlashComment() {
        val p = makeProcessor("src/neg4.ts", """// ${'$'}t('hello')""")
        assertZeroExtractions(p, "行注释里的 \$t('hello')")
    }

    fun testBlockComment() {
        val p = makeProcessor("src/neg5.ts", """/* ${'$'}t('hello') */""")
        assertZeroExtractions(p, "块注释里的 \$t('hello')")
    }

    fun testBlockCommentMultiline() {
        val p = makeProcessor(
            "src/neg6.ts",
            """
            /*
            const x = ${'$'}t('hello');
            */
            """.trimIndent()
        )
        assertZeroExtractions(p, "多行块注释里的 \$t('hello')")
    }

    fun testVueHtmlComment() {
        val p = makeProcessor(
            "src/neg7.vue",
            """
            <template>
                <!-- ${'$'}t('hello') -->
                <div>普通</div>
            </template>
            """.trimIndent()
        )
        assertZeroExtractions(p, "Vue 模板注释里的 \$t('hello') —— 普通中文也应被提取但 \$t 在注释中")
    }

    // ── 模板字符串：${…} 内的伪调用 ──────────────────────────────

    fun testTemplateLiteralWrappedTText() {
        val p = makeProcessor("src/neg8.ts", "const x = `text ${'$'}t('hello')`;")
        assertZeroExtractions(p, "模板字符串文本里的 \$t('hello') 是英文非文案")
    }

    fun testTemplateInterpolationNotACall() {
        val p = makeProcessor("src/neg9.ts", "const y = `val-${'$'}{name};`;")
        assertZeroExtractions(p, "模板字符串里普通插值不是 \$t 调用且为英文")
    }

    // ── 函数引用（未调用）───────────────────────────────────────

    fun testAssignFunctionReference() {
        val p = makeProcessor("src/neg10.ts", """const fn = ${'$'}t;""")
        assertZeroExtractions(p, "\$t 作为函数引用赋给变量，未调用")
    }

    fun testPassFunctionAsArg() {
        val p = makeProcessor("src/neg11.ts", """someFunction(${'$'}t);""")
        assertZeroExtractions(p, "\$t 作为实参传递但不是…调用（此处是引用，非 \$t('') 调用）")
    }

    fun testObjectPropertyValueIsFunctionRef() {
        val p = makeProcessor("src/neg12.ts", """const o = { handler: ${'$'}t };""")
        assertZeroExtractions(p, "对象属性值绑定 \$t 引用")
    }

    // ── 普通变量 / 非翻译标识符 ──────────────────────────────────

    fun testPlainVariableAssignment() {
        val p = makeProcessor("src/neg13.ts", """const title = '只是标题';""")
        assertZeroExtractions(p, "普通英文字符串变量不提取")
    }

    fun testNonTranslationIdentifier() {
        val p = makeProcessor("src/neg14.ts", """const msg = message('hello');""")
        assertZeroExtractions(p, "message() 不是 i18n 翻译调用且参数为英文")
    }

    // ── 非 translation function 的 `.t()` ─────────────────────────

    fun testObjectMethodT() {
        val p = makeProcessor("src/neg15.ts", """const r = obj.t('hello');""")
        assertZeroExtractions(p, "obj.t() 不是翻译函数")
    }

    fun testUnknownQualifierT() {
        val p = makeProcessor("src/neg16.ts", """const r = foo.bar.t('hello');""")
        assertZeroExtractions(p, "foo.bar.t() 不是 i18n 翻译函数")
    }

    fun testHttpUrlInString() {
        val p = makeProcessor(
            "src/neg17.ts",
            "const url = 'https://example.com/path?id=1';"
        )
        assertZeroExtractions(p, "URL 字符串不提取（既非中文也非 \$t 调用）")
    }

    // ── JSX / Vue attribute 普通文本 ────────────────────────────

    fun testJsxAttributeEnglish() {
        val p = makeProcessor(
            "src/neg18.tsx",
            """
            export function C() {
                return <div title="hello world">content</div>;
            }
            """.trimIndent()
        )
        assertZeroExtractions(p, "JSX attribute/文本为英文不提取")
    }

    fun testVueAttributeEnglish() {
        val p = makeProcessor(
            "src/neg19.vue",
            """
            <template>
                <div title="hello world">text</div>
            </template>
            """.trimIndent()
        )
        assertZeroExtractions(p, "Vue attribute 为英文不提取")
    }

    fun testAlreadyTranslatedExistingKeyCallsNotExtracted() {
        // 已经是 i18n 调用（翻译态）的 key 不进入 extractedStrings
        val p = makeProcessor(
            "src/neg20.vue",
            """
            <script setup lang="ts">
            import { useI18n } from 'vue-i18n';
            const { t: ${'$'}t } = useI18n();
            const msg = ${'$'}t('already.key');
            </script>
            """.trimIndent()
        )
        assertTrue("已有 \$t('key') 调用应进入 existingStrings", p.existingStrings.containsKey("already.key"))
        assertEquals("已翻译 key 不应被当作待提取硬编码", 0, p.extractedStrings.size)
    }

    fun testTranslationResourceFileSkip() {
        // 语言包文件：即便含中文也不提取（直接短路）
        val psi = myFixture.addFileToProject(
            "src/locales/en-US.ts",
            """
            export default {
              hello: 'hello',
              welcome: 'welcome',
            };
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(psi.virtualFile)
        val processor = I18nProcessor(project, psi)
        processor.collect()
        assertEquals("翻译资源文件跳过提取", 0, processor.extractedStrings.size)
    }
}