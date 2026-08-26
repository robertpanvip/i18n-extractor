package com.pan.extractor.bootstrap

import com.pan.extractor.log.PluginLogBuffer
import com.pan.extractor.core.RegexCatalog
import com.pan.extractor.project.ProjectStructure
import com.pan.extractor.locate.I18nInstanceLocator
import com.pan.extractor.editor.TsFileEditor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.PsiElement

/**
 * i18n bootstrap 的实际执行器：
 * 在用户于最后确认弹框中选择「确定」后，真正落地三件事：
 *   1. 修改 package.json，添加缺的多语言依赖（React → i18next + react-i18next；Vue → vue-i18n）
 *   2. 自动创建中文语言包入口文件（src/locales/<defaultLocale>.ts），保证后续写回有目标
 *   3. 创建 i18n 初始化文件（src/locales/i18n.ts），并自动填充中文入口 import
 *
 * 必须在 WriteCommandAction + EDT 内调用（会写 VFS）。
 * 新建文件使用 PSI 创建（[com.intellij.psi]），使文件与内容进入 undo 栈，Ctrl+Z 可整体回滚，而非
 * 直接 VFS [VirtualFile.setBinaryContent] 绕过撤销。
 * 返回本次创建/定位到的中文语言包入口文件；无需创建时返回 null。
 */
object I18nBootstrap {

    private val LOG = Logger.getInstance(I18nBootstrap::class.java)

    /**
     * 若 [missing] 命中，则执行 bootstrap。
     * 幂等：依赖已存在 / 初始化文件已存在 / 入口文件已存在时跳过对应写入。
     */
    fun maybeApply(
        project: Project,
        psiFile: PsiFile,
        missing: I18nBootstrapSupport.MissingBootstrap,
    ): VirtualFile? {
        val root = ProjectStructure.findProjectRoot(psiFile) ?: return null
        // ── 1) package.json 补依赖 ──
        val pkg = root.findChild("package.json")
        if (pkg != null) {
            val text = try {
                String(pkg.contentsToByteArray(), Charsets.UTF_8)
            } catch (t: Throwable) {
                PluginLogBuffer.warn(LOG,"I18nBootstrap: 读取 package.json 失败", t)
                null
            }
            if (text != null) {
                val updated = I18nBootstrapSupport.addDepsToPackageJson(text, missing.depsToAdd)
                if (updated != text) {
                    TsFileEditor.writeVirtualFileText(pkg, updated)
                }
            }
        }

        // ── 2) 确保中文语言包入口文件存在（src/locales/<defaultLocale>.ts）──
        val srcDir = root.findChild("src")
        val baseDir = srcDir ?: root
        val defaultLocale = defaultLocaleOf(psiFile)
        val entryFileName = "$defaultLocale.ts"
        var entryVf: VirtualFile? = null
        val localesDir = try {
            VfsUtil.createDirectoryIfMissing(baseDir, "locales")
        } catch (t: Throwable) {
            PluginLogBuffer.warn(LOG,"I18nBootstrap: 创建 locales 目录失败", t)
            null
        }
        if (localesDir != null) {
            entryVf = localesDir.findChild(entryFileName)
            if (entryVf == null) {
                try {
                    // P0：先经 PSI 建空文件，再用 Document 写入内容（须处于同一 WriteCommandAction 内）。
                    //     创建与内容都进入 undo 栈，Ctrl+Z 可整体回滚；避免 setBinaryContent 写字节绕过撤销。
                    val psiDir = PsiManager.getInstance(project).findDirectory(localesDir)
                    if (psiDir != null) {
                        val vf = psiDir.createFile(entryFileName).virtualFile
                        if (vf != null) {
                            FileDocumentManager.getInstance().getDocument(vf)?.setText(
                                I18nBootstrapSupport.buildLocaleEntryFileContent()
                            )
                            entryVf = vf
                        }
                    }
                } catch (t: Throwable) {
                    PluginLogBuffer.warn(LOG,"I18nBootstrap: 创建语言包入口文件 $entryFileName 失败", t)
                    entryVf = null
                }
            }
        }

        // ── 3) 初始化文件：与语言包同放 locales 目录（src/locales/i18n.ts）──
        val initVf = localesDir?.findChild("i18n.ts")
        if (initVf == null) {
            val entryName = entryVf?.nameWithoutExtension ?: defaultLocale
            val content = I18nBootstrapSupport.buildInitFileContent(
                missing.framework,
                defaultLocale,
                entryName
            )
            try {
                // 与语言包入口一致：PSI 建空文件 + Document 写内容，保证可撤销
                val psiDir = if (localesDir != null) {
                    PsiManager.getInstance(project).findDirectory(localesDir)
                } else {
                    PsiManager.getInstance(project).findDirectory(baseDir)
                }
                if (psiDir != null) {
                    val vf = psiDir.createFile("i18n.ts").virtualFile
                    if (vf != null) {
                        FileDocumentManager.getInstance().getDocument(vf)?.setText(content)
                    }
                }
            } catch (t: Throwable) {
                // 创建失败不阻断主流程
                PluginLogBuffer.warn(LOG,"I18nBootstrap: 创建初始化文件 i18n.ts 失败", t)
            }
        }
        // ── 4) 在项目入口文件（index.tsx / main.tsx / App.tsx）首行添加 import "@/locales/i18n" ──
        if (initVf == null && localesDir != null) {
            // 只在本次新建了初始化文件时才注入入口 import（避免重复）
            val entryImport = "import \"@/locales/i18n\";\n"
            val entryFile = findEntryFile(root)
            if (entryFile != null && !hasEntryI18nImport(entryFile, entryImport)) {
                try {
                    val psiEntry = PsiManager.getInstance(project).findFile(entryFile)
                    if (psiEntry != null) {
                        injectImportAtTop(psiEntry, entryImport)
                    }
                } catch (t: Throwable) {
                    PluginLogBuffer.warn(LOG, "I18nBootstrap: 注入入口 import 失败", t)
                }
            }
        }
        return entryVf
    }

