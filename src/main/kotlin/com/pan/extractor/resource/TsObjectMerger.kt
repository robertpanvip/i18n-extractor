package com.pan.extractor.resource

import com.pan.extractor.core.RegexCatalog
import com.pan.extractor.project.Util
import com.pan.extractor.staticparser.StaticObjectParser
import com.pan.extractor.staticparser.StaticValueParser
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

/**
 * Resource 层 —— TS/JS 对象字面量翻译资源的**合并 / 重新生成 / spread 引用路由**实现。
 *
 * 迁移自 [com.pan.extractor.editor.TsFileEditor] 的对应方法（实现 1:1），目标架构 Resource 层
 * 的底层实现（与 [TsResourceWriter] / [JsonWriter] 同包自包含，不再反向依赖 TsFileEditor）。
 *
 * 职责：
 *  - [mergeFlatIntoNested]：扁平 key → 嵌套 Map 深合并（点式 key 展开、冲突以新为准、drop 旧 key）；
 *  - [regenerateObjectLiteralBody]：把合并结果按行扫描重写旧对象字面量（静态行改写、非静态行原样保留、新 key 追加）；
 *  - spread 一族（[findSpreadRefs] / [resolveSpreadTarget] / [isUnder] / [relativeKey] / [joinPath]）：
 *    路由 `...common` 等引用到其归属文件；
 *  - [newRegionText] / [applyRangeReplacements]：文本区间替换原语。
 *
 * 解析底层委托 [com.pan.extractor.staticparser.StaticObjectParser] / [StaticValueParser]；
 * JSON 目标复用同包 [JsonWriter.jsonElementToNestedMap]。
 */
object TsObjectMerger {

    private val LOG = Logger.getInstance(TsObjectMerger::class.java)

    // ==========================================================================
    // 合并：existingKV + 新 JSON（都是扁平 key） → 新的嵌套 Map
    //         （深度合并 + 保留旧静态值 + 新 JSON key 若是嵌套的点式 key，先展开）
    // ==========================================================================
    /**
     * 把扁平 Map<String, String> 的翻译资源合并到现有嵌套结构里。
     *  - 扁平 key 若含 "."（如 "common.confirm"）→ 尝试写入嵌套 Map；写不进去就退化为顶层带点的 key。
     *  - 冲突（新 val != 旧 val）：以新 JSON 为准。
     */
    fun mergeFlatIntoNested(
        existingNested: Map<String, Any?>,
        newFlat: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): Map<String, Any?> {
        // 深拷贝一份 existing（mutable），避免修改入参
        val result = deepCloneMap(existingNested)
        for ((k, v) in newFlat) {
            // 判断 key 是否是"点式嵌套"。
            // 只有"每个点分段都非空"才视为真正的嵌套路径（如 common.confirm）。
            // 中英文案里常见的省略号（"加载中..."）会带点，但空分段说明它不是结构嵌套，
            // 必须整体当作一个扁平 key 字面写回，否则会错生成 { '': { '': {...} } }。
            val segments = k.split('.')
            val isCleanDottedPath = k.contains('.') && segments.all { it.isNotBlank() }
            if (isCleanDottedPath && tryWriteNested(result, k, v)) continue
            // 写不进去（中间段冲突且不是对象）或不是干净的点式路径
            // → 退化直接写顶层 key，覆盖已存在的同名 key（重复 key 以新值为准）
            result[k] = v
        }
        // 因子化合并后，原始整句 key（如 "请输入搜索关键词"）已被骨架+差异段承载，
        // 若入口文件里还留有这份旧 key（历史提取），应一并删除，避免与骨架 key 重复。
        for (k in dropExistingKeys) {
            result.remove(k)
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun deepCloneMap(m: Map<String, Any?>): MutableMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in m) {
            result[k] = when (v) {
                is Map<*, *> -> deepCloneMap(v as Map<String, Any?>)
                is List<*> -> (v as List<Any?>).map {
                    when (it) {
                        is Map<*, *> -> deepCloneMap(it as Map<String, Any?>)
                        is List<*> -> (it as List<Any?>).toList()
                        else -> it
                    }
                }.toMutableList()
                else -> v
            }
        }
        return result
    }

