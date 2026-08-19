package com.pan.extractor.staticparser

/**
 * export 锚点定位器 —— 从 TS/JS 文件文本中找到 export default / export const /
 * module.exports / exports = / export default defineXxx({...}) 对应的对象字面量起点。
 *
 * 拆分自 [StaticObjectParser]（原 findExportedObjectStart / inferIndent）。
 * 只负责"定位对象字面量的 { 起点位置 + 推断缩进"，不负责解析对象内部。
 */
internal object ExportAnchorFinder {

    internal data class ExportAnchor(
        val objBraceStart: Int,
        val exportType: String,
        val indentUnit: String,
    )

    /** 在 [text] 中查找 export 对象字面量起点；找不到返回 null。 */
    fun find(text: String): ExportAnchor? {
        // 模式 1：export default {
        Regex("""export\s+default\s*\{""").find(text)?.let { m ->
            val braceIdx = m.range.last
            return ExportAnchor(braceIdx, "default", inferIndent(text, braceIdx))
        }
        // 模式 2：export default <name> = { （非常少见，但兜底）
        Regex("""export\s+default\s+[\w$][\w$]*\s*=\s*\{""").find(text)?.let { m ->
            val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
            return ExportAnchor(braceIdx, "default", inferIndent(text, braceIdx))
        }
        // 模式 3：export const <name> = { / export let / export var（含可选类型标注）
        Regex("""export\s+(const|let|var)\s+([\w$][\w$]*)\s*(?::[^=\n]+)?\s*=\s*\{""").find(text)?.let { m ->
            val name = m.groupValues[2]
            val eqLocal = m.value.lastIndexOf('=')
            var i = m.range.first + eqLocal + 1
            while (i < text.length && text[i] != '{') i++
            if (i >= text.length) return@let
            return ExportAnchor(i, "named:$name", inferIndent(text, i))
        }
        // 模式 4：module.exports = {
        Regex("""module\.exports\s*=\s*\{""").find(text)?.let { m ->
            val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
            return ExportAnchor(braceIdx, "module.exports", inferIndent(text, braceIdx))
        }
        // 模式 5：exports = {
        Regex("""(^|;)\s*exports\s*=\s*\{""").find(text)?.let { m ->
            val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
            return ExportAnchor(braceIdx, "exports", inferIndent(text, braceIdx))
        }
        // 模式 6：export default defineXxx({ ... }) —— i18n 常用包裹函数
        // （defineI18nConfig / defineMessages / defineConfig / createI18n 等），对象字面量在函数括号内。
        // 兼容 TypeScript 泛型：export default defineMessages<SomeType>({ ... })
        Regex("""export\s+default\s+([A-Za-z_$][\w$]*)\s*(?:<[^()]*>)?\s*\(\s*\{""").find(text)?.let { m ->
            val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
            return ExportAnchor(braceIdx, "default:${m.groupValues[1]}", inferIndent(text, braceIdx))
        }
        return null
    }

    /** 推断 [braceIdx] 处 `{` 所在行的缩进（前导空白）；无则默认 2 spaces。 */
    internal fun inferIndent(text: String, braceIdx: Int): String {
        var lineStart = braceIdx
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val wsPrefix = text.substring(lineStart, braceIdx).takeWhile { it == ' ' || it == '\t' }
        return wsPrefix.ifEmpty { "  " }
    }
}
