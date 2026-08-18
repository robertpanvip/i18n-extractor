package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 3.3 / 5.3 — i18n 初始化调用检测的 PSI 级防护。
 *
 * 文本级 `contains`/正则（即便先剥离注释）仍会把【字符串字面量】里的字样误判为真实初始化调用：
 *   `const desc = "createI18n()"` 里的 createI18n( 不是调用，
 *   `const x = "i18n.init()"` 里的 i18n.init( 不是调用。
 *
 * [I18nInstanceLocator.containsI18nInitCall] 是 PSI 版本：只在可执行节点
 * （JSCallExpression / JSReferenceExpression）上判断，天然排除注释与字符串字面量里的字样。
 * 生产路径 findVueI18nInstanceFile / findI18nInitFileInRoot / findReact / findSolid 在具备
 * project 时已用该方法做 PSI 级复核（见 confirmI18nInitViaPsi / hasRealCreateI18nCall）。
 * 本文件验证该 PSI 判定本身的行为。
 */
class I18nInstanceLocatorPsiTest : BasePlatformTestCase() {

    private fun psiOf(name: String, text: String) = myFixture.configureByText(name, text.trimIndent())

    // ── 字符串字面量里的字样：不应判定为初始化调用 ──────────────────

    fun testStringLiteralCreateI18nIsNotInitCall() {
        val file = psiOf(
            "a.ts",
            """
            const desc = "uses createI18n() to setup";
            const other = 'createI18n (';
            export const a = 1;
            """.trimIndent()
        )
        assertFalse("字符串字面量中的 createI18n( 不是真实调用", I18nInstanceLocator.containsI18nInitCall(file))
    }

    fun testStringLiteralI18nDotInitIsNotInitCall() {
        val file = psiOf(
            "b.ts",
            """
            const msg = "please call i18n.init() manually";
            """.trimIndent()
        )
        assertFalse("字符串字面量中的 i18n.init() 不是真实调用", I18nInstanceLocator.containsI18nInitCall(file))
    }

    fun testStringLiteralInitReactIsNotInitCall() {
        val file = psiOf(
            "c.ts",
            """
            const note = "initReactI18next";
            """.trimIndent()
        )
        assertFalse("字符串字面量中的 initReactI18next 不是引用", I18nInstanceLocator.containsI18nInitCall(file))
    }

    // ── 注释里的字样：不应判定（PSI 天然排除） ─────────────────────

    fun testLineCommentIsNotInitCall() {
        val file = psiOf("d.ts", "// 这里 createI18n() 只是文档说明\nconst a = 1;")
        assertFalse("行注释中的 createI18n( 不是调用", I18nInstanceLocator.containsI18nInitCall(file))
    }

    fun testBlockCommentIsNotInitCall() {
        val file = psiOf("e.ts", "/* \u8bf7\u53c2\u8003 createI18n({ legacy: false }) \u521d\u59cb\u5316 */\nconst a = 1;")
        assertFalse("块注释中的 createI18n( 不是调用", I18nInstanceLocator.containsI18nInitCall(file))
    }

    // ── 真实调用：应当判定 ─────────────────────────────────────────

    fun testRealVueInitCall() {
        val file = psiOf(
            "f.ts",
            """
            import { createI18n } from 'vue-i18n';
            const i18n = createI18n({ legacy: false, locale: 'zh' });
            export default i18n;
            """.trimIndent()
        )
        assertTrue("真实 createI18n(...) 调用应判定为初始化", I18nInstanceLocator.containsI18nInitCall(file))
    }

    fun testRealReactInitCall() {
        val file = psiOf(
            "g.ts",
            """
            import i18n from 'i18next';
            import { initReactI18next } from 'react-i18next';
            i18n.use(initReactI18next).init({ lng: 'zh' });
            """.trimIndent()
        )
        assertTrue("真实 i18n.use(initReactI18next).init(...) 应判定为初始化", I18nInstanceLocator.containsI18nInitCall(file))
    }

    fun testRealSolidInitCall() {
        val file = psiOf(
            "h.ts",
            """
            import { useI18n } from '@solid-primitives/i18n';
            const [t, { locale }] = useI18n({}, () => 'zh');
            """.trimIndent()
        )
        assertTrue("真实 useI18n(...) 调用应判定为初始化", I18nInstanceLocator.containsI18nInitCall(file))
    }
}