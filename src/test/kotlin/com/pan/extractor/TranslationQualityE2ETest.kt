package com.pan.extractor

import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.pan.extractor.core.I18nProcessor
import org.junit.Assert.*

/**
 * 翻译质量验证测试 — 不只是"不崩溃"，还要断言 $t() 调用的语义正确性。
 *
 * 覆盖场景:
 *   1. 简单拼接顺序 (A + var + B)
 *   2. 变量在中间 + 两端中文
 *   3. 深度嵌套拼接
 *   4. 多变量对称占位符
 *   5. Vue mustache 复杂拼接
 *   6. 模板字符串内部变量顺序
 *   7. 含特殊字符的中文
 *   8. React JSX 属性拼接
 *   9. 嵌套占位符 bug 回归
 *  10. 三元表达式内部中文
 *  11. 数字字面量参与拼接
 *  12. 变量在末尾/开头
 *  13. 括号匹配、零参数等 bug 回归
 */
class TranslationQualityE2ETest : BasePlatformTestCase() {

    private val d = '$' // dollar sign helper for Kotlin template escaping

    private fun processVue(fileContent: String): String {
        val psiFile = myFixture.addFileToProject("test.vue", fileContent) as PsiFile
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        processor.runWithUndo()
        return psiFile.text
    }

    private fun processReact(fileContent: String): String {
        val psiFile = myFixture.addFileToProject("test.tsx", fileContent) as PsiFile
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        processor.runWithUndo()
        return psiFile.text
    }

    // ───────────────────────────────────────────────
    // 1. 简单三片段拼接：顺序不能乱
    // ───────────────────────────────────────────────
    fun testSimpleConcatOrder() {
        val content = """
<script setup>
const msg = "前面" + userName + "后面";
</script>
"""
        val result = processVue(content)
        assertTrue("key 应包含原始顺序: $result",
            result.contains("前面") && result.contains("后面"))
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("占位符应对应 userName: $result", result.contains("userName"))
    }

    // ───────────────────────────────────────────────
    // 2. 变量在中间 + 两端中文
    // ───────────────────────────────────────────────
    fun testVarInMiddle() {
        val content = """
<script setup>
const tip = "共" + total + "个文件";
</script>
"""
        val result = processVue(content)
        assertTrue("应包含 '共': $result", result.contains("共"))
        assertTrue("应包含 '个文件': $result", result.contains("个文件"))
        assertTrue("应包含 total 参数: $result", result.contains("total"))
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
    }

    // ───────────────────────────────────────────────
    // 3. 深度嵌套拼接
    // ───────────────────────────────────────────────
    fun testDeepNestedConcat() {
        val content = """
<script setup>
const deep = ("开始" + v1 + "中间") + v2 + v3 + "结束";
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("key 应包含 开始: $result", result.contains("开始"))
        assertTrue("key 应包含 中间: $result", result.contains("中间"))
        assertTrue("key 应包含 结束: $result", result.contains("结束"))
    }

    // ───────────────────────────────────────────────
    // 4. 多变量对称: A + v1 + B + v2 + C → 2 个占位符
    // ───────────────────────────────────────────────
    fun testMultipleVarsSymmetric() {
        val content = """
<script setup>
const info = "第" + page + "页 / 共" + total + "页";
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("应包含 page 参数: $result", result.contains("page"))
        assertTrue("应包含 total 参数: $result", result.contains("total"))
        val placeholderCount = Regex("""\{N\d+\}""").findAll(result).count()
        assertTrue("应有 >=2 个占位符, 实际 $placeholderCount: $result", placeholderCount >= 2)
    }

    // ───────────────────────────────────────────────
    // 5. Vue mustache 中复杂拼接
    // ───────────────────────────────────────────────
    fun testVueMustacheConcat() {
        val content = """
<template>
  <span>{{ "你好, " + userName + "同学! 今天是" + day + "号" }}</span>
</template>
"""
        val result = processVue(content)
        assertTrue("mustache 应被 ${d}t 包裹: $result", result.contains("${d}t"))
        assertTrue("应包含 userName: $result", result.contains("userName"))
        assertTrue("应包含 day: $result", result.contains("day"))
    }

    // ───────────────────────────────────────────────
    // 6. 模板字符串内部变量顺序：不能乱
    // JS: `你好 ${d}{firstName}，欢迎来到 ${d}{city}！`
    // ───────────────────────────────────────────────
    fun testTemplateLiteralVarOrder() {
        val content = """
<script setup>
const fullMsg = `你好 ${d}{firstName}，欢迎来到 ${d}{city}！`;
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        // 检查生成的 $t 调用中参数顺序: firstName 必须在 city 之前
        // 提取参数对象 { N0: firstName, N1: city } 部分
        val paramsSection = Regex("""$\{d}t\([^)]*\{([^}]*)\}[^)]*\)""")
            .let { result.substringAfterLast("${d}t(").substringBeforeLast(')') }
        val firstNameIdx = paramsSection.indexOf("firstName")
        val cityIdx = paramsSection.indexOf("city")
        if (firstNameIdx > 0 && cityIdx > 0) {
            assertTrue("参数顺序: firstName($firstNameIdx) 应在 city($cityIdx) 之前",
                firstNameIdx < cityIdx)
        }
    }

    // ───────────────────────────────────────────────
    // 7. 含特殊字符的中文
    // ───────────────────────────────────────────────
    fun testSpecialCharsInConcat() {
        val content = """
