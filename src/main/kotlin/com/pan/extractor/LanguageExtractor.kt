package com.pan.extractor

/**
 * 可插拔的“目标语言”提取器。每种语言一个实现，统一定义：
 *  - [judge]：文本是否包含该语言（用于提取判定，取代原先硬编码的 `[\u4e00-\u9fff]`）
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

/** 语言提取器注册表：内置 zh/ja/ko（英文后置，暂不纳入）。 */
object LanguageRegistry {
    val all: List<LanguageExtractor> = listOf(ChineseExtractor, JapaneseExtractor, KoreanExtractor)

    fun byId(id: String): LanguageExtractor? = all.firstOrNull { it.id == id }
}