package com.pan.extractor.staticparser

/**
 * 静态值解析器 —— 把单个表达式片段解析为静态值（迁移自 TsFileEditor.tryParseStaticValue）。
 *
 * **本版本的识别覆盖**（在原有基础上补全）：
 *  - 字面量：null / undefined / true / false
 *  - 数字（十进制）：整数 / 小数 / 科学计数（1e3 / 1.5E-2）/ 数字分隔符（1_000_000）
 *  - 数字（进制）：BigInt（123n）/ 十六进制（0x1F）/ 二进制（0b101）/ 八进制（0o17）
 *  - 字符串：单引号 / 双引号 / 反引号（无插值 或 仅含静态字面量插值）
 *  - 模板字面量带静态插值：`` `prefix${'静态'}suffix` `` → 求值为 "prefix静态suffix"
 *  - 一元运算：`-1` / `+1` / `!true` / `!false` / `-1.5`
 *  - 字符串拼接（全字面量）：`'a' + 'b' + "c"` → "abc"（含模板字面量拼接）
 *  - 对象字面量 / 数组字面量（递归解析）
 *  - TS 类型断言剥除：`expr as Type` / `expr as const` / `expr satisfies Type`
 *
 * 非静态（返回 null，由调用方按"整条保留"处理）：
 *  - 标识符引用（变量、import 绑定）
 *  - 函数调用（除已知常量）
 *  - spread（...arr）
 *  - 三元 / 逻辑运算（含变量）
 *  - 模板字面量含动态插值（`` `a${var}` ``）
 */
object StaticValueParser {

    /** 数字字面量正则（含进制/BigInt/科学计数/分隔符），高频复用避免重编译。 */
    private val HEX_RE = Regex("""-?0[xX][0-9a-fA-F]+(_[0-9a-fA-F]+)*n?""")
    private val BIN_RE = Regex("""-?0[bB][01]+(_[01]+)*n?""")
    private val OCT_RE = Regex("""-?0[oO][0-7]+(_[0-7]+)*n?""")
    private val DEC_INT_RE = Regex("""-?\d+(_\d+)*n?""")
    private val DEC_FLOAT_RE = Regex("""-?(\d+(_\d+)*)?\.\d+(_\d+)*([eE][+-]?\d+)?|-?\d+(_\d+)*[eE][+-]?\d+""")
    private val STRING_TEMPLATE_INTERPOL_RE = Regex("""\$\{([^}]*)\}""")

    /** 去掉值表达式尾部与静态判定无关的 TS 后缀（as Type / as const / satisfies Type）。 */
    fun stripValueSuffixes(expr: String): String {
        var t = expr.trim()
        // 反复剥除（支持 'x' as const as string 这种链式，虽罕见）
        var changed = true
        while (changed) {
            changed = false
            if (t.endsWith(" as const")) {
                t = t.removeSuffix(" as const").trim(); changed = true; continue
            }
            // as <Type>：Type 为标识符路径（含 . <> [], () 等），用宽松匹配到行尾
            val asMatch = Regex("""\s+as\s+[\w$.<>\[\],()|&'" ?]+${'$'}""").find(t)
            if (asMatch != null && !t.substring(0, asMatch.range.first).endsWith(" as")) {
                t = t.substring(0, asMatch.range.first).trim(); changed = true; continue
            }
            // satisfies <Type>
            val satMatch = Regex("""\s+satisfies\s+[\w$.<>\[\],()|&'" ?]+${'$'}""").find(t)
            if (satMatch != null) {
                t = t.substring(0, satMatch.range.first).trim(); changed = true; continue
            }
        }
        return t
    }

