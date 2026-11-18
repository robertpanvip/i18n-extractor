package com.pan.extractor

import com.pan.extractor.analyzer.TranslationAnalyzer
import com.pan.extractor.core.I18nProcessor
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 回归：拼接串改写必须产生完整的 $t(...) JSCallExpression，而非纯文本叶子节点。
 *
 * 根因：JS_TEMPLATE 路径旧实现用 LeafPsiElement(STRING_LITERAL, "$t(...)") 注入，
 * PSI 树里不是真正的调用节点。二次翻译（同一会话内、删掉资源 key 后再翻译）时
 * collectExistingTKeys 靠 findChildrenOfType(JSCallExpression) 收集，找不到这些 key，
 * 导致已翻译的 `$t('第{versionNum}版', {...})` 无法补回 zh-CN.ts。
 */
class ConcatRewriteFullPsiTest : BasePlatformTestCase() {
    private val d = '$'

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """{"name":"vue-test","dependencies":{"vue":"^3.0.0","vue-i18n":"^9.0.0"}}""".trimIndent()
        )
        myFixture.addFileToProject(
            "src/locales/index.ts",
            "import { createI18n } from 'vue-i18n'\nexport const i18n = createI18n({})\nexport * from 'vue-i18n'"
        )
    }

    private val realVue = """
<script setup lang="ts">
import { message } from "ant-design-vue";
const { data: options, run } = useRequest(
  async () => {
    const res = await AgentApi.getReleasedVersionList(id, true);
    return (
      res.map((item) => ({
        label: "第" + item.versionNum + "版",
        value: item.id,
      })) ?? []
    );
  },
  { manual: true }
);
message.success("发布成功");
const { data: versionInfo } = useRequest(async () => {
  const res = await AgentApi.getAgentReleaseInfo(id, releaseType);
  return {
    label: res.versionNum ? "第" + res.versionNum + "版" : "",
    agentConfId: res.agentConfId,
    id: res.id,
  };
});
</script>
<template>
  <div>
    <a-button type="primary">发布</a-button>
    <span>发布的版本：</span>
    <span>{{ versionInfo?.label }}</span>
  </div>
</template>
""".trimIndent()

    fun testConcatRewriteProducesFullCallPsi() {
        val file = myFixture.configureByText("ReleaseTabPane.vue", realVue) as PsiFile

        // 第一遍翻译：拼接串被改写成 $t('第{versionNum}版', {...})
        val p1 = I18nProcessor(project, file)
        p1.collect()
        assertTrue(
            "第一遍应提取到 第{versionNum}版",
            p1.analyzer.extractedStrings.keys.any { it.contains("versionNum") }
        )
        p1.runWithUndo()

        // 关键断言：改写后 PSI 树里，两处拼接改写出的 $t('第{versionNum}版') 必须是真正的
        // JSCallExpression（而不是纯文本叶子节点）。
        val allCalls = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
        val versionTCalls = allCalls.filter {
            it.text.startsWith("${d}t('第{versionNum}版'")
        }
        assertTrue(
            "改写后应有 2 处 $d t('第{versionNum}版') 完整调用 PSI，实际=${allCalls.map { it.text.take(40) }}\n源码:\n${file.text}",
            versionTCalls.size == 2
        )
        assertTrue(
            "这些调用应被识别为 TRANSLATION",
            versionTCalls.all { TranslationAnalyzer.statusOf(it) == com.pan.extractor.analyzer.TranslationCallStatus.TRANSLATION }
        )

        // 第二遍 collect（同一 PSI，不 reparse）：应能收集到 key
        val p2 = I18nProcessor(project, file)
        p2.collect()
        assertTrue(
            "第二遍应收集到 第{versionNum}版，实际 existing=${p2.analyzer.existingStrings}",
            p2.analyzer.existingStrings.keys.any { it.contains("versionNum") }
        )
    }
}
