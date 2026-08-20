package com.pan.extractor.testutil

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.junit.Assert.assertTrue

/**
 * 测试断言工具 —— Rewrite 后 PSI 语法完整性（P0 C 组, §19）。
 *
 * 自动修改插件最重要的指标之一是「改写后代码仍是合法 PSI」（`PsiErrorElement == 0`），
 * 而不是「字符串结果正确」。本 helper 供所有 Golden / Rewrite 测试复用，统一断言：
 *
 * ```
 * Rewrite
 *   ↓
 * reparse（重新建立 PsiFile / 对改写产物重解析）
 *   ↓
 * assertNoPsiErrors() == 通过
 * ```
 *
 * 用法（在 BasePlatformTestCase 子类中）：
 * ```kotlin
 * val psi = myFixture.addFileToProject("locales/zh.ts", rewrittenText)
 * assertNoPsiErrors(psi, "Vue 模板改写后")
 * ```
 */
object TestPsiAssertions {

    /** 收集 [PsiFile] 内所有 [PsiErrorElement]（跨 JS/TS/TSX/Vue 注入 PSI 通用）。 */
    fun findPsiErrors(file: PsiFile): List<PsiErrorElement> =
        PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).toList()

    /**
     * 断言 [file] 内没有任何 PSI 语法错误。
     *
     * @param file      改写产物对应的 PsiFile（re-parse 之后）。
     * @param context   人类可读的改写场景描述（用于失败信息定位）。
     * @throws AssertionError 存在语法错误时，错误描述 + 文件内容随附在 message。
     */
    fun assertNoPsiErrors(file: PsiFile, context: String) {
        val errors = findPsiErrors(file)
        assertTrue(
            "$context 改写产物存在 PSI 语法错误（PsiErrorElement=${errors.size}）：\n" +
                errors.map { it.text.trim() }.distinct().joinToString("\n  - ") { "- <$it>" } +
                "\n\n文件内容：\n" + file.text,
            errors.isEmpty()
        )
    }
}