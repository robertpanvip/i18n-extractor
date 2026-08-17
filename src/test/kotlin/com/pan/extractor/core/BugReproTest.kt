package com.pan.extractor.core

import com.pan.extractor.TsFileEditor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现验证：Vue placeholder 因子化后写回 zh.ts 的 key 重复问题。
 * 旧 zh.ts 里已有整句 key（历史提取，如 "请输入搜索关键词"），因子化后新增了骨架 key，
 * 写回时必须把旧的整句 key 一并删除，否则出现重复。
 */
class BugReproTest {

    @Test
    fun generateZhTsShouldDropConsumedWholeKey() {
        // 旧 zh.ts：已有整句 key（历史提取）
        val old = """
export default {
  '请输入搜索关键词': '请输入搜索关键词',
  '用户名': '用户名',
}
""".trim()
        val info = TsFileEditor.parseTsExportedObject(old)!!
        // 因子化后的 finalExtracted：占位句被消费(移除)，改为骨架 + 内层 diff key
        val newFlat = mapOf(
            "请输入{N}" to "请输入{N}",
            "搜索关键词" to "搜索关键词",
        )
        val merged = TsFileEditor.mergeFlatIntoNested(
            info.staticKV, newFlat,
            dropExistingKeys = setOf("请输入搜索关键词")
        )
        val oldObjBody = old.substring(info.objectRange.first, info.objectRange.endExclusive)
        val newObjBody = TsFileEditor.regenerateObjectLiteralBody(
            oldObjBody, merged,
            dropKeys = setOf("请输入搜索关键词")
        )
        println("=== new Zh.ts body ===\n$newObjBody\n")

        // 骨架 key 应存在
        assertTrue("骨架 key 应存在，实际:\n$newObjBody", "'请输入{N}'" in newObjBody)
        // 旧的整句 key 应被删除，不再重复
        assertFalse("旧整句 key 不应保留（已由骨架承载），实际:\n$newObjBody", "'请输入搜索关键词'" in newObjBody)
    }
}