    @Suppress("UNCHECKED_CAST")
    private fun tryWriteNested(root: MutableMap<String, Any?>, dottedKey: String, value: String): Boolean {
        val segments = dottedKey.split('.')
        var cur: MutableMap<String, Any?> = root
        for (i in 0 until segments.size - 1) {
            val seg = segments[i]
            when (val next = cur[seg]) {
                is MutableMap<*, *> -> cur = next as MutableMap<String, Any?>
                null -> {
                    val nm = LinkedHashMap<String, Any?>()
                    cur[seg] = nm
                    cur = nm
                }
                else -> return false  // 冲突：中间段已被其他类型（字符串/数组）占用
            }
        }
        cur[segments.last()] = value
        return true
    }

    // ==========================================================================
    // 重新生成对象字面量文本（TS 语法，带换行/缩进，支持嵌套 Map/List/原始值）
    // ==========================================================================
    private data class StaticPropertyLine(
        val key: String,
        val fullKeyExpr: String,    // 引号包裹或 identifier：例如 "common" / common / 'a.b'
        val lineStartInObj: Int,    // 行起始相对 objBody（即 "{\n" 之后）的 offset
        val lineEndInObj: Int,      // 行尾（包含换行为止）
        val trailingComma: Boolean  // 末尾是否有逗号
    )

    /**
     * 将合并后的 nested Map 合并写回到旧的对象字面量文本里。
     * - 旧静态 KV：值相同 → 保留原行（避免格式漂移）；值不同 → 仅替换该行的 value 部分
     * - 旧非静态行（spread/方法/引用/表达式）→ 原样保留
     * - 新 key → 在 } 之前追加，按 key 字典序追加
     */
    fun regenerateObjectLiteralBody(oldObjBody: String, mergedNested: Map<String, Any?>, dropKeys: Set<String> = emptySet()): String {
        val oldBody = oldObjBody.trim().let {
            if (it.startsWith("{") && it.endsWith("}")) it.substring(1, it.length - 1) else it
        }
        val lines = oldBody.split("\n").toMutableList()
        val mergedKeys = mergedNested.keys.toSet()

        // 推断默认缩进单位（首行有内容的 indent）
        val innerIndentUnit = lines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
            .ifBlank { "  " }

        data class SingleRewrite(
            val lineIdx: Int,
            val key: String,
            val colonPosInLine: Int,
            val trailingComma: Boolean,
            val trailingComment: String   // 已有尾注释（如 " // 说明"），重写时保留
        )
        val singleRewrites = ArrayList<SingleRewrite>()
        data class BlockRewrite(
            val start: Int,
            val end: Int,
            val key: String,
            val keyRowPrefix: String,
            val trailingComment: String
        )
        val blockRewrites = ArrayList<BlockRewrite>()
        val parsedTopKeys = HashSet<String>()
        val consumed = BooleanArray(lines.size)

        var idx = 0
        while (idx < lines.size) {
            if (consumed[idx]) { idx++; continue }
            val rawLine = lines[idx]
            val indent = RegexCatalog.LEADING_WHITESPACE.find(rawLine)?.groupValues?.get(1).orEmpty()
            val line = rawLine.trimStart()
            if (line.isBlank() || line.startsWith("//") || line.startsWith("/*")) { idx++; continue }
            val colonPosInTrimmed = findTopLevelColon(line) ?: run { idx++; continue }
            val keyExpr = line.substring(0, colonPosInTrimmed).trim()
            val key = parsePropertyKey(keyExpr) ?: run { idx++; continue }
            parsedTopKeys.add(key)
            val valuePartRaw = line.substring(colonPosInTrimmed + 1).trim()
            val (valuePart, trailingComment) = splitTrailingComment(valuePartRaw)

            if (isSingleLineStaticValue(valuePart)) {
                val trailingComma = valuePart.trimEnd().endsWith(",")
                singleRewrites.add(SingleRewrite(idx, key, indent.length + colonPosInTrimmed, trailingComma, trailingComment))
            } else {
                val isBlock = valuePart.startsWith("{") || valuePart.startsWith("[")
                val blockEnd = if (isBlock) findBlockEndIndex(lines, idx) else idx
                for (j in idx + 1..blockEnd) consumed[j] = true
                val blockText = lines.subList(idx, blockEnd + 1).joinToString("\n")
                val staticBlock = isBlock && !containsNonStaticCollection(blockText)
                if (staticBlock && key in mergedKeys) {
                    val keyColonInRow = indent.length + colonPosInTrimmed
                    val keyRowPrefix = rawLine.substring(0, keyColonInRow + 1)  // "  key:"
                    blockRewrites.add(BlockRewrite(idx, blockEnd, key, keyRowPrefix, trailingComment))
                }
            }
            idx++
        }

        val blockByStart = blockRewrites.associateBy { it.start }
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val block = blockByStart[i]
            if (block != null) {
                if (block.key in dropKeys) { i = block.end + 1; continue }
                val rendered = renderStaticValue(mergedNested[block.key], innerIndentUnit, nestingDepth = 2)
                val rLines = rendered.split("\n")
                val comment = block.trailingComment
                if (rLines.size == 1) {
                    out.add("${block.keyRowPrefix} $rendered,$comment")
                } else {
                    out.add("${block.keyRowPrefix} ${rLines.first()}")
                    out.addAll(rLines.subList(1, rLines.lastIndex))
                    out.add(rLines.last() + ",$comment")
                }
                i = block.end + 1
                continue
            }
            val rw = singleRewrites.firstOrNull { it.lineIdx == i }
            if (rw != null) {
                if (rw.key in dropKeys) { i++; continue }
                if (rw.key in mergedKeys) {
                    val valueStr = renderStaticValue(mergedNested[rw.key], innerIndentUnit, nestingDepth = 1)
                    val prefix = lines[i].substring(0, rw.colonPosInLine + 1)
                    val suffix = if (rw.trailingComma) "," else ""
                    out.add("$prefix $valueStr$suffix${rw.trailingComment}")
                    i++
                    continue
                }
            }
            out.add(lines[i])
            i++
        }

