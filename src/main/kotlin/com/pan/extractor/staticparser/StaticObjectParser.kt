package com.pan.extractor.staticparser

/**
 * staticparser 子包 —— TS/JS 翻译文件的静态解析层。
 *
 * 把 [com.pan.extractor.TsFileEditor] 中**纯文本级**的静态解析逻辑抽离到此处，
 * 形成独立的、可纯单元测试的解析模块（不依赖 IntelliJ PSI / Application）。
 *
 * 职责拆分（5 个对象，各司其职）：
 *  - [ExportAnchorFinder]：定位 export 对象字面量起点（6 种 export 模式）+ 推断缩进；
 *  - [StringUnquoter]：字符串字面量反引用（\n \t \uXXXX \xXX 等转义）；
 *  - [NumericLiteralParser]：数字字面量解析（进制/BigInt/科学计数/分隔符）；
 *  - [TemplateLiteralEvaluator]：模板字面量带静态插值 + 字符串拼接求值；
 *  - [StaticObjectParser]：对象字面量结构解析（属性/key/数组元素拆分）；
 *  - [StaticValueParser]：静态值编排入口（字面量 + 一元 + 类型断言）。
 *
 * 写回（合并 + 重新生成 + 写盘）仍在 [com.pan.extractor.resource] 层；
 * 本包只做"读"侧的静态识别，是 Resource 层与 LocaleMessages 的共享底层。
 */

/**
 * TS/JS 文件 export 对象的解析结果。
 * （迁移自 TsFileEditor.TsExportedObjectInfo，字段 1:1）
 */
data class TsExportedObjectInfo(
    /** 对象字面量 { ... } 在文件文本中的 [start, end)。 */
    val objectRange: IntRange,
    /** 静态可确定的 KV（嵌套 Map / List / String / Number / Boolean / null）。 */
    val staticKV: Map<String, Any?>,
    /** 导出形式："default" / "named:<name>" / "module.exports" / "exports" / "default:<wrapper>"。 */
    val exportType: String,
    /** 推断的缩进（2 spaces / 4 spaces / tab），用于重新生成。 */
    val indentUnit: String,
)

/**
 * 对象字面量结构解析器（无状态，线程安全）。
 *
 * 职责：解析 { ... } 内部的属性/key/数组元素拆分。
 * export 起点定位委托 [ExportAnchorFinder]；字符串反引用委托 [StringUnquoter]。
 */
object StaticObjectParser {

    /**
     * 从 TS/JS 文件内容中找到 export default / export const / module.exports 对应的
     * 对象字面量，并抽取其中的静态 key-value。
     */
    fun parseTsExportedObject(text: String): TsExportedObjectInfo? {
        val anchor = ExportAnchorFinder.find(text) ?: return null
        val objEnd = findBalancedCloseBrace(text, anchor.objBraceStart) ?: return null
        val objBody = text.substring(anchor.objBraceStart, objEnd)
        val staticKV = parseObjectLiteralBody(objBody)
        return TsExportedObjectInfo(
            objectRange = anchor.objBraceStart until objEnd,
            staticKV = staticKV,
            exportType = anchor.exportType,
            indentUnit = anchor.indentUnit,
        )
    }

