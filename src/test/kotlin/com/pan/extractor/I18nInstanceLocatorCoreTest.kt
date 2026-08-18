package com.pan.extractor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * i18n 初始化文件检测的纯文本级回归测试。
 *
 * 针对 BUG_ANALYSIS 3.3：原实现直接对源码文本做 `contains("createI18n(")` /
 * `contains("initReactI18next")` / `i18n.init(` 正则匹配，会把注释里的字样误判为
 * 真实初始化调用。修复方式为检测前先剥离 JS/TS 注释（[I18nInstanceLocator.stripJsComments]）。
 *
 * 纯 JUnit（不依赖 IntelliJ 平台），可在任意环境运行验证。
 */
class I18nInstanceLocatorCoreTest {

    // ── stripJsComments：注释中的 createI18n / initReactI18next / i18n.init 被剥离 ──

    @Test
    fun `stripJsComments removes createI18n inside line comment`() {
        val stripped = I18nInstanceLocator.stripJsComments("// createI18n()")
        assertFalse("行注释中的 createI18n( 应被剥掉", stripped.contains("createI18n("))
    }

    @Test
    fun `stripJsComments removes initReactI18next inside block comment`() {
        val stripped = I18nInstanceLocator.stripJsComments("/* initReactI18next */")
        assertFalse("块注释中的 initReactI18next 应被剥掉", stripped.contains("initReactI18next"))
    }

    @Test
    fun `stripJsComments removes i18n dot init inside block comment with slash star`() {
        val src = "/* i18n.init('zh') */"
        val stripped = I18nInstanceLocator.stripJsComments(src)
        assertFalse("块注释中的 i18n.init 应被剥掉", stripped.contains("i18n.init("))
    }

    @Test
    fun `stripJsComments keeps real createI18n call`() {
        val src = "const i18n = createI18n({ legacy: false }) // createI18n here"
        val stripped = I18nInstanceLocator.stripJsComments(src)
        assertTrue("可执行代码中的 createI18n( 应保留", stripped.contains("createI18n("))
    }

    @Test
    fun `stripJsComments keeps code before a trailing line comment`() {
        val src = "i18n.use(initReactI18next).init({ lng: 'zh' }); // init"
        val stripped = I18nInstanceLocator.stripJsComments(src)
        assertTrue("真实 init( 调用应保留", stripped.contains("init({"))
        assertFalse("尾部行注释应被剥掉", stripped.contains("// init"))
    }

    @Test
    fun `stripJsComments keeps code when comment appears inside multiline()`() {
        val src = "i18n\n  .init(\n    { lng: 'zh' }\n  );"
        val stripped = I18nInstanceLocator.stripJsComments(src)
        assertTrue("跨行的真实 i18n.init( 应保留", stripped.contains(".init("))
    }

    @Test
    fun `stripJsComments handles block comment containing a line-comment marker`() {
        val src = "const a = 1; /* a // b comment */ const i18n = createI18n();"
        val stripped = I18nInstanceLocator.stripJsComments(src)
        assertFalse("块注释整体应被剥掉", stripped.contains("b comment"))
        assertTrue("块注释外的 createI18n() 应保留", stripped.contains("createI18n("))
    }

    // ── isI18nInitText：仅注释命中时不判定为初始化文件，真实调用仍判定 ──

    @Test
    fun `isI18nInitText false when only createI18n in comment`() {
        val src = """
            // 这里是我们 createI18n() 的文档说明
            // TODO: 参考 createI18n 初始化
            const a = 1;
        """.trimIndent()
        assertFalse("仅注释含 createI18n( 不应判定为初始化文件", I18nInstanceLocator.isI18nInitText(src))
    }

    @Test
    fun `isI18nInitText false when only initReactI18next in comment`() {
        val src = "// console.log(\"initReactI18next\")\nconst b = 2;"
        assertFalse("注释中的 initReactI18next 不应判定为 React 初始化", I18nInstanceLocator.isI18nInitText(src))
    }

    @Test
    fun `isI18nInitText true for real vue init`() {
        val src = """
            import { createI18n } from 'vue-i18n';
            const i18n = createI18n({ legacy: false });
            export default i18n;
        """.trimIndent()
        assertTrue("真实 createI18n( 应判定为初始化文件", I18nInstanceLocator.isI18nInitText(src))
    }

    @Test
    fun `isI18nInitText true for real react init`() {
        val src = """
            import i18n from 'i18next';
            import { initReactI18next } from 'react-i18next';
            i18n.use(initReactI18next).init({ lng: 'zh' });
        """.trimIndent()
        assertTrue("真实 initReactI18next 应判定为初始化文件", I18nInstanceLocator.isI18nInitText(src))
    }
}