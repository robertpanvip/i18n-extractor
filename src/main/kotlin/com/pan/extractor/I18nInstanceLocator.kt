package com.pan.extractor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.relativeToOrNull

/**
 * i18n 实例文件定位（Vue createI18n / React i18n.init 初始化文件、import 路径推断、导出方式判定）。
 * 从 [EntryFileLocator] 拆分而来，行为不变；与"翻译资源入口文件定位"职责分离。
 *
 * findRelativeFile / walkVirtualFile 仍由 [ProjectStructure] 提供（通用 VirtualFile 遍历工具）。
 */
object I18nInstanceLocator {

    private val TS_JS_EXTS = setOf("ts", "tsx", "js", "jsx")

    /**
     * 在 Vue 项目中查找调用了 `createI18n(` 的文件（通常是 @/locales/index.ts 之类）。
     *
     * 查找顺序：
     * 1. 优先在项目根下的常见目录查找（src/locales, locales, src/i18n, i18n），
     *    只在这些目录下做文件内文本匹配，避免遍历 whole repo 太慢。
     * 2. 如果这些目录都没有命中（或都不存在），再在项目根做 walk 扫描（限制深度 4）。
     *
     * 注意：使用 IntelliJ VirtualFile API 遍历（而不是 java.io.File），
     *       这样既能在真实项目中工作，也能在内存测试 Fixture 中工作。
     *
     * @return 命中的文件（VirtualFile），未找到返回 null
     */
    fun findVueI18nInstanceFile(currentPsiFile: PsiFile): VirtualFile? {
        val projectRoot = ProjectStructure.findProjectRoot(currentPsiFile) ?: return null
        return findVueI18nInstanceFileInRoot(projectRoot)
    }

