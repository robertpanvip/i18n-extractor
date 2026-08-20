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

    /**
     * 递归深度上限。静态解析包含模板字面量、对象/数组、字符串拼接、一元运算等多处相互递归，
     * 且 concat 片段等可彼此嵌套。设一个硬上限：一旦超过即按"非静态"返回 null，
     * 保证无论输入如何病态（指数级嵌套/病态拼接）都不可能再触发 StackOverflowError。
     * 512 层对正常产物（嵌套 ≤ 数十层）绰绰有余，开销仅是每次递归比一个 int。
     */
    private const val MAX_PARSE_DEPTH = 512

    /** 数字字面量正则（含进制/BigInt/科学计数/分隔符），高频复用避免重编译。 */
    private val HEX_RE = Regex("""-?0[xX][0-9a-fA-F]+(_[0-9a-fA-F]+)*n?""")
    private val BIN_RE = Regex("""-?0[bB][01]+(_[01]+)*n?""")
    private val OCT_RE = Regex("""-?0[oO][0-7]+(_[0-7]+)*n?""")
    private val DEC_INT_RE = Regex("""-?\d+(_\d+)*n?""")
    private val DEC_FLOAT_RE = Regex("""-?(\d+(_\d+)*)?\.\d+(_\d+)*([eE][+-]?\d+)?|-?\d+(_\d+)*[eE][+-]?\d+""")
    private val STRING_TEMPLATE_INTERPOL_RE = Regex("""\$\{([^}]*)\}""")

    /**
     * 去掉值表达式尾部与静态判定无关的 TS 后缀（as Type / as const / satisfies Type）。
     *
     * 实现说明：**不使用正则表达式**。
     * 原先用 `[\w$.<>\[\],()|&'" ?]+$` 贪婪匹配类型字符，因字符类中含成对括号 + 贪心量词 + `$`，
     * 会在 Java 正则编译阶段因 NFA 状态机构建发生**递归栈溢出**（PatternSyntaxException wrapped StackOverflowError）。
     * 改为括号深度扫描：定位关键字位置 → 从该位置向后计数括号/字符串深度 → 到行尾且未越界时视为合法类型后缀，
     * O(n) 无回溯，同时避免误把字符串内部的 `as` / `satisfies` 子串当成类型断言关键字。
     */
    fun stripValueSuffixes(expr: String): String {
        var t = expr.trim()
        var changed = true
        while (changed) {
            changed = false
            if (t.endsWith(" as const")) {
                t = t.removeSuffix(" as const").trim(); changed = true; continue
            }
            // 在"不在字符串 / 注释内部"的前提下，从右向左找最后一个 ` as ` 关键字
            val asIdx = findToplevelKeywordLast(t, " as ")
            if (asIdx >= 0 && isBalancedTypeSuffix(t, asIdx + " as ".length)) {
                t = t.substring(0, asIdx).trim(); changed = true; continue
            }
            val satIdx = findToplevelKeywordLast(t, " satisfies ")
            if (satIdx >= 0 && isBalancedTypeSuffix(t, satIdx + " satisfies ".length)) {
                t = t.substring(0, satIdx).trim(); changed = true; continue
            }
        }
        return t
    }

    /**
     * 左到右单次扫描，返回最后一个"不在字符串/反引号模板字面量内部"的 [keyword] 起点下标。
     * 未找到返回 -1。[keyword] 必须以空格开头并结尾，以此自然保证关键字的词边界；
     * 同时额外校验前后字符非 Java 标识符一部分，避免把 `hasAssertion` 等标识符误拆。
     */
    private fun findToplevelKeywordLast(s: String, keyword: String): Int {
        var topIdx = -1
        var inString: Char? = null
        var escapeNext = false
        var k = 0
        while (k <= s.length - keyword.length) {
            val c = s[k]
            when {
                escapeNext -> { escapeNext = false; k++ }
                inString != null -> {
                    when (c) {
                        '\\' -> escapeNext = true
                        inString -> inString = null
                    }
                    k++
                }
                else -> {
                    if (c == '"' || c == '\'' || c == '`') {
                        inString = c; k++
                    } else if (s.regionMatches(k, keyword, 0, keyword.length, ignoreCase = false)) {
                        // keyword 形如 " as " / " satisfies "，首尾自带空格，天然不可能嵌入标识符内，
                        // 因此不必做前后字符边界校验（额外校验会把类型名首字符 / 表达式尾字符
                        // 误判为"标识符的一部分"，导致所有合法类型后缀都匹配不到）。
                        topIdx = k
                        k += keyword.length
                    } else {
                        k++
                    }
                }
            }
        }
        return topIdx
    }

    /**
     * 校验 `s[from..end)` 是否是一个"括号/字符串边界都闭合的 TS 类型后缀"（允许末尾空白）。
     * 遇到未闭合的右括号（深度 < 0）直接返回 false；行末深度必须 >= 0（对多余的右括号
     * 保守放行，因为它们本就不属于当前表达式，大概率是外层对象/数组的闭合符）。
     * 另：作为"类型后缀"，起点必须紧跟关键字，不能是表达式主语法里的右括号/逗号等 ——
     * 但因为我们已经用关键字切割，只需检查括号深度平衡即可。
     */
    private fun isBalancedTypeSuffix(s: String, from: Int): Boolean {
        if (from > s.length) return false
        val trail = s.substring(from)
        // 类型后缀不能为空
        if (trail.isBlank()) return false

        var depthAng = 0   // <>
        var depthSqb = 0   // []
        var depthPar = 0   // ()
        var depthCur = 0   // {}（理论上类型里不出对象字面量，但防御性统计）
        var inString: Char? = null
        var escapeNext = false
        // 使用带下标循环（而非 for-in），便于在遇到 `->`（函数类型箭头）时一次跳 2 字符，
        // 防止箭头中的 `>` 被误计为泛型的闭合尖括号。
        var i = 0
        while (i < trail.length) {
            val c = trail[i]
            when {
                escapeNext -> { escapeNext = false; i++ }
                inString != null -> {
                    when (c) {
                        '\\' -> escapeNext = true
                        inString -> inString = null
                    }
                    i++
                }
                else -> {
                    // 函数类型箭头 `->`：双字符跳过，不参与括号计数
                    if (c == '-' && i + 1 < trail.length && trail[i + 1] == '>') {
                        i += 2; continue
                    }
                    when (c) {
                        '"', '\'', '`' -> inString = c
                        '<' -> depthAng++
                        '>' -> depthAng = (depthAng - 1).also { if (it < 0) return false }
                        '[' -> depthSqb++
                        ']' -> depthSqb = (depthSqb - 1).also { if (it < 0) return false }
                        '(' -> depthPar++
                        ')' -> depthPar = (depthPar - 1).also { if (it < 0) return false }
                        '{' -> depthCur++
                        '}' -> depthCur = (depthCur - 1).also { if (it < 0) return false }
                    }
                    i++
                }
            }
        }
        // 字符串未闭合 → 不是合法类型后缀
        if (inString != null) return false
        // 括号深度全部归零才算合法
        return depthAng == 0 && depthSqb == 0 && depthPar == 0 && depthCur == 0
    }

    /** 尝试把一个表达式片段解析为静态值；非静态返回 null。 */
    fun tryParseStaticValue(expr: String): Any? = parseStaticValue(expr, 0)

    /** 递归核心：所有相互递归统一经过 [depth] 硬上限，超限即返回 null（杜绝栈溢出）。 */
    internal fun parseStaticValue(expr: String, depth: Int): Any? {
        if (depth > MAX_PARSE_DEPTH) return null
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
                val inner = parseStaticValue(s.substring(1), depth + 1) ?: return null
                val b = inner as? Boolean ?: return null
                return !b
            }
            s.startsWith("-") || s.startsWith("+") -> {
                val inner = parseStaticValue(s.substring(1), depth + 1) ?: return null
                return applyUnaryPrefix(s[0], inner) ?: return null
            }
        }

        // 字符串拼接（全字面量）：'a' + 'b' + "c" + `d`
        // 注意：必须先验证确实存在顶层 `+`（splitTopLevelPlus 切出 ≥2 段）才进入拼接求值。
        // 若 `+` 全部位于字符串内部（如 'a+b'），splitTopLevelPlus 只会返回 1 段，
        // 此时若照常走 tryEvaluateConcat 会以【同一段】再次进入本函数 → 无限递归 → 栈溢出；
        // 落回字面量分支则能正确解析出普通字符串 "a+b"。
        val concatParts = if (looksLikeConcat(s)) splitTopLevelPlus(s) else emptyList()
        if (concatParts.size >= 2) {
            return tryEvaluateConcat(concatParts, depth + 1)
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
            return evaluateTemplateLiteral(s, depth + 1) ?: return null
        }

        // 对象字面量
        if (s.startsWith("{") && s.endsWith("}")) {
            return StaticObjectParser.parseObjectLiteralBody(s, depth + 1)
        }
        // 数组字面量
        if (s.startsWith("[") && s.endsWith("]")) {
            return parseArrayLiteralBody(s, depth + 1)
        }

        // 其他（引用、spread、函数调用、三元、运算等）→ 跳过
        return null
    }

    /** 解析数组字面量内部。 */
    private fun parseArrayLiteralBody(raw: String, depth: Int): List<Any?> {
        val inner = raw.trim().let { if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it }
        val elements = StaticObjectParser.splitTopLevelArrayElements(inner)
        val result = mutableListOf<Any?>()
        for (e in elements) {
            if (e.isBlank()) continue
            if (e.trimStart().startsWith("...")) continue
            val v = parseStaticValue(e, depth + 1)
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
    private fun evaluateTemplateLiteral(s: String, depth: Int): String? {
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder()
        var lastEnd = 0
        for (m in STRING_TEMPLATE_INTERPOL_RE.findAll(inner)) {
            sb.append(inner, lastEnd, m.range.first)
            val expr = m.groupValues[1].trim()
            // 嵌套模板字面量在 ${} 内 → 递归求值
            val v = if (expr.startsWith("`") && expr.endsWith("`")) {
                evaluateTemplateLiteral(expr, depth + 1)
            } else {
                parseStaticValue(expr, depth + 1)?.toString()
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
     * 递归求值字符串拼接：`['a', 'b', '"c"']` → "abc"。
     * 仅当所有操作数都是静态字符串（含模板字面量）时返回拼接结果；否则 null。
     * [parts] 必须已由 [splitTopLevelPlus] 切为 ≥2 段（调用方保证），避免对单段无限递归。
     */
    private fun tryEvaluateConcat(parts: List<String>, depth: Int): String? {
        val sb = StringBuilder()
        for (part in parts) {
            val v = parseStaticValue(part, depth + 1) ?: return null
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
