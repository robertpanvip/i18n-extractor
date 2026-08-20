package com.pan.extractor.core

import com.pan.extractor.locate.EntryFileLocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 专门测试核心纯函数：EntryFileLocator 的语言包/翻译资源文件识别。
 *
 * 这是 $t() 折叠展示功能的关键前置：折叠时必须先判断某文件是不是
 * 翻译资源文件，才能定位目标语言入口、加载译文。isTranslationResourceFile
 * 传 null path 时是纯字符串判定，不依赖 IntelliJ 平台，可作纯单元测试。
 *
 * 覆盖 isTranslationResourceFile 的判定规则：
 *   1. 支持的后缀（.ts/.tsx/.js/.jsx/.json）与快速剔除（其它后缀）
 *   2. 路径目录段命中（locales/i18n/locale/lang/translations...）——但纯测试下
 *      不传 path 或传普通 path 时依赖 basename 判定
 *   3. 基名本身就是 locale code（en / zh-US / zh_CN / zhs）
 *   4. 翻译前缀 + locale code（messages.en-US / i18n.zh_CN）
 *   5. 常见语言-region 连写（zhHans / ptBR / enUS）
 *   6. 普通业务文件不应被误判
 */
class EntryFileLocatorCoreFunctionTest {

    // ── 后缀快速剔除 ─────────────────────────────────────────

    @Test
    fun rejectsUnsupportedExtensions() {
        assertFalse(EntryFileLocator.isTranslationResourceFile("en.txt", null))
        assertFalse(EntryFileLocator.isTranslationResourceFile("en.md", null))
        assertFalse(EntryFileLocator.isTranslationResourceFile("en.scss", null))
        assertFalse(EntryFileLocator.isTranslationResourceFile("en.vue", null))
    }

    @Test
    fun acceptsSupportedScriptExtensions() {
        assertTrue(EntryFileLocator.isTranslationResourceFile("en.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("en.tsx", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("en.js", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("en.json", null))
    }

    // ── 基名本身就是 locale code ─────────────────────────────

    @Test
    fun basenameIsLocaleCode() {
        assertTrue(EntryFileLocator.isTranslationResourceFile("en.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("zh-US.tsx", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("zh_CN.js", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("zhs.json", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("ja-JP.ts", null))
    }

    @Test
    fun invalidLanguageCodeNotLocale() {
        // 非 ISO 语言码（如普通业务缩写）不应被当作 locale
        assertFalse(EntryFileLocator.isTranslationResourceFile("config.ts", null))
        assertFalse(EntryFileLocator.isTranslationResourceFile("index.ts", null))
        assertFalse(EntryFileLocator.isTranslationResourceFile("styles.ts", null))
    }

    // ── 翻译前缀 + locale code ───────────────────────────────

    @Test
    fun prefixPlusLocaleCode() {
        assertTrue(EntryFileLocator.isTranslationResourceFile("messages.en-US.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("i18n.zh_CN.js", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("strings.zh_TW.tsx", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("locale.ja.json", null))
    }

    @Test
    fun prefixPlusNonLocaleNotResource() {
        assertFalse(EntryFileLocator.isTranslationResourceFile("messages.router.ts", null))
        assertFalse(EntryFileLocator.isTranslationResourceFile("i18n.utils.ts", null))
    }

    // ── 语言-region 连写（不带分隔符） ────────────────────────

    @Test
    fun commonLanguageRegionCompound() {
        assertTrue(EntryFileLocator.isTranslationResourceFile("zhHans.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("zhCN.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("ptBR.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("enGB.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("jaJP.ts", null))
        assertTrue(EntryFileLocator.isTranslationResourceFile("koKR.ts", null))
    }

    @Test
    fun unknownCompoundNotResource() {
        assertFalse(EntryFileLocator.isTranslationResourceFile("zhXY.ts", null))
        assertFalse(EntryFileLocator.isTranslationResourceFile("xxYY.ts", null))
    }

    // ── 目录段命中（带 path） ─────────────────────────────────

    @Test
    fun directorySegmentHit() {
        // 路径中出现 locales/i18n 等目录段即视为翻译文件
        assertTrue(EntryFileLocator.isTranslationResourceFile("whatever.ts", "/proj/src/locales/whatever.ts"))
        assertTrue(EntryFileLocator.isTranslationResourceFile("foo.json", "/proj/i18n/foo.json"))
        assertTrue(EntryFileLocator.isTranslationResourceFile("bar.ts", "/proj/src/lang/bar.ts"))
        assertTrue(EntryFileLocator.isTranslationResourceFile("baz.tsx", "/proj/translations/baz.tsx"))
    }

    @Test
    fun directorySegmentSubstringNotMisclassified() {
        // 避免把 mailing / 之类误判成 lang / mailing
        assertFalse(EntryFileLocator.isTranslationResourceFile("mailing.ts", "/proj/src/mailing/mailing.ts"))
        assertFalse(EntryFileLocator.isTranslationResourceFile("index.ts", "/proj/src/interesting/index.ts"))
    }

    // ── 常规业务文件不误判 ────────────────────────────────────

    @Test
    fun normalBusinessFilesNotResource() {
        assertFalse(EntryFileLocator.isTranslationResourceFile("App.tsx", "/proj/src/App.tsx"))
        assertFalse(EntryFileLocator.isTranslationResourceFile("api.ts", "/proj/src/api/api.ts"))
        assertFalse(EntryFileLocator.isTranslationResourceFile("utils.ts", "/proj/src/utils.ts"))
    }
}