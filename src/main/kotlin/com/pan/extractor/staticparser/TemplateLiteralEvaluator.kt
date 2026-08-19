package com.pan.extractor.staticparser

/**
 * 模板字面量与字符串拼接求值器 —— 把含 `${静态插值}` 的模板字面量与全字面量拼接求值为字符串。
 *
 * 拆分自 [StaticValueParser] 的模板/拼接求值部分。
 * 仅当所有插值/操作数都是静态字面量时才求值；含动态插值返回 null。
 */
internal object TemplateLiteralEvaluator {

    private val STRING_TEMPLATE_INTERPOL_RE = Regex("""\$\{([^}]*)\}""")

    /**
     * 求值模板字面量 [s]（形如 `` `prefix-${'静态'}-suffix` ``）。
     * 所有 `${...}` 内部递归调用 [StaticValueParser.tryParseStaticValue] 求值；
     * 嵌套模板字面量在插值内递归求值。含动态插值返回 null。
     */
    fun evaluateTemplate(s: String): String? {
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder()
        var lastEnd = 0
        for (m in STRING_TEMPLATE_INTERPOL_RE.findAll(inner)) {
            sb.append(inner, lastEnd, m.range.first)
            val expr = m.groupValues[1].trim()
            val v = if (expr.startsWith("`") && expr.endsWith("`")) {
                evaluateTemplate(expr)
            } else {
                StaticValueParser.tryParseStaticValue(expr)?.toString()
            } ?: return null
            sb.append(v)
            lastEnd = m.range.last + 1
        }
        sb.append(inner, lastEnd, inner.length)
        return sb.toString()
    }

    /** 判断 [s] 是否像字符串拼接（含顶层 `+` 且首字符是字符串/模板字面量起点）。 */
    fun looksLikeConcat(s: String): Boolean {
        if (!s.contains("+")) return false
        val first = s.trimStart().firstOrNull() ?: return false
        return first == '\'' || first == '"' || first == '`'
    }

    /**
     * 递归求值字符串拼接：`'a' + 'b' + "c"` → "abc"。
     * 仅当所有操作数都是静态字符串（含模板字面量）时返回拼接结果；否则 null。
     */
    fun evaluateConcat(s: String): String? {
        val parts = splitTopLevelPlus(s)
        val sb = StringBuilder()
        for (part in parts) {
            val v = StaticValueParser.tryParseStaticValue(part) ?: return null
            if (v !is String) return null  // 仅字符串拼接可静态求值；数字+数字按算术不算静态文案
            sb.append(v)
        }
        return sb.toString()
    }

    /** 按顶层 `+` 切分（处理嵌套与字符串）。 */
    private fun splitTopLevelPlus(s: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{', '[', '(' -> depth++
                    '}', ']', ')' -> depth = (depth - 1).coerceAtLeast(0)
                    '+' -> if (depth == 0 && i > 0) {
                        parts += s.substring(start, i).trim()
                        start = i + 1
                    }
                }
            }
            i++
        }
        if (start < s.length) parts += s.substring(start).trim()
        return parts.filter { it.isNotEmpty() }
    }
}
