package com.pan.extractor.staticparser

/**
 * 静态值解析器 —— 把单个表达式片段解析为静态值的编排入口。
 *
 * 拆分后只保留**编排逻辑**（字面量分发 + 一元运算 + 类型断言剥除）；
 * 具体识别委托：
 *  - [NumericLiteralParser]：数字字面量（进制/BigInt/科学计数/分隔符）；
 *  - [TemplateLiteralEvaluator]：模板字面量带静态插值 + 字符串拼接；
 *  - [StringUnquoter]：字符串反引用；
 *  - [StaticObjectParser]：对象/数组字面量结构。
 *
 * 非静态（返回 null，由调用方按"整条保留"处理）：
 *  - 标识符引用（变量、import 绑定）
 *  - 函数调用（除已知常量）
 *  - spread（...arr）
 *  - 三元 / 逻辑运算（含变量）
 *  - 模板字面量含动态插值（`` `a${var}` ``）
 */
object StaticValueParser {

    /** 去掉值表达式尾部与静态判定无关的 TS 后缀（as Type / as const / satisfies Type，链式剥除）。 */
    fun stripValueSuffixes(expr: String): String {
        var t = expr.trim()
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
        if (TemplateLiteralEvaluator.looksLikeConcat(s)) {
            return TemplateLiteralEvaluator.evaluateConcat(s)
        }

        // 数字字面量（进制/BigInt/科学计数/分隔符）
        if (NumericLiteralParser.looksLikeNumber(s)) {
            return NumericLiteralParser.parse(s)
        }

        // 字符串：单/双/反引号
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) ||
            (s.startsWith("'") && s.endsWith("'") && s.length >= 2)) {
            return StringUnquoter.unquote(s)
        }
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            return TemplateLiteralEvaluator.evaluateTemplate(s) ?: return null
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

    private fun applyUnaryPrefix(op: Char, value: Any): Any? = when (value) {
        is Long -> if (op == '-') -value else value
        is Double -> if (op == '-') -value else value
        else -> null
    }
}
