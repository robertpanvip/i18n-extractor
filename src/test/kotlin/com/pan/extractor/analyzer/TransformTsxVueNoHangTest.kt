package com.pan.extractor.analyzer

import com.pan.extractor.core.I18nProcessor
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * 回归卫士：对一个**几乎全量已 `$t` 化**、且 `<script setup lang="tsx">` 里带 JSX 的 Vue 文件，
 * 跑完整 `collect()`（collectExistingTKeys + collectFromPsi）。
 *
 * 这类文件在 SymbolAnalyzer 缓存化之前，会让「转化」陷入极慢（等价卡死）：
 *  - 文件里每个**已存在**的 JSCallExpression 都会被 collectExistingTKeys 执行一次 analyzeCall；
 *  - 每个 string/调用都会触发逐名全文件 findChildrenOfType、barrel 递归、resolve() 重复；
 *  - 同一 call 在 detect / collect 两阶段被重复 analyze。
 *
 * 本测试断言 collect() 能正常返回，且：遗留中文被提取、已 `$t` 的 key 不被重复提取。
 * 作为缓存回归护栏 —— 任何让上述路径回退成"无缓存重扫"的改动都会在此放大耗时甚至超时。
 */
class TransformTsxVueNoHangTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """{"name": "no-hang", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" }}"""
        )
    }

    private fun configureFile(name: String, text: String): PsiFile {
        val psiFile = myFixture.addFileToProject(name, text)
        myFixture.configureFromExistingVirtualFile(psiFile.virtualFile)
        return psiFile
    }

    fun testTransformHeavilyTranslatedTsxVueCompletes() {
        val file = configureFile(
            "src/HitTest.vue",
            """
            <script setup lang="tsx">
            import { useI18n } from 'vue-i18n'
            import { Button, message } from 'ant-design-vue'
            import { computed, ref } from 'vue'
            import { useRoute } from 'vue-router'
            import { HitTestApi } from '@/service/hit-test'

            const { t: ${'$'}t } = useI18n()
            const route = useRoute()
            const kb = (route.params.id as string) || ''
            const currentRecord = ref(null)
            const crudRef = ref()

            const CONFIG_OPTIONS = [
              { label: ${'$'}t('语义检索'), value: 'DENSE' },
              { label: ${'$'}t('关键词检索'), value: 'BM25' },
            ]

            const columns = computed(() => [
              { title: ${'$'}t('测试内容'), dataIndex: 'q', width: 130,
                render: ({ value }: any) => (<span title={String(value ?? '')}>{value || '--'}</span>) },
              { title: ${'$'}t('测试时间'), dataIndex: 'd', width: 130 },
              { title: ${'$'}t('测试耗时'), dataIndex: 't', width: 70,
                render: ({ value }: any) => <span>{value ?? '--'}</span> },
            ])

            const handleRun = async () => {
              const text = '输入内容'.trim()
              if (!text) { message.warning(${'$'}t('测试文本不能为空')); return }
              const res = await HitTestApi.execute(kb, { query: text })
              currentRecord.value = { id: res.hitLogId, kb, q: res.query }
              await crudRef.value?.refreshAsync()
            }
            </script>
            <template>
              <div>
                <span>{{ ${'$'}t('命中测试') }}</span>
                <Button :title="${'$'}t('查询设置')" >${'$'}t('开始测试')</Button>
                <div>这是需要提取的模板文本</div>
                <div class="hit">{{ '只需要提取这句' }}</div>
              </div>
            </template>
            """.trimIndent()
        )

        val processor = I18nProcessor(project, file)
        processor.collect()

        // 已经 $t 化的 key 不应被再次提取
        assertFalse("已 ${'$'}t 化的 key 不应重复提取",
            processor.analyzer.extractedStrings.keys.any { "命中测试" in it } ||
                processor.analyzer.extractedStrings.containsKey("命中测试"))

        // 遗留中文应被提取
        assertTrue("模板文本遗留中文应被提取",
            processor.analyzer.extractedStrings.values.any { "这是需要提取的模板文本" in it })

        // 整个 collect 正常返回，未挂死（若缓存回归，上两步会先因超时失败）
        assertTrue(processor.analyzer.rewrites.isNotEmpty())
    }
}