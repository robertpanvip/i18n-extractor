package com.pan.extractor

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * 读取 $t() 折叠展示所用语言的翻译资源，解析为扁平 key→原始文案 映射。
 *
 * 折叠展示的占位文本来自指定语言（[I18nSettings.foldDisplayLanguage]）的 locale 文件：
 *  - 优先 [Util.findLocaleFileForLanguage]（只匹配该语言的独立文件）
 *  - 找不到独立文件时回退到项目的默认目标语言入口文件
 *
 * 支持 .json / .ts / .tsx / .js / .jsx。嵌套对象按 `.` 拼成扁平 key（i18n 常见约定）。
 */
object LocaleMessages {

    private data class CacheKey(val entryPath: String, val displayLang: String, val modStamp: Long)

    /** 缓存上限：防止长期打开大量项目时内存无限增长。 */
    private const val MAX_CACHE_ENTRIES = 200

    private val cacheLock = Any()

    /** 按入口文件路径 + 修改时间缓存，文件改动后自动失效；LRU 淘汰，容量有上限。 */
    private val cache = object : java.util.LinkedHashMap<CacheKey, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, Map<String, String>>
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    /** 折叠场景使用的带缓存入口：按当前折叠展示语言解析翻译资源。 */
    fun loadCached(project: Project, contextPsiFile: PsiFile?): Map<String, String> {
        val displayLang = I18nSettings.getInstance().foldDisplayLanguage()
        val extractor = LanguageRegistry.byId(displayLang)
        val entry = extractor?.let { Util.findLocaleFileForLanguage(project, contextPsiFile, it) }
            ?: Util.findChineseLocaleEntryFile(project, contextPsiFile)
            ?: return emptyMap()
        val key = CacheKey(entry.path, displayLang, entry.modificationStamp)
        synchronized(cacheLock) {
            cache[key]?.let { return it }
            return parseEntry(project, entry).also { cache[key] = it }
        }
    }

    /** 按折叠展示语言解析出扁平 key→文案 映射；解析失败返回空 map（不抛错）。 */
    fun load(project: Project, contextPsiFile: PsiFile?): Map<String, String> {
        val settings = I18nSettings.getInstance()
        val extractor = LanguageRegistry.byId(settings.foldDisplayLanguage())
        val entry = extractor?.let { Util.findLocaleFileForLanguage(project, contextPsiFile, it) }
            ?: Util.findChineseLocaleEntryFile(project, contextPsiFile)
            ?: return emptyMap()
        return parseEntry(project, entry)
    }

    private fun parseEntry(project: Project, entry: com.intellij.openapi.vfs.VirtualFile): Map<String, String> {
        val text = Util.readVirtualFileText(project, entry) ?: return emptyMap()
        return when (entry.extension?.lowercase()) {
            "json" -> parseJsonFlat(text)
            else -> parseTsFlat(text)
        }
    }

    private fun parseJsonFlat(text: String): Map<String, String> {
        return try {
            val root = JsonParser.parseString(text)
            if (root.isJsonNull || !root.isJsonObject) return emptyMap()
            val out = LinkedHashMap<String, String>()
            flattenNested(root.asJsonObject.entrySet().asSequence().map { it.key to it.value }.toMap(), "", out)
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun parseTsFlat(text: String): Map<String, String> {
        val info = Util.parseTsExportedObject(text) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        flattenNested(info.staticKV, "", out)
        return out
    }

    private fun flattenNested(map: Map<String, Any?>, prefix: String, out: MutableMap<String, String>) {
        for ((k, v) in map) {
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            when (v) {
                is Map<*, *> -> flattenNested(v as Map<String, Any?>, key, out)
                else -> if (v != null) out[key] = v.toString()
            }
        }
    }
}