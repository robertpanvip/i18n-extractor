package com.pan.extractor.editor

import com.pan.extractor.project.Util
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

object TsFileEditor {
    // ==========================================================================
    // TS 文件：解析 export default / export const 对象字面量 → 嵌套 Map
    //         遇到无法确定的表达式跳过（整条属性整条跳过，不抛错）
    // ==========================================================================
    /** 解析结果：类型别名指向 staticparser 包的真实实现，旧引用保持兼容。 */
    typealias TsExportedObjectInfo = com.pan.extractor.staticparser.TsExportedObjectInfo

    /**
     * 从 TS/JS 文件内容中找到 export default / export const / module.exports 对应的对象字面量，
     * 并抽取其中的静态 key-value。
     * 实现已迁入 com.pan.extractor.staticparser.StaticObjectParser，此处委托（行为 1:1）。
     */
    fun parseTsExportedObject(text: String): TsExportedObjectInfo? =
        com.pan.extractor.staticparser.StaticObjectParser.parseTsExportedObject(text)

    fun parseObjectLiteralBody(raw: String): Map<String, Any?> =
        com.pan.extractor.staticparser.StaticObjectParser.parseObjectLiteralBody(raw)

    internal fun findBalancedCloseBrace(text: String, openIdx: Int): Int? =
        com.pan.extractor.staticparser.StaticObjectParser.findBalancedCloseBrace(text, openIdx)

    internal fun splitTopLevelProperties(body: String): List<String> =
        com.pan.extractor.staticparser.StaticObjectParser.splitTopLevelProperties(body)

    internal fun parseOneProperty(prop: String): Pair<String, String>? =
        com.pan.extractor.staticparser.StaticObjectParser.parseOneProperty(prop)

    internal fun parsePropertyKey(keyPart: String): String? =
        com.pan.extractor.staticparser.StaticObjectParser.parsePropertyKey(keyPart)

    internal fun unquoteString(s: String): String =
        com.pan.extractor.staticparser.StaticObjectParser.unquoteString(s)

    internal fun stripValueSuffixes(expr: String): String =
        com.pan.extractor.staticparser.StaticValueParser.stripValueSuffixes(expr)

    internal fun splitTopLevelArrayElements(body: String): List<String> =
        com.pan.extractor.staticparser.StaticObjectParser.splitTopLevelArrayElements(body)

    @Suppress("unused")
    private data class ExportAnchorRemovedMarker(val placeholder: Unit = Unit)

    // 以下方法实现已迁入 staticparser 包（StaticObjectParser / StaticValueParser），
    // 此处保留为 internal 委托门面（见上方各 fun 定义）。原 private 解析辅助已删除。
    @Suppress("unused") private fun migratedMarker() = Unit

    // findBalancedCloseBrace / parseObjectLiteralBody / splitTopLevelProperties / parseOneProperty /
    // parsePropertyKey / unquoteString / stripValueSuffixes / splitTopLevelArrayElements 等解析方法
    // 实现已迁入 staticparser 包（见文件顶部 internal 委托）。原 private 解析辅助已删除。

    /*
     * 以下保留 merge / regenerate / spread 等写回相关方法（仍在本 object 内）。
     * 旧 parseOneProperty / stripLeadingComments / parsePropertyKey / UNICODE_IDENTIFIER_RE /
     * unquoteString / stripValueSuffixes / tryParseStaticValue / parseArrayLiteralBody /
     * splitTopLevelArrayElements 实现已迁入 staticparser 包，委托见文件顶部。
     */

    // ==========================================================================
    // 合并：existingKV + 新 JSON（都是扁平 key） → 新的嵌套 Map
    //         （为了简化写回，这里采用"深度合并 + 保留旧静态值 + 新 JSON key 若是嵌套的点式 key，先展开"）
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
    // 策略：
    //   · 旧对象中存在"非静态表达式占位属性"（方法/spread/引用等）→ 需要保留原样
    //   · 我们的做法：**按行扫描原对象字面量**，识别"静态属性行"（可精确匹配 key）→ 按合并后的新值重写；
    //     非静态行/无法识别的行 → 原样保留；
    //     新 key（合并后新增、旧对象没有的）→ 追加到对象末尾（} 之前）。
    //   · 这样既不会把用户写的 spread / 函数 / 引用弄丢，也能完整合并新值。
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
        // 方式：先扫描旧对象，识别每个顶层属性；单行静态值 → 行内重写；
        // 多行对象/数组块且 key 在合并结果中 → 整块重写（从而能合入"点式"新增的嵌套子 key）；
        // 非静态行（spread/方法/引用）→ 原样保留。最后追加全新顶层 key。
        val oldBody = oldObjBody.trim().let {
            if (it.startsWith("{") && it.endsWith("}")) it.substring(1, it.length - 1) else it
        }
        val lines = oldBody.split("\n").toMutableList()
        val mergedKeys = mergedNested.keys.toSet()