    /**
     * 在项目根下查找 React/Vue 入口文件（main.tsx / index.tsx / App.tsx / main.ts / index.ts）。
     * 优先 src/ 目录，再回退根目录。
     */
    private fun findEntryFile(root: VirtualFile): VirtualFile? {
        val srcDir = root.findChild("src") ?: root
        val candidates = listOf("main.tsx", "index.tsx", "App.tsx", "main.ts", "index.ts", "app.tsx", "app.ts")
        for (name in candidates) {
            srcDir.findChild(name)?.let { return it }
        }
        // 回退根目录
        for (name in candidates) {
            root.findChild(name)?.let { return it }
        }
        return null
    }

    /** 检查入口文件是否已包含 i18n 初始化文件的 import。 */
    private fun hasEntryI18nImport(entryVf: VirtualFile, importText: String): Boolean {
        return try {
            val text = String(entryVf.contentsToByteArray(), Charsets.UTF_8)
            text.contains("@/locales/i18n") || text.contains("./locales/i18n") || text.contains("../locales/i18n")
        } catch (t: Throwable) {
            false
        }
    }

    /** 在 PsiFile 顶部注入 import 语句。 */
    private fun injectImportAtTop(psiFile: PsiFile, importText: String) {
        val imports = PsiTreeUtil.findChildrenOfType(psiFile, ES6ImportDeclaration::class.java)
        val stmt = com.pan.extractor.project.I18nPsiTools.createJSStatementFromText(importText, psiFile)
        if (imports.isNotEmpty()) {
            val firstImport = imports.first()
            firstImport.parent.addBefore(stmt, firstImport)
        } else {
            var firstChild = psiFile.firstChild
            while (firstChild != null && firstChild is PsiWhiteSpace) {
                firstChild = firstChild.nextSibling
            }
            if (firstChild != null) {
                psiFile.addBefore(stmt, firstChild)
            } else {
                psiFile.add(stmt)
            }
        }
    }

    /** 默认语言：优先取当前文件所在中文入口的 locale，否则回退 zh。 */
    private fun defaultLocaleOf(psiFile: PsiFile): String {
        val root = ProjectStructure.findProjectRoot(psiFile)
        if (root != null) {
            // P0：把 project 传入 Locator 以启用 PSI 级复核，避免字符串/注释里的 createI18n( 误判
            val init = I18nInstanceLocator.findI18nInitFileInRoot(root, psiFile.project)
            if (init != null) {
                val text = try {
                    String(init.contentsToByteArray(), Charsets.UTF_8)
                } catch (t: Throwable) {
                    PluginLogBuffer.warn(LOG,"I18nBootstrap: 读取默认语言所用初始化文件失败", t)
                    null
                }
                if (text != null) {
                    RegexCatalog.LANGUAGE_CODE
                        .find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return "zh"
    }
}