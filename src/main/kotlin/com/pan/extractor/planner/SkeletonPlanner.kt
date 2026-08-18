package com.pan.extractor.planner

import com.intellij.openapi.application.ApplicationManager
import com.pan.extractor.I18nSettings

/**
 * Planner 层 —— 骨架合并计划描述（迁移自 [com.pan.extractor.MergeApplier] 的纯函数）。
 *
 * 职责：把「公共前后缀 / 数字抽取」合并组描述成 RewritePlan 的素材——
 * 占位符映射（`{N0}` → Vue `{N0}` / React `{{0}}`）、参数对象文本、差异段/数字的字面量渲染。
 * 全部为纯函数（[buildPlaceholderRewrite] 在插件上下文读取占位符前缀设置），不操作 PSI、
 * 不修改任何文件 —— 符合「Plan 阶段只描述修改」的架构原则。
 */
object SkeletonPlanner {

    private val NUMBER_RE = Regex("""-?\d+(?:\.\d+)?""")

    /** 构建参数表达式里的占位符到 (占位, 参数 key) 映射（迁移自 MergeApplier.buildPlaceholderRewrite）。 */
    fun buildPlaceholderRewrite(
        isVue: Boolean,
        isReact: Boolean,
        pairs: List<Pair<String, String>>,
    ): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        // 纯函数：仅在存在 Application（插件运行上下文）时才读取设置里的前缀，
        // 否则回退默认 "N"，保证纯单元测试（无平台）也能运行。
        val app = ApplicationManager.getApplication()
        val vuePrefix = if (app != null) I18nSettings.getInstance().vuePlaceholderPrefix() else "N"
        pairs.forEachIndexed { i, (key, _) ->
            require(key.startsWith("N")) { "placeholder keys should be N0/N1 form" }
            val rawIndex = key.substring(1).toIntOrNull() ?: i
            when {
                isVue -> {
                    val k = "$vuePrefix$rawIndex"
                    result[key] = "{$k}" to k
                }
                isReact -> {
                    val k = rawIndex.toString()
                    result[key] = "{{$k}}" to "\"$k\""
                }
                else -> {
                    val k = rawIndex.toString()
                    result[key] = "{$k}" to "\"$k\""
                }
            }
        }
        return result
    }

    /** 构建调用侧参数对象文本（迁移自 MergeApplier.buildParamsObjectString）。 */
    fun buildParamsObjectString(isVue: Boolean, keyVals: List<Pair<String, String>>): String {
        if (keyVals.isEmpty()) return "{}"
        return keyVals.joinToString(prefix = "{ ", postfix = " }") { (k, vExpr) ->
            "$k: $vExpr"
        }
    }

    /** 把一个纯字符串渲染成 JS 字面量（差异段非中文时用；字符串加引号，数字不加）。 */
    fun renderLiteralValue(diff: String): String {
        if (diff.matches(NUMBER_RE)) return diff
        return quoteString(diff)
    }

    /** 数字抽取的占位值渲染：前导零（如 0755）会破坏 JS 字面量，必须加引号当字符串；纯数值保持数字。 */
    fun renderDigitLiteral(d: String): String {
        val isPlainNumber = d.matches(NUMBER_RE)
        val hasLeadingZero = d.length > 1 && d.startsWith("0") && !d.startsWith("0.")
        if (!isPlainNumber || hasLeadingZero) return quoteString(d)
        return d
    }

    private fun quoteString(s: String): String {
        val quote = if ('\'' !in s) "'" else "\""
        return "$quote${s.replace(quote, "\\$quote")}$quote"
    }
}
