package com.pan.extractor.staticparser

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TS 翻译文件静态解析引擎性能基准。
 *
 * 被测主体：[StaticObjectParser.parseTsExportedObject] —— 即"解析 TS 翻译文件"那块：
 * 从文件文本定位 export default {...} 对象，抽取静态 KV（含嵌套对象 / 数组 / 字面量 / 类型后缀 / 模板字面量）。
 *
 * 设计：
 *  - 用真实形态的 TS 翻译文件（扁平字符串 key 为主，混入嵌套对象 / 数组 / 数字 / 布尔 / `as const` 后缀 / 模板字面量 / 转义）做输入；
 *  - 每个规模先 warmup 再计时取平均，避免 JIT 冷启动噪声；
 *  - 打印 per-parse 耗时与吞吐（keys/ms、parses/sec），并做一次正确性断言（解析结果非空且满足规模预期）。
 *
 * 这是一个"观测型"基准，不依赖特定机器的绝对阈值；但保留一个非常宽松的回归护栏（见 [GUARD_MS_PER_5K]）。
 */
class TsParserPerformanceTest {

    companion object {
        /** 宽松护栏：5000 条顶层入口的 avg 单次解析不得超过此毫秒数（防极端退化，正常机器远低于此）。 */
        private const val GUARD_MS_PER_5K = 800.0

        /** 不同规模（顶层入口条数）。 */
        private val SIZES = intArrayOf(500, 2_000, 5_000, 10_000, 50_000)

        private const val WARMUP = 5
        private const val ITERATIONS = 15
    }

    /** 生成一个真实形态的 TS 翻译文件文本（export default { ... }）。 */
    private fun buildTranslationFile(topLevelCount: Int): String {
        val sb = StringBuilder()
        sb.append("export default {\n")
        var produced = 0
        var i = 0
        while (produced < topLevelCount) {
            i++
            val kind = i % 10
            when {
                // 10%：嵌套对象（3 个子 key）
                kind == 0 -> {
                    sb.append("  'group_$i': {\n")
                    sb.append("    'sub1': '嵌套值一 with \\n newline',\n")
                    sb.append("    'sub2': '嵌套值二 \\u4e2d\\u6587',\n")
                    sb.append("    'sub3': '嵌套值三',\n")
                    sb.append("  },\n")
                }
                // 10%：数组字面量
                kind == 1 -> {
                    sb.append("  'arr_$i': ['alpha', 'beta', 'gamma'],\n")
                }
                // 10%：数字 / 布尔 / 带类型后缀 / 模板字面量 / 拼接
                kind == 2 -> sb.append("  'num_$i': 12345,\n")
                kind == 3 -> sb.append("  'bool_$i': true,\n")
                kind == 4 -> sb.append("  'suffixed_$i': '带后缀的值' as const,\n")
                kind == 5 -> sb.append("  'tmpl_$i': `prefix \${'插值'} suffix`,\n")
                kind == 6 -> sb.append("  'concat_$i': 'a' + 'b' + 'c',\n")
                // 其余 30%：普通带转义字符串（最常见形态）
                else -> {
                    val esc = if (i % 3 == 0) " with \\t tab and \\u4f60" else ""
                    sb.append("  'key_$i': '这是第 $i 条翻译文案$esc',\n")
                }
            }
            produced++
        }
        sb.append("};\n")
        return sb.toString()
    }

    @Test
    fun parseTsExportedObjectPerformance() {
        println()
        println("==================================================================")
        println(" TS 翻译文件静态解析性能基准 (StaticObjectParser.parseTsExportedObject)")
        println("==================================================================")
        println(
            "%-10s %-14s %-12s %-14s %-14s".format(
                "规模", "文件大小(KB)", "avg(ms)", "keys/ms", "parses/sec"
            )
        )
        println("-".repeat(66))

        var guardTripped = false

        for (size in SIZES) {
            val text = buildTranslationFile(size)
            val kb = text.length / 1024.0

            // warmup（不计入）
            repeat(WARMUP) {
                StaticObjectParser.parseTsExportedObject(text)
            }

            // 计时
            var totalNs = 0L
            var info: TsExportedObjectInfo? = null
            repeat(ITERATIONS) {
                val t0 = System.nanoTime()
                info = StaticObjectParser.parseTsExportedObject(text)
                totalNs += (System.nanoTime() - t0)
            }
            val avgMs = totalNs / ITERATIONS / 1_000_000.0
            val keysPerMs = size / avgMs
            val parsesPerSec = 1_000.0 / avgMs

            println(
                "%-10d %-14.1f %-12.3f %-14.1f %-14.1f".format(
                    size, kb, avgMs, keysPerMs, parsesPerSec
                )
            )

            // 正确性断言：解析结果非空，且顶层 KV 数量与生成规模一致
            assertNotNull("解析结果不应为 null（size=$size）", info)
            val topKeys = info!!.staticKV.size
            assertTrue(
                "顶层 KV 数量应接近生成规模（期望≈$size，实际=$topKeys）",
                topKeys >= (size * 0.9)
            )

            // 护栏：仅对 5000 量级做宽松检查（取 2000 与 10000 的线性外推偏保守，这里直接测 5000 档）
            if (size == 5_000 && avgMs > GUARD_MS_PER_5K) guardTripped = true
        }

        println("-".repeat(66))
        println("提示：单次解析为纯文本线性扫描；正常机器 1 万条应在数十 ms 内完成。")
        println("==================================================================")
        println()

        assertTrue(
            "性能护栏触发：5000 条顶层入口 avg 解析超过 $GUARD_MS_PER_5K ms，疑似严重退化",
            !guardTripped
        )
    }
}
