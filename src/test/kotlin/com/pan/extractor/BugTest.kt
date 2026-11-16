package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class BugTest : BasePlatformTestCase() {
    override fun setUp() { super.setUp() }
    override fun getTestDataPath(): String = "src/test/resources"

    private fun runVue(content: String) {
        val file = myFixture.addFileToProject("test.vue", content)
        val processor = com.pan.extractor.core.I18nProcessor(project, file)
        processor.collect()
        println("  collected: ${processor.analyzer.extractedStrings}")
        println("  existing:  ${processor.analyzer.existingStrings}")
        println("  rewrites: ${processor.analyzer.rewrites.size}")
    }

    // 用户报告的 bug 场景:
    // t("请上传「Skill {no}」的技能包", { no: invalidSkillIndex + 1 })
    // 最终 JSON / 翻译文件里没有这个 key —— 为什么？
    fun testBugExactUserScenario() {
        val ds = "${'$'}"
        runVue("""
            <script setup>
            const msg = ${ds}t("请上传「Skill no」的技能包", { no: invalidSkillIndex + 1 })
            </script>
        """.trimIndent())
    }

    // 已有 $t('中文') 内部有简单标识符 no 作为参数 —— 简单场景验证
    fun testBugSimpleExistingTDollarWithNo() {
        val ds = "${'$'}"
        runVue("""
            <script setup>
            const msg = ${ds}t("请上传「Skill」的技能包", { no: invalidSkillIndex + 1 })
            </script>
        """.trimIndent())
    }

    // 没有 $t 的普通中文 —— 对照组
    fun testBugBaseline() {
        runVue("""
            <script setup>
            const msg = "请上传「Skill」的技能包" + no
            </script>
        """.trimIndent())
    }
}