        val newKeys = mergedKeys.filter { it !in parsedTopKeys }.sorted()
        if (newKeys.isNotEmpty()) {
            var insertPos = out.size
            for (k in out.indices.reversed()) {
                if (out[k].isNotBlank()) {
                    insertPos = k + 1
                    val last = out[k]
                    val lastTrimmed = last.trimEnd()
                    val endsWithOpen = lastTrimmed.endsWith(",") || lastTrimmed.endsWith("{") || lastTrimmed.endsWith("[")
                    if (lastTrimmed.isNotEmpty() && !endsWithOpen) {
                        out[k] = last.substring(0, lastTrimmed.length) + "," + last.substring(lastTrimmed.length)
                    }
                    break
                }
            }
            val additions = newKeys.map { k ->
                val keyExpr = if (k.matches(RegexCatalog.IDENTIFIER)) k else quoteForTs(k)
                val valueStr = renderStaticValue(mergedNested[k], innerIndentUnit, nestingDepth = 1)
                "$innerIndentUnit$keyExpr: $valueStr,"
            }
            out.addAll(insertPos, additions)
        }

        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)

        // 保留原始对象体的括号换行风格：若原始对象内部含有换行（多行风格），
        // 则 { 与首属性、} 与前一行各自换行 —— 避免「{ 与首属性被粘到同一行」的格式塌陷；
        // 单行风格（{ a: 1 }）保持同行，不强行拆行。
        val multiLineStyle = oldBody.contains('\n')
        val closeIndent = if (multiLineStyle) {
            // 复刻原始闭合 } 前的缩进（多数顶层级 export 为列 0，嵌套对象可能带缩进）
            val trimmedOuter = oldObjBody.trim()
            val lastNl = trimmedOuter.lastIndexOf('\n')
            if (lastNl >= 0) trimmedOuter.substring(lastNl + 1).takeWhile { it == ' ' || it == '\t' } else ""
        } else ""
        val open = if (multiLineStyle) "{\n" else "{"
        val close = if (multiLineStyle) "\n$closeIndent}" else "}"
        return open + out.joinToString("\n") + close
    }

    /** 从 startIdx 行开始，找到与行首开括号匹配的闭合行索引（含）。 */
    private fun findBlockEndIndex(lines: List<String>, startIdx: Int): Int {
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        var inBlockComment = false
        for (i in startIdx until lines.size) {
            val row = lines[i]
            var j = 0
            while (j < row.length) {
                val c = row[j]
                when {
                    inBlockComment -> {
                        if (c == '*' && j + 1 < row.length && row[j + 1] == '/') { inBlockComment = false; j++ }
                    }
                    escapeNext -> escapeNext = false
                    inString != null -> when (c) {
                        '\\' -> escapeNext = true
                        inString -> inString = null
                    }
                    c == '/' && j + 1 < row.length && row[j + 1] == '/' -> break
                    c == '/' && j + 1 < row.length && row[j + 1] == '*' -> { inBlockComment = true; j++ }
                    else -> when (c) {
                        '"', '\'', '`' -> inString = c
                        '{', '[' -> depth++
                        '}', ']' -> { depth--; if (depth == 0) return i }
                    }
                }
                j++
            }
        }
        return startIdx
    }

    private fun findTopLevelColon(line: String): Int? {
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        for ((i, c) in line.withIndex()) {
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
                    ':' -> if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** 从属性值片段末尾剥离尾注释（行注释或块注释），返回 (纯值, 含前导空白的注释)。 */
    private fun splitTrailingComment(expr: String): Pair<String, String> {
        var inString: Char? = null
        var escapeNext = false
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            val next = expr.getOrNull(i + 1)
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> {
                    if (c == '/' && next == '/') {
                        return expr.substring(0, i).trimEnd() to expr.substring(i)
                    } else if (c == '/' && next == '*') {
                        return expr.substring(0, i).trimEnd() to expr.substring(i)
                    } else when (c) {
                        '"', '\'', '`' -> inString = c
                    }
                }
            }
            i++
        }
        return expr to ""
    }

    private fun isSingleLineStaticValue(expr: String): Boolean {
        val t = expr.trim().trimEnd(',')
        if (t.startsWith("...")) return false
        if (t.contains("=>") || t.startsWith("function")) return false
        val isPrimitive = (t == "true" || t == "false" || t == "null" || t == "undefined" ||
                t.toDoubleOrNull() != null ||
                (t.startsWith("\"") && t.endsWith("\"")) ||
                (t.startsWith("'") && t.endsWith("'")) ||
                (t.startsWith("`") && t.endsWith("`") && !t.substring(1, t.length - 1).contains("\${")) ||
                (t.startsWith("{") && matchingBraces(t) && !containsNonStaticCollection(t)) ||
                (t.startsWith("[") && matchingBraces(t) && !containsNonStaticCollection(t)))
        return isPrimitive
    }

    private fun matchingBraces(s: String): Boolean {
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        for (c in s) {
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
                }
            }
        }
        return depth == 0
    }

    /** 判断一个对象/数组字面量是否含非静态内容（spread `...`、方法、引用、调用等）。 */
    private fun containsNonStaticCollection(text: String): Boolean {
        var v = text.trim().trimEnd(',')
        v = if (v.endsWith("as const")) v.removeSuffix("as const").trim() else v
        var t = v
        val colon = findTopLevelColon(t)
        if (colon != null) {
            val valPart = t.substring(colon + 1).trim()
            if (valPart.startsWith("{") || valPart.startsWith("[")) t = valPart
        }
        val inner = if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]")))
            t.substring(1, t.length - 1) else return false
        if (inner.isBlank()) return false
        for (prop in StaticObjectParser.splitTopLevelProperties(inner)) {
            val p = prop.trim()
            if (p.startsWith("...")) return true
            val colon = findTopLevelColon(p)
            if (colon == null) {
                if (!isSingleLineStaticValue(p)) return true
                continue
            }
            val pv = p.substring(colon + 1).trim()
            if (pv.startsWith("...") || pv.contains("=>") || pv.startsWith("function")) return true
            if (pv.startsWith("{") || pv.startsWith("[")) {
                if (containsNonStaticCollection(pv)) return true
            } else if (!isSingleLineStaticValue(pv)) {
                return true
            }
        }
        return false
    }

    /** 把静态值渲染为 TS 字面量字符串（value 段）。nestingDepth = 当前对象嵌套层数（1 = 对象首层级）。 */
    @Suppress("UNCHECKED_CAST")
    private fun renderStaticValue(value: Any?, indentUnit: String, nestingDepth: Int): String {
        val indent = indentUnit.repeat(nestingDepth)
        val outerIndent = indentUnit.repeat((nestingDepth - 1).coerceAtLeast(0))
        return when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Number -> {
                if (value is Double && value.isNaN()) "null" else value.toString()
            }
            is String -> quoteForTs(value)
            is Map<*, *> -> {
                val m = value as Map<String, Any?>
                if (m.isEmpty()) return "{}"
                val inner = m.entries.joinToString(",\n") { (k, v) ->
                    val keyExpr = if (k.matches(RegexCatalog.IDENTIFIER)) k else quoteForTs(k)
                    val vStr = renderStaticValue(v, indentUnit, nestingDepth + 1)
                    "$indent$keyExpr: $vStr"
                }
                "{\n$inner,\n$outerIndent}"
            }
            is List<*> -> {
                if (value.isEmpty()) return "[]"
                val inner = value.joinToString(",\n") { v ->
                    val vStr = renderStaticValue(v, indentUnit, nestingDepth + 1)
                    "$indent$vStr"
                }
                "[\n$inner,\n$outerIndent]"
            }
            else -> "null"
        }
    }

    private fun quoteForTs(s: String): String {
        val q = when {
            '\'' !in s -> "'"
            '"' !in s -> "\""
            else -> "'"
        }
        val escaped = s.flatMap { c ->
            when {
                c == '\\' -> listOf('\\', '\\')
                c == q.first() -> listOf('\\', q.first())
                c == '\n' -> listOf('\\', 'n')
                c == '\r' -> listOf('\\', 'r')
                c == '\t' -> listOf('\\', 't')
                c.code in 0..8 -> listOf('\\', 'u') + c.code.toString(16).padStart(4, '0').flatMap { listOf(it) }
                else -> listOf(c)
            }
        }.joinToString("")
        return "$q$escaped$q"
    }

    private fun parsePropertyKey(keyPart: String): String? =
        StaticObjectParser.parsePropertyKey(keyPart)

    // ==========================================================================
    // Spread 引用解析：export default { ...common } 中 common 指向同文件 const 或本地 import。
    // ==========================================================================
    internal data class ResolvedSpreadTarget(
        val file: VirtualFile,        // 要写入的文件（同文件 const 时 = 入口文件）
        val objRangeInText: IntRange, // 目标对象在 file 文本中的区间（JSON 目标占位 0..-1）
        val existingKeys: Map<String, Any?>,
        val kind: String,             // "const" | "ts" | "json"
        val readOnly: Boolean = false // node_modules 等只读目标：识别内容但不写盘
    )

    /** 一个 spread 引用：`...varName`，path 为它被展开所在的容器对象路径（顶层为空列表）。 */
    internal data class SpreadRef(val varName: String, val path: List<String>)

    /**
     * 从对象字面量文本中递归提取 spread 引用（含嵌套对象里的 spread，如 `nav: { ...common }`）。
     * path 记录每个 spread 所在的容器路径，用于把新 key 精确路由到对应目标文件。
     */
    internal fun findSpreadRefs(objBody: String, path: List<String>, depth: Int = 0): List<SpreadRef> {
        // 深度防护：字面嵌套极其罕见会超过此深度，防止病态递归导致栈溢出。
        if (depth > 32) return emptyList()
        val result = mutableListOf<SpreadRef>()
        val body = objBody.trim().let {
            if (it.startsWith("{") && it.endsWith("}")) it.substring(1, it.length - 1) else it
        }
        for (prop in StaticObjectParser.splitTopLevelProperties(body)) {
            val t = prop.trim()
            if (t.startsWith("...")) {
                val name = t.removePrefix("...").trim()
                if (name.matches(RegexCatalog.IDENTIFIER)) result.add(SpreadRef(name, path))
                continue
            }
            val (k, v) = StaticObjectParser.parseOneProperty(prop) ?: continue
            val vClean = StaticValueParser.stripValueSuffixes(v)
            if (vClean.startsWith("{") && vClean.endsWith("}")) {
                result.addAll(findSpreadRefs(vClean, path + k, depth + 1))
            }
        }
        return result
    }

    /** 判断 key 是否位于 path 容器之下（path 为空 → 恒 true）。 */
    internal fun isUnder(path: List<String>, key: String): Boolean {
        if (path.isEmpty()) return true
        val prefix = path.joinToString(".")
        return key == prefix || key.startsWith("$prefix.")
    }

    /** 把 key 转成相对 path 容器下的相对 key；key 不在 path 容器下则返回 null。 */
    internal fun relativeKey(path: List<String>, key: String): String? {
        if (path.isEmpty()) return key
        val prefix = path.joinToString(".")
        return if (key.startsWith("$prefix.")) key.removePrefix("$prefix.") else null
    }

    /** 把容器路径 + 相对 key 拼成入口扁平 key。 */
    internal fun joinPath(path: List<String>, k: String): String {
        return if (path.isEmpty()) k else path.joinToString(".") + "." + k
    }

    /** 解析一个 spread 变量指向的目标对象。 */
    internal fun resolveSpreadTarget(
        project: Project,
        entryVf: VirtualFile,
        entryText: String,
        varName: String,
        path: List<String> = emptyList(),
        visited: MutableSet<String> = HashSet()
    ): ResolvedSpreadTarget? {
        // 循环防护：const a = {...b} / const b = {...a} 相互 spread 时避免无限递归。
        if (!visited.add(varName)) return null
        // 1) 同文件 const：const <varName> = { ... }（兼容可选类型标注 const <varName>: T = { ... }）
        val constRe = Regex("""\bconst\s+${Regex.escape(varName)}\s*(?::[^=\n]+)?\s*=\s*\{""")
        val cm = constRe.find(entryText)
        if (cm != null) {
            val eqLocal = cm.value.lastIndexOf('=')
            var bi = cm.range.first + eqLocal + 1
            while (bi < entryText.length && entryText[bi] != '{') bi++
            if (bi >= entryText.length) return null
            val objEnd = StaticObjectParser.findBalancedCloseBrace(entryText, bi) ?: return null
            val objBody = entryText.substring(bi, objEnd)
            val constKeys = StaticObjectParser.parseObjectLiteralBody(objBody)
            val constTarget = ResolvedSpreadTarget(entryVf, bi until objEnd, constKeys, "const")
            if (constKeys.isEmpty()) {
                val inner = findSpreadRefs(objBody, path)
                for (ref in inner) {
                    val deeper = resolveSpreadTarget(project, entryVf, entryText, ref.varName, ref.path, visited)
                    if (deeper != null && !deeper.readOnly && deeper.file.path != entryVf.path) {
                        return deeper
                    }
                }
            }
            return constTarget
        }
        // 2) import：import <varName> from '...'、import * as <varName> from '...'、import <varName>, { ... } from '...'
        val importRe = Regex("""import\s+(?:\*\s+as\s+)?(${Regex.escape(varName)})\s*(?:,\s*\{[^}]*\})?\s+from\s*['"]([^'"]+)['"]""")
        val im = importRe.find(entryText) ?: return null
        val spec = im.groupValues[2]
        // 本地相对/绝对路径优先；裸包名（node_modules）作为只读识别
        val localVf = resolveLocalImportFile(entryVf, spec)
        val targetVf = localVf ?: resolveNodeModulesFile(entryVf, spec) ?: return null
        val readOnly = localVf == null // node_modules → 只读（识别内容但不可写盘）
        val targetText = Util.readVirtualFileText(project, targetVf) ?: return null
        return when (targetVf.extension?.lowercase()) {
            "json" -> {
                val root = try {
                    JsonParser.parseString(targetText)
                } catch (e: Exception) {
                    LOG.warn("TsObjectMerger: 解析 target JSON 失败，返回 null", e)
                    return null
                }
                val existing = JsonWriter.jsonElementToNestedMap(if (root.isJsonObject) root else JsonParser.parseString("{}"))
                ResolvedSpreadTarget(targetVf, 0 until 0, existing, "json", readOnly)
            }
            else -> {
                val info = StaticObjectParser.parseTsExportedObject(targetText) ?: return null
                ResolvedSpreadTarget(targetVf, info.objectRange, info.staticKV, "ts", readOnly)
            }
        }
    }

    /** 把相对/绝对导入路径解析为本地 VirtualFile；裸包名（node_modules）等非本地返回 null。 */
    internal fun resolveLocalImportFile(fromFile: VirtualFile, spec: String): VirtualFile? {
        val clean = spec.trim()
        if (clean.isEmpty() || !(clean.startsWith(".") || clean.startsWith("/"))) return null
        val base = fromFile.parent ?: return null
        val rel = if (clean.startsWith("/")) clean.removePrefix("/") else clean
        val candidates = buildList {
            add(rel)
            if (!rel.substringAfterLast('/').contains('.')) {
                add("$rel.ts"); add("$rel.tsx"); add("$rel.js"); add("$rel.jsx"); add("$rel.json")
            }
            add("$rel/index.ts"); add("$rel/index.js"); add("$rel/index.json")
        }.distinct()
        for (p in candidates) {
            val vf = base.findFileByRelativePath(p) ?: continue
            if (vf.isDirectory) continue
            return vf
        }
        return null
    }

    /**
     * 把裸包名（node_modules）导入解析为实际文件：向上找最近的 node_modules，
     * 用 package.json 的 main 字段优先，否则退化到 index.js/index.json/dist/index.js。
     * 仅用于「识别内容」，返回的文件会被标记为只读，不会写盘。
     */
    private fun resolveNodeModulesFile(fromFile: VirtualFile, spec: String): VirtualFile? {
        val clean = spec.trim()
        if (clean.isEmpty() || clean.startsWith(".") || clean.startsWith("/")) return null
        var dir: VirtualFile? = fromFile.parent
        while (dir != null) {
            val nm = dir.findChild("node_modules")
            if (nm != null) {
                val pkg = nm.findFileByRelativePath(clean)
                if (pkg != null) {
                    if (pkg.isDirectory) {
                        val pkgJson = pkg.findChild("package.json")
                        var main: String? = null
                        if (pkgJson != null) {
                            main = try {
                                val root = JsonParser.parseString(String(pkgJson.contentsToByteArray(), StandardCharsets.UTF_8))
                                root.takeIf { it.isJsonObject }?.asJsonObject?.get("main")
                                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                            } catch (e: Exception) {
                                LOG.debug("TsObjectMerger: 解析包 main 字段失败，回退内置候选", e)
                                null
                            }
                        }
                        val candidates = buildList {
                            if (!main.isNullOrEmpty()) add(main)
                            add("index.js"); add("index.json"); add("dist/index.js")
                        }.distinct()
                        for (c in candidates) {
                            val vf = pkg.findFileByRelativePath(c) ?: continue
                            if (vf.isDirectory) continue
                            return vf
                        }
                    } else if (pkg.extension in setOf("js", "json", "mjs", "cjs")) {
                        return pkg
                    }
                }
            }
            dir = dir.parent
        }
        return null
    }

    /** 计算某个对象区间在给定文本中的新文本（基于合并后的扁平 key）。 */
    internal fun newRegionText(text: String, objRange: IntRange, newFlat: Map<String, String>, existing: Map<String, Any?>, dropExistingKeys: Set<String> = emptySet()): String {
        val merged = mergeFlatIntoNested(existing, newFlat, dropExistingKeys)
        val oldObjBody = text.substring(objRange.first, objRange.last + 1)
        return regenerateObjectLiteralBody(oldObjBody, merged, dropExistingKeys)
    }

    /** 对同一文本应用多处区间替换（按区间从后往前，避免偏移漂移）。 */
    internal fun applyRangeReplacements(text: String, replacements: List<Pair<IntRange, String>>): String {
        var result = text
        for ((range, newText) in replacements.sortedByDescending { it.first.last }) {
            result = result.substring(0, range.first) + newText + result.substring(range.last + 1)
        }
        return result
    }
}