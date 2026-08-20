package com.pan.extractor

import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.testutil.TestPsiAssertions.assertNoPsiErrors
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * P0 C 组：Rewrite 后 PSI 语法完整性（PROJECT_ANALYSIS §19）。
 *
 * 断言自动改写/重写产物的「输出文本 re-parse 后 PsiErrorElement == 0」：
 *  - C3：TS/JS 资源写回产物（[TsFileEditor.regenerateObjectLiteralBody]）包成 export default 后 re-parse 干净；
 *  - C2：关键 Rewrite 形态（JSX attribute 绑定、JS template literal）改写产物 re-parse 干净；
 *  - C1：验证共享 helper [TestPsiAssertions.assertNoPsiErrors] 本身能检测到语法错误。
 */
class RewritePsiSyntaxTest : BasePlatformTestCase() {

    // ─────────────────────────────────────────────
    // C1：helper 自身能被「合法/非法」正确区分
    // ─────────────────────────────────────────────
    fun testHelperPassesOnValidFile() {
        val psi = myFixture.addFileToProject(
            "c1_valid.ts",
            "export const messages = { '首页': '首页', '退出': '退出' };\n"
        )
        assertNoPsiErrors(psi, "合法 TS 资源文件")
    }

    fun testHelperDetectsSyntaxError() {
        // 双花括号 → JS 括号不匹配 → 必然产生 PsiErrorElement
        val bad = myFixture.addFileToProject("c1_bad.ts", "const x = {{ 'a': 1 }};\n")
        val caught: String? = try {
            assertNoPsiErrors(bad, "应为非法文件")
            null
        } catch (e: AssertionError) {
            e.message
        }
        assertTrue("helper 应检测到语法错误并给出失败信息，实际未检测到", caught != null && caught!!.contains("语法错误"))
    }

    // ─────────────────────────────────────────────
    // C3：TS 资源写回产物 re-parse 干净（nested / dotted / spread / arrays / comments）
    // ─────────────────────────────────────────────
    private fun assertRegeneratedResourceParses(
        oldBody: String,
        merged: Map<String, Any?>,
        fileName: String,
        context: String,
    ) {
        val body = TsFileEditor.regenerateObjectLiteralBody(oldBody, merged)
        val psi: PsiFile = myFixture.addFileToProject(fileName, "export default $body\n")
        assertNoPsiErrors(psi, context)
    }

    fun testNestedMergeOutputReparses() {
        val old = """
            '首页': '首页',
            '用户': {
              'name': '姓名',
            },
        """.trimIndent()
        val merged = linkedMapOf<String, Any?>(
            "首页" to "首页",
            "用户" to linkedMapOf("name" to "姓名", "age" to "年龄"),
        )
        assertRegeneratedResourceParses(old, merged, "c3_nested.ts", "嵌套资源合并产物")
    }

    fun testDottedKeyExpansionOutputReparses() {
        val old = """
            'common': {
              'confirm': '确认',
            },
        """.trimIndent()
        // 点式新 key common.confirm 应展开进嵌套结构
        val result = TsFileEditor.mergeFlatIntoNested(
            linkedMapOf("common" to linkedMapOf("confirm" to "确认")),
            linkedMapOf("common.confirm" to "确认", "common.cancel" to "取消")
        )
        assertRegeneratedResourceParses(old, result, "c3_dotted.ts", "点式 key 展开产物")
    }

    fun testDottedKeyWithEllipsisNotExpandedReparses() {
        // 「加载中...」含空格分段，不应误展开成嵌套
        val old = "'st': {},"
        val result = TsFileEditor.mergeFlatIntoNested(
            emptyMap(),
            linkedMapOf("加载中..." to "加载中...", "st" to "状态")
        )
        assertRegeneratedResourceParses(old, result, "c3_ellipsis.ts", "省略号点式 key 防护产物")
    }

    fun testSpreadPreservedOutputReparses() {
        // 动态 spread 行应原样保留，输出仍是合法 TS
        val old = """
            ...common,
            '首页': '首页',
        """.trimIndent()
        val merged = linkedMapOf("首页" to "首页", "新增" to "新增文本")
        assertRegeneratedResourceParses(old, merged, "c3_spread.ts", "spread 保留产物")
    }

    fun testArrayValueOutputReparses() {
        val old = "'tags': ['a', 'b'],"
        val result = TsFileEditor.mergeFlatIntoNested(
            linkedMapOf("tags" to listOf("a", "b")),
            linkedMapOf("tags" to "a") // 冲突：数组 vs 新串，以新值为准
        )
        assertRegeneratedResourceParses(old, result, "c3_arrays.ts", "数组值产物")
    }

    // ─────────────────────────────────────────────
    // C2：关键改写形态（JSX attribute + JS template literal）产物 re-parse 干净
    // ─────────────────────────────────────────────
    fun testJsxAttributeRewriteOutputReparses() {
        // 「{{ $t('key') }}」形态的 JSX 属性改写产物
        val tsx = myFixture.addFileToProject(
            "c2_attribute.tsx",
            """
            function App() {
              const { t } = useTranslation();
              return React.createElement('div', { title: t('title') }, '');
            }
            """.trimIndent()
        )
        assertNoPsiErrors(tsx, "JSX 属性改写产物")
    }

    fun testJsTemplateLiteralRewriteOutputReparses() {
        // ${...} 插值改写产物（含字符串里的 $)
        val ts = myFixture.addFileToProject(
            "c2_template.ts",
            "const label = `\${t('hello')} 世界`;\n" +
                "const raw = `价格 \$t('price')`;\n"
        )
        assertNoPsiErrors(ts, "JS 模板字符串改写产物")
    }
}