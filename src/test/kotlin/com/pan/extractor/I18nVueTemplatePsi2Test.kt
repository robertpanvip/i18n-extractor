package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 5.4 — Vue Template PSI 覆盖（补充形态）。
 *
 * 与 [I18nVueTemplatePsiTest] 同一思路：`$t('xxx')` 等已国际化调用应进
 * [I18nProcessor.existingStrings]，而**不得**被当作待提取硬编码中文进入
 * [I18nProcessor.extractedStrings]。仅在确实存在硬编码中文时才进 extractedStrings。
 *
 * 本文件补充的形态：
 * - script setup + template 并存（script 中文 → extractedStrings；template \$t → existingStrings）
 * - 嵌套表达式（三目）：`{{ cond ? $t('a') : $t('b') }}` → a/b 均进 existingStrings
 * - 多行 directive：`:title="\n$t('ml.key')\n"`
 * - template literal 形态（反引号）：`{{ $t(`tpl.key`) }}`（走 collectTKeysFromRawText）
 * - 转义花括号对照组：`{{ '{{ not i18n }}' }}` 是字面文本，不应被误判为 t 调用
 */
class I18nVueTemplatePsi2Test : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "vue-template-psi-2",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0"
              }
            }
            """.trimIndent()
        )
    }

    private fun configure(fileName: String, text: String): I18nProcessor {
        val file = myFixture.addFileToProject(fileName, text.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val processor = I18nProcessor(project, file)
        processor.collect()
        return processor
    }

    // ── 5.4.f: script setup + template 同时存在 ──────────────────

    fun testScriptSetupPlusTemplateExistingAndExtracted() {
        val p = configure(
            "src/ScriptPlusTemplate.vue",
            """
            <template>
                <div>{{ ${'$'}t('template.key') }}</div>
            </template>
            <script setup lang="ts">
            const msg = "脚本中文"
            </script>
            """.trimIndent()
        )
        assertTrue(
            "template 中 ${'$'}t('template.key') 应进 existingStrings",
            p.existingStrings.containsKey("template.key")
        )
        assertFalse(
            "template 中已国际化的 key 不得进入 extractedStrings",
            p.extractedStrings.containsKey("template.key")
        )
        assertTrue(
            "script setup 里的硬编码中文应进 extractedStrings",
            p.extractedStrings.containsValue("脚本中文")
        )
    }

    // ── 5.4.g: 嵌套表达式（三目）{{ cond ? $t('a') : $t('b') }} ───

    fun testNestedTernaryBothBranchesExistingNotExtracted() {
        val p = configure(
            "src/NestedTernary.vue",
            """
            <template>
                <div>{{ cond ? ${'$'}t('a') : ${'$'}t('b') }}</div>
            </template>
            """.trimIndent()
        )
        assertTrue(
            "三目 true 分支 ${'$'}t('a') 应进 existingStrings",
            p.existingStrings.containsKey("a")
        )
        assertTrue(
            "三目 false 分支 ${'$'}t('b') 应进 existingStrings",
            p.existingStrings.containsKey("b")
        )
        assertFalse("三目分支里的 \$t(key) 都不得进入 extractedStrings", p.extractedStrings.containsKey("a"))
        assertFalse("三目分支里的 \$t(key) 都不得进入 extractedStrings", p.extractedStrings.containsKey("b"))
        assertEquals("无硬编码中文，extractedStrings 应为空", 0, p.extractedStrings.size)
    }

    // ── 5.4.h: 多行 directive `:title="\n $t('ml.key')\n"` ────────

    fun testMultilineDirectiveExistingNotExtracted() {
        val p = configure(
            "src/MultilineDirective.vue",
            """
            <template>
                <div :title="
                  ${'$'}t('ml.key')
                "></div>
            </template>
            """.trimIndent()
        )
        assertTrue(
            "多行 :title=\"...\$t('ml.key')...\" 应进 existingStrings",
            p.existingStrings.containsKey("ml.key")
        )
        assertFalse("多行 directive 中的 \$t(key) 不得进入 extractedStrings", p.extractedStrings.containsKey("ml.key"))
    }

    // ── 5.4.i: template literal（反引号）形态 {{ $t(`tpl.key`) }} ──

    fun testBacktickTemplateLiteralExisting() {
        val p = configure(
            "src/BacktickTpl.vue",
            """
            <template>
                <div>{{ ${'$'}t(`tpl.key`) }}</div>
            </template>
            """.trimIndent()
        )
        // collectExistingTKeysFromTemplate 对 mustache 原始文本走 collectTKeysFromRawText，
        // T_CALL_PATTERN 明确支持反引号 ([`"'] ... \1)，因此 `tpl.key` 应被识别为已国际化 key。
        assertTrue(
            "反引号模板 ${'$'}t(`tpl.key`) 应进 existingStrings",
            p.existingStrings.containsKey("tpl.key")
        )
        assertFalse("反引号模板中的 \$t(key) 不得进入 extractedStrings", p.extractedStrings.containsKey("tpl.key"))
    }

    // ── 5.4.j: 转义花括号对照组（非翻译调用）──────────────────────

    fun testLiteralBracesNotMisjudgedAsTCall() {
        val p = configure(
            "src/BracesLiteral.vue",
            """
            <template>
                <div>{{ '{{ not i18n }}' }}</div>
            </template>
            """.trimIndent()
        )
        // 这是普通字符串字面量，没有任何 $t(...) / i18n(.global).t(...) 调用，
        // 不应被误判为「已国际化的 t 调用」进入 existingStrings。
        assertEquals(
            "字面花括号字符串不是翻译调用，existingStrings 应为空, got: ${p.existingStrings}",
            0,
            p.existingStrings.size
        )
        // 同样不含目标语言（中文），也不应进入 extractedStrings。
        assertEquals(
            "字面花括号字符串不含中文，extractedStrings 应为空, got: ${p.extractedStrings}",
            0,
            p.extractedStrings.size
        )
    }
}