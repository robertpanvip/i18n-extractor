package com.pan.extractor.resource

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.pan.extractor.TsFileEditor
import com.pan.extractor.Util
import java.nio.charset.StandardCharsets

/**
 * Resource 层 —— TS/JS 翻译资源写回（迁移自 [com.pan.extractor.TsFileEditor] 的 TS 写回部分）。
 *
 * 职责：负责 TS 对象字面量翻译资源的 merge、写回和格式保持。
 * [com.pan.extractor.TsFileEditor] 的对应方法已改为委托本对象（行为 1:1，测试不破坏）。
 * 底层解析/merge helper（parseTsExportedObject / mergeFlatIntoNested / regenerateObjectLiteralBody /
 * findSpreadRefs / resolveSpreadTarget / newRegionText / applyRangeReplacements）暂留在
 * [com.pan.extractor.TsFileEditor]，作为本层的底层实现，后续可继续内迁。
 */
object TsResourceWriter {

    /**
     * TS 入口文件写回：解析 export default/const 对象字面量 → 合并扁平 JSON → 重新生成对象体。
     * 迁移自 [com.pan.extractor.TsFileEditor.regenerateTsFileWithNewJson]（实现体 1:1）。
     *
     * @return 新文件文本；无法解析时返回 null（调用方回退剪贴板）。
     */
    fun regenerateTsFile(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): String? {
        val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile?> {
            PsiManager.getInstance(project).findFile(entryVf)
        }
        val rawText = if (psiFile != null) psiFile.text else try {
            String(entryVf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) { return null }
        // P0：TS 写回需保持原文件的换行风格（LF / CRLF）。先把原始文本归一化为 LF 处理
        //（parseTsExportedObject 的 objectRange 偏移基于归一化文本，与后续 substring 保持一致），
        // 最后再把整个结果统一转回原风格，避免重写后 \r\n 与 \n 混用。
        val isCrlf = rawText.contains("\r\n")
        val text = if (isCrlf) rawText.replace("\r\n", "\n") else rawText
        val info = TsFileEditor.parseTsExportedObject(text) ?: return null
        val merged = TsFileEditor.mergeFlatIntoNested(info.staticKV, newFlatJson, dropExistingKeys)
        // objectRange 是 exclusive 区间 [objStart, objEnd)，endExclusive 指向闭合 } 的后一位。
        // 必须包含闭合 }，regenerateObjectLiteralBody 才能正确去掉外层大括号重写。
        val oldObjBody = text.substring(info.objectRange.first, info.objectRange.last + 1)
        val newObjBody = TsFileEditor.regenerateObjectLiteralBody(oldObjBody, merged, dropExistingKeys)
        val newText = text.substring(0, info.objectRange.first) + newObjBody + text.substring(info.objectRange.last + 1)
        return if (isCrlf) newText.replace("\n", "\r\n") else newText
    }

    /**
     * TS 入口 + spread 路由写回：识别 `...common` 等 spread 引用并定向写入目标文件。
     * 迁移自 [com.pan.extractor.TsFileEditor.regenerateTsFileWithSpreadRouting]（实现体 1:1）。
     *
     * @return (VirtualFile, 新文本) 列表（首项为入口文件）；无 spread 或全部无法解析时返回 null（回退旧逻辑）。
     */
    fun regenerateTsFileWithSpreadRouting(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet()
    ): List<Pair<VirtualFile, String>>? {
        val entryText = Util.readVirtualFileText(project, entryVf) ?: return null
        val entryInfo = TsFileEditor.parseTsExportedObject(entryText) ?: return null
        val entryObjBody = entryText.substring(entryInfo.objectRange.first, entryInfo.objectRange.last + 1)
        val spreadRefs = TsFileEditor.findSpreadRefs(entryObjBody, emptyList())
        if (spreadRefs.isEmpty()) return null
        val entryKeys = entryInfo.staticKV.keys.toSet()

        // 解析每个 spread 引用指向的目标（含 node_modules 只读识别）。
        // 共享 visited 集合防止 const 相互 spread 造成重复解析/循环依赖。
        val visited = HashSet<String>()
        val resolved = spreadRefs.mapNotNull { ref ->
            TsFileEditor.resolveSpreadTarget(project, entryVf, entryText, ref.varName, ref.path, visited)?.let { ref to it }
        }
        if (resolved.isEmpty()) return null // 全部无法解析 → 回退旧逻辑
        // 所有目标已识别的 key（按各自容器路径展开成入口扁平 key），用于避免重复写入
        val covered = resolved.flatMap { (ref, target) ->
            target.existingKeys.keys.map { TsFileEditor.joinPath(ref.path, it) }
        }.toSet()
        val writableResolved = resolved.filter { !it.second.readOnly }

        // 只有只读（node_modules）目标 → 识别内容，真正新增的 key 写入口对象
        if (writableResolved.isEmpty()) {
            val entryAll = newFlatJson.filterKeys { it in entryKeys || it !in covered }
            return listOf(entryVf to TsFileEditor.applyRangeReplacements(entryText, listOf(
                entryInfo.objectRange to TsFileEditor.newRegionText(entryText, entryInfo.objectRange, entryAll, entryInfo.staticKV, dropExistingKeys)
            )))
        }

        // 为每个真正新增的 key 决定去向：优先最深的可写容器 spread 目标，否则入口
        data class WriteUnit(val target: TsFileEditor.ResolvedSpreadTarget, val path: List<String>, val relative: MutableMap<String, String>)
        val targetWrites = linkedMapOf<String, WriteUnit>() // key = 目标文件 path
        val entryNew = mutableMapOf<String, String>()

        for ((k, v) in newFlatJson) {
            if (k in entryKeys) { entryNew[k] = v; continue }
            if (k in covered) continue // 已被某个 spread 提供的 key 覆盖
            val best = writableResolved
                .filter { TsFileEditor.isUnder(it.first.path, k) }
                .maxByOrNull { it.first.path.size }
            if (best == null) { entryNew[k] = v; continue }
            val rel = TsFileEditor.relativeKey(best.first.path, k) ?: run { entryNew[k] = v; continue }
            targetWrites.getOrPut(best.second.file.path) { WriteUnit(best.second, best.first.path, linkedMapOf()) }
                .relative[rel] = v
        }

        // 组装入口写盘（含同文件 const 目标范围）
        val entryReplacements = mutableListOf<Pair<IntRange, String>>(entryInfo.objectRange to
                TsFileEditor.newRegionText(entryText, entryInfo.objectRange, entryNew, entryInfo.staticKV, dropExistingKeys))
        val separateWrites = mutableListOf<Pair<VirtualFile, String>>()
        for ((_, unit) in targetWrites) {
            val target = unit.target
            // 入口扁平 drop key → 该容器下的相对 key（best-effort；历史整句 key 通常在入口对象里）
            val relativeDrop = dropExistingKeys.mapNotNull { TsFileEditor.relativeKey(unit.path, it) }.toSet()
            when (target.kind) {
                "json" -> {
                    val newTarget = JsonWriter.regenerateJsonFile(target.file, unit.relative, relativeDrop) ?: return null
                    separateWrites.add(target.file to newTarget)
                }
                "ts" -> {
                    val targetText = Util.readVirtualFileText(project, target.file) ?: return null
                    val newTarget = TsFileEditor.applyRangeReplacements(targetText, listOf(
                        target.objRangeInText to TsFileEditor.newRegionText(targetText, target.objRangeInText, unit.relative, target.existingKeys, relativeDrop)
                    ))
                    separateWrites.add(target.file to newTarget)
                }
                else -> { // const：与入口同文件，合并进同一文本替换
                    entryReplacements.add(
                        target.objRangeInText to TsFileEditor.newRegionText(entryText, target.objRangeInText, unit.relative, target.existingKeys, relativeDrop)
                    )
                }
            }
        }
        val entryCombined = TsFileEditor.applyRangeReplacements(entryText, entryReplacements)
        return listOf(entryVf to entryCombined) + separateWrites
    }

    /**
     * 把 VirtualFile 内容替换为新文本（Write 安全封装）。
     * 迁移自 [com.pan.extractor.TsFileEditor.writeVirtualFileText]（实现体 1:1）。
     * 调用方需要自己包裹在 WriteCommandAction / invokeAndWait 中。
     * 返回是否写入成功；newText 若以 \uFEFF 开头则以 UTF-8 BOM 写盘（跨平台保留）。
     *
     * P0（可撤销性）：常规路径改走 Document 层 —— 在同一 WriteCommandAction 内调用
     * [com.intellij.openapi.editor.Document.setText] 会产生 undoable edit，Ctrl+Z 可撤销；
     * 仅 BOM 场景（Document 无法表达 \uFEFF，属极罕见）保留 VFS 二进制写盘回退。
     */
    fun writeVirtualFileText(entryVf: VirtualFile, newText: String): Boolean {
        return try {
            if (!newText.startsWith('\uFEFF')) {
                val document = FileDocumentManager.getInstance().getDocument(entryVf)
                if (document != null) {
                    // 必须在 WriteCommandAction 内调用：Document 修改进入撤销栈（P0 修复）。
                    document.setText(newText)
                    // Document 是内存缓冲：显式落盘，使 VFS 内容（contentsToByteArray / 其它读取）同步
                    // 到这次写入；否则 undo 命令结束前磁盘仍是旧内容，读取方会读到过期数据。
                    FileDocumentManager.getInstance().saveDocument(document)
                    return true
                }
            }
            // 无内存 Document 或 BOM 场景回退：setBinaryContent(content, startOffset, endOffset, requestor)
            // 会把 content 插入替换原文件的 [startOffset, endOffset) 区间。endOffset 必须取「原文件长度」
            // 而非新内容长度，否则当新内容比原文件短时，原文件尾部旧字节会被保留，造成文件损坏（P0）。
            val bytes = newText.toByteArray(StandardCharsets.UTF_8)
            entryVf.setBinaryContent(bytes, 0L, entryVf.length, null)
            true
        } catch (_: Exception) {
            false
        }
    }
}
