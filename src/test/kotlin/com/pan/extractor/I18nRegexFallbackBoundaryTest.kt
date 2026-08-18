package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 3.2 — 锁定「JS/TS 语义分析走 PSI，Regex 仅作 Vue 模板 fallback」的边界。
 *
 * 这些用例直接验证 [I18nProcessor] 对 JS/TS 的主语义分析由 PSI（[JSCallExpression] /
 * [JSReferenceExpression] / [JSStringTemplateExpression]）驱动，Regex 只作为 Vue
 * mustache 无法被注入 JS 解析时的兜底（collectTKeysFromRawText）。
 *
 * 覆盖的 tricky 形态：
 *  - 多行 `$t(\n 'key'\n)` → existingStrings
 *  - 换行链式 `i18n\n.global\n.t('key')` → existingStrings
 *  - 模板字符串插值 `` $t(`a ${x}`) `` → key 不可确定，不进入 existingStrings，也不被当硬编码提取
 *  - 硬编码中文 → extractedStrings
 */
class I18nRegexFallbackBoundaryTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "regex-fallback-boundary",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0"
              }
            }
            """.trimIndent()
        )
    }

    private fun collectFs(fileName: String, text: String): I18nProcessor {
        val file = myFixture.addFileToProject(fileName, text.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val processor = I18nProcessor(project, file)
        processor.collect()
        return processor
    }

    // ── 多行 $t (\n 'key' \n) → PSI existing ───────────────────────────

    fun testMultilineExistingCallViaPsi() {
        val p = collectFs(
            "src/multiline.ts",
            """
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            const msg = t(
                'toast.title'
            );
            """.trimIndent()
        )
        assertTrue("多行 t(\n 'toast.title'\n) 应识别为已有 key", p.existingStrings.containsKey("toast.title"))
        assertFalse("已有 key 不得进入 extractedStrings", p.extractedStrings.containsKey("toast.title"))
    }

    // ── 换行链式 i18n\n.global\n.t('key') → PSI existing ───────────────

    fun testChainedMultilineExistingCallViaPsi() {
        val p = collectFs(
            "src/chained.ts",
            """
            import i18n from '@/locales/i18n';
            const msg = i18n
                .global
                .t('nav.home');
            """.trimIndent()
        )
        assertTrue("换行链式 i18n.global.t('nav.home') 应识别为已有 key", p.existingStrings.containsKey("nav.home"))
        assertFalse("链式已有 key 不得进入 extractedStrings", p.extractedStrings.containsKey("nav.home"))
    }

    // ── 模板字符串插值 $t(`a ${x}`) → key 不可确定 ─────────────────────

    fun testTemplateLiteralWithInterpolationNotAKey() {
        val p = collectFs(
            "src/backticks.ts",
            """
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            const a = t(`dynamic.${'$'}{suffix}`);
            const b = "硬编码中文";
            """.trimIndent()
        )
        // `${suffix}` 插值 → key 不确定，不作为已有 key
        assertFalse("带插值的模板字符串不应被当已有 key", p.existingStrings.keys.any { it.contains("dynamic.") })
        assertFalse("带插值的模板字符串不应进入 extractedStrings", p.extractedStrings.containsKey("dynamic."))
        // 同文件硬编码中文仍应被提取（证明 PSI 主提取路径正常）
        assertTrue("同文件硬编码中文应进入 extractedStrings", p.extractedStrings.containsValue("硬编码中文"))
    }

    // ── 跨行反引号 raw-text fallback 仍能收集已有 key（Vue mustache）────

    fun testVueMustacheBacktickRawTextFallback() {
        val p = collectFs(
            "src/Tpl.vue",
            """
            <template>
                <div>{{ `${'$'}t('wrap.tick')` }}</div>
            </template>
            """.trimIndent()
        )
        assertTrue("Vue mustache backtick 中的 ${'$'}t('wrap.tick') 应进 existingStrings", p.existingStrings.containsKey("wrap.tick"))
    }
}