        // 推断默认缩进单位（首行有内容的 indent）
        val innerIndentUnit = lines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
            .ifBlank { "  " }

        // 第一遍扫描：
        //  - singleRewrites: 单行静态值可重写（记录原始 lineIdx）
        //  - blockRewrites: 多行对象/数组块（key 在 mergedNested 中才重写）
        //  - consumed[i]: 该行属于某个多行块，不应再被当作独立顶层行处理（避免嵌套行被误判为顶层属性）
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
            val indent = Regex("""^(\s*)""").find(rawLine)?.groupValues?.get(1).orEmpty()
            val line = rawLine.trimStart()
            // 空行或纯注释行 → 跳过
            if (line.isBlank() || line.startsWith("//") || line.startsWith("/*")) { idx++; continue }
            // 找顶层 ':'（不在字符串/嵌套 {}[] 中）
            val colonPosInTrimmed = findTopLevelColon(line) ?: run { idx++; continue }
            val keyExpr = line.substring(0, colonPosInTrimmed).trim()
            val key = parsePropertyKey(keyExpr) ?: run { idx++; continue }
            parsedTopKeys.add(key)
            val valuePartRaw = line.substring(colonPosInTrimmed + 1).trim()
            // 分离值末尾的尾注释（// xxx 或 /* xxx */），使：
            //   1) 静态判定不受尾注释干扰（否则 `key: 'a', // note` 会被误判为不可重写而漏改）
            //   2) 重写 value 时能保留原有尾注释，避免格式漂移
            val (valuePart, trailingComment) = splitTrailingComment(valuePartRaw)

            if (isSingleLineStaticValue(valuePart)) {
                val trailingComma = valuePart.trimEnd().endsWith(",")
                singleRewrites.add(SingleRewrite(idx, key, indent.length + colonPosInTrimmed, trailingComma, trailingComment))
            } else {
                // 多行对象/数组块：始终定位其范围并"消费"，避免嵌套行被误判为顶层属性；
                // 只有 key 在合并结果中才整块重写（从而合入新增的嵌套子 key）。
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

        // 第二遍：按序重建输出；块重写整体替换，单行重写行内替换，其余原样保留
        val blockByStart = blockRewrites.associateBy { it.start }
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val block = blockByStart[i]
            if (block != null) {
                // 因子化承载后需删除的旧整句 key：整块直接跳过，不写入输出
                if (block.key in dropKeys) { i = block.end + 1; continue }
                val rendered = renderStaticValue(mergedNested[block.key], innerIndentUnit, nestingDepth = 2)
                val rLines = rendered.split("\n")
                val comment = block.trailingComment
                if (rLines.size == 1) {
                    // 标量：整块替换成单行
                    out.add("${block.keyRowPrefix} $rendered,$comment")
                } else {
                    out.add("${block.keyRowPrefix} ${rLines.first()}")
                    out.addAll(rLines.subList(1, rLines.lastIndex))
                    out.add(rLines.last() + ",$comment")
                }
                i = block.end + 1
                continue
            }
            // 单行重写
            val rw = singleRewrites.firstOrNull { it.lineIdx == i }
            if (rw != null) {
                // 因子化承载后需删除的旧整句 key：跳过该行（不写入输出）
                if (rw.key in dropKeys) { i++; continue }
                if (rw.key in mergedKeys) {
                    val valueStr = renderStaticValue(mergedNested[rw.key], innerIndentUnit, nestingDepth = 1)
                    val prefix = lines[i].substring(0, rw.colonPosInLine + 1)  // "  key:"
                    val suffix = if (rw.trailingComma) "," else ""
                    out.add("$prefix $valueStr$suffix${rw.trailingComment}")
                    i++
                    continue
                }
            }
            out.add(lines[i])
            i++
        }

