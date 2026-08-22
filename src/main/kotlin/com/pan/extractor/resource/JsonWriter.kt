package com.pan.extractor.resource

import com.pan.extractor.log.PluginLogBuffer
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

/**
 * Resource 层 —— 翻译资源读写（迁移自 [com.pan.extractor.editor.TsFileEditor] 的 JSON 写回部分）。
 *
 * 职责：负责 JSON / TS 等翻译资源的 merge、写回和格式保持。
 * [com.pan.extractor.editor.TsFileEditor] 的对应方法已改为委托本对象（行为 1:1，测试不破坏）。
 */

/** 记录原 JSON 文件的编码/换行特征，供写回时保持格式。 */
data class JsonWriteFormat(
    val bom: Boolean,
    val crlf: Boolean,
) {
    val newline: String get() = if (crlf) "\r\n" else "\n"
}

/** JSON 资源写入器：扁平 key → value 的格式化、merge 与写回。 */
object JsonWriter {

    private val LOG = Logger.getInstance(JsonWriter::class.java)

    private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** 把平面 key → value 序列化为 JSON 文本。 */
    fun toJsonText(flatJson: Map<String, String>): String = prettyGson.toJson(flatJson)

    /**
     * 去掉 JSON 文本的最外层花括号（供拼入 TS 对象 / 已有文件 merge 使用）。
     * 迁移自 [com.pan.extractor.project.Util.getJsonContent]。
     */
    fun innerJsonContent(json: String): String {
        return json.trim().removePrefix("{").removeSuffix("}").trim()
    }

    /** 记录原 JSON 文件的编码/换行特征（迁移自 TsFileEditor.detectJsonWriteFormat）。 */
    fun detectJsonWriteFormat(content: String): JsonWriteFormat {
        val body = if (content.startsWith("\uFEFF")) content.removePrefix("\uFEFF") else content
        return JsonWriteFormat(bom = content != body, crlf = body.contains("\r\n"))
    }

    /** 依据格式特征补回换行风格与 UTF-8 BOM（迁移自 TsFileEditor.applyJsonWriteFormat）。 */
    private fun applyJsonWriteFormat(jsonText: String, fmt: JsonWriteFormat): String {
        val nlJson = if (fmt.crlf) jsonText.replace("\n", "\r\n") else jsonText
        return if (fmt.bom) "\uFEFF$nlJson" else nlJson
    }

    /**
     * JSON 入口文件写回：解析 + 合并扁平 JSON（点式 key 尝试展开嵌套，冲突以新为准）+ 重新生成。
     * 迁移自 [com.pan.extractor.editor.TsFileEditor.regenerateJsonFileWithNewJson]（实现体 1:1）。
     * 写回时保持原文件的 UTF-8 BOM 与换行风格（LF / CRLF），并 disableHtmlEscaping 保证非 ASCII 原文写出。
     */
    fun regenerateJsonFile(
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): String? {
        val content = try {
            String(entryVf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            PluginLogBuffer.warn(LOG,"JsonWriter: 读取翻译文件内容失败，返回 null", e)
            return null
        }
        val fmt = detectJsonWriteFormat(content)
        val body = if (fmt.bom) content.removePrefix("\uFEFF") else content
        val rootJson: JsonElement = try {
            JsonParser.parseString(body)
        } catch (e: Exception) {
            PluginLogBuffer.warn(LOG,"JsonWriter: JSON 解析失败，回退覆盖写回", e)
            // JSON 解析失败 → 兜底：把新 JSON 格式化返回（整个文件被新值覆盖）
            return applyJsonWriteFormat(prettyGson.toJson(newFlatJson), fmt)
        }
        val existingMap = jsonElementToNestedMap(rootJson)
        val merged = TsObjectMerger.mergeFlatIntoNested(existingMap, newFlatJson, dropExistingKeys)
        return applyJsonWriteFormat(prettyGson.toJson(merged), fmt)
    }

    /** JsonElement → 嵌套 Map（迁移自 TsFileEditor.jsonElementToNestedMap / jsonElementToKotlin）。 */
    internal fun jsonElementToNestedMap(el: JsonElement): Map<String, Any?> {
        if (!el.isJsonObject) return emptyMap()
        val obj = el.asJsonObject
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in obj.entrySet()) {
            result[k] = jsonElementToKotlin(v)
        }
        return result
    }

    private fun jsonElementToKotlin(el: JsonElement): Any? {
        return when {
            el.isJsonNull -> null
            el.isJsonPrimitive -> {
                val p = el.asJsonPrimitive
                when {
                    p.isBoolean -> p.asBoolean
                    p.isNumber -> {
                        val n = p.asNumber
                        if (n is Long || n is Int) n.toLong() else n.toDouble()
                    }
                    p.isString -> p.asString
                    else -> p.asString
                }
            }
            el.isJsonObject -> jsonElementToNestedMap(el)
            el.isJsonArray -> el.asJsonArray.map { jsonElementToKotlin(it) }.toList()
            else -> null
        }
    }
}
