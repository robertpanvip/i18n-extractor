package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue

/**
 * 回归：Vue SFC 首次注入 useI18n 时，`import { useI18n }` 必须位于
 * `const { t: $t } = useI18n()` 之前，且两条语句分行。
 *
 * 背景（SourceRewriter.applyImportPlan 的顺序 bug）：
 *   imports 先注入为「文本 Leaf」，尚未 reparse 成 ES6ImportDeclaration；
 *   hooks 阶段 injectVueSfcScriptConst 用 findChildrenOfType 查不到这个新 import，
 *   误判「文件无 import」而把 const 投到 script 首位 → 出现
 *     const { t: $t } = useI18n();
 *     import { useI18n } from 'vue-i18n';
 *   的倒序（import 提升虽能运行，但顺序错乱、排版难看）。
 *   修复：把 imports 阶段的锚点透传给 hook 阶段兜底定位。
 */
class VueUseI18nInjectionOrderTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "package.json",
            """
            { "name": "vue-inject-order", "dependencies": { "vue": "^3.0.0", "vue-i18n": "^9.0.0" } }
            """.trimIndent()
        )
    }

    /**
     * 场景 A：script 无任何 import + 模板与 script 都有硬编码中文。
     * 期望：注入的 import 在 const 之前，且两者不在同一行。
     */
    fun testImportBeforeConstWhenScriptHadNoImports() {
        val file = myFixture.addFileToProject(
            "src/Order.vue",
            """
            <template>
              <div><span>需要翻译</span></div>
            </template>
            <script setup lang="ts">
            const msg = '脚本里的中文'
            </script>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val result = file.text
        assertInjectedOrder(result)
    }

    /**
     * 场景 B：script 已有其它 import + 硬编码中文（走「原生 import」分支）。
     * 期望同样：useI18n import 与 const 都在，且 import 在 const 之前。
     */
    fun testImportBeforeConstWhenScriptAlreadyHasImports() {
        val file = myFixture.addFileToProject(
            "src/OrderWithImport.vue",
            """
            <template>
              <div><span>需要翻译</span></div>
            </template>
            <script setup lang="ts">
            import { ref } from 'vue'
            const count = ref(0)
            const msg = '脚本里的中文'
            </script>
            """.trimIndent()
        )
        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val processor = I18nProcessor(project, file)
        processor.collect()
        processor.runWithUndo()

        val result = file.text
        assertInjectedOrder(result)
    }

    /** 断言：useI18n 的 import 在 const 之前，且两条语句各自独占一行（未粘连）。 */
    private fun assertInjectedOrder(result: String) {
        val importIdx = result.indexOf("import { useI18n } from 'vue-i18n'")
        val constIdx = result.indexOf("const { t: \$t } = useI18n()")
        assertTrue("应注入 useI18n import, got:\n$result", importIdx >= 0)
        assertTrue("应注入 useI18n const 解构, got:\n$result", constIdx >= 0)
        assertTrue(
            "import { useI18n } 必须位于 const { t: \$t } = useI18n() 之前, got:\n$result",
            importIdx < constIdx
        )
        // 两条语句不能粘在同一行：import 语句结束的 ';' 与 const 之间应存在换行
        val importStmtEnd = importIdx + "import { useI18n } from 'vue-i18n';".length
        val between = result.substring(importStmtEnd, constIdx)
        assertTrue(
            "import 与 const 之间必须有换行（不得粘连成 `import...;const...;`）, got:\n$result",
            between.contains('\n')
        )
    }
}
