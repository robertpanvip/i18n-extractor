package com.pan.extractor

import com.pan.extractor.bootstrap.I18nBootstrapSupport
import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.lang.EnglishExtractor
import com.pan.extractor.lang.FrenchExtractor
import com.pan.extractor.lang.GermanExtractor
import com.pan.extractor.lang.ItalianExtractor
import com.pan.extractor.lang.LanguageExtractor
import com.pan.extractor.lang.LanguageRegistry
import com.pan.extractor.lang.PortugueseExtractor
import com.pan.extractor.lang.SiteKind
import com.pan.extractor.lang.SpanishExtractor
import com.pan.extractor.locate.EntryFileLocator
import com.pan.extractor.messages.LocaleMessages
import com.pan.extractor.project.I18nPsiTools
import com.pan.extractor.project.Util
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.strategy.ReactIntlStrategy
import com.pan.extractor.strategy.SolidI18nStrategy
import com.pan.extractor.strategy.VueI18nStrategy
import com.pan.extractor.ui.*

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Phase 6：完整生命周期测试（PROJECT_ANALYSIS §8 / §9 / §14）。
 *
 * 目标：验证「Rewrite → Reparse → 重新获取 PSI → 再次 Rewrite」在 Vue 注入 PSI 场景下正确：
 *  - 第一次改写后源码中的 `\$t('key')` 能被二次 collect 识别为「已翻译」，不再重复提取；
 *  - 注入 PSI（{{ }} mustache）内的字符串改写后不会出现双重 `\$t`；
 *  - 同一文件内多个 site / 嵌套表达式的 pointer 连续改写稳定（无悬空、无截断）。
 *
 * 全部为只读断言 + WriteCommandAction 改写，不改动任何主代码。
 */
class VueLifecycleTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // 与 VueI18nProcessorTest 同源：Vue 项目 + vue-i18n 依赖 + createI18n 实例文件，
        // 保证框架检测与注入路径一致。
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "vue-test-project",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0"
              }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import { createI18n } from 'vue-i18n';

            const messages = {
              zh: {},
              en: {}
            };

            export const i18n = createI18n({
              legacy: false,
              globalInjection: true,
              locale: 'zh',
              messages,
            });
            """.trimIndent()
        )
    }

    private fun configureFile(fileName: String, text: String): PsiFile {
        return if (fileName.contains('/')) {
            val psiFile = myFixture.addFileToProject(fileName, text)
            myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
            psiFile
        } else {
            myFixture.configureByText(fileName, text)
        }
    }

    /** 去掉所有空白（空格/换行/制表符）后再做子串判断，容忍 PSI 排版差异。 */
    private fun String.containsIgnoringWs(other: String): Boolean =
        this.replace("\\s+".toRegex(), "").contains(other.replace("\\s+".toRegex(), ""))

    /** 统计 [target] 在 [this] 中出现的次数（正则字面量，含 `\$t('key')` 形态）。 */
    private fun String.occurrencesRegex(target: String): Int {
        val escaped = Regex.escape(target)
        return Regex(escaped).findAll(this).count()
    }

    /**
     * §9：Vue 注入 PSI 完整生命周期 —— {{ }} 内两个中文，
     * 第一遍改写为 `\$t(...)`，第二遍（同一 PsiFile，reparse 后）应识别为「已翻译」。
     */
    fun testVueTernaryRewriteThenReparseThenRecognizeAsTranslated() {
        val file = configureFile(
            "src/Lifecycle.vue",
            """
            <template>
                <div>{{ isVip ? '会员专享' : '普通用户' }}</div>
            </template>
            """.trimIndent()
        )

        // ── 第一遍：collect + apply ──
        val p1 = I18nProcessor(project, file)
        p1.collect()
        assertEquals(
            "第一遍应提取 2 个字符串，got: ${p1.analyzer.extractedStrings}",
            2, p1.analyzer.extractedStrings.size
        )
        p1.runWithUndo()

        val afterFirst = file.text
        assertTrue(
            "第一遍后应改写为 \$t('会员专享')，got:\n$afterFirst",
            afterFirst.containsIgnoringWs("${'$'}t('会员专享')")
        )
        assertTrue(
            "第一遍后应改写为 \$t('普通用户')，got:\n$afterFirst",
            afterFirst.containsIgnoringWs("${'$'}t('普通用户')")
        )
        // 不允许出现双重 \$t（\$t(cond ? \$t(...) : \$t(...))）
        assertEquals(
            "'会员专享' 的 \$t 应恰好出现一次，got:\n$afterFirst",
            1, afterFirst.occurrencesRegex("${'$'}t('会员专享')")
        )

        // ── 第二遍：同一 PsiFile 重新 collect（文件已被 WriteCommandAction 改写，PSI 已重解析）──
        val p2 = I18nProcessor(project, file)
        p2.collect()
        assertTrue(
            "第二遍不应再提取（已翻译），got: ${p2.analyzer.extractedStrings}",
            p2.analyzer.extractedStrings.isEmpty()
        )
        assertTrue(
            "第二遍应识别 '会员专享' 为已存在翻译，got: ${p2.analyzer.existingStrings}",
            p2.analyzer.existingStrings.containsValue("会员专享")
        )
        assertTrue(
            "第二遍应识别 '普通用户' 为已存在翻译，got: ${p2.analyzer.existingStrings}",
            p2.analyzer.existingStrings.containsValue("普通用户")
        )

        // 再 apply 一次也必须是幂等的：源码不发生重复改写
        p2.runWithUndo()
        val afterSecond = file.text
        assertEquals(
            "第二遍 apply 后 '会员专享' 的 \$t 仍应恰好一次，got:\n$afterSecond",
            1, afterSecond.occurrencesRegex("${'$'}t('会员专享')")
        )
    }

    /**
     * §8：同一文件多个 sibling pointer 连续 rewrite（WriteCommandAction 内逐个替换）稳定，
     * 每个站点独立生成 key，互不串扰、不丢。
     */
    fun testVueMultipleSiblingSitesRewriteStable() {
        val file = configureFile(
            "src/Multi.vue",
            """
            <template>
                <div>
                    <span>第一个</span>
                    <span>第二个</span>
                    <span>第三个</span>
                </div>
            </template>
            """.trimIndent()
        )

        val p = I18nProcessor(project, file)
        p.collect()
        assertEquals(
            "应提取 3 个字符串，got: ${p.analyzer.extractedStrings}",
            3, p.analyzer.extractedStrings.size
        )
        p.runWithUndo()

        // 模板纯文本节点的替换使用反引号模板字符串：{{ $t(`key`) }}
        val out = file.text
        for (text in listOf("第一个", "第二个", "第三个")) {
            assertTrue(
                "$text 应被改写为 \$t(`$text`)，got:\n$out",
                out.containsIgnoringWs("${'$'}t(`$text`)")
            )
            assertEquals(
                "$text 的 \$t 应恰好出现一次，got:\n$out",
                1, out.occurrencesRegex("${'$'}t(`$text`)")
            )
        }
    }

    /**
     * §9：嵌套注入表达式（三目内层多字符串）单遍改写后，二次 collect 稳定。
     * 覆盖 injected PSI 重建后再查找的指针生命周期。
     */
    fun testVueMustacheStringRewriteThenReparseStable() {
        val file = configureFile(
            "src/Nested.vue",
            """
            <template>
                <div>{{ count > 0 ? '启用中' : '未启用' }}</div>
            </template>
            """.trimIndent()
        )

        val p1 = I18nProcessor(project, file)
        p1.collect()
        assertTrue(
            "应提取 '启用中'，got: ${p1.analyzer.extractedStrings}",
            p1.analyzer.extractedStrings.containsValue("启用中")
        )
        p1.runWithUndo()

        val first = file.text
        assertTrue(
            "'启用中' 应被改写为 \$t，got:\n$first",
            first.containsIgnoringWs("${'$'}t('启用中')")
        )

        // 二次 collect：不再重复提取，且不产生源码漂移
        val p2 = I18nProcessor(project, file)
        p2.collect()
        p2.runWithUndo()
        val second = file.text
        assertEquals(
            "二次 apply 后源码应与第一次 apply 后一致（幂等），\n一次:\n$first\n\n二次:\n$second",
            first, second
        )
    }

    /**
     * §C1：Vue directive(`v-t`) + interpolation(`{{ }}`) 混合生命周期。
     * 同一模板内同时出现：指令属性 `v-t="'中文'"` 与 mustache 插值 `{{ '中文' }}`。
     * 第一遍：两者各自被改写一次；第二遍（同一 PsiFile reparse 后）均识别为已翻译，
     * 不重复提取、不产生双重 `\$t` 嵌套。
     */
    fun testVueDirectiveAndInterpolationMixedLifecycle() {
        val file = configureFile(
            "src/MixedDirectives.vue",
            """
            <template>
                <div>
                    <span v-t="'提示信息'"></span>
                    <span>{{ '欢迎回来' }}</span>
                </div>
            </template>
            """.trimIndent()
        )

        // ── 第一遍：collect + apply ──
        val p1 = I18nProcessor(project, file)
        p1.collect()
        assertEquals(
            "第一遍应提取 2 个字符串（指令 + 插值各一），got: ${p1.analyzer.extractedStrings}",
            2, p1.analyzer.extractedStrings.size
        )
        p1.runWithUndo()

        val afterFirst = file.text
        assertTrue(
            "v-t 指令值应改写为 \$t(key)，got:\n$afterFirst",
            afterFirst.containsIgnoringWs("${'$'}t('提示信息')")
        )
        assertTrue(
            "mustache 插值应改写为 \$t(key)，got:\n$afterFirst",
            afterFirst.containsIgnoringWs("${'$'}t('欢迎回来')")
        )
        // 两段文本各自的 \$t 都只能出现一次，禁止双重嵌套。
        assertEquals(
            "'提示信息' 的 \$t 应恰好出现一次，got:\n$afterFirst",
            1, afterFirst.occurrencesRegex("${'$'}t('提示信息')")
        )
        assertEquals(
            "'欢迎回来' 的 \$t 应恰好出现一次，got:\n$afterFirst",
            1, afterFirst.occurrencesRegex("${'$'}t('欢迎回来')")
        )

        // ── 第二遍：同一 PsiFile 重新 collect（文件已被改写，PSI 已重解析）──
        val p2 = I18nProcessor(project, file)
        p2.collect()
        assertTrue(
            "第二遍不应再提取任何字符串（均已翻译），got: ${p2.analyzer.extractedStrings}",
            p2.analyzer.extractedStrings.isEmpty()
        )
        assertTrue(
            "第二遍应识别 '提示信息' 为已有翻译，got: ${p2.analyzer.existingStrings}",
            p2.analyzer.existingStrings.containsValue("提示信息")
        )
        assertTrue(
            "第二遍应识别 '欢迎回来' 为已有翻译，got: ${p2.analyzer.existingStrings}",
            p2.analyzer.existingStrings.containsValue("欢迎回来")
        )

        // 再 apply 一次必须幂等：源码不再发生重复改写。
        p2.runWithUndo()
        val afterSecond = file.text
        assertEquals(
            "二次 apply 后 '提示信息' 的 \$t 仍应恰好一次，got:\n$afterSecond",
            1, afterSecond.occurrencesRegex("${'$'}t('提示信息')")
        )
        assertEquals(
            "二次 apply 后 '欢迎回来' 的 \$t 仍应恰好一次，got:\n$afterSecond",
            1, afterSecond.occurrencesRegex("${'$'}t('欢迎回来')")
        )
    }
}