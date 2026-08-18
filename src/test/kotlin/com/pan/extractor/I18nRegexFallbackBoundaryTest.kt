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

    // ── 3.2：非 i18n 实例的 `.t()` 不得被当成「已翻译」而漏提 ───────────

    fun testObjectMethodTChineseExtracted() {
        val p = collectFs(
            "src/objchinese.ts",
            """
            const obj = { t: (s: string) => s };
            const r = obj.t('中文文案');
            """.trimIndent()
        )
        assertTrue("obj.t('中文文案') 是普通方法调用，其中的中文应被提取（3.2）",
            p.extractedStrings.containsValue("中文文案"))
        assertFalse("obj.t 不应被当作 i18n 已有 key", p.existingStrings.containsKey("中文文案"))
    }

    fun testChainedNonI18nTChineseExtracted() {
        val p = collectFs(
            "src/chainchinese.ts",
            """
            namespace ns {
                export function t(s: string) { return s; }
            }
            const r = ns.t('嵌套中文');
            """.trimIndent()
        )
        assertTrue("ns.t('嵌套中文') 不是 i18n 全局实例，中文应被提取（3.2）",
            p.extractedStrings.containsValue("嵌套中文"))
        assertFalse("ns.t 不应进 existingStrings", p.existingStrings.containsKey("嵌套中文"))
    }

    fun testConfirmedI18nChainStillExisting() {
        val p = collectFs(
            "src/i18nchain.ts",
            """
            import i18n from '@/locales/i18n';
            const msg = i18n.global.t('nav.home');
            """.trimIndent()
        )
        assertTrue("i18n.global.t('nav.home') 仍是已确认的 i18n 调用，应进 existingStrings",
            p.existingStrings.containsKey("nav.home"))
        assertFalse("i18n 全局链式 key 不得进 extractedStrings", p.extractedStrings.containsKey("nav.home"))
    }

    // ── 3.2c symbol collision：本地 const/let 函数变量覆盖同名 t/tc ────
    // 见 PROJECT_ANALYSIS §2：`const t = fn; t('中文')` 是本文件普通函数变量，
    // 应被当成普通函数调用，参数中的中文仍应提取（不得漏提），也不能进 existingStrings。

    fun testLocalConstFunctionVarTChineseExtracted() {
        val p = collectFs(
            "src/constfnvar.ts",
            """
            const t = (s: string) => s.trim();
            const r = t('本地变量函数中文');
            """.trimIndent()
        )
        assertTrue("const t = …; t('本地变量函数中文') 是普通函数调用，中文应被提取（symbol collision）",
            p.extractedStrings.containsValue("本地变量函数中文"))
        assertFalse("本地变量函数 t 不应进 existingStrings", p.existingStrings.containsKey("本地变量函数中文"))
    }

    fun testLocalLetFunctionVarTcChineseExtracted() {
        val p = collectFs(
            "src/letfuncvar.ts",
            """
            let tc = function (s: string) { return s; };
            const r = tc('本地函数变量中文');
            """.trimIndent()
        )
        assertTrue("let tc = function…; tc('本地函数变量中文') 中文应被提取（symbol collision）",
            p.extractedStrings.containsValue("本地函数变量中文"))
        assertFalse("本地变量函数 tc 不应进 existingStrings", p.existingStrings.containsKey("本地函数变量中文"))
    }

    // 反向保证：真实 useI18n 解构的裸 t 仍应判定为已翻译 key，不被上述逻辑误伤
    fun testDestructuredRealI18nTBareNameStillExisting() {
        val p = collectFs(
            "src/usei18nbare.ts",
            """
            import { useI18n } from 'vue-i18n';
            const { t } = useI18n();
            const msg = t('nav.about');
            """.trimIndent()
        )
        assertTrue("useI18n 解构的裸 t('nav.about') 应仍是 i18n 调用", p.existingStrings.containsKey("nav.about"))
        assertFalse("解构裸 t 的 key 不得进 extractedStrings", p.extractedStrings.containsKey("nav.about"))
    }

    // ── §2：import alias reference resolve ────────────────────────────
    // 见 PROJECT_ANALYSIS §2「支持 import alias reference resolve」：
    // `import { t as translate } from 'react-i18next'; translate('key')` 中名字被 alias 改成
    // translate，但其来源是 i18n 框架 → 仍应识别为已翻译 key（不重提取、不进 extracted）。

    fun testImportAliasRenameStillExisting() {
        val p = collectFs(
            "src/alias.ts",
            """
            import { t as translate } from 'react-i18next';
            const msg = translate('nav.home');
            """.trimIndent()
        )
        assertTrue("import { t as translate } from 'react-i18next'; translate('nav.home') 应识别为 i18n",
            p.existingStrings.containsKey("nav.home"))
        assertFalse("别名 i18n key 不得进 extractedStrings", p.extractedStrings.containsKey("nav.home"))
    }

    fun testImportFrameworkNameStillExisting() {
        val p = collectFs(
            "src/tfromi18next.ts",
            """
            import { t } from 'i18next';
            const msg = t('greeting');
            """.trimIndent()
        )
        assertTrue("import { t } from 'i18next'; t('greeting') 应识别为 i18n",
            p.existingStrings.containsKey("greeting"))
        assertFalse("i18next 的 t key 不得进 extractedStrings", p.extractedStrings.containsKey("greeting"))
    }

    fun testUseTranslationDestructuredBareTStillExisting() {
        val p = collectFs(
            "src/reactbare.ts",
            """
            import { useTranslation } from 'react-i18next';
            const { t } = useTranslation();
            const msg = t('react.welcome');
            """.trimIndent()
        )
        assertTrue("useTranslation 解构的裸 t('react.welcome') 应识别为 i18n",
            p.existingStrings.containsKey("react.welcome"))
        assertFalse("解构裸 t 的 react key 不得进 extractedStrings", p.extractedStrings.containsKey("react.welcome"))
    }

    // ── §2：destructured translation function 语义判断（非 i18n 来源不得漏提） ──
    // 旧实现里"裸名 t 一律视为已翻译"会把工具模块/普通 hook 解构的 t 也误判为 i18n，导致漏提。
    // 现在只有解析到"i18n 框架 import 或已知 i18n hook 解构"才算已翻译，否则中文字符正常提取。

    fun testNonI18nImportedTBareNameExtracted() {
        val p = collectFs(
            "src/utilimport.ts",
            """
            import { t } from './utils';
            const r = t('工具函数中文');
            """.trimIndent()
        )
        assertTrue("import { t } from './utils'; t('工具函数中文') 是非 i18n 普通函数，中文应被提取（§2 修漏提）",
            p.extractedStrings.containsValue("工具函数中文"))
        assertFalse("非 i18n 工具 import 的 t 不应视为已翻译 key",
            p.existingStrings.containsKey("工具函数中文"))
    }

    fun testNonI18nHookDestructuredTBareNameExtracted() {
        val p = collectFs(
            "src/otherhook.ts",
            """
            const { t } = someOtherHook();
            const r = t('其它hook中文');
            """.trimIndent()
        )
        // 【三态 UNKNOWN】`const { t } = someOtherHook()`：someOtherHook 无法证明来自已知 i18n hook
        //（名字是弱特征，不是语义证明）→ 调用归 UNKNOWN → 参数既不提取也不改写（零误改）。
        // 旧模型（名字兜底 + 非已知 hook 即提取）的行为已废弃：宁可漏一次提取机会，也不破坏用户代码。
        assertFalse("三态 UNKNOWN：无法证明来源的 hook 解构 t，参数保守跳过（不提取）",
            p.extractedStrings.containsValue("其它hook中文"))
        assertFalse("三态 UNKNOWN：也不把无法证明的调用声称成已翻译 key",
            p.existingStrings.containsKey("其它hook中文"))
    }

    fun testImportAliasFromNonI18nModuleExtracted() {
        val p = collectFs(
            "src/aliasutil.ts",
            """
            import { t as translate } from './helper';
            const r = translate('别名工具函数中文');
            """.trimIndent()
        )
        assertTrue("import { t as translate } from './helper'; translate('别名工具函数中文') 非 i18n，中文应被提取",
            p.extractedStrings.containsValue("别名工具函数中文"))
        assertFalse("非 i18n 别名不应视为已翻译 key",
            p.existingStrings.containsKey("别名工具函数中文"))
    }
}