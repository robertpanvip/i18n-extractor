package com.pan.extractor.staticparser

/**
 * 数字字面量解析器 —— 把 JS/TS 数字字面量解析为 Long/Double。
 *
 * 拆分自 [StaticValueParser] 的数字识别部分。
 * 覆盖：十进制整数/小数/科学计数、数字分隔符、BigInt、十六进制/二进制/八进制。
 */
internal object NumericLiteralParser {

    private val HEX_RE = Regex("""-?0[xX][0-9a-fA-F]+(_[0-9a-fA-F]+)*n?""")
    private val BIN_RE = Regex("""-?0[bB][01]+(_[01]+)*n?""")
    private val OCT_RE = Regex("""-?0[oO][0-7]+(_[0-7]+)*n?""")
    private val DEC_INT_RE = Regex("""-?\d+(_\d+)*n?""")
    private val DEC_FLOAT_RE = Regex("""-?(\d+(_\d+)*)?\.\d+(_\d+)*([eE][+-]?\d+)?|-?\d+(_\d+)*[eE][+-]?\d+""")

    /** 判断 [s] 是否匹配任一数字字面量形态。 */
    fun looksLikeNumber(s: String): Boolean =
        HEX_RE.matches(s) || BIN_RE.matches(s) || OCT_RE.matches(s) ||
            DEC_INT_RE.matches(s) || DEC_FLOAT_RE.matches(s)

    /** 解析数字字面量；非数字返回 null。 */
    fun parse(s: String): Number? {
        if (HEX_RE.matches(s)) return parseRadix(s, 16)
        if (BIN_RE.matches(s)) return parseRadix(s, 2)
        if (OCT_RE.matches(s)) return parseRadix(s, 8)
        if (DEC_INT_RE.matches(s)) {
            val clean = s.replace("_", "")
            return if (clean.endsWith("n")) clean.dropLast(1).toLongOrNull() ?: return null
            else clean.toLongOrNull() ?: return null
        }
        if (DEC_FLOAT_RE.matches(s)) {
            return s.replace("_", "").toDoubleOrNull() ?: return null
        }
        return null
    }

    /** 进制数字解析：剥除 0x/0b/0o 前缀、_ 分隔符、n 后缀后按 [radix] 解析。 */
    private fun parseRadix(s: String, radix: Int): Long? {
        val clean = s.removePrefix("-").replace("_", "").removeSuffix("n")
        val digits = clean.removePrefix("0x").removePrefix("0X")
            .removePrefix("0b").removePrefix("0B")
            .removePrefix("0o").removePrefix("0O")
        val sign = if (s.startsWith("-")) -1 else 1
        return digits.toLongOrNull(radix)?.let { sign * it }
    }
}
