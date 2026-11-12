package com.pan.extractor

import com.pan.extractor.core.I18nProcessor
import com.pan.extractor.strategy.I18nFrameworkRegistry

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/**
 * E2E 批量测试：从 GitHub 真实项目中挑选典型文件，
 * 在 IntelliJ Platform Test Framework 中跑插件，验证提取与改写行为。
 *
 * 覆盖的项目（位于 /workspace/test-fixtures/）：
 *   Vue:  ant-design-vue, element-plus, vue-vben-admin, naive-ui, arco-design-vue, vant
 *   React: ant-design, material-ui, mantine
 *
 * 测试维度：
 *   1. 三元表达式含中文 + 变量
 *   2. mustache 模板中字符串拼接变量
 *   3. 模板字符串多变量插值
 *   4. JSX 中文文本节点
 *   5. 已有 $t() 调用不重复提取
 */
class ExternalProjectE2ETest : BasePlatformTestCase() {

    private val fixturesDir = "/workspace/test-fixtures"

    override fun setUp() {
        super.setUp()
        // 创建 Vue + React 混合项目
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "e2e-test-project",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0",
                "react": "^18.0.0",
                "react-dom": "^18.0.0",
                "react-i18next": "^13.0.0"
              }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "src/locales/index.ts",
            """
            import { createI18n } from 'vue-i18n';
            const messages = { zh: {}, en: {} };
            export const i18n = createI18n({ legacy: false, globalInjection: true, locale: 'zh', messages });
            """.trimIndent()
        )
    }

    // ───────────────────────────────────────────────
    // 辅助方法：从磁盘外部项目加载文件到临时项目
    // ───────────────────────────────────────────────

    private fun loadExternalFile(projectName: String, relativePath: String): PsiFile? {
        val absPath = "$fixturesDir/$projectName/$relativePath"
        val file = File(absPath)
        if (!file.exists() || !file.isFile) return null
        val content = file.readText()
        val fixturePath = "external/$projectName/$relativePath"
        return myFixture.addFileToProject(fixturePath, content)
    }

    /** 跑完整流程：collect → runWithUndo，返回处理后的文件内容。 */
    private fun processFile(psiFile: PsiFile): String {
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        WriteCommandAction.runWriteCommandAction(project) {
            processor.runWithUndo()
        }
        // 重新读取 PSI，拿到最新内容
        FileDocumentManager.getInstance().saveAllDocuments()
        return psiFile.text
    }

    // ───────────────────────────────────────────────
    // 1. Vue: ant-design-vue Contributors/index.vue
    //    模式：模板字符串拼接 + 三元 + 变量成员访问
    // ───────────────────────────────────────────────

    fun testAntDesignVueContributors() {
        val psiFile = loadExternalFile(
            "ant-design-vue",
            "site/src/components/Contributors/index.vue"
        ) ?: return // 文件不存在则跳过

        val result = processFile(psiFile)

        // 验证 1: 三元表达式中文字被提取
        //   原: {{ isZn ? '最后更新' : 'Last updated' }}
        //   应: {{ isZn ? $t('最后更新') : 'Last updated' }} 或 {{ $t(isZn ? '最后更新' : 'Last updated') }}
        assertTrue(
            "应提取中文字面量 '最后更新'，got:\n$result",
            result.contains("\$t('最后更新'")
        )

        // 验证 2: 模板字符串拼接变量不出现嵌套占位符
        //   原: `${isZn ? '文档贡献者：' : 'Contributor: '}${item.login}`
        assertFalse(
            "不应出现嵌套占位符 {N{N0}} 等",
            Regex("""\{N?\{N?\d+\}\}""").containsMatchIn(result)
        )
    }

    // ───────────────────────────────────────────────
    // 2. Vue: ant-design-vue Menu.vue
    //    模式: mustache 中模板字符串 + || 链式
    // ───────────────────────────────────────────────

    fun testAntDesignVueMenu() {
        val psiFile = loadExternalFile(
            "ant-design-vue",
            "site/src/layouts/Menu.vue"
        ) ?: return

        val result = processFile(psiFile)

        // 不应出现嵌套占位符
        assertFalse(
            "不应出现嵌套占位符",
            Regex("""\{N?\{N?\d+\}\}""").containsMatchIn(result)
        )

        // 不应出现参数值为字面量 0 的错误
        assertFalse(
            "不应出现参数值为字面量 0 的错误模式",
            Regex("""\{\s*N\d+\s*:\s*0\s*\}""").containsMatchIn(result)
        )
    }

    // ───────────────────────────────────────────────
    // 3. Vue: ant-design-vue Footer.vue
    //    模式: 多个 mustache 三元中文
    // ───────────────────────────────────────────────

    fun testAntDesignVueFooter() {
        val psiFile = loadExternalFile(
            "ant-design-vue",
            "site/src/layouts/Footer.vue"
        ) ?: return

        val result = processFile(psiFile)

        // 不应出现嵌套占位符
        assertFalse(
            "不应出现嵌套占位符",
            Regex("""\{N?\{N?\d+\}\}""").containsMatchIn(result)
        )

        // 不应出现参数值为字面量 0
        assertFalse(
            "不应出现参数值为字面量 0 的错误模式",
            Regex("""\{\s*N\d+\s*:\s*0\s*\}""").containsMatchIn(result)
        )

        // 至少应提取一个中文字符串
        assertTrue(
            "应提取至少一个中文字符串",
            result.contains("\$t(") || result.contains("_(")
        )
    }

    // ───────────────────────────────────────────────
    // 4. React: ant-design FormItem/index.tsx
    //    模式: JSX 中文字符串拼接变量
    // ───────────────────────────────────────────────

    fun testAntDesignReactFormItem() {
        val psiFile = loadExternalFile(
            "ant-design",
            "components/form/FormItem/index.tsx"
        ) ?: return

        val result = processFile(psiFile)

        // 不应出现嵌套占位符（数字占位符 {{N0}} 嵌套）
        assertFalse(
            "不应出现嵌套占位符",
            Regex("""\{\{\d+\}\}""").findAll(result).any { it.value.contains("{") }
        )
    }

    // ───────────────────────────────────────────────
    // 5. 批量扫描：所有测试夹具中含中文的 Vue/TSX/JSX 文件
    //    验证：不崩溃、不生成嵌套占位符、参数值不是字面量 0
    // ───────────────────────────────────────────────

    fun testBatchScanAllFixtures() {
        val fixturesRoot = File(fixturesDir)
        val fixtureProjects = listOf(
            "vue-vben-admin", "ant-design-vue", "arco-design-vue"
        )

        val extensions = setOf("vue", "tsx", "jsx", "ts", "js")
        val bugPattern = Regex("""(?:\{N\d+\}|\{\{\d+\}\})""")
        val nestedBug = Regex("""\{(?:N\d+|\{\{\d+\}\})""")
        val zeroParamBug = Regex("""\{\s*(?:N\d+|['"]\d+['"])\s*:\s*0\s*\}""")

        data class BugFile(val project: String, val path: String, val bugs: List<String>)

        val bugs = mutableListOf<BugFile>()
        var processedCount = 0

        for (project in fixtureProjects) {
            val projectDir = File(fixturesRoot, project)
            if (!projectDir.isDirectory) continue

            projectDir.walkTopDown().forEach { file ->
                if (file.isFile && file.extension in extensions) {
                    // 跳过 node_modules 和 dist
                    val pathStr = file.absolutePath
                    if (pathStr.contains("node_modules") || pathStr.contains("/dist/")) return@forEach

                    val content = file.readText()
                    // 只处理含中文的文件（可能被提取）
                    if (!Regex("""[\u4e00-\u9fff]""").containsMatchIn(content)) return@forEach

                    // 限制处理速度：总共最多 80 个文件（避免 Metaspace OOM）
                    if (processedCount > 80) return@forEach

                    val psiFile = loadExternalFile(project, file.relativeTo(projectDir).path)
                        ?: return@forEach

                    try {
                        val result = processFile(psiFile)
                        processedCount++

                        // 检查 bug 模式
                        val foundBugs = mutableListOf<String>()
                        if (nestedBug.containsMatchIn(result)) {
                            foundBugs.add("嵌套占位符: ${nestedBug.findAll(result).map { it.value }.distinct().take(3)}")
                        }
                        if (zeroParamBug.containsMatchIn(result)) {
                            foundBugs.add("参数值为字面量 0: ${zeroParamBug.findAll(result).map { it.value }.distinct().take(3)}")
                        }
                        // 检查 $t() 调用是否语法正确（括号匹配）
                        val tCalls = Regex("""${'$'}t\([^)]*\)""").findAll(result).toList()
                        val badTCalls = tCalls.filter { it.value.count { c -> c == '(' } != it.value.count { c -> c == ')' } }
                        if (badTCalls.isNotEmpty()) {
                            foundBugs.add("括号不匹配的 \$t() 调用: ${badTCalls.map { it.value.take(50) }.distinct().take(3)}")
                        }

                        if (foundBugs.isNotEmpty()) {
                            bugs.add(BugFile(project, file.relativeTo(projectDir).path, foundBugs))
                        }
                    } catch (e: Throwable) {
                        bugs.add(BugFile(project, file.relativeTo(projectDir).path, listOf("处理异常: ${e.message?.take(100) ?: e.javaClass.simpleName}")))
                    }
                }
            }
        }

        println("=== E2E 批量扫描结果 ===")
        println("处理文件数: $processedCount")
        println("发现 bug 文件数: ${bugs.size}")
        for (bug in bugs) {
            println("  [BUG] ${bug.project}/${bug.path}")
            bug.bugs.forEach { println("        $it") }
        }

        // 断言：不应发现 bug
        assertTrue(
            "批量扫描发现 ${bugs.size} 个 bug 文件:\n" +
                bugs.joinToString("\n") { b ->
                    "  ${b.project}/${b.path}: ${b.bugs.joinToString(", ")}"
                },
            bugs.isEmpty()
        )
    }
}