    /** [findVueI18nInstanceFile] 的 root 版本：给定项目根，查找调用了 createI18n( 的文件。 */
    fun findVueI18nInstanceFileInRoot(projectRoot: VirtualFile): VirtualFile? {
        val commonDirs = listOf(
            "src/locales",
            "locales",
            "src/i18n",
            "i18n",
            "src/locale",
            "locale"
        )

        // 阶段 1：常见目录内精确匹配 .ts/.tsx/.js/.jsx 文件（最大深度 2）
        for (relPath in commonDirs) {
            val dir = ProjectStructure.findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = ProjectStructure.walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    if (vfContainsCreateI18n(vf)) vf else null
                } else null
            }
            if (result != null) return result
        }

        // 阶段 2：常见目录未命中，在项目根做 walk（最大深度 4，排除 node_modules）
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                if (vfContainsCreateI18n(vf)) vf else null
            } else null
        }
    }

    /**
     * 读取 VirtualFile 内容并检测是否包含 createI18n( 调用。
     */
    private fun vfContainsCreateI18n(vf: VirtualFile): Boolean {
        val text = try {
            String(vf.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        return text.contains("createI18n(") || text.contains("createI18n (")
    }

    /** 判断文本是否是一个 i18n 初始化文件（Vue 的 createI18n 或 React 的 i18n/i18next.init）。 */
    private fun isI18nInitText(text: String): Boolean {
        if (text.contains("createI18n(") || text.contains("createI18n (")) return true              // Vue
        if (text.contains("initReactI18next")) return true                                          // React (react-i18next)
        return Regex("""\b(?:i18n|i18next)\s*\.\s*init\s*\(""").containsMatchIn(text)                 // React / CJS
    }

    /** 给定项目根，查找初始化了 i18n 的文件（createI18n 或 i18n/i18next.init），Vue 与 React 通用。 */
    fun findI18nInitFileInRoot(projectRoot: VirtualFile): VirtualFile? {
        val commonDirs = listOf(
            "src/locales", "locales", "src/i18n", "i18n",
            "src/locale", "locale", "src/lang", "lang"
        )
        for (relPath in commonDirs) {
            val dir = ProjectStructure.findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = ProjectStructure.walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                    if (isI18nInitText(t)) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                if (isI18nInitText(t)) vf else null
            } else null
        }
    }

    /**
     * React 专用：查找"导出了 i18n 实例"的初始化文件。
     *
     * 与 [findI18nInitFileInRoot] 的区别：只匹配 React 初始化文件（initReactI18next /
     * i18n.init），且文件必须导出了 i18n（`export default i18n` / `export const i18n` /
     * `export { i18n }`）。这样避免混合项目里命中 Vue 的 createI18n 文件，也满足
     * "如果 locale 初始化导出了 i18n 才用它"的语义——未导出 i18n 的初始化文件视为不可用。
     */
    fun findReactI18nInstanceFileInRoot(projectRoot: VirtualFile): VirtualFile? {
        val commonDirs = listOf(
            "src/locales", "locales", "src/i18n", "i18n",
            "src/locale", "locale", "src/lang", "lang"
        )
        for (relPath in commonDirs) {
            val dir = ProjectStructure.findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = ProjectStructure.walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                    if (isReactI18nInitWithExport(t)) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                if (isReactI18nInitWithExport(t)) vf else null
            } else null
        }
    }

    /** 判断文本是否是一个"React 初始化且导出了 i18n"的文件。 */
    private fun isReactI18nInitWithExport(text: String): Boolean {
        val isReactInit = text.contains("initReactI18next") ||
            Regex("""\b(?:i18n|i18next)\s*\.\s*init\s*\(""").containsMatchIn(text)
        if (!isReactInit) return false
        return Regex("""export\s+(const|let|var)\s+i18n\b""").containsMatchIn(text) ||
            Regex("""export\s*\{[^}]*\bi18n\b[^}]*\}""").containsMatchIn(text) ||
            Regex("""export\s+default\s+i18n\b""").containsMatchIn(text)
    }

    /**
     * 构造从当前文件 [currentPsiFile] 导入 Vue i18n 实例文件 [i18nVFile] 的路径。
     *
     * 优先级：
     * 1. 如果 i18n 实例文件在项目根的 `src/` 下，且当前文件也在 `src/` 下，使用 `@/xxx` 别名。
     *    此时会检查是否是目录 index 文件，从而省略 `/index` 后缀。
     * 2. 否则使用相对路径（以 `./` 或 `../` 开头）。
     *
     * 返回值为不含引号的路径字符串，例如 `"@/locales"` 或 `"./locales/index"`。
     * 返回 null 代表无法推断路径（fallback 由调用方处理）。
     */
    fun resolveVueI18nImportPath(currentPsiFile: PsiFile, i18nVFile: VirtualFile): String? {
        val projectRoot = ProjectStructure.findProjectRoot(currentPsiFile) ?: return null
        val rootPath = File(projectRoot.path).toPath()
        val i18nPath = File(i18nVFile.path).toPath()
        val currentPath = currentPsiFile.virtualFile?.let { File(it.path).toPath() } ?: return null

        val srcDir = rootPath.resolve("src")

        // 1) 别名路径：两个文件都在 src/ 下
        if (i18nPath.startsWith(srcDir) && currentPath.startsWith(srcDir)) {
            val i18nRel = i18nPath.relativeToOrNull(srcDir)?.toString()?.replace("\\", "/")
                ?: return null
            val noExt = stripTsJsExtension(i18nRel)
            val clean = if (noExt.endsWith("/index")) noExt.removeSuffix("/index") else noExt
            return "@/$clean"
        }

        // 2) 相对路径
        val currentDir = currentPath.parent ?: return null
        val relative = i18nPath.relativeToOrNull(currentDir)?.toString()?.replace("\\", "/")
            ?: return null
        val noExt = stripTsJsExtension(relative)
        val clean = if (noExt.endsWith("/index")) noExt.removeSuffix("/index") else noExt
        return if (!clean.startsWith(".")) "./$clean" else clean
    }

    /**
     * 检测 createI18n 文件中的导出方式：
     * - 命名导出：`export const i18n = createI18n(...)` / `export { i18n }`
     * - 默认导出：`export default i18n` / `export default createI18n(...)`
     *
     * 默认认为是命名导出（与用户习惯一致），仅当文件文本中存在默认导出而无命名导出时才返回 true。
     */
    fun isVueI18nDefaultExport(i18nVFile: VirtualFile): Boolean {
        val content = try {
            String(i18nVFile.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val hasNamedExport =
            content.contains(Regex("export\\s+(const|let|var)\\s+i18n\\b")) ||
                content.contains(Regex("export\\s*\\{[^}]*\\bi18n\\b[^}]*\\}"))
        val hasDefaultExport =
            content.contains(Regex("export\\s+default\\s+i18n\\b")) ||
                content.contains(Regex("export\\s+default\\s+createI18n\\s*\\("))
        return hasDefaultExport && !hasNamedExport
    }

    private fun stripTsJsExtension(path: String): String {
        val lc = path.lowercase()
        return when {
            lc.endsWith(".tsx") -> path.substring(0, path.length - 4)
            lc.endsWith(".ts") -> path.substring(0, path.length - 3)
            lc.endsWith(".jsx") -> path.substring(0, path.length - 4)
            lc.endsWith(".js") -> path.substring(0, path.length - 3)
            else -> path
        }
    }
}
