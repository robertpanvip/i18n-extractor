package com.pan.extractor.resource

/**
 * Resource 层内部的「扁平 key → 嵌套」合并原语。
 *
 * §12 解耦：原本驻留在代码编辑器 [com.pan.extractor.TsFileEditor] 的合并 helper 内迁到本包，
 * 使 resource 包彻底不依赖编辑 / 框架 / PSI —— 本层只处理纯数据（Map），由 [JsonWriter] /
 * [TsResourceWriter] 在写回时调用。[com.pan.extractor.TsFileEditor] 保留同名委托以兼容既有调用。
 */
internal object ResourceMerge {

    /**
     * 把新扁平 key → value 合并进已有的嵌套 Map。
     *
     *  - 点式 key（clean dotted path）→ 尝试按嵌套路径写入；
     *  - 冲突（中间段不是对象）或非干净点式路径 → 退化写顶层 key（重复以新值为准）；
     *  - [dropExistingKeys] 中的旧整句 key → 合并后删除（骨架 + 差异段已承载）。
     */
    fun mergeFlatIntoNested(
        existingNested: Map<String, Any?>,
        newFlat: Map<String, String>,
        dropExistingKeys: Set<String> = emptySet(),
    ): Map<String, Any?> {
        val result = deepCloneMap(existingNested)
        for ((k, v) in newFlat) {
            val segments = k.split('.')
            val isCleanDottedPath = k.contains('.') && segments.all { it.isNotBlank() }
            if (isCleanDottedPath && tryWriteNested(result, k, v)) continue
            result[k] = v
        }
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
                else -> return false
            }
        }
        cur[segments.last()] = value
        return true
    }
}