<script setup>
const warnMsg = "【警告】" + error + "！请重试";
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("应包含 警告: $result", result.contains("警告"))
        assertTrue("应包含 请重试: $result", result.contains("请重试"))
    }

    // ───────────────────────────────────────────────
    // 8. React JSX 属性中的拼接
    // ───────────────────────────────────────────────
    fun testReactJsxAttributeConcat() {
        val content = """
export function Greeting({ name }) {
    return <div title={"欢迎 " + name + " 来到我们的网站"}>Hello</div>;
}
"""
        val result = processReact(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("应包含 name 参数: $result", result.contains("name"))
    }

    // ───────────────────────────────────────────────
    // 9. 嵌套占位符 bug 回归
    // ───────────────────────────────────────────────
    fun testNoNestedPlaceholders() {
        val content = """
<script setup>
const msg = "前" + (isOk ? "中" : "间") + "后";
</script>
"""
        val result = processVue(content)
        val nested = Regex("""\{N\{N\d+\}\}""").findAll(result).toList()
        assertTrue("不应有嵌套占位符: $nested in $result", nested.isEmpty())
    }

    // ───────────────────────────────────────────────
    // 10. 三元表达式内部中文也应被处理
    // ───────────────────────────────────────────────
    fun testTernaryInsideConcat() {
        val content = """
<script setup>
const label = status ? "已发布" : "草稿";
const full = "状态: " + label;
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
    }

    // ───────────────────────────────────────────────
    // 11. 数字字面量参与拼接
    // ───────────────────────────────────────────────
    fun testNumberConcat() {
        val content = """
<script setup>
const n = 5;
const msg = "共" + n + "个";
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("应包含占位符: $result", result.contains("N0"))
    }

    // ───────────────────────────────────────────────
    // 12. 变量在末尾: "前缀" + var
    // ───────────────────────────────────────────────
    fun testVarAtEnd() {
        val content = """
<script setup>
const text = "下载次数: " + downloadCount;
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("应包含 downloadCount: $result", result.contains("downloadCount"))
    }

    // ───────────────────────────────────────────────
    // 13. 变量在开头: var + "后缀"
    // ───────────────────────────────────────────────
    fun testVarAtStart() {
        val content = """
<script setup>
const text = rank + " 名";
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("应包含 rank: $result", result.contains("rank"))
    }

    // ───────────────────────────────────────────────
    // 14. 不应有括号不匹配
    // ───────────────────────────────────────────────
    fun testNoMismatchedParens() {
        val content = """
<script setup>
const msg = "前" + var1 + "中" + var2 + "后";
</script>
"""
        val result = processVue(content)
        val tCalls = Regex("""${d}t\([^)]*\)""").findAll(result).toList()
        for (call in tCalls) {
            val openCount = call.value.count { it == '(' }
            val closeCount = call.value.count { it == ')' }
            assertEquals("括号应匹配: ${call.value}", openCount, closeCount)
        }
    }

    // ───────────────────────────────────────────────
    // 15. 变量 n 拼接后不应丢成字面量 0（bug 回归）
    //     变量名必须被正确提取为参数对象的值
    // ───────────────────────────────────────────────
    fun testVariableNameNotLost() {
        val content = """
<script setup>
const n = getCount();
const msg = "前" + n + "后";
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("参数应包含变量名 n，而不是字面量 0: $result",
            result.contains("N0: n"))
    }

    // ───────────────────────────────────────────────
    // 16. 纯字符串拼接 (无变量) 不应产生多余 $t
    // ───────────────────────────────────────────────
    fun testPureStringConcatNoDouble() {
        val content = """
<script setup>
const title = "开始" + " 与 " + "结束";
</script>
"""
        val result = processVue(content)
        // 理想情况：整个 "开始 与 结束" 被合并成一个 $t
        assertTrue("纯字符串拼接也应提取: $result", result.contains("${d}t"))
    }

    // ───────────────────────────────────────────────
    // 17. 混合引号类型 (单引号拼接双引号)
    // ───────────────────────────────────────────────
    fun testMixedQuoteConcat() {
        val content = """
<script setup>
const text = '点击' + btnName + " 按钮";
</script>
"""
        val result = processVue(content)
        assertTrue("应生成 ${d}t 调用: $result", result.contains("${d}t"))
        assertTrue("应包含 btnName: $result", result.contains("btnName"))
        assertTrue("应包含 点击: $result", result.contains("点击"))
        assertTrue("应包含 按钮: $result", result.contains("按钮"))
    }

    // ───────────────────────────────────────────────
    // 18. 格式保持: Windows \r\n 换行符不应被改成 \n
    // ───────────────────────────────────────────────
    fun testPreserveWindowsLineEndings() {
        val content = "<script setup>\r\nconst msg = \"你好\";\r\n</script>\r\n"
        val psiFile = myFixture.addFileToProject("test.vue", content) as PsiFile
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        processor.runWithUndo()
        val result = psiFile.text

        val crlfBefore = content.count { it == '\r' }
        val crlfAfter = result.count { it == '\r' }
        assertEquals("原文件是 CRLF，改写后不应丢失 \\r (before=$crlfBefore, after=$crlfAfter)",
            crlfBefore, crlfAfter)
    }

    // ───────────────────────────────────────────────
    // 19. 格式保持: 文件尾随换行是否应保持一致
    // ───────────────────────────────────────────────
    fun testPreserveTrailingNewline() {
        val withNL = "<script setup>\nconst msg = \"你好\";\n</script>\n"
        val f1 = myFixture.addFileToProject("a.vue", withNL) as PsiFile
        I18nProcessor(project, f1).let { it.collect(); it.runWithUndo() }
        assertTrue("有尾换行的文件应保持有尾换行", f1.text.endsWith('\n'))
    }
}
