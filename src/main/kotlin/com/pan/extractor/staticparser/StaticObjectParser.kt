package com.pan.extractor.staticparser

/**
 * staticparser 子包 —— TS/JS 翻译文件的静态解析层。
 *
 * 把 [com.pan.extractor.editor.TsFileEditor] 中**纯文本级**的静态解析逻辑抽离到此处，
 * 形成独立的、可纯单元测试的解析模块（不依赖 IntelliJ PSI / Application）。
 *
 * 职责：
 *  - [StaticObjectParser.parseTsExportedObject]：从 TS/JS 文件文本找到 export default/const/module.exports
 *    对应的对象字面量，抽取其中的静态 KV（嵌套 Map / List / 字面量）；
 *  - [StaticObjectParser.parseObjectLiteralBody]：解析对象字面量内部的静态 KV；
 *  - [StaticValueParser.tryParseStaticValue]：把单个表达式片段解析为静态值（覆盖所有字面量形态）。
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
 * 静态对象字面量解析器（无状态，线程安全）。
 * 迁移自 [com.pan.extractor.editor.TsFileEditor] 的解析方法群（行为 1:1，TsFileEditor 改为委托）。
 */
object StaticObjectParser {

    private data class ExportAnchor(
        val objBraceStart: Int,
        val exportType: String,
        val indentUnit: String,
    )

    /**
     * 从 TS/JS 文件内容中找到 export default / export const / module.exports 对应的
     * 对象字面量，并抽取其中的静态 key-value。
     */
    fun parseTsExportedObject(text: String): TsExportedObjectInfo? {
        val (objStart, exportType, indentUnit) = findExportedObjectStart(text) ?: return null
        val objEnd = findBalancedCloseBrace(text, objStart) ?: return null
        val objBody = text.substring(objStart, objEnd)
        val staticKV = parseObjectLiteralBody(objBody)
        return TsExportedObjectInfo(
            objectRange = objStart until objEnd,
            staticKV = staticKV,
            exportType = exportType,
            indentUnit = indentUnit,
        )
    }

    private fun findExportedObjectStart(text: String): ExportAnchor? {
        // 模式 1：export default {
        run {
            val re = Regex("""export\s+default\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.range.last
                return ExportAnchor(braceIdx, "default", inferIndent(text, braceIdx))
            }
        }
        // 模式 2：export default <name> = {
        run {
            val re = Regex("""export\s+default\s+[\w$][\w$]*\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "default", inferIndent(text, braceIdx))
            }
        }
        // 模式 3：export const <name> = { / export let / export var（含可选类型标注）
        run {
            val re = Regex("""export\s+(const|let|var)\s+([\w$][\w$]*)\s*(?::[^=\n]+)?\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val name = m.groupValues[2]
                val eqLocal = m.value.lastIndexOf('=')
                var i = m.range.first + eqLocal + 1
                while (i < text.length && text[i] != '{') i++
                if (i >= text.length) return@run
                return ExportAnchor(i, "named:$name", inferIndent(text, i))
            }
        }
        // 模式 4：module.exports = {
        run {
            val re = Regex("""module\.exports\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "module.exports", inferIndent(text, braceIdx))
            }
        }
        // 模式 5：exports = {
        run {
            val re = Regex("""(^|;)\s*exports\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "exports", inferIndent(text, braceIdx))
            }
        }
        // 模式 6：export default defineXxx({ ... }) —— i18n 常用包裹函数
        run {
            val re = Regex("""export\s+default\s+([A-Za-z_$][\w$]*)\s*(?:<[^()]*>)?\s*\(\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "default:${m.groupValues[1]}", inferIndent(text, braceIdx))
            }
        }
        // 模式 7：export default <name>; —— <name> 指向同文件 const/let/var <name> = {...} 的对象字面量。
        // 覆盖「先定义对象字面量、再导出一份引用」这一常见翻译入口形态（如 `const messages = {...}; export default messages`）。
        // findBalancedCloseBrace 会跳过 export default <name> 行，因此必须回推到 <name> 定义处的对象字面量。
        run {
            val refRe = Regex("""\bexport\s+default\s+([A-Za-z_$][\w$]*)""")
            val rm = refRe.find(text) ?: return@run
            val name = rm.groupValues[1]
            val constRe = Regex("""\b(const|let|var)\s+${Regex.escape(name)}\s*(?::[^=\n]*)?\s*=\s*\{""")
            val cm = constRe.find(text) ?: return@run
            val eqLocal = cm.value.lastIndexOf('=')
            var bi = cm.range.first + eqLocal + 1
            while (bi < text.length && text[bi] != '{') bi++
            if (bi >= text.length) return@run
            return ExportAnchor(bi, "named:$name", inferIndent(text, bi))
        }
        return null
    }

    private fun inferIndent(text: String, braceIdx: Int): String {
        var lineStart = braceIdx
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val wsPrefix = text.substring(lineStart, braceIdx).takeWhile { it == ' ' || it == '\t' }
        if (wsPrefix.isNotEmpty()) return wsPrefix
        return "  "
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

    /** 解析对象字面量 { ... } 内部的静态 KV。[depth] 透传递归深度上限，避免深层嵌套栈溢出。 */
    fun parseObjectLiteralBody(raw: String, depth: Int = 0): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        val stripped = raw.trim()
        val body = if (stripped.startsWith("{") && stripped.endsWith("}")) {
            stripped.substring(1, stripped.length - 1)
        } else stripped
        val result = LinkedHashMap<String, Any?>()
        val props = splitTopLevelProperties(body)
        for (prop in props) {
            val (k, vExpr) = parseOneProperty(prop) ?: continue
            val value = StaticValueParser.parseStaticValue(vExpr, depth + 1) ?: continue
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
                    unquoteString(inner)
                inner.toIntOrNull() != null -> inner
                inner.toDoubleOrNull() != null -> inner
                else -> null
            }
        }
        if ((keyPart.startsWith("\"") && keyPart.endsWith("\"")) ||
            (keyPart.startsWith("'") && keyPart.endsWith("'")) ||
            (keyPart.startsWith("`") && keyPart.endsWith("`"))) {
            return unquoteString(keyPart)
        }
        if (keyPart.matches(UNICODE_IDENTIFIER_RE)) return keyPart
        return null
    }

    /** 允许任意 Unicode 字母开头的裸 key 标识符（中日韩/法语/德语/俄语/阿拉伯语等）。 */
    private val UNICODE_IDENTIFIER_RE = Regex("""[\p{L}_$][\p{L}\p{N}\p{M}_$]*""")

    /** 去掉字符串两侧成对的引号并解析转义。 */
    fun unquoteString(s: String): String {
        if (s.length < 2) return s
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val nc = inner[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    '0' -> sb.append('\u0000')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '`' -> sb.append('`')
                    'u' -> {
                        // \uXXXX 形式的 Unicode 转义
                        if (i + 6 <= inner.length) {
                            val hex = inner.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(Char(code))
                                i += 6
                                continue
                            }
                        }
                        sb.append('u'); i += 2
                    }
                    'x' -> {
                        // \xXX 形式的十六进制转义
                        if (i + 4 <= inner.length) {
                            val hex = inner.substring(i + 2, i + 4)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(Char(code))
                                i += 4
                                continue
                            }
                        }
                        sb.append('x'); i += 2
                    }
                    else -> sb.append(nc)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

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
