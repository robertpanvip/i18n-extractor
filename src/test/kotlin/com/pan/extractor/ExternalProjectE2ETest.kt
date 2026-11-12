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

    /** 跑完整流程：collect → runWithUndo，返回处理后的文件内容。
     *  禁用后处理格式化以加速（真实使用场景也不需要）。 */
    private fun processFile(psiFile: PsiFile): String {
        val processor = I18nProcessor(project, psiFile)
        processor.collect()
        com.intellij.psi.impl.source.PostprocessReformattingAspect.getInstance(project).disablePostprocessFormattingInside {
            WriteCommandAction.runWriteCommandAction(project) {
                processor.runWithUndo()
            }
        }
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
            // Vue 项目
            "vue-vben-admin", "vue-pure-admin", "arco-design-vue",
            "naive-ui", "pptist", "geeker-admin", "ant-design-vue",
            "element-plus", "vant",
            // React 项目
            "pro-components", "tdesign-react", "arco-design",
            "ant-design-mobile", "ant-design", "docusaurus",
            "material-ui", "uniapp"
        )

        val extensions = setOf("vue", "tsx", "jsx", "ts", "js")
        val bugPattern = Regex("""(?:\{N\d+\}|\{\{\d+\}\})""")
        // 真正的嵌套占位符: {N{N0}} 或 {{0{{1}}}} — 占位符内部又出现占位符格式
        val nestedBug = Regex("""(?:\{N\{N\d+\}\}|\{\{\d+\{\{\d+\}\}\}\})""")
        val zeroParamBug = Regex("""\{\s*(?:N\d+|['"]\d+['"])\s*:\s*0\s*\}""")

        data class BugFile(val project: String, val path: String, val bugs: List<String>)

        val bugs = mutableListOf<BugFile>()
        var processedCount = 0
        val perProjectLimit = 15  // 每项目最多扫描 15 个中文文件
        val totalLimit = 200      // 总共最多 200 个文件

        for (project in fixtureProjects) {
            val projectDir = File(fixturesRoot, project)
            if (!projectDir.isDirectory) continue
            var projectCount = 0

            projectDir.walkTopDown().forEach { file ->
                if (file.isFile && file.extension in extensions) {
                    // 跳过 node_modules 和 dist
                    val pathStr = file.absolutePath
                    if (pathStr.contains("node_modules") || pathStr.contains("/dist/")) return@forEach

                    val content = file.readText()
                    // 只处理含中文的文件（可能被提取）
                    if (!Regex("""[\u4e00-\u9fff]""").containsMatchIn(content)) return@forEach

                    // 每项目上限
                    if (projectCount >= perProjectLimit) return@forEach
                    // 总上限
                    if (processedCount >= totalLimit) return@forEach

                    val psiFile = loadExternalFile(project, file.relativeTo(projectDir).path)
                        ?: return@forEach

                    try {
                        val result = processFile(psiFile)
                        processedCount++
                        projectCount++

                        // 检查 bug 模式
                        val foundBugs = mutableListOf<String>()
                        if (nestedBug.containsMatchIn(result)) {
                            foundBugs.add("嵌套占位符: ${nestedBug.findAll(result).map { it.value }.distinct().take(3).toList()}")
                        }
                        if (zeroParamBug.containsMatchIn(result)) {
                            foundBugs.add("参数值为字面量 0: ${zeroParamBug.findAll(result).map { it.value }.distinct().take(3).toList()}")
                        }
                        // 检查 $t() 调用是否语法正确（括号匹配）
                        val tCalls = Regex("""${'$'}t\([^)]*\)""").findAll(result).toList()
                        val badTCalls = tCalls.filter { it.value.count { c -> c == '(' } != it.value.count { c -> c == ')' } }
                        if (badTCalls.isNotEmpty()) {
                            foundBugs.add("括号不匹配的 \$t() 调用: ${badTCalls.map { it.value.take(50) }.distinct().take(3).toList()}")
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

    // ───────────────────────────────────────────────
    // 暴力全量扫描：无任何截断，跑所有含中文文件
    // 暴露插件在真实项目中可能遇到的所有问题
    // 用法: gradle test --tests "*testBruteForceScanAll*"
    // 或: gradle batchE2ETest (已在 build.gradle.kts 配置)
    // ───────────────────────────────────────────────

    fun testBruteForceScanAll() {
        val fixturesRoot = File(fixturesDir)
        val fixtureProjects = listOf(
            "vue-vben-admin", "vue-pure-admin", "arco-design-vue",
            "naive-ui", "pptist", "geeker-admin", "ant-design-vue",
            "element-plus", "vant", "pro-components", "tdesign-react",
            "arco-design", "ant-design-mobile", "ant-design", "docusaurus",
            "material-ui", "uniapp"
        )

        val extensions = setOf("vue", "tsx", "jsx", "ts", "js")

        // 扩展 bug 检测模式
        val nestedBug = Regex("""(?:\{N\{N\d+\}\}|\{\{\d+\{\{\d+\}\}\}\})""")
        val bracketMismatch = Regex("""${'$'}t\([^)]*""")  // 开了没关
        val emptyTCall = Regex("""${'$'}t\(\s*['"`]\s*['"`]\s*\)""")  // $t('') 空 key

        // 还没被翻译但含中文（rewrites = 0）
        val hasChinese = Regex("""[\u4e00-\u9fff]""")
        val hasTCall = Regex("""${'$'}t\(""")
        val hasDollarTCall = Regex("""\bt\([^)]*['"`][\u4e00-\u9fff]""")  // 普通 t() 调用含中文

        data class BugFile(
            val project: String,
            val path: String,
            val fileLines: Int,
            val bugs: List<String>
        )

        data class CrashFile(
            val project: String,
            val path: String,
            val fileLines: Int,
            val exceptionClass: String,
            val exceptionMessage: String
        )

        val allBugs = mutableListOf<BugFile>()
        val crashes = mutableListOf<CrashFile>()
        val missedTranslations = mutableListOf<BugFile>()  // 有中文但没翻译
        var totalProcessed = 0
        var totalWithChinese = 0
        var filesWithTAfter = 0
        var emptyProjectStats = mutableMapOf<String, Int>()  // 每项目处理数

        println("\n==============================================")
        println(" 🔍 暴力全量扫描开始 — 无限制，跑所有中文文件")
        println("==============================================\n")

        for (project in fixtureProjects) {
            val projectDir = File(fixturesRoot, project)
            if (!projectDir.isDirectory) continue
            var projectCount = 0

            val allZhFiles = mutableListOf<Pair<File, Int>>()  // (file, lineCount)
            projectDir.walkTopDown().forEach { file ->
                if (file.isFile && file.extension in extensions) {
                    val pathStr = file.absolutePath
                    if (pathStr.contains("node_modules") || pathStr.contains("/dist/")) return@forEach
                    val content = file.readText()
                    if (hasChinese.containsMatchIn(content)) {
                        allZhFiles.add(file to content.lines().size)
                    }
                }
            }

            // 按行数从大到小排序（复杂文件优先）
            allZhFiles.sortByDescending { it.second }
            totalWithChinese += allZhFiles.size

            println("📂 $project: ${allZhFiles.size} 个中文文件待处理")

            for ((file, lineCount) in allZhFiles) {
                val relPath = file.relativeTo(projectDir).path
                val psiFile = loadExternalFile(project, relPath) ?: continue

                try {
                    val contentBefore = file.readText()
                    val result = processFile(psiFile)
                    totalProcessed++
                    projectCount++

                    // 检查 bug
                    val foundBugs = mutableListOf<String>()

                    if (nestedBug.containsMatchIn(result)) {
                        foundBugs.add("嵌套占位符: ${nestedBug.findAll(result).map { it.value }.distinct().take(3).toList()}")
                    }
                    if (emptyTCall.containsMatchIn(result)) {
                        foundBugs.add("空 \$t() 调用: ${emptyTCall.findAll(result).map { it.value }.distinct().take(3).toList()}")
                    }
                    val allTCalls = Regex("""${'$'}t\([^)]*\)""").findAll(result).toList()
                    val openCalls = Regex("""${'$'}t\(""").findAll(result).count()
                    if (openCalls > allTCalls.size) {
                        foundBugs.add("括号可能不匹配: 有 $openCalls 个 \$t( 但只匹配到 ${allTCalls.size} 个完整调用")
                    }

                    if (foundBugs.isNotEmpty()) {
                        allBugs.add(BugFile(project, relPath, lineCount, foundBugs))
                    }

                    if (hasChinese.containsMatchIn(contentBefore)
                        && !hasTCall.containsMatchIn(result)
                        && !hasDollarTCall.containsMatchIn(result)
                    ) {
                        val nonCommentChinese = extractNonCommentChinese(contentBefore)
                        if (nonCommentChinese.isNotBlank()) {
                            missedTranslations.add(BugFile(
                                project, relPath, lineCount,
                                listOf("有中文但处理后无 \$t() — 可能漏翻译: ${nonCommentChinese.take(80)}")
                            ))
                        }
                    }

                    if (hasTCall.containsMatchIn(result)) filesWithTAfter++

                } catch (e: Throwable) {
                    crashes.add(CrashFile(
                        project, relPath, lineCount,
                        e.javaClass.simpleName,
                        (e.message ?: "no message").take(200)
                    ))
                } finally {
                    // 保存后移除文件，LightProject 不保留已处理文件以节省内存
                    FileDocumentManager.getInstance().saveAllDocuments()
                }
            }
            emptyProjectStats[project] = projectCount
        }

        // ── 汇总输出 ──
        println("\n==============================================")
        println(" 🏁 暴力全量扫描完成")
        println("==============================================\n")
        println("📊 总统计:")
        println("   含中文文件总数: $totalWithChinese")
        println("   成功处理: $totalProcessed")
        println("   生成 \$t() 的文件: $filesWithTAfter")
        println("   发现 bug: ${allBugs.size}")
        println("   发生崩溃: ${crashes.size}")
        println("   可能漏翻译: ${missedTranslations.size}")

        println("\n📋 每项目详情:")
        emptyProjectStats.forEach { (p, c) -> println("   $p: $c 文件") }

        if (allBugs.isNotEmpty()) {
            println("\n❌ BUG 文件 (${allBugs.size}):")
            allBugs.forEach { b ->
                println("   [BUG] ${b.project}/${b.path} (${b.fileLines}行)")
                b.bugs.forEach { println("         $it") }
            }
        }

        if (crashes.isNotEmpty()) {
            println("\n💥 崩溃文件 (${crashes.size}):")
            crashes.forEach { c ->
                println("   [CRASH] ${c.project}/${c.path} (${c.fileLines}行)")
                println("           ${c.exceptionClass}: ${c.exceptionMessage}")
            }
        }

        if (missedTranslations.isNotEmpty()) {
            println("\n⚠️  可能漏翻译 (${missedTranslations.size}, 显示前 30):")
            missedTranslations.take(30).forEach { m ->
                println("   [MISS] ${m.project}/${m.path} (${m.fileLines}行)")
                m.bugs.forEach { println("         $it") }
            }
            if (missedTranslations.size > 30) {
                println("   ... 还有 ${missedTranslations.size - 30} 个未显示")
            }
        }

        // 核心断言：不应有崩溃和明显 bug
        val fatal = crashes.map { c -> Triple(c.project, c.path, c.exceptionClass) } +
                     allBugs.map { b -> Triple(b.project, b.path, b.bugs.joinToString(",")) }
        assertTrue(
            "暴力全量扫描发现 ${fatal.size} 个严重问题:\n" +
                fatal.joinToString("\n") { (p, path, detail) -> "  $p/$path: $detail" },
            fatal.isEmpty()
        )
    }

    /** 提取非注释中的中文（跳过 // / * * / / <!-- --> 注释） */
    private fun extractNonCommentChinese(content: String): String {
        val lineComment = Regex("""//[^\n]*""")
        val blockComment = Regex("""(?s)/\*.*?\*/""")
        val htmlComment = Regex("""(?s)<!--.*?-->""")
        val cleaned = content
            .replace(lineComment, "")
            .replace(blockComment, "")
            .replace(htmlComment, "")
        return Regex("""[\u4e00-\u9fff]+""").findAll(cleaned)
            .map { it.value }.distinct().joinToString("、")
    }

    // ───────────────────────────────────────────────
    // Top 100 复杂文件扫描（手工挑选）
    // 路径列表在 /tmp/top100_paths.txt（相对 test-fixtures/）
    // 每 10 个文件 GC 一次，不 save 避免 stripTrailingSpaces
    // ───────────────────────────────────────────────

    fun testTop100Complex() {
        val topPathsFile = File("/tmp/top100_paths.txt")
        assertTrue("/tmp/top100_paths.txt 不存在，先跑 python3 挑选", topPathsFile.exists())
        val relPaths = topPathsFile.readLines().filter { it.isNotBlank() }
        println("\n==============================================")
        println(" 🔬 Top 100 复杂文件扫描")
        println("==============================================")
        println("路径数: ${relPaths.size}\n")

        val fixturesRoot = File(fixturesDir)

        val nestedBug = Regex("""(?:\{N\{N\d+\}\}|\{\{\d+\{\{\d+\}\}\}\})""")
        val emptyTCall = Regex("""${'$'}t\(\s*['"`]\s*['"`]\s*\)""")
        val hasChinese = Regex("""[\u4e00-\u9fff]""")
        val hasTCall = Regex("""${'$'}t\(""")
        val hasDollarTCall = Regex("""\bt\([^)]*['"`][\u4e00-\u9fff]""")

        data class BugFile(val path: String, val lines: Int, val bugs: List<String>)
        data class CrashFile(val path: String, val lines: Int, val ex: String, val msg: String)

        val allBugs = mutableListOf<BugFile>()
        val crashes = mutableListOf<CrashFile>()
        val missed = mutableListOf<BugFile>()
        var processed = 0

        for (relPath in relPaths) {
            val file = File(fixturesRoot, relPath.removePrefix("./"))
            if (!file.exists()) {
                println("  ⚠️  跳过不存在: $relPath")
                continue
            }
            val lines = file.readText().lines().size
            processed++

            if (processed % 10 == 0) {
                println("  ... 已处理 $processed / ${relPaths.size}（GC 中）")
                System.gc()
                Thread.sleep(200)
            }

            // 解析 project + 相对路径
            val parts = relPath.removePrefix("./").split("/", limit = 2)
            val projectName = parts[0]
            val innerPath = if (parts.size > 1) parts[1] else relPath

            val psiFile = loadExternalFile(projectName, innerPath) ?: continue
            val contentBefore = file.readText()

            try {
                val processor = I18nProcessor(project, psiFile)
                processor.collect()
                com.intellij.psi.impl.source.PostprocessReformattingAspect.getInstance(project).disablePostprocessFormattingInside {
                    WriteCommandAction.runWriteCommandAction(project) {
                        processor.runWithUndo()
                    }
                }
                // 不 save，直接从 PSI 读结果（避免 stripTrailingSpaces 等副作用）
                val result = psiFile.text

                val bugs = mutableListOf<String>()
                if (nestedBug.containsMatchIn(result)) {
                    bugs.add("嵌套占位符: ${nestedBug.findAll(result).map { it.value }.distinct().take(3)}")
                }
                if (emptyTCall.containsMatchIn(result)) {
                    bugs.add("空 \$t(): ${emptyTCall.findAll(result).map { it.value }.distinct().take(3)}")
                }
                val allTCalls = Regex("""${'$'}t\([^)]*\)""").findAll(result).toList()
                val openT = Regex("""${'$'}t\(""").findAll(result).count()
                if (openT > allTCalls.size) {
                    bugs.add("括号不匹配: $openT 个 \$t( 只匹配 ${allTCalls.size} 个完整调用")
                }

                if (bugs.isNotEmpty()) allBugs.add(BugFile(relPath, lines, bugs))

                if (hasChinese.containsMatchIn(contentBefore)
                    && !hasTCall.containsMatchIn(result)
                    && !hasDollarTCall.containsMatchIn(result)
                ) {
                    val nonComment = extractNonCommentChinese(contentBefore)
                    if (nonComment.isNotBlank()) {
                        missed.add(BugFile(relPath, lines,
                            listOf("有中文但无 \$t() — 可能漏翻译: ${nonComment.take(80)}")))
                    }
                }

                // GC 会自动回收 psiFile（下一次循环会覆盖变量）
            } catch (e: Throwable) {
                crashes.add(CrashFile(relPath, lines, e.javaClass.simpleName,
                    (e.message ?: "").take(150)))
            }
        }

        println("\n==============================================")
        println(" 🏁 Top 100 扫描完成")
        println("==============================================")
        println("处理文件: $processed")
        println("❌ Bug: ${allBugs.size}")
        println("💥 崩溃: ${crashes.size}")
        println("⚠️  可能漏翻译: ${missed.size}")

        if (allBugs.isNotEmpty()) {
            println("\n--- BUG ---")
            allBugs.forEach { b ->
                println("  [BUG] ${b.path} (${b.lines}行)")
                b.bugs.forEach { println("        $it") }
            }
        }
        if (crashes.isNotEmpty()) {
            println("\n--- CRASH ---")
            crashes.forEach { c ->
                println("  [CRASH] ${c.path} (${c.lines}行)")
                println("          ${c.ex}: ${c.msg}")
            }
        }
        if (missed.isNotEmpty()) {
            println("\n--- 可能漏翻译 (前 30) ---")
            missed.take(30).forEach { m ->
                println("  [MISS] ${m.path} (${m.lines}行)")
                m.bugs.forEach { println("         $it") }
            }
            if (missed.size > 30) println("  ... 还有 ${missed.size - 30} 个")
        }

        val fatal = crashes.map { Triple(it.path, it.ex, it.msg) } +
                     allBugs.map { Triple(it.path, "BUG", it.bugs.joinToString(",")) }
        assertTrue(
            "Top 100 扫描发现 ${fatal.size} 个严重问题:\n" +
                fatal.joinToString("\n") { (p, t, d) -> "  [$t] $p: $d" },
            fatal.isEmpty()
        )
    }
}
