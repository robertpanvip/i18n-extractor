package com.pan.extractor.lang

import com.pan.extractor.core.RegexCatalog

/**
 * 提取站点的“上下文类型”（Approach A：更看节点在语法树里的角色，而非只看字符）。
 *
 * 作用：让语言提取器不只凭文本内容判定，还能按“这个字符串出现在哪类节点里”决定是否候选。
 * CJK 语言（zh/ja/ko）基于字符区间，任何上下文都可接受（默认 [LanguageExtractor.accepts] = true）；
 * 未来英文等无法靠字符判定的语言，可通过收紧 [LanguageExtractor.accepts] 只认文案类上下文来降低误报。
 */
enum class SiteKind {
    /** JSX / Vue 模板里的文本节点（标签之间的文字）。 */
    TEXT,

    /** 属性值（Vue 静态属性 / JSX attribute / slot 等）。 */
    ATTRIBUTE,

    /** JS 字符串字面量（'x' / "x"）。 */
    JS_STRING,

    /** 模板字符串内容（`x${y}z`）。 */
    JS_TEMPLATE,

    /** 字符串拼接表达式（"a" + "b"）。 */
    JS_CONCAT,

    /** 其它 / 未明确上下文（默认）。 */
    OTHER
}

// ───────────────────────────────────────────────
// 拉丁字母语系共享判定（英/法/德/西/意/葡共用 26 个拉丁字母）
// ───────────────────────────────────────────────

private val LATIN_LETTER_RE = Regex("[a-zA-Z]")
private val CJK_RE = RegexCatalog.HAN
private val KANA_RE = Regex("""[\u3040-\u30ff]""")
private val HANGUL_RE = Regex("""[\uac00-\ud7af\u1100-\u11ff\u3130-\u318f]""")
private val URL_LIKE_RE =
    Regex("(https?://|www\\.|^[\\w.-]+\\.(com|org|net|io|cn|dev|co)([:/]|$))", RegexOption.IGNORE_CASE)
private val SENTENCE_HINT = " .,!?;:"

/**
 * 拉丁字母语系（Latin script）共享的「字母句子」判定。
 *
 * 拉丁字母语系（英/法/德/西/意/葡）共用 26 个英文字母。文本若看起来是
 * 「拉丁字母句子」（含 a-zA-Z、无 CJK/假名/谚文、非 URL、含空格或句子标点），
 * 语系内所有语言都视为命中，从而把纯 ASCII 文案（如 "Hello world" / "Hallo Welt"）
 * 也纳入提取，而不只是命中英文。
 *
 * 单 token（无空格/标点）与 URL 仍被排除，避免把代码标识符 / 超链接误当文案。
 */
fun isLatinAlphabetSentence(text: CharSequence): Boolean {
    val s = text.toString().trim()
    if (s.isEmpty()) return false
    if (!LATIN_LETTER_RE.containsMatchIn(s)) return false
    // 其它文字（CJK / 假名 / 谚文）出现 → 不是拉丁字母句子
    if (CJK_RE.containsMatchIn(s) || KANA_RE.containsMatchIn(s) || HANGUL_RE.containsMatchIn(s)) return false
    // 明显代码特征（URL）
    if (URL_LIKE_RE.containsMatchIn(s)) return false
    // 句子/短语特征：含空格 或 句子标点（排除单 token 标识符/常量）
    return s.any { it.isWhitespace() } || s.any { it in SENTENCE_HINT }
}

/**
 * 可插拔的“目标语言”提取器。每种语言一个实现，统一定义：
 *  - [judge]：文本是否包含该语言（用于提取判定，取代原先硬编码的 `[\u4e00-\u9fff]`）
 *  - [accepts]：该语言是否接受某类提取站点上下文（Approach A 的核心；默认全接受）
 *  - [localeNameCandidates]：该语言 locale 文件的命名候选（取代硬编码的 ZH_LOCALE_NAMES）
 *  - [langTagPrefix] / [regionCodes]：用于 `<tag><region>`（zhCN / jaJP / koKR）国家码兜底匹配
 *
 * 默认只启用中文（【向后兼容】），其余语言由用户在 Settings 面板勾选启用。
 */
interface LanguageExtractor {
    val id: String
    val displayName: String

    /** 文本是否包含至少一个该语言的字符。 */
    fun judge(text: CharSequence): Boolean

    /** 该语言是否接受在 [SiteKind] 上下文下提取。默认全接受（CJK 语义）。 */
    fun accepts(site: SiteKind): Boolean = true

    /** 该语言常被用作 locale 文件名的候选（如 zh-CN / ja-JP / ko_KR）。 */
    fun localeNameCandidates(): List<String>

    /** 语言标签前缀（BCP-47 前的语言码），用于 `<tag><region>` 兜底。 */
    val langTagPrefix: String

    /** 常见国家/地区码，配合 [langTagPrefix] 做 zhCN / jaJP 这类命中的兜底。 */
    val regionCodes: Set<String>
}