    /** 找到与 [openIdx] 处 `{` 配对的闭合 `}`（含字符串/注释/嵌套）；不配对返回 null。 */
    fun findBalancedCloseBrace(text: String, openIdx: Int): Int? {
        if (openIdx < 0 || openIdx >= text.length || text[openIdx] != '{') return null
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        var inLineComment = false
        var inBlockComment = false
        var i = openIdx
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            when {
                inLineComment -> { if (c == '\n') inLineComment = false }
                inBlockComment -> { if (c == '*' && next == '/') { inBlockComment = false; i++ } }
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> {
                    if (c == '/' && next == '/') { inLineComment = true; i++ }
                    else if (c == '/' && next == '*') { inBlockComment = true; i++ }
                    else when (c) {
                        '"', '\'', '`' -> inString = c
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0 && c == '}') return i + 1
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    /** 解析对象字面量 { ... } 内部的静态 KV。 */
    fun parseObjectLiteralBody(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        val stripped = raw.trim()
        val body = if (stripped.startsWith("{") && stripped.endsWith("}")) {
            stripped.substring(1, stripped.length - 1)
        } else stripped
        val result = LinkedHashMap<String, Any?>()
        for (prop in splitTopLevelProperties(body)) {
            val (k, vExpr) = parseOneProperty(prop) ?: continue
            val value = StaticValueParser.tryParseStaticValue(vExpr) ?: continue
            result[k] = value
        }
        return result
    }

    /** 把 { ... } 内部按顶层逗号拆成属性列表（处理嵌套 {} [] 字符串 注释）。 */
    fun splitTopLevelProperties(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        var inLineComment = false
        var inBlockComment = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            val next = body.getOrNull(i + 1)
            when {
                inLineComment -> { if (c == '\n') inLineComment = false }
                inBlockComment -> { if (c == '*' && next == '/') { inBlockComment = false; i++ } }
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> {
                    if (c == '/' && next == '/') { inLineComment = true; i++ }
                    else if (c == '/' && next == '*') { inBlockComment = true; i++ }
                    else when (c) {
                        '"', '\'', '`' -> inString = c
                        '{', '[' -> depth++
                        '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
                        ',' -> if (depth == 0) {
                            parts += body.substring(start, i)
                            start = i + 1
                        }
                    }
                }
            }
            i++
        }
        if (start < body.length) parts += body.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 解析单个属性，返回 (key, valueExpr)；shorthand/方法简写返回 null。 */
    fun parseOneProperty(prop: String): Pair<String, String>? {
        val body = stripLeadingComments(prop)
        var inString: Char? = null
        var escapeNext = false
        var depth = 0
        var colonIdx = -1
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    ':' -> if (depth == 0 && colonIdx == -1) colonIdx = i
                }
            }
            i++
        }
        if (colonIdx < 0) return null
        val keyPart = body.substring(0, colonIdx).trim()
        val valuePart = body.substring(colonIdx + 1).trim()
        val key = parsePropertyKey(keyPart) ?: return null
        return key to valuePart
    }

    /** 剥离属性片段前导的 // 行注释或 /* */ 块注释（可含多行）。 */
    private fun stripLeadingComments(s: String): String {
        var text = s.trimStart()
        var changed = true
        while (changed && text.isNotEmpty()) {
            changed = false
            if (text.startsWith("//")) {
                val nl = text.indexOf('\n')
                text = if (nl < 0) "" else text.substring(nl + 1).trimStart()
                changed = true
            } else if (text.startsWith("/*")) {
                val end = text.indexOf("*/")
                text = if (end < 0) "" else text.substring(end + 2).trimStart()
                changed = true
            }
        }
        return text
    }

    /** 解析属性 key：foo / 'foo' / "foo" / `foo` / [123] / [foo]。 */
    fun parsePropertyKey(keyPart: String): String? {
        if (keyPart.startsWith("[") && keyPart.endsWith("]")) {
            val inner = keyPart.substring(1, keyPart.length - 1).trim()
            return when {
                (inner.startsWith("\"") && inner.endsWith("\"")) ||
                        (inner.startsWith("'") && inner.endsWith("'")) ||
                        (inner.startsWith("`") && inner.endsWith("`")) ->
                    StringUnquoter.unquote(inner)
                inner.toIntOrNull() != null -> inner
                inner.toDoubleOrNull() != null -> inner
                else -> null
            }
        }
        if ((keyPart.startsWith("\"") && keyPart.endsWith("\"")) ||
            (keyPart.startsWith("'") && keyPart.endsWith("'")) ||
            (keyPart.startsWith("`") && keyPart.endsWith("`"))) {
            return StringUnquoter.unquote(keyPart)
        }
        if (keyPart.matches(UNICODE_IDENTIFIER_RE)) return keyPart
        return null
    }

    /** 允许任意 Unicode 字母开头的裸 key 标识符（中日韩/法语/德语/俄语/阿拉伯语等）。 */
    private val UNICODE_IDENTIFIER_RE = Regex("""[\p{L}_$][\p{L}\p{N}\p{M}_$]*""")

    /** 去掉字符串两侧成对的引号并解析转义（委托 [StringUnquoter]）。 */
    fun unquoteString(s: String): String = StringUnquoter.unquote(s)

    /** 拆分数组字面量顶层元素（按逗号切，处理嵌套与字符串）。 */
    fun splitTopLevelArrayElements(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{', '[' -> depth++
                    '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
                    ',' -> if (depth == 0) {
                        parts += body.substring(start, i)
                        start = i + 1
                    }
                }
            }
            i++
        }
        if (start < body.length) parts += body.substring(start)
        return parts
    }
}
