package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 5.4 — Vue Template PSI 覆盖。
 *
 * 验证文档点名的 Vue 模板形态中 `$t('xxx')` / `$ts('xxx')` 等调用应被识别为
 * 【已经国际化的 key】（进 existingStrings），而**不得**作为待提取硬编码中文进入
 * extractedStrings。一旦某个调用形态被误判为待提取，就会在 write-back 时被二次包裹，
 * 破坏语义（例如 `$t('$t('hello')')`）。
 *
 * 覆盖形态：
 * - interpolation：`{{ $t('hello') }}`
 * - directive：`:title="$t('hello')"`
 * - component prop：`<MyComponent :title="$t('hello')" />`
 * - Vue template comment：`<!-- {{ $t('hello') }} -->`（注释中的应整体忽略）
 * - slot：`<template #default>` 中的 `{{ $t('hello') }}`
 * - 普通中文文本（对照组）：应进入 extractedStrings
 */
class I18nVueTemplatePsiTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "vue-template-psi",
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

    // ── 5.4.a: interpolation `{{ $t('hello') }}` ─────────────────

    fun testInterpolationExistingKeyNotExtracted() {
        val p = configure(
            "src/Interp.vue",
            """
            <template>
                <div>{{ ${'$'}t('interp.hello') }}</div>
            </template>
            """.trimIndent()
        )
        assertTrue(
            "mustache 插值中的 ${'$'}t(key) 应进 existingStrings",
            p.existingStrings.containsKey("interp.hello")
        )
        assertFalse("插值中的已国际化调用不得被当作待提取", p.extractedStrings.isNotEmpty())
    }

    // ── 5.4.b: directive `:title="$t('hello')"` ──────────────────

    fun testDirectiveExistingKeyNotExtracted() {
        val p = configure(
            "src/Directive.vue",
            """
            <template>
                <div :title="${'$'}t('dir.title')">正文</div>
            </template>
            """.trimIndent()
        )
        assertTrue(
            ":title=\"${'$'}t(key)\" 应进 existingStrings",
            p.existingStrings.containsKey("dir.title")
        )
        // "正文"是真实中文硬编码，应被提取；但 $t('dir.title') 不能再次进入 extractedStrings
        assertTrue("中文正文应被提取", p.extractedStrings.containsValue("正文"))
        assertFalse("指令中的 \$t(key) 不得进入 extractedStrings", p.extractedStrings.containsKey("dir.title"))
    }

    // ── 5.4.c: component prop `<MyComponent :title="$t('hello')" />` ──

    fun testComponentPropExistingKeyNotExtracted() {
        val p = configure(
            "src/ComponentProp.vue",
            """
            <template>
                <MyComponent :title="${'$'}t('comp.title')" />
            </template>
            """.trimIndent()
        )
        assertTrue(
            "组件 prop :title=\"${'$'}t(key)\" 应进 existingStrings",
            p.existingStrings.containsKey("comp.title")
        )
        assertFalse("组件 prop 中的 \$t(key) 不得进入 extractedStrings", p.extractedStrings.containsKey("comp.title"))
    }

    // ── 5.4.d: template comment `<!-- {{ $t('hello') }} -->` ─────

    fun testTemplateCommentNotExtracted() {
        val p = configure(
            "src/TplComment.vue",
            """
            <template>
                <!-- {{ ${'$'}t('comment.key') }} -->
                <div>可见文本</div>
            </template>
            """.trimIndent()
        )
        // 注释本身必须被忽略：注释里的 $t('comment.key') 不应进入 existingStrings 或 extractedStrings
        assertFalse("注释中的 \$t(key) 不得进入 extractedStrings", p.extractedStrings.containsKey("comment.key"))
        // 但可见中文仍应被提取
        assertTrue("注释外可见中文应被提取", p.extractedStrings.containsValue("可见文本"))
    }

    // ── 5.4.e: slot `{{ $t('hello') }}` ──────────────────────────

    fun testSlotInterpolationExistingKeyNotExtracted() {
        val p = configure(
            "src/Slot.vue",
            """
            <template>
                <div>
                    <template #default>{{ ${'$'}t('slot.key') }}</template>
                </div>
            </template>
            """.trimIndent()
        )
        assertTrue(
            "slot 中 mustache 插值 \$t(key) 应进 existingStrings",
            p.existingStrings.containsKey("slot.key")
        )
        assertFalse("slot 插值中的 \$t(key) 不得进入 extractedStrings", p.extractedStrings.containsKey("slot.key"))
    }
}