/** 中文：CJK 统一表意文字基本区。 */
object ChineseExtractor : LanguageExtractor {
    override val id = "zh"
    override val displayName = "中文"
    private val RE = Regex("""[\u4e00-\u9fff]""")
    override fun judge(text: CharSequence): Boolean = RE.containsMatchIn(text)
    override fun localeNameCandidates(): List<String> = listOf(
        "zh-CN", "zh_CN", "zhCN", "zhHans", "zh-Hans", "zhs",
        "zh", "zhcn", "cn", "zh-CHS", "zh-hans-cn", "zh-Hans-CN",
        "zh-SG", "zh_SG", "zhSG"
    )
    override val langTagPrefix = "zh"
    override val regionCodes: Set<String> = setOf("hans", "hant", "cn", "tw", "hk", "sg", "mo", "my")
}

/** 日文：平假名 + 片假名（纯汉字日文会与中文重叠，属已知取舍）。 */
object JapaneseExtractor : LanguageExtractor {
    override val id = "ja"
    override val displayName = "日文"
    private val RE = Regex("""[\u3040-\u30ff]""")
    override fun judge(text: CharSequence): Boolean = RE.containsMatchIn(text)
    override fun localeNameCandidates(): List<String> = listOf("ja", "ja-JP", "ja_JP", "jaJP", "jp")
    override val langTagPrefix = "ja"
    override val regionCodes: Set<String> = setOf("jp")
}

/** 韩文：谚文音节 + 兼容 Jamo。 */
object KoreanExtractor : LanguageExtractor {
    override val id = "ko"
    override val displayName = "韩文"
    private val RE = Regex("""[\uac00-\ud7af\u1100-\u11ff\u3130-\u318f]""")
    override fun judge(text: CharSequence): Boolean = RE.containsMatchIn(text)
    override fun localeNameCandidates(): List<String> = listOf("ko", "ko-KR", "ko_KR", "koKR", "kr")
    override val langTagPrefix = "ko"
    override val regionCodes: Set<String> = setOf("kr")
}

/**
 * 英文：代码本身全是英文，无法用“字符区间”判定，只能靠上下文 + 内容启发式（Approach A）。
 *
 * v1 规则（句子级、控误报）：
 *  - [judge]：必须是“含英文且看起来像整句/短语”的文本——含空格或句子标点，
 *    排除纯代码特征（URL、其它语言字符、单 token 标识符）。
 *  - [accepts]：不接受 ATTRIBUTE 上下文（避免把 `class="main container"`、id、style 等
 *    非文案属性误当文案；已知文案属性 title/placeholder 等后续可精细化）。
 */
object EnglishExtractor : LanguageExtractor {
    override val id = "en"
    override val displayName = "英文"

    override fun judge(text: CharSequence): Boolean = isLatinAlphabetSentence(text)

    /** 拉丁语系文案靠 ASCII 句子启发式判定，`class/id/style` 等 ATTRIBUTE 上下文极易含空格而误判，需要拒绝。 */
    override fun accepts(site: SiteKind): Boolean = site != SiteKind.ATTRIBUTE

    override fun localeNameCandidates(): List<String> = listOf("en", "en-US", "en_US", "enUS", "us")
    override val langTagPrefix = "en"
    override val regionCodes: Set<String> = setOf("us", "gb", "au", "ca", "in", "sg", "ie", "nz")
}

/**
 * 法语：拉丁字母 + 法语专属重音符（é è ê ë à â ä ç î ï ô ù û ü œ æ ÿ）。
 *
 * 属于拉丁字母语系：除专属重音符外，还共享英文 26 字母的句子判定，
 * 因此纯 ASCII 法语（如 "Bonjour tout le monde"）也能被识别提取。
 * 重音符文本在 ATTRIBUTE 里多为真实文案（title/aria-label 等），故仍接受所有上下文（与 CJK 一致）。
 */
object FrenchExtractor : LanguageExtractor {
    override val id = "fr"
    override val displayName = "法语"
    private val RE = Regex(
        """[éèêëàâäçîïôùûüÿœæ]"""
    )
    override fun judge(text: CharSequence): Boolean =
        RE.containsMatchIn(text) || isLatinAlphabetSentence(text)
    override fun localeNameCandidates(): List<String> =
        listOf("fr", "fr-FR", "fr_FR", "frFR", "fr-CA", "fr_CH", "frfr", "frCH", "fr-BE", "fr-CH")
    override val langTagPrefix = "fr"
    override val regionCodes: Set<String> = setOf("fr", "ca", "ch", "be", "lu", "mc")
}

/** 俄语：西里尔字母（与中文/日文/韩文一样可按字符区间确定性判定）。 */
object RussianExtractor : LanguageExtractor {
    override val id = "ru"
    override val displayName = "俄语"
    private val RE = Regex("""[\u0400-\u04ff]""")
    override fun judge(text: CharSequence): Boolean = RE.containsMatchIn(text)
    override fun localeNameCandidates(): List<String> =
        listOf("ru", "ru-RU", "ru_RU", "ruRU", "ru-UA", "ru_UA", "ru-KZ", "ru-BY")
    override val langTagPrefix = "ru"
    override val regionCodes: Set<String> = setOf("ru", "ua", "kz", "by")
}

