package com.pan.extractor.editor

import com.pan.extractor.project.Util
import com.pan.extractor.resource.JsonWriter
import com.pan.extractor.resource.JsonWriteFormat
import com.pan.extractor.resource.TsObjectMerger
import com.pan.extractor.resource.TsResourceWriter
import com.pan.extractor.staticparser.StaticObjectParser
import com.pan.extractor.staticparser.StaticValueParser
import com.pan.extractor.staticparser.TsExportedObjectInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * 翻译资源文件（.ts / .js / .json）处理门面 —— 目标架构 Resource / staticparser 层的**纯委托门面**。
 *
 * 完整收敛后，本对象不再持有任何写回/合并/spread 实现（已迁入
 * [com.pan.extractor.resource.TsObjectMerger] / [TsResourceWriter] / [JsonWriter] 与
 * [com.pan.extractor.staticparser.StaticObjectParser] / [StaticValueParser]），仅保留对外签名
 * 做委托，使既有调用方与测试不破坏。
 *
 * 职责映射：
 *  - 解析（export default/const 对象、属性/数组/值）→ [StaticObjectParser] / [StaticValueParser]；
 *  - 合并/重新生成对象字面量/spread 路由/区间替换 → [TsObjectMerger]；
 *  - TS/JS/JSON 文件写回（merge + 重新生成 + 写盘）→ [TsResourceWriter] / [JsonWriter]。
 */
object TsFileEditor {

    /** 解析结果：类型别名指向 staticparser 包的真实实现，旧引用保持兼容。 */
    typealias TsExportedObjectInfo = com.pan.extractor.staticparser.TsExportedObjectInfo

    // ==========================================================================
    // ① 解析（委托 staticparser）
    // ==========================================================================
    /** 从 TS/JS 文件内容中找到 export default / export const / module.exports 对应的对象字面量，并抽取静态 key-value。 */
    fun parseTsExportedObject(text: String): TsExportedObjectInfo? =
        StaticObjectParser.parseTsExportedObject(text)

    fun parseObjectLiteralBody(raw: String): Map<String, Any?> =
        StaticObjectParser.parseObjectLiteralBody(raw)

    internal fun findBalancedCloseBrace(text: String, openIdx: Int): Int? =
        StaticObjectParser.findBalancedCloseBrace(text, openIdx)

    internal fun splitTopLevelProperties(body: String): List<String> =
        StaticObjectParser.splitTopLevelProperties(body)

    internal fun parseOneProperty(prop: String): Pair<String, String>? =
        StaticObjectParser.parseOneProperty(prop)

    internal fun parsePropertyKey(keyPart: String): String? =
        StaticObjectParser.parsePropertyKey(keyPart)

    internal fun unquoteString(s: String): String =
        StaticObjectParser.unquoteString(s)

    internal fun stripValueSuffixes(expr: String): String =
        StaticValueParser.stripValueSuffixes(expr)

    internal fun splitTopLevelArrayElements(body: String): List<String> =
        StaticObjectParser.splitTopLevelArrayElements(body)

    // ==========================================================================
    // ③ 合并 / 重新生成 / spread 路由（委托 TsObjectMerger）
    // ==========================================================================
    /**
     * 把扁平 Map<String, String> 的翻译资源合并到现有嵌套结构里（点式 key 展开、冲突以新为准）。
     * 实现见 [TsObjectMerger.mergeFlatIntoNested]。
     */
    fun mergeFlatIntoNested(
        existingNested: Map<String, Any?>,
        newFlat: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): Map<String, Any?> = TsObjectMerger.mergeFlatIntoNested(existingNested, newFlat, dropExistingKeys)

    /**
     * 将合并后的 nested Map 合并写回到旧的对象字面量文本里（静态行改写、非静态行原样保留、新 key 追加）。
     * 实现见 [TsObjectMerger.regenerateObjectLiteralBody]。
     */
    fun regenerateObjectLiteralBody(oldObjBody: String, mergedNested: Map<String, Any?>, dropKeys: Set<String> = emptySet()): String =
        TsObjectMerger.regenerateObjectLiteralBody(oldObjBody, mergedNested, dropKeys)

