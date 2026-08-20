package com.pan.extractor.core

import com.pan.extractor.messages.LocaleMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * 专门测试核心纯函数：LocaleMessages 的翻译资源扁平化逻辑。
 *
 * $t() 折叠展示译文时，会把入口语言文件（.json/.ts）解析成
 * 「扁平 key → 文案」映射（嵌套对象按 `.` 拼成 i18n key）。
 * 这里通过反射调用私有纯函数 flattenNested，验证：
 *   1. 顶层 key 直接展平
 *   2. 多级嵌套按 `.` 拼接
 *   3. 非字符串值（数字/布尔）转成字符串
 *   4. null 值被跳过
 *   5. 空 map 不产生输出
 */
class LocaleMessagesCoreFunctionTest {

    private val flattenNested: Method by lazy {
        LocaleMessages::class.java.getDeclaredMethod(
            "flattenNested",
            Map::class.java,
            String::class.java,
            MutableMap::class.java
        ).apply { isAccessible = true }
    }

    private fun flatten(map: Map<String, Any?>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        flattenNested.invoke(LocaleMessages, map, "", out)
        return out
    }

    @Test
    fun flattensTopLevelKeys() {
        val out = flatten(mapOf("hello" to "你好", "bye" to "再见"))
        assertEquals("你好", out["hello"])
        assertEquals("再见", out["bye"])
    }

    @Test
    fun flattensNestedObjectsWithDot() {
        val out = flatten(
            mapOf(
                "common" to mapOf(
                    "confirm" to "确定",
                    "actions" to mapOf("save" to "保存"),
                )
            )
        )
        assertEquals("确定", out["common.confirm"])
        assertEquals("保存", out["common.actions.save"])
    }

    @Test
    fun convertsNonStringValuesToString() {
        val out = flatten(mapOf("count" to 42, "flag" to true))
        assertEquals("42", out["count"])
        assertEquals("true", out["flag"])
    }

    @Test
    fun skipsNullValues() {
        val out = flatten(mapOf("a" to "x", "nullKey" to null))
        assertEquals("x", out["a"])
        assertTrue("null 值应被跳过", "nullKey" !in out)
    }

    @Test
    fun emptyMapProducesNoOutput() {
        assertTrue(flatten(emptyMap()).isEmpty())
    }

    @Test
    fun mixedNestedAndScalar() {
        val out = flatten(
            mapOf(
                "title" to "标题",
                "errors" to mapOf("notFound" to "未找到", "server" to "服务器错误"),
            )
        )
        assertEquals("标题", out["title"])
        assertEquals("未找到", out["errors.notFound"])
        assertEquals("服务器错误", out["errors.server"])
    }
}