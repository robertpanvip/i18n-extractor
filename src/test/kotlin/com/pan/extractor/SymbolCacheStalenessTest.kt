package com.pan.extractor

import com.pan.extractor.analyzer.TranslationAnalyzer
import com.pan.extractor.analyzer.TranslationCallStatus
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 回归：「同一 IDEA 会话内改写源文件后，符号分析缓存必须失效」。
 *
 * 根因复盘（用户实测）：
 *  1. 第一次翻译把 `label: "第" + item.versionNum + "版"` 改写成
 *     `label: $t('第{versionNum}版', { versionNum: item.versionNum })`；
 *  2. 删除语言包里 `第{versionNum}版` key；再次翻译，该 key 没被重新收集；
 *  3. 重启 IDEA 后再翻译则正常。
 *
 * 症结在 SymbolAnalyzer.fileScanMemo：缓存代只用 VirtualFile.modificationStamp，而插件
 * 改写走 Document.setText —— vf stamp 不变、doc stamp 变。于是同一会话内复用改写前的旧
 * memo，其 calleeAnalysis 按「纯 offset」索引：
 *  - 改写前 offset X 是普通调用 `foo()` → 缓存为 NON_TRANSLATION；
 *  - 改写后 offset X 变成 `$t('第{versionNum}版')` → analyze 命中旧 NON_TRANSLATION；
 *  - collectExistingTKeys 因此不把该 key 收进 existingStrings，无法写回语言包；
 *  - 只有重启 IDEA 清空 object 级静态缓存才恢复。
 *
 * 本测试把改写前后的调用精确对齐到同一 offset，锁定该行为。修复口径：缓存代同时纳入
 * Document.modificationStamp（与 LocaleMessages 一致），改写即整代重建。
 */
class SymbolCacheStalenessTest : BasePlatformTestCase() {
    private val d = '$'

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """{"name":"t","dependencies":{"vue":"^3.0.0","vue-i18n":"^9.0.0"}}""".trimIndent(),
        )
        myFixture.addFileToProject(
            "src/locales/index.ts",
            "import { createI18n } from 'vue-i18n'\nexport const i18n = createI18n({})\nexport * from 'vue-i18n'",
        )
    }

    fun testRewriteInvalidatesCalleeAnalysisCache() {
        // 第一次：一个普通调用 foo() 位于 offset X，被判定并缓存为 NON_TRANSLATION。
        val before = """
            import { something } from 'somewhere';
            function foo() {}
            const a = foo();
        """.trimIndent()
        val file = myFixture.configureByText("a.ts", before) as PsiFile
        val callBefore = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
            .first { it.text.startsWith("foo(") }
        val offset = callBefore.textRange.startOffset
        assertEquals(TranslationCallStatus.NON_TRANSLATION, TranslationAnalyzer.statusOf(callBefore))

        // 改写：在同一 offset X 放一个 $t(...) 调用。
        // 用 Document.setText 模拟 IDE 会话内改写（VFS 未保存 → vf stamp 不变，doc stamp 变）。
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val head = "const { t: ${'$'}t } = useI18n();\n"
        assertTrue("前置声明需短于目标 offset", head.length <= offset)
        val prefix = head + " ".repeat(offset - head.length)
        val afterText = prefix + "${d}t('第{versionNum}版', { versionNum: 1 });\n"
        assertEquals(offset, afterText.substring(0, offset).length)

        val vf = file.virtualFile!!
        val vfStampBefore = vf.modificationStamp
        ApplicationManager.getApplication().runWriteAction { doc.setText(afterText) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        // VFS stamp 未变、Document stamp 已变 —— 缓存代必须因此失效。
        assertEquals(vfStampBefore, vf.modificationStamp)
        assertTrue(
            "Document stamp 应当变化以触发缓存失效",
            doc.modificationStamp > 0,
        )

        val callAfter = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
            .first { it.text.startsWith("${d}t(") }
        assertEquals("改写后的调用应位于同一 offset", offset, callAfter.textRange.startOffset)

        // 关键断言：不能被改写前同 offset 的 foo()=NON_TRANSLATION 陈旧结果污染。
        assertEquals(
            "改写后的 \$t 必须被识别为 TRANSLATION，否则第{versionNum}版 无法二次收集写回",
            TranslationCallStatus.TRANSLATION,
            TranslationAnalyzer.statusOf(callAfter),
        )
    }
}
