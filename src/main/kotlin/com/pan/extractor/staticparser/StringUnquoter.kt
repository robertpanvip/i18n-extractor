package com.pan.extractor.staticparser

/**
 * 字符串反引用器 —— 把单/双/反引号包裹的字符串字面量还原为原始文本。
 *
 * 拆分自 [StaticObjectParser]（原 unquoteString）。
 * 支持 JS/TS 全部转义：`\n \t \r \b \f \0 \\ \' \" \``、`\uXXXX`、`\xXX`。
 */
internal object StringUnquoter {

    /**
     * 去掉 [s] 两侧成对的引号并解析转义。
     * [s] 形如 `'abc'` / `"abc"` / `` `abc` ``，长度 < 2 时原样返回。
     */
    fun unquote(s: String): String {
        if (s.length < 2) return s
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                i = when (val nc = inner[i + 1]) {
                    'n' -> { sb.append('\n'); i + 2 }
                    't' -> { sb.append('\t'); i + 2 }
                    'r' -> { sb.append('\r'); i + 2 }
                    'b' -> { sb.append('\b'); i + 2 }
                    'f' -> { sb.append('\u000c'); i + 2 }
                    '0' -> { sb.append('\u0000'); i + 2 }
                    '\\' -> { sb.append('\\'); i + 2 }
                    '\'' -> { sb.append('\''); i + 2 }
                    '"' -> { sb.append('"'); i + 2 }
                    '`' -> { sb.append('`'); i + 2 }
                    'u' -> unescapeUnicode(inner, i, 6, sb)   // \uXXXX
                    'x' -> unescapeUnicode(inner, i, 4, sb)    // \xXX
                    else -> { sb.append(nc); i + 2 }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 解析 `\uXXXX` / `\xXX` 转义；hexLen 含 `\u`/`\x` 前缀的总长（6/4）。 */
    private fun unescapeUnicode(inner: String, i: Int, hexLen: Int, sb: StringBuilder): Int {
        if (i + hexLen <= inner.length) {
            val hex = inner.substring(i + 2, i + hexLen)
            val code = hex.toIntOrNull(16)
            if (code != null) {
                sb.append(Char(code))
                return i + hexLen
            }
        }
        // 解析失败：保留转义符字面（如 `\xG` → "xG"）
        sb.append(inner[i + 1])
        return i + 2
    }
}
