package com.pan.extractor.resource

import com.google.gson.GsonBuilder

/**
 * Resource 层 —— 翻译资源读写（目标架构，迁移自 [com.pan.extractor.TsFileEditor] /
 * [com.pan.extractor.AllI18nExtractorAction.applyFinalOutput] 的写回部分）。
 *
 * 职责：负责 JSON / TS 等翻译资源的 merge、写回和格式保持。
 * 后续迁移：TsFileEditor 的 regenerateJsonFileWithNewJson / regenerateTsFileWithNewJson
 * / spread 路由 / writeVirtualFileText 按格式拆到 JsonWriter / TsResourceWriter。
 */

/** JSON 资源写入器：把 key → value 平面表输出为格式化 JSON 文本。 */
object JsonWriter {

    private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** 把平面 key → value 序列化为 JSON 文本。 */
    fun toJsonText(flatJson: Map<String, String>): String = prettyGson.toJson(flatJson)

    /**
     * 去掉 JSON 文本的最外层花括号（供拼入 TS 对象 / 已有文件 merge 使用）。
     * 迁移自 [com.pan.extractor.Util.getJsonContent]。
     */
    fun innerJsonContent(json: String): String {
        val content = json
            .trim()
            .removePrefix("{")
            .removeSuffix("}")
            .trim()
        return content
    }
}

/** TS 资源写入器（TODO(迁移)：TsFileEditor 的 TS 对象字面量生成与合并）。 */
object TsResourceWriter {
    // TODO(迁移)：regenerateTsFileWithNewJson / spread 路由 / 格式保持
}
