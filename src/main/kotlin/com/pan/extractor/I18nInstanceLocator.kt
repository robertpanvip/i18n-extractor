package com.pan.extractor

import com.pan.extractor.ui.*

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
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
        return findVueI18nInstanceFileInRoot(projectRoot, currentPsiFile.project)
    }

    /** [findVueI18nInstanceFile] 的 root 版本：给定项目根，查找调用了 createI18n( 的文件。 */
    fun findVueI18nInstanceFileInRoot(projectRoot: VirtualFile, project: Project? = null): VirtualFile? {
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
                    if (hasRealCreateI18nCall(vf, project)) vf else null
                } else null
            }
            if (result != null) return result
        }

        // 阶段 2：常见目录未命中，在项目根做 walk（最大深度 4，排除 node_modules）
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                if (hasRealCreateI18nCall(vf, project)) vf else null
            } else null
        }
    }

    /**
     * 去掉 JS/TS 行注释（//）与块注释（/* ... */），避免把注释里的
     * createI18n / i18n.init / initReactI18next 等字样误判为真实初始化调用
     * （BUG_ANALYSIS 3.3 的文本搜索误判问题）。纯文本变换，不影响可执行代码。
     *
     * 先剥块注释再剥行注释，保证 /* ... // ... */ 这类块注释里夹行注释的结构也能正确剥离；
     * 未处理字符串字面量里的字样（如 `const t = "createI18n()"`），留待后续 PSI 化步骤。
     */
    internal fun stripJsComments(text: String): String {
        val noBlock = BLOCK_COMMENT_RE.replace(text, " ")
        return LINE_COMMENT_RE.replace(noBlock, " ")
    }

    private val BLOCK_COMMENT_RE = Regex("""/\*[\s\S]*?\*/""")
    private val LINE_COMMENT_RE = Regex("""//[^\n]*""")

    // ── PSI 级初始化调用检测（BUG_ANALYSIS 3.3 第二步） ──────────────

    /**
     * 遍历 [psiFile] 的 PSI 树，检测是否包含真实的 i18n 初始化调用：
     * - Vue: `createI18n(...)` — callee 名为 `createI18n`
     * - React: `i18n.init(...)` / `i18n.use(initReactI18next)` — callee 为 `.init` 或 `.use`
     * - React: `initReactI18next` 引用出现（import 或调用）
     * - Solid: `useI18n(...)` 或 `createAppI18n(...)` 调用
     *
     * 与 `isI18nInitText` 的文本匹配不同，PSI 遍历只在**可执行节点**（JSCallExpression /
     * JSReferenceExpression）上判断，天然排除注释和字符串字面量中的字样，
     * 消除 `const s = "createI18n()"` 这类文本级误判。
     *
     * @return 是否检测到真实的初始化调用
     */
    internal fun containsI18nInitCall(psiFile: PsiFile): Boolean {
        var found = false
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (found) return  // 短路：已找到，不再深入
                // JSCallExpression: createI18n() / i18n.init() / i18n.use() / useI18n() / createAppI18n()
                if (element is JSCallExpression) {
                    val method = element.methodExpression
                    if (method is JSReferenceExpression) {
                        val refName = method.referenceName
                        // Vue: createI18n(...)
                        if (refName == "createI18n") { found = true; return }
                        // React: i18n.init(...) / i18n.use(...)
                        if (refName == "init" || refName == "use") {
                            // 确认 qualifier 含 i18n/i18next（排除 foo.init() 误命中）
                            val qualText = method.qualifier?.text
                            if (qualText != null && (qualText.contains("i18n") || qualText.contains("i18next"))) {
                                found = true; return
                            }
                        }
                        // Solid: useI18n(...) / createAppI18n(...)
                        if (refName == "useI18n" || refName == "createAppI18n") { found = true; return }
                    }
                }
                // JSReferenceExpression: initReactI18next 引用（import 或参数传递）
                if (element is JSReferenceExpression && element.referenceName == "initReactI18next") {
                    found = true; return
                }
                super.visitElement(element)
            }
        })
        return found
    }

    /**
     * 检测 [psiFile] 是否为"React 初始化且导出了 i18n"的文件（PSI 版本）。
     * 与 [containsI18nInitCall] 配合，在确认有 React 初始化调用的基础上，
     * 检查文件是否包含 `export const i18n` / `export { i18n }` / `export default i18n`。
     */
    internal fun containsReactI18nInitWithExport(psiFile: PsiFile): Boolean {
        // 先确认含 React 初始化调用
        var hasReactInit = false
        psiFile.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: com.intellij.psi.PsiElement) {
                if (hasReactInit) return
                if (element is JSCallExpression) {
                    val method = element.methodExpression
                    if (method is JSReferenceExpression) {
                        val refName = method.referenceName
                        if (refName == "init" || refName == "use") {
                            val qualText = method.qualifier?.text
                            if (qualText != null && (qualText.contains("i18n") || qualText.contains("i18next"))) {
                                hasReactInit = true; return
                            }
                        }
                    }
                }
                if (element is JSReferenceExpression && element.referenceName == "initReactI18next") {
                    hasReactInit = true; return
                }
                super.visitElement(element)
            }
        })
        if (!hasReactInit) return false
        // 检查导出语句（文本级，因为 export 解析不需要 PSI 精确度）
        val code = stripJsComments(psiFile.text)
        return Regex("""export\s+(const|let|var)\s+i18n\b""").containsMatchIn(code) ||
            Regex("""export\s*\{[^}]*\bi18n\b[^}]*\}""").containsMatchIn(code) ||
            Regex("""export\s+default\s+i18n\b""").containsMatchIn(code)
    }

    /**
     * 读取 VirtualFile 内容，先做文本级预筛（含注释剥离）提取 createI18n( 字样；
     * 命中后若 [project] 可用，再用 [containsI18nInitCall] 做 PSI 级确认，
     * 排除字符串字面量 / 注释里的 createI18n( 字样误判（BUG_ANALYSIS 3.3 / 5.3）。
     * [project] 为 null 时退回文本级结果（无 PSI 可用）。
     */
    private fun hasRealCreateI18nCall(vf: VirtualFile, project: Project?): Boolean {
        val text = try {
            String(vf.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val code = stripJsComments(text)
        if (!code.contains("createI18n(") && !code.contains("createI18n (")) return false
        if (project == null) return true
        val psi = PsiManager.getInstance(project).findFile(vf) ?: return true
        return containsI18nInitCall(psi)
    }

    /**
     * 通用 PSI 级确认（BUG_ANALYSIS 5.3）：[textPassed] 为文本级检测结果。
     * - 文本级未命中 → false；
     * - 命中且无 [project]（无法获得 PSI）→ 退回文本级结果 true；
     * - 命中且有 [project] → 加载 PSI 用 [containsI18nInitCall] 复核，排除字符串字面量 /
     *   注释里的 `createI18n(` / `i18n.init(` / `initReactI18next` / `useI18n(` 等字样误判。
     */
    private fun confirmI18nInitViaPsi(project: Project?, vf: VirtualFile, textPassed: Boolean): Boolean {
        if (!textPassed) return false
        if (project == null) return true
        val psi = PsiManager.getInstance(project).findFile(vf) ?: return true
        return containsI18nInitCall(psi)
    }

    /** 判断文本是否是一个 i18n 初始化文件（Vue createI18n / React i18n.init / Solid useI18n 顶层调用），忽略注释中的字样。 */
    internal fun isI18nInitText(text: String): Boolean {
        val code = stripJsComments(text)
        if (code.contains("createI18n(") || code.contains("createI18n (")) return true             // Vue
        if (code.contains("initReactI18next")) return true                                          // React (react-i18next)
        if (Regex("""\b(?:i18n|i18next)\s*\.\s*init\s*\(""").containsMatchIn(code)) return true    // React / CJS
        // Solid: 顶层 useI18n( 调用 + 导出 createAppI18n / useI18n 的工厂函数（@solid-primitives/i18n）
        if (code.contains("useI18n(") &&
            (code.contains("createAppI18n") || Regex("""export\s+(const|function)\s+\w*[Ii]18n\w*""").containsMatchIn(code))
        ) return true
        return false
    }

    /** 给定项目根，查找初始化了 i18n 的文件（createI18n 或 i18n/i18next.init），Vue 与 React 通用。 */
    fun findI18nInitFileInRoot(projectRoot: VirtualFile, project: Project? = null): VirtualFile? {
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
                    if (confirmI18nInitViaPsi(project, vf, isI18nInitText(t))) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                if (confirmI18nInitViaPsi(project, vf, isI18nInitText(t))) vf else null
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
    fun findReactI18nInstanceFileInRoot(projectRoot: VirtualFile, project: Project? = null): VirtualFile? {
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
                    if (confirmI18nInitViaPsi(project, vf, isReactI18nInitWithExport(t))) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                if (confirmI18nInitViaPsi(project, vf, isReactI18nInitWithExport(t))) vf else null
            } else null
        }
    }

    /** 判断文本是否是一个"React 初始化且导出了 i18n"的文件（忽略注释；导出的判定不做注释剥离，因为 export 必在可执行层）。 */
    private fun isReactI18nInitWithExport(text: String): Boolean {
        val code = stripJsComments(text)
        val isReactInit = code.contains("initReactI18next") ||
            Regex("""\b(?:i18n|i18next)\s*\.\s*init\s*\(""").containsMatchIn(code)
        if (!isReactInit) return false
        return Regex("""export\s+(const|let|var)\s+i18n\b""").containsMatchIn(text) ||
            Regex("""export\s*\{[^}]*\bi18n\b[^}]*\}""").containsMatchIn(text) ||
            Regex("""export\s+default\s+i18n\b""").containsMatchIn(text)
    }

    /**
     * Solid 专用：查找 `@solid-primitives/i18n` 的 useI18n 工厂文件（通常导出 createAppI18n）。
     *
     * 与 [findReactI18nInstanceFileInRoot] 对称：只匹配包含 `useI18n(` 调用且导出了
     * i18n 工厂（`createAppI18n` / `export const useI18n` / `export function ...I18n`）的文件，
     * 避免误命中 Vue 的 createI18n 或 React 的 i18n.init。
     */
    fun findSolidI18nInstanceFileInRoot(projectRoot: VirtualFile, project: Project? = null): VirtualFile? {
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
                    if (confirmI18nInitViaPsi(project, vf, isSolidI18nInitWithExport(t))) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                if (confirmI18nInitViaPsi(project, vf, isSolidI18nInitWithExport(t))) vf else null
            } else null
        }
    }

    /** 判断文本是否是一个 "Solid @solid-primitives/i18n 初始化且导出了工厂" 的文件（忽略注释中的字样；import/export 判定保留原文文本）。 */
    private fun isSolidI18nInitWithExport(text: String): Boolean {
        val code = stripJsComments(text)
        // 必须 import 自 @solid-primitives/i18n，且包含 useI18n( 调用
        if (!code.contains("@solid-primitives/i18n")) return false
        if (!code.contains("useI18n(")) return false
        // 必须导出 i18n 工厂：createAppI18n / 含 I18n 的命名导出 / 默认导出
        return code.contains("createAppI18n") ||
            Regex("""export\s+(const|let|var)\s+\w*[Ii]18n\w*""").containsMatchIn(code) ||
            Regex("""export\s+function\s+\w*[Ii]18n\w*""").containsMatchIn(code) ||
            Regex("""export\s*\{[^}]*\b\w*[Ii]18n\w*[^}]*\}""").containsMatchIn(code) ||
            Regex("""export\s+default\s+\w*[Ii]18n\w*""").containsMatchIn(code)
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
        val code = stripJsComments(content)
        val hasNamedExport =
            code.contains(Regex("export\\s+(const|let|var)\\s+i18n\\b")) ||
                code.contains(Regex("export\\s*\\{[^}]*\\bi18n\\b[^}]*\\}"))
        val hasDefaultExport =
            code.contains(Regex("export\\s+default\\s+i18n\\b")) ||
                code.contains(Regex("export\\s+default\\s+createI18n\\s*\\("))
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