    /** spread 引用模式：`...varName`（path 记录容器路径）。实现见 [TsObjectMerger.SpreadRef]。 */
    internal typealias SpreadRef = TsObjectMerger.SpreadRef

    /** spread 目标解析结果。实现见 [TsObjectMerger.ResolvedSpreadTarget]。 */
    internal typealias ResolvedSpreadTarget = TsObjectMerger.ResolvedSpreadTarget

    internal fun findSpreadRefs(objBody: String, path: List<String>, depth: Int = 0): List<SpreadRef> =
        TsObjectMerger.findSpreadRefs(objBody, path, depth)

    internal fun isUnder(path: List<String>, key: String): Boolean =
        TsObjectMerger.isUnder(path, key)

    internal fun relativeKey(path: List<String>, key: String): String? =
        TsObjectMerger.relativeKey(path, key)

    internal fun joinPath(path: List<String>, k: String): String =
        TsObjectMerger.joinPath(path, k)

    internal fun resolveSpreadTarget(
        project: Project,
        entryVf: VirtualFile,
        entryText: String,
        varName: String,
        path: List<String> = emptyList(),
        visited: MutableSet<String> = HashSet()
    ): ResolvedSpreadTarget? = TsObjectMerger.resolveSpreadTarget(project, entryVf, entryText, varName, path, visited)

    internal fun resolveLocalImportFile(fromFile: VirtualFile, spec: String): VirtualFile? =
        TsObjectMerger.resolveLocalImportFile(fromFile, spec)

    internal fun newRegionText(text: String, objRange: IntRange, newFlat: Map<String, String>, existing: Map<String, Any?>, dropExistingKeys: Set<String> = emptySet()): String =
        TsObjectMerger.newRegionText(text, objRange, newFlat, existing, dropExistingKeys)

    internal fun applyRangeReplacements(text: String, replacements: List<Pair<IntRange, String>>): String =
        TsObjectMerger.applyRangeReplacements(text, replacements)

    // ==========================================================================
    // ② 写回（委托 resource 层），签名不变（测试 / 调用方兼容）
    // ==========================================================================
    /** 生成写回入口 TS 文件所需的新文本（无法解析返回 null，回退剪贴板）。 */
    fun regenerateTsFileWithNewJson(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): String? = TsResourceWriter.regenerateTsFile(project, entryVf, newFlatJson, dropExistingKeys)

    /** 生成写回入口 JSON 文件所需的新文本（保持 BOM / 换行风格）。 */
    fun regenerateJsonFileWithNewJson(
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): String? = JsonWriter.regenerateJsonFile(entryVf, newFlatJson, dropExistingKeys)

    /** 识别入口 TS/JS 对象里的 spread 引用并路由写到其归属文件；返回 null 表示回退旧逻辑。 */
    fun regenerateTsFileWithSpreadRouting(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet()
    ): List<Pair<VirtualFile, String>>? = TsResourceWriter.regenerateTsFileWithSpreadRouting(
        project, entryVf, newFlatJson, dropExistingKeys
    )

    internal fun detectJsonWriteFormat(content: String): JsonWriteFormat =
        JsonWriter.detectJsonWriteFormat(content)

    /** 把 VirtualFile 内容替换为新文本（Write 安全封装，Document 层可撤销；调用方自包 WriteCommandAction）。 */
    fun writeVirtualFileText(entryVf: VirtualFile, newText: String): Boolean =
        TsResourceWriter.writeVirtualFileText(entryVf, newText)

    /** 把虚拟文件路径作为"候选"持久化，供下次优先命中。 */
    fun persistEntryPathIfNeeded(project: Project, entryVf: VirtualFile) {
        Util.setStoredEntryPath(project, entryVf.path)
    }

}