    /** 尝试把一个表达式片段解析为静态值；非静态返回 null。 */
    fun tryParseStaticValue(expr: String): Any? {
        val s = stripValueSuffixes(expr)
        if (s.isEmpty()) return null

        // 字面量：null / undefined / true / false
        when (s) {
            "null" -> return null
            "undefined" -> return null
            "true" -> return true
            "false" -> return false
        }

        // 一元运算：! / - / + （仅作用于静态字面量）
        when {
            s.startsWith("!") -> {
                val inner = tryParseStaticValue(s.substring(1)) ?: return null
                val b = inner as? Boolean ?: return null
                return !b
            }
            s.startsWith("-") || s.startsWith("+") -> {
                val inner = tryParseStaticValue(s.substring(1)) ?: return null
                return applyUnaryPrefix(s[0], inner) ?: return null
            }
        }

        // 字符串拼接（全字面量）：'a' + 'b' + "c" + `d`
        if (looksLikeConcat(s)) {
            return tryEvaluateConcat(s)
        }

        // 数字（进制）：十六进制 / 二进制 / 八进制 / BigInt
        if (HEX_RE.matches(s)) return parseRadixNumber(s, 16)
        if (BIN_RE.matches(s)) return parseRadixNumber(s, 2)
        if (OCT_RE.matches(s)) return parseRadixNumber(s, 8)

        // 数字（十进制）：BigInt / 整数 / 小数 / 科学计数 / 分隔符
        if (DEC_INT_RE.matches(s)) {
            val clean = s.replace("_", "")
            return if (clean.endsWith("n")) clean.dropLast(1).toLongOrNull() ?: return null
            else clean.toLongOrNull() ?: return null
        }
        if (DEC_FLOAT_RE.matches(s)) {
            val clean = s.replace("_", "")
            return clean.toDoubleOrNull() ?: return null
        }

        // 字符串：单/双/反引号
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) ||
            (s.startsWith("'") && s.endsWith("'") && s.length >= 2)) {
            return StaticObjectParser.unquoteString(s)
        }
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            return evaluateTemplateLiteral(s) ?: return null
        }

        // 对象字面量
        if (s.startsWith("{") && s.endsWith("}")) {
            return StaticObjectParser.parseObjectLiteralBody(s)
        }
        // 数组字面量
        if (s.startsWith("[") && s.endsWith("]")) {
            return parseArrayLiteralBody(s)
        }

        // 其他（引用、spread、函数调用、三元、运算等）→ 跳过
        return null
    }

    /** 解析数组字面量内部。 */
    fun parseArrayLiteralBody(raw: String): List<Any?> {
        val inner = raw.trim().let { if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it }
        val elements = StaticObjectParser.splitTopLevelArrayElements(inner)
        val result = mutableListOf<Any?>()
        for (e in elements) {
            if (e.isBlank()) continue
            if (e.trimStart().startsWith("...")) continue
            val v = tryParseStaticValue(e)
            if (v != null) result.add(v)
        }
        return result
    }

    // ───────────────────────────────────────────────
    // 内部：进制数字 / 一元运算 / 模板字面量 / 字符串拼接
    // ───────────────────────────────────────────────

    private fun parseRadixNumber(s: String, radix: Int): Long? {
        val clean = s.removePrefix("-").replace("_", "").removeSuffix("n")
        // Kotlin 的 toLongOrNull(radix) 不接受 0x/0b/0o 前缀，必须剥除
        val digits = clean.removePrefix("0x").removePrefix("0X")
            .removePrefix("0b").removePrefix("0B")
            .removePrefix("0o").removePrefix("0O")
        val sign = if (s.startsWith("-")) -1 else 1
        return digits.toLongOrNull(radix)?.let { sign * it }
    }

    private fun applyUnaryPrefix(op: Char, value: Any): Any? = when (value) {
        is Long -> if (op == '-') -value else value
        is Double -> if (op == '-') -value else value
        else -> null
    }

    /**
     * 模板字面量求值：`` `prefix${'静态'}suffix` `` → "prefix静态suffix"。
     * 仅当所有 `${...}` 内部都是静态字面量时才求值；含动态插值返回 null。
     */
    private fun evaluateTemplateLiteral(s: String): String? {
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder()
        var lastEnd = 0
        for (m in STRING_TEMPLATE_INTERPOL_RE.findAll(inner)) {
            sb.append(inner, lastEnd, m.range.first)
            val expr = m.groupValues[1].trim()
            // 嵌套模板字面量在 ${} 内 → 递归求值
            val v = if (expr.startsWith("`") && expr.endsWith("`")) {
                evaluateTemplateLiteral(expr)
            } else {
                tryParseStaticValue(expr)?.toString()
            }
            if (v == null) return null
            sb.append(v)
            lastEnd = m.range.last + 1
        }
        sb.append(inner, lastEnd, inner.length)
        return sb.toString()
    }

    /** 判断是否像字符串拼接（含顶层 `+`）。 */
    private fun looksLikeConcat(s: String): Boolean {
        // 仅当含 + 且首字符是字符串/模板字面量起点时才进入拼接求值（避免误吞算术）
        if (!s.contains("+")) return false
        val first = s.trimStart().firstOrNull() ?: return false
        return first == '\'' || first == '"' || first == '`'
    }

    /**
     * 递归求值字符串拼接：`'a' + 'b' + "c"` → "abc"。
     * 仅当所有操作数都是静态字符串（含模板字面量）时返回拼接结果；否则 null。
     */
    private fun tryEvaluateConcat(s: String): String? {
        val parts = splitTopLevelPlus(s)
        val sb = StringBuilder()
        for (part in parts) {
            val v = tryParseStaticValue(part) ?: return null
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
