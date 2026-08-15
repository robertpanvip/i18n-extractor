package com.pan.extractor

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

/** 中文：CJK 统一表意文字基本区（与旧 hasChinese 语义一致）。 */
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

    private val EN_LETTER = Regex("[a-zA-Z]")
    private val CJK = Regex("""[\u4e00-\u9fff]""")
    private val KANA = Regex("""[\u3040-\u30ff]""")
    private val HANGUL = Regex("""[\uac00-\ud7af\u1100-\u11ff\u3130-\u318f]""")
    private val URL_LIKE =
        Regex("(https?://|www\\.|^[\\w.-]+\\.(com|org|net|io|cn|dev|co)([:/]|$))", RegexOption.IGNORE_CASE)
    private val SENTENCE_HINT = " .,!?;:，。！？；："

    override fun judge(text: CharSequence): Boolean {
        val s = text.toString().trim()
        if (s.isEmpty()) return false
        if (!EN_LETTER.containsMatchIn(s)) return false
        // 其它语言字符 → 不是英文
        if (CJK.containsMatchIn(s) || KANA.containsMatchIn(s) || HANGUL.containsMatchIn(s)) return false
        // 明显代码特征（URL）
        if (URL_LIKE.containsMatchIn(s)) return false
        // 句子/短语特征：含空格 或 句子标点（排除单 token 标识符/常量）
        val hasSpace = s.any { it.isWhitespace() }
        val hasPunct = s.any { it in SENTENCE_HINT }
        return hasSpace || hasPunct
    }

    override fun localeNameCandidates(): List<String> = listOf("en", "en-US", "en_US", "enUS", "us")
    override val langTagPrefix = "en"
    override val regionCodes: Set<String> = setOf("us", "gb", "au", "ca", "in", "sg", "ie", "nz")
}

/**
 * 法语：拉丁字母 + 法语专属重音符（é è ê ë à â ä ç î ï ô ù û ü œ æ ÿ）。
 *
 * 与英文同为拉丁字母，无法用整段字符区间与英文完全区分；因此用法语专属重音符做确定性判定
 * （含重音符 → 法语），纯 ASCII 法语（如 "bonjour"）与英文重叠属已知取舍，与英文方案一致。
 * 重音符几乎不会出现在 class/id/style 等非文案属性里，故接受所有上下文（与 CJK 一致）。
 */
object FrenchExtractor : LanguageExtractor {
    override val id = "fr"
    override val displayName = "法语"
    private val RE = Regex(
        """[éèêëàâäçîïôùûüÿœæ]"""
    )
    override fun judge(text: CharSequence): Boolean = RE.containsMatchIn(text)
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
 * 与英/法同为拉丁字母，无法用整段字符区间与英文完全区分；因此用德语专属变音符做确定性判定
 * （含变音符 → 德语），纯 ASCII 德语（如 "Hallo"）与英文重叠属已知取舍，与英文/法语方案一致。
 * 变音符几乎不会出现在 class/id/style 等非文案属性里，故接受所有上下文。
 */
object GermanExtractor : LanguageExtractor {
    override val id = "de"
    override val displayName = "德语"
    private val RE = Regex("""[äöüßÄÖÜ]""")
    override fun judge(text: CharSequence): Boolean = RE.containsMatchIn(text)
    override fun localeNameCandidates(): List<String> =
        listOf("de", "de-DE", "de_DE", "deDE", "de-AT", "de_AT", "de-CH", "de_CH")
    override val langTagPrefix = "de"
    override val regionCodes: Set<String> = setOf("de", "at", "ch", "li", "lu")
}

/**
 * 西班牙语：拉丁字母 + 西语专属字符（ñ、带重音的元音、倒问句/叹句）。
 *
 * 与英/法/德同为拉丁字母，无法用整段字符区间与英文完全区分；因此用西语专属字符做确定性判定
 * （含 ñ / 重音元音等 → 西语），纯 ASCII 西语（如 "hola"）与英文重叠属已知取舍，与英文/法语/德语方案一致。
 * 这些字符几乎不会出现在 class/id/style 等非文案属性里，故接受所有上下文。
 */
object SpanishExtractor : LanguageExtractor {
    override val id = "es"
    override val displayName = "西班牙语"
    private val RE = Regex("""[ñáéíóúüÑÁÉÍÓÚÜ¿¡]""")
    override fun judge(text: CharSequence): Boolean = RE.containsMatchIn(text)
    override fun localeNameCandidates(): List<String> =
        listOf("es", "es-ES", "es_ES", "esES", "es-MX", "es_MX", "es-AR", "es-CL", "es-CO")
    override val langTagPrefix = "es"
    override val regionCodes: Set<String> = setOf("es", "mx", "ar", "cl", "co", "pe", "ve", "us")
}

/** 语言提取器注册表：内置 zh/ja/ko/en/fr/ru/de/es。 */
object LanguageRegistry {
    val all: List<LanguageExtractor> =
        listOf(
            ChineseExtractor, JapaneseExtractor, KoreanExtractor,
            EnglishExtractor, FrenchExtractor, RussianExtractor,
            GermanExtractor, SpanishExtractor
        )

    fun byId(id: String): LanguageExtractor? = all.firstOrNull { it.id == id }
}