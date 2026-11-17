package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * owner 行为需求（2026-11）：属性链插值取最后一段作占位 key
 *   `${d}{item.versionNum}` → `{versionNum}`，参数值保留完整表达式。
 * 同一句内 key 冲突（如 item.versionNum 与 user.versionNum 都取 versionNum）
 * → 冲突双方回退编号 {N0}/{N1}。
 */
class LastSegmentPlaceholderKeyTest : BasePlatformTestCase() {

    private val d = '$'

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """{ "name": "t", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0", "react": "^18.0.0", "react-dom": "^18.0.0", "react-i18next": "^13.0.0" } }"""
        )
    }

    private fun rewriteVue(text: String): String {
        val psiFile = myFixture.addFileToProject("Demo${System.nanoTime()}.vue", text) as PsiFile
        val p = I18nProcessor(project, psiFile)
        p.collect(); p.runWithUndo()
        return psiFile.text
    }

    private fun rewriteTsx(text: String, name: String = "Demo.tsx"): String {
        val psiFile = myFixture.addFileToProject(name, text) as PsiFile
        val p = I18nProcessor(project, psiFile)
        p.collect(); p.runWithUndo()
        return psiFile.text
    }

    fun testDotChainMustUseLastSegmentAsKey() {
        val r = rewriteVue("""
            <template><span>{{ "第" + record.versionNum + "版" }}</span></template>
            <script setup lang="ts">
            const record = { versionNum: 1 }
            </script>
        """.trimIndent())
        assertTrue("应生成 ${d}t('第{versionNum}版', ...)，got:\n$r", r.contains("第{versionNum}版"))
        assertTrue("参数值应保留完整表达式，got:\n$r", r.contains("versionNum: record.versionNum"))
        assertFalse("不应回退编号占位，got:\n$r", r.contains("第{N0}版"))
    }

    fun testDeepChainMustUseTailSegment() {
        val r = rewriteVue("""
            <script setup lang="ts">
            const msg = `共${d}{user.profile.nickname}人`
            </script>
        """.trimIndent())
        assertTrue("深链应取尾段 nickname，got:\n$r", r.contains("{nickname}"))
        assertTrue("参数值保留完整链，got:\n$r", r.contains("nickname: user.profile.nickname"))
    }

    fun testCollisionInsideSameMessageFallsBackToNumbered() {
        val r = rewriteVue("""
            <script setup lang="ts">
            const msg = `第${d}{item.versionNum}和第${d}{user.versionNum}版`
            </script>
        """.trimIndent())
        assertTrue("同句 key 冲突应回退编号，got:\n$r", r.contains("第{N0}和第{N1}版"))
        assertTrue("N0 应绑定第一个表达式，got:\n$r", r.contains("N0: item.versionNum"))
        assertTrue("N1 应绑定第二个表达式，got:\n$r", r.contains("N1: user.versionNum"))
    }

    fun testNonCollisionLastSegmentsCoexistWithNumericFallback() {
        val r = rewriteVue("""
            <script setup lang="ts">
            const msg = `${d}{item.versionNum}共${d}{count + 1}项`
            </script>
        """.trimIndent())
        assertTrue("非冲突尾段应保留语义 key，got:\n$r", r.contains("{versionNum}"))
        assertTrue("复杂表达式仍回退编号，got:\n$r", r.contains("共{N1}项"))
    }

    fun testReactDotChainLastSegment() {
        // 双依赖（vue+react）项目 detect 设计上优先 Vue（isVue 优先）→ 就近放纯 react 依赖的 package.json
        myFixture.addFileToProject(
            "r/package.json",
            """{ "name": "t", "dependencies": { "react": "^18.0.0", "react-dom": "^18.0.0", "react-i18next": "^13.0.0" } }"""
        )
        val r = rewriteTsx("""
            import { useTranslation } from 'react-i18next';
            export function App({ item }: { item: { versionNum: number } }) {
              const { t: ${d}t } = useTranslation();
              return <div>{'第' + item.versionNum + '版'}</div>;
            }
        """.trimIndent(), "r/App.tsx")
        assertTrue("React 占位应 {{versionNum}}，got:\n$r", r.contains("{{versionNum}}"))
        assertTrue("React 参数键带引号且值保留表达式，got:\n$r", r.contains("\"versionNum\": item.versionNum"))
    }
}