        // 追加新 key（mergedNested 里有，但旧对象没有的）
        val newKeys = mergedKeys.filter { it !in parsedTopKeys }.sorted()
        if (newKeys.isNotEmpty()) {
            // 找最后一行非空行的位置，在其后面追加
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
                val keyExpr = if (k.matches(Regex("""[A-Za-z_$][\w$]*"""))) k else quoteForTs(k)
                val valueStr = renderStaticValue(mergedNested[k], innerIndentUnit, nestingDepth = 1)
                "$innerIndentUnit$keyExpr: $valueStr,"
            }
            out.addAll(insertPos, additions)
        }

        // 去掉首尾空行（来自对象字面量首尾换行产生的空元素），避免 } 前出现多余空行
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)

        // 重新组合对象字面量
        return "{" + out.joinToString("\n") + "\n}"
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
                    c == '/' && j + 1 < row.length && row[j + 1] == '/' -> break  // 行注释：忽略本行剩余
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

    /**
     * 从属性值片段末尾剥离尾注释（行注释或块注释），返回 (纯值, 含前导空白的注释)。
     * 找不到注释时返回 (原值, "")。逐字符扫描以避开字符串内的注释标记。
     */
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
        // 1) 首先要是单行（没有 '\n'）— 调用方已经按行切过，所以一般成立
        // 2) 且表达式中没有"未闭合"的 { 或 [（这样就不会是跨行的对象/数组）
        // 3) 非静态开头：spread / 方法 / 引用 / 函数调用 / as const → 判 false
        val t = expr.trim().trimEnd(',')
        if (t.startsWith("...")) return false
        // 方法简写的情况：() => { ... } 或 function(){}
        if (t.contains("=>") || t.startsWith("function")) return false
        // 引用 / 调用：以 identifier 开头但不是 "true/false/null/undefined/数字/字符串"
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

    /**
     * 判断一个对象/数组字面量是否含非静态内容（spread `...`、方法、引用、调用等）。
     * 含则返回 true：这类值重新生成会丢失原始表达式（如 `sub: { ...deeper }` 会被压成 `{}`），
     * 必须原样保留，不能走静态重写。
     */
    private fun containsNonStaticCollection(text: String): Boolean {
        var v = text.trim().trimEnd(',')
        v = if (v.endsWith("as const")) v.removeSuffix("as const").trim() else v
        var t = v
        // 带 key 前缀的整行/整块（如 "sub: { ...deeper }"）→ 剥掉 key 部分只分析值，
        // 否则会被误判为静态而把动态表达式压成 {}（见 testTargetWithOwnNestedSpreadIsResilient）。
        val colon = findTopLevelColon(t)
        if (colon != null) {
            val valPart = t.substring(colon + 1).trim()
            if (valPart.startsWith("{") || valPart.startsWith("[")) t = valPart
        }
        // 不是 {…}/{…} 集合字面量（如纯字符串/数字/引用）→ 不在此判定范围，按静态处理
        val inner = if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]")))
            t.substring(1, t.length - 1) else return false
        if (inner.isBlank()) return false
        for (prop in splitTopLevelProperties(inner)) {
            val p = prop.trim()
            if (p.startsWith("...")) return true
            val colon = findTopLevelColon(p)
            if (colon == null) {
                // 数组元素：无冒号不是对象属性
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
                    val keyExpr = if (k.matches(Regex("""[A-Za-z_$][\w$]*"""))) k else quoteForTs(k)
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
        // 优先单引号，字符串中有单引号用双引号，两个都有则转义单引号
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

    // ==========================================================================
    // 整合：给定入口 VirtualFile（.ts/.tsx/.js/.jsx）+ 新的扁平翻译 JSON，
    //       生成写回该文件所需的"新文本"。
    // 返回：Pair(newText, writtenEntryRange in newText) 或 null（无法解析，回退剪贴板）
    // ==========================================================================
    // 目标架构 Resource 层：实现体已迁入 com.pan.extractor.resource.TsResourceWriter，
    // 此处保留为委托门面（签名不变，测试兼容）。
    fun regenerateTsFileWithNewJson(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): String? = com.pan.extractor.resource.TsResourceWriter.regenerateTsFile(
        project, entryVf, newFlatJson, dropExistingKeys
    )

    // ==========================================================================
    // JSON 文件：直接解析 + 合并扁平 JSON（点式 key 尝试展开嵌套，冲突以新为准）+ 重新生成
    //
    // 边界（P1 §11 Resource Writer）：写回时保持原文件的 UTF-8 BOM 与换行风格（LF / CRLF），
    // 并用 disableHtmlEscaping 保证非 ASCII（中文/emoji）以原文写出而非被转义。
    // ==========================================================================
    /**
     * 记录原 JSON 文件的编码/换行特征（实现已迁入 resource.JsonWriter）。
     * 此处的 data class 保留为类型别名兼容（测试直接引用）；写回逻辑在 JsonWriter 内。
     */
    @Deprecated("迁至 com.pan.extractor.resource.JsonWriteFormat")
    data class JsonWriteFormat(
        val bom: Boolean,
        val crlf: Boolean,
    ) {
        val newline: String get() = if (crlf) "\r\n" else "\n"
    }

    internal fun detectJsonWriteFormat(content: String): com.pan.extractor.resource.JsonWriteFormat =
        com.pan.extractor.resource.JsonWriter.detectJsonWriteFormat(content)

    /** 目标架构 Resource 层：实现体已迁入 com.pan.extractor.resource.JsonWriter，此处委托。 */
    fun regenerateJsonFileWithNewJson(
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): String? = com.pan.extractor.resource.JsonWriter.regenerateJsonFile(
        entryVf, newFlatJson, dropExistingKeys
    )

    // ==========================================================================
    // Spread 引用解析：export default { ...common } 中 common 指向同文件 const 或本地 import。
    // 支持：同文件 const 对象、本地 import 的 TS/JS、本地 import 的 JSON（非 node_modules）。
    // 路由规则：新 key 写进 spread 变量指向的文件，入口对象只更新自身已有的 key。
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
        for (prop in splitTopLevelProperties(body)) {
            val t = prop.trim()
            if (t.startsWith("...")) {
                val name = t.removePrefix("...").trim()
                if (name.matches(Regex("""[A-Za-z_$][\w$]*"""))) result.add(SpreadRef(name, path))
                continue
            }
            // 值本身是对象字面量 → 递归进入（识别嵌套 spread）
            val (k, v) = parseOneProperty(prop) ?: continue
            val vClean = stripValueSuffixes(v)
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
            // 定位 '=' 之后第一个 '{'（类型标注里可能含 '{'，不能直接用最后一个 '{'）
            val eqLocal = cm.value.lastIndexOf('=')
            var bi = cm.range.first + eqLocal + 1
            while (bi < entryText.length && entryText[bi] != '{') bi++
            if (bi >= entryText.length) return null
            val objEnd = findBalancedCloseBrace(entryText, bi) ?: return null
            val objBody = entryText.substring(bi, objEnd)
            val constKeys = parseObjectLiteralBody(objBody)
            val constTarget = ResolvedSpreadTarget(entryVf, bi until objEnd, constKeys, "const")
            // 多级递归：仅当 const 是「纯转发光束」（无自身静态 key，如 `const common = {...deeper}`）时，
            // 才继续下钻到更深的「非入口文件」可写目标，使新 key 写到真正归属的模块文件，
            // 而不是堆积在入口文件里这个本地 const 块。
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
                val root = try { JsonParser.parseString(targetText) } catch (_: Exception) { return null }
                val existing = com.pan.extractor.resource.JsonWriter.jsonElementToNestedMap(if (root.isJsonObject) root else JsonParser.parseString("{}"))
                ResolvedSpreadTarget(targetVf, 0 until 0, existing, "json", readOnly)
            }
            else -> {
                val info = parseTsExportedObject(targetText) ?: return null
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
                            } catch (_: Exception) { null }
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

    /**
     * 识别入口 TS/JS 对象里的 spread 引用（如 `...common`），并把新 key 路由写到该变量指向的文件。
     * 返回要写盘的 (VirtualFile, newText) 列表；返回 null 表示未处理（无 spread 或无可解析目标），
     * 调用方应回退到 regenerateTsFileWithNewJson。
     */
    // 目标架构 Resource 层：实现体已迁入 com.pan.extractor.resource.TsResourceWriter，此处委托。
    fun regenerateTsFileWithSpreadRouting(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet()
    ): List<Pair<VirtualFile, String>>? = com.pan.extractor.resource.TsResourceWriter.regenerateTsFileWithSpreadRouting(
        project, entryVf, newFlatJson, dropExistingKeys
    )

    // ==========================================================================
    // 把 VirtualFile 内容替换为新文本（Write 安全封装）。
    // 调用方需要自己包裹在 WriteCommandAction / invokeAndWait 中。
    // 返回是否写入成功；newText 若以 \uFEFF 开头则以 UTF-8 BOM 写盘（跨平台保留）。
    // ==========================================================================
    // 目标架构 Resource 层：实现体已迁入 com.pan.extractor.resource.TsResourceWriter，此处委托。
    fun writeVirtualFileText(entryVf: VirtualFile, newText: String): Boolean =
        com.pan.extractor.resource.TsResourceWriter.writeVirtualFileText(entryVf, newText)

    /** 把虚拟文件路径作为"候选"持久化，供下次优先命中。 */
    fun persistEntryPathIfNeeded(project: Project, entryVf: VirtualFile) {
        Util.setStoredEntryPath(project, entryVf.path)
    }

}
