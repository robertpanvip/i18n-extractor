package com.pan.extractor.messages

import com.pan.extractor.project.Util
import com.pan.extractor.locate.EntryFileLocator
import com.pan.extractor.lang.LanguageRegistry
import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.ui.*

import com.google.gson.JsonParser
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * 读取 $t() 折叠展示所用语言的翻译资源，解析为扁平 key→原始文案 映射。
 *
 * 折叠展示的占位文本来自指定语言（[I18nSettings.foldDisplayLanguage]）的 locale 文件：
 *  - 优先 [EntryFileLocator.findLocaleFileForLanguage]（只匹配该语言的独立文件）
 *  - 找不到独立文件时回退到项目的默认目标语言入口文件
 *
 * 支持 .json / .ts / .tsx / .js / .jsx。嵌套对象按 `.` 拼成扁平 key（i18n 常见约定）。
 */
object LocaleMessages {

    private data class CacheKey(val entryPath: String, val displayLang: String, val modStamp: Long)

    /** 入口定位缓存键：宿主文件路径 + 折叠展示语言。 */
    private data class EntryKey(val contextPath: String, val displayLang: String)

    /** 翻译映射缓存上限：防止长期打开大量项目时内存无限增长。 */
    private const val MAX_CACHE_ENTRIES = 200

    /** 入口定位缓存上限。 */
    private const val MAX_ENTRY_CACHE_ENTRIES = 200

    private val cacheLock = Any()
    private val entryLock = Any()

    /** 按入口文件路径 + 修改时间缓存，文件改动后自动失效；LRU 淘汰，容量有上限。 */
    private val cache = object : java.util.LinkedHashMap<CacheKey, Map<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, Map<String, String>>
        ): Boolean = size > MAX_CACHE_ENTRIES
    }

    /**
     * 定位入口文件的结果缓存（宿主文件 + 语言 → 入口路径）。
     * 折叠场景下项目目录结构稳定，缓存可避免每次折叠都做项目目录扫描。
     */
    private val entryCache = object : java.util.LinkedHashMap<EntryKey, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EntryKey, String>): Boolean =
            size > MAX_ENTRY_CACHE_ENTRIES
    }

    /** 折叠场景使用的带缓存入口：按当前折叠展示语言解析翻译资源。 */
    fun loadCached(project: Project, contextPsiFile: PsiFile?): Map<String, String> {
        val displayLang = I18nSettings.getInstance().foldDisplayLanguage()
        val entry = entryVirtualFile(project, contextPsiFile, displayLang) ?: return emptyMap()
        // 使用 PSI 文档的 modificationStamp 而非 VirtualFile 的，
        // 确保提取翻译后未保存到磁盘的变更也能让缓存失效。
        val docStamp = runReadAction {
            val psi = PsiManager.getInstance(project).findFile(entry)
            psi?.let { PsiDocumentManager.getInstance(project).getDocument(it) }?.modificationStamp
        }
        val modStamp = docStamp ?: entry.modificationStamp
        val key = CacheKey(entry.path, displayLang, modStamp)
        synchronized(cacheLock) {
            cache[key]?.let { return it }
            return parseEntry(project, entry).also { cache[key] = it }
        }
    }

    /**
     * 定位指定语言的翻译入口文件（带缓存，避免每次折叠都做项目目录扫描）。
     * 入口文件失效（被删除/改名）时自动回退重新定位。
     */
    private fun entryVirtualFile(project: Project, contextPsiFile: PsiFile?, displayLang: String): VirtualFile? {
        val contextPath = contextPsiFile?.virtualFile?.path ?: ""
        val locKey = EntryKey(contextPath, displayLang)
        synchronized(entryLock) {
            val cached = entryCache[locKey]
            if (cached != null) {
                LocalFileSystem.getInstance().findFileByPath(cached)?.let { if (it.isValid && !it.isDirectory) return it }
            }
        }
        val extractor = LanguageRegistry.byId(displayLang)
        val entry = extractor?.let { EntryFileLocator.findLocaleFileForLanguage(project, contextPsiFile, it) }
            ?: EntryFileLocator.findChineseLocaleEntryFile(project, contextPsiFile)
        if (entry != null && entry.isValid && !entry.isDirectory) {
            synchronized(entryLock) { entryCache[locKey] = entry.path }
        }
        return entry
    }

    /** 按折叠展示语言解析出扁平 key→文案 映射；解析失败返回空 map（不抛错）。 */
    fun load(project: Project, contextPsiFile: PsiFile?): Map<String, String> {
        val settings = I18nSettings.getInstance()
        val extractor = LanguageRegistry.byId(settings.foldDisplayLanguage())
        val entry = extractor?.let { EntryFileLocator.findLocaleFileForLanguage(project, contextPsiFile, it) }
            ?: EntryFileLocator.findChineseLocaleEntryFile(project, contextPsiFile)
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
        val info = TsFileEditor.parseTsExportedObject(text) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        flattenNested(info.staticKV, "", out)
        return out
    }

    @Suppress("UNCHECKED_CAST")
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