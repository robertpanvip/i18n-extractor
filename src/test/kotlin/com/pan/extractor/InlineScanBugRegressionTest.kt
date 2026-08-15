package com.pan.extractor

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File

/**
 * 把走查时"猜测"的文件扫描类 bug 固化为用例。
 *
 * 注意：findFilesByIncludePatterns / findTsConfigFile 走的是 `project.basePath`
 * 的真实磁盘目录（File.walkTopDown / findFileByRelativePath），而 myFixture 的
 * addFileToProject 不会把文件落到 basePath 磁盘上，因此这里直接写磁盘 + 刷新 VFS。
 *
 * 覆盖：
 *   1. tsconfig 存在但没有 include 数组时，resolveScanFiles 返回空列表（未回退到全项目扫描）；
 *   2. findFilesByIncludePatterns 遍历整个项目目录，不排除 node_modules，会把
 *      node_modules 里的文件也当成可翻译源文件返回。
 */
class InlineScanBugRegressionTest : BasePlatformTestCase() {

    /** 把文件写到 basePath 磁盘上并刷新 VFS，供 findFilesByIncludePatterns / findTsConfigFile 读取。 */
    private fun addFileOnDisk(relativePath: String, text: String) {
        val base = File(project.basePath ?: error("project.basePath 为空"))
        val f = File(base, relativePath)
        f.parentFile.mkdirs()
        f.writeText(text)
        LocalFileSystem.getInstance().refresh(false)
    }

    /**
     * 基线：include 带具体模式（匹配 src 下所有 .ts）时，能按模式在磁盘上找到 src 下的文件。
     * 直接走 Util.findFilesByIncludePatterns，避免依赖 findTsConfigFile 的
     * getBaseDirectories() 与 FilenameIndex 索引在测试夹具里的不确定性。
     */
    fun testIncludePatternWorks() {
        addFileOnDisk("src/App.ts", "export const a = '你好';")

        val files = Util.findFilesByIncludePatterns(project, listOf("src/**/*.ts"))
        assertTrue(
            "应按 include 模式找到 src/App.ts，实际: ${files.map { it.path }}",
            files.any { it.path.endsWith("src/App.ts") }
        )
    }

    /**
     * Bug 3：tsconfig 存在但没有 include 数组时，parseTsConfigInclude 返回空列表，
     * 随后 findFilesByIncludePatterns(emptyList) 匹配不到任何文件，返回空——
     * 没有回退到"全项目扫描"。项目里明明有 src/App.ts 却扫不到。
     */
    fun testNoIncludeFallbackShouldStillFindFiles() {
        addFileOnDisk("tsconfig.json", """{ "compilerOptions": {} }""")
        addFileOnDisk("src/App.ts", "export const a = '你好';")

        // 1) 无 include 时解析结果确实为空
        val tsConfigVf = LocalFileSystem.getInstance()
            .findFileByIoFile(File(project.basePath ?: error("basePath 为空"), "tsconfig.json"))
            ?: error("tsconfig.json 未写入磁盘")
        val includes = AllI18nExtractorAction().parseTsConfigInclude(tsConfigVf)
        assertTrue("无 include 时 parseTsConfigInclude 应为空，实际: $includes", includes.isEmpty())

        // 2) 空 include 模式 → findFilesByIncludePatterns 返回空（应回退到全项目扫描而非空）
        val files = Util.findFilesByIncludePatterns(project, includes)
        assertTrue(
            "include 为空时不应返回空（应回退到全项目扫描 src/App.ts）而非空，实际: ${files.map { it.path }}",
            files.any { it.path.endsWith("src/App.ts") }
        )
    }

    /**
     * Bug 4：findFilesByIncludePatterns 用 File.walkTopDown() 全目录遍历，
     * 不排除 node_modules。宽模式（如匹配所有 .ts）下会把 node_modules 里的 .ts 也返回。
     */
    fun testNodeModulesShouldBeExcluded() {
        addFileOnDisk("src/App.ts", "export const a = '你好';")
        addFileOnDisk("node_modules/foo.ts", "export const b = '内部';")

        val files = Util.findFilesByIncludePatterns(project, listOf("**/*.ts"))
        val hitPaths = files.filter { it.path.contains("node_modules") }.map { it.path }
        assertFalse(
            "不应把 node_modules 下的文件当作翻译源，实际命中: $hitPaths",
            hitPaths.isNotEmpty()
        )
    }

    /**
     * Bug A8：目录型 include（tsconfig include 里常写裸目录名，如 "src"）应匹配该目录下
     * 所有文件。当前 globToRegex("src") 只生成 ^src$，匹配不到 src/App.ts。
     */
    fun testDirectoryIncludeShouldMatchFilesUnderIt() {
        addFileOnDisk("src/App.ts", "export const a = '你好';")
        addFileOnDisk("src/components/Card.ts", "export const b = '卡片';")

        val files = Util.findFilesByIncludePatterns(project, listOf("src"))
        assertTrue(
            "裸目录 src 应匹配其下所有文件，实际: ${files.map { it.path }}",
            files.any { it.path.endsWith("src/App.ts") } && files.any { it.path.endsWith("src/components/Card.ts") }
        )
    }

    /**
     * Bug A7：glob 根目录写法（tsconfig 里可能是 "/src" 加双星、或 "./src" 加双星）
     * 应等价于相对项目根的 "src" 加双星。当前带前导 / 或 ./ 的模式匹配不到相对路径。
     */
    fun testRootPrefixedGlobShouldMatch() {
        addFileOnDisk("src/App.ts", "export const a = '你好';")

        val files = Util.findFilesByIncludePatterns(project, listOf("/src/**/*.ts"))
        assertTrue(
            "带前导 / 的 glob 应匹配 src 下文件，实际: ${files.map { it.path }}",
            files.any { it.path.endsWith("src/App.ts") }
        )
    }
}