/**
 * 德语：拉丁字母 + 德语专属变音（ä ö ü 与 ß）。
 *
 * 属于拉丁字母语系：除专属变音外，还共享英文 26 字母的句子判定，
 * 因此纯 ASCII 德语（如 "Hallo Welt"）也能被识别提取。
 * 变音文本在 ATTRIBUTE 里多为真实文案，故仍接受所有上下文。
 */
object GermanExtractor : LanguageExtractor {
    override val id = "de"
    override val displayName = "德语"
    private val RE = Regex("""[äöüßÄÖÜ]""")
    override fun judge(text: CharSequence): Boolean =
        RE.containsMatchIn(text) || isLatinAlphabetSentence(text)
    override fun localeNameCandidates(): List<String> =
        listOf("de", "de-DE", "de_DE", "deDE", "de-AT", "de_AT", "de-CH", "de_CH")
    override val langTagPrefix = "de"
    override val regionCodes: Set<String> = setOf("de", "at", "ch", "li", "lu")
}

/**
 * 西班牙语：拉丁字母 + 西语专属字符（ñ、带重音的元音、倒问句/叹句）。
 *
 * 属于拉丁字母语系：除西语专属字符外，还共享英文 26 字母的句子判定，
 * 因此纯 ASCII 西语（如 "Hola mundo"）也能被识别提取。
 * 专属字符文本在 ATTRIBUTE 里多为真实文案，故仍接受所有上下文。
 */
object SpanishExtractor : LanguageExtractor {
    override val id = "es"
    override val displayName = "西班牙语"
    private val RE = Regex("""[ñáéíóúüÑÁÉÍÓÚÜ¿¡]""")
    override fun judge(text: CharSequence): Boolean =
        RE.containsMatchIn(text) || isLatinAlphabetSentence(text)
    override fun localeNameCandidates(): List<String> =
        listOf("es", "es-ES", "es_ES", "esES", "es-MX", "es_MX", "es-AR", "es-CL", "es-CO")
    override val langTagPrefix = "es"
    override val regionCodes: Set<String> = setOf("es", "mx", "ar", "cl", "co", "pe", "ve", "us")
}

/**
 * 意大利语：拉丁字母 + 意语专属重音元音（à è é ì ò ù）。
 *
 * 属于拉丁字母语系：除意语专属重音外，还共享英文 26 字母的句子判定，
 * 因此纯 ASCII 意语（如 "Ciao mondo"）也能被识别提取。
 * 注意 à/è/é/ù 与法语重叠（如 "città" 也会被法语判中），属确定性方案的固有取舍。
 * 专属字符文本在 ATTRIBUTE 里多为真实文案，故仍接受所有上下文。
 */
object ItalianExtractor : LanguageExtractor {
    override val id = "it"
    override val displayName = "意大利语"
    private val RE = Regex("""[àèéìòù]""")
    override fun judge(text: CharSequence): Boolean =
        RE.containsMatchIn(text) || isLatinAlphabetSentence(text)
    override fun localeNameCandidates(): List<String> =
        listOf("it", "it-IT", "it_IT", "itIT", "it-CH", "it_CH")
    override val langTagPrefix = "it"
    override val regionCodes: Set<String> = setOf("it", "ch", "sm")
}

/**
 * 葡萄牙语：拉丁字母 + 葡语专属字符（ã õ 波浪元音，及 à á â ç é ê í ó ô ú ü）。
 *
 * 属于拉丁字母语系：除葡语专属字符外，还共享英文 26 字母的句子判定，
 * 因此纯 ASCII 葡语（如 "Ola mundo"）也能被识别提取。
 * ã/õ 为葡语（及加利西亚语）特有，可稳定把葡语与西/法/意区分开。
 * 专属字符文本在 ATTRIBUTE 里多为真实文案，故仍接受所有上下文。
 */
object PortugueseExtractor : LanguageExtractor {
    override val id = "pt"
    override val displayName = "葡萄牙语"
    private val RE = Regex("""[àáâãçéêíóôõúü]""")
    override fun judge(text: CharSequence): Boolean =
        RE.containsMatchIn(text) || isLatinAlphabetSentence(text)
    override fun localeNameCandidates(): List<String> =
        listOf("pt", "pt-BR", "pt_PT", "pt-PT", "ptBR", "ptPT", "pt-MO", "pt-AO")
    override val langTagPrefix = "pt"
    override val regionCodes: Set<String> = setOf("br", "pt", "mo", "ao", "cv")
}

/** 语言提取器注册表：内置 zh/ja/ko/en/fr/ru/de/es/it/pt。 */
object LanguageRegistry {
    val all: List<LanguageExtractor> =
        listOf(
            ChineseExtractor, JapaneseExtractor, KoreanExtractor,
            EnglishExtractor, FrenchExtractor, RussianExtractor,
            GermanExtractor, SpanishExtractor, ItalianExtractor, PortugueseExtractor
        )

    fun byId(id: String): LanguageExtractor? = all.firstOrNull { it.id == id }
}