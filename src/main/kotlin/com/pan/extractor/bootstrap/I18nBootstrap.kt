package com.pan.extractor.bootstrap

import com.pan.extractor.project.ProjectStructure
import com.pan.extractor.locate.I18nInstanceLocator
import com.pan.extractor.editor.TsFileEditor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager

/**
 * i18n bootstrap 的实际执行器：
 * 在用户于最后确认弹框中选择「确定」后，真正落地三件事：
 *   1. 修改 package.json，添加缺的多语言依赖（React → i18next + react-i18next；Vue → vue-i18n）
 *   2. 自动创建中文语言包入口文件（src/locales/<defaultLocale>.ts），保证后续写回有目标
 *   3. 创建 i18n 初始化文件（src/i18n.ts），并自动填充中文入口 import
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
                LOG.warn("I18nBootstrap: 读取 package.json 失败", t)
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
            LOG.warn("I18nBootstrap: 创建 locales 目录失败", t)
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
                    LOG.warn("I18nBootstrap: 创建语言包入口文件 $entryFileName 失败", t)
                    entryVf = null
                }
            }
        }

        // ── 3) 初始化文件：优先 src/i18n.ts，其次项目根 i18n.ts ──
        val initVf = baseDir.findChild("i18n.ts")
        if (initVf == null) {
            val entryName = entryVf?.nameWithoutExtension ?: defaultLocale
            val content = I18nBootstrapSupport.buildInitFileContent(
                missing.framework,
                defaultLocale,
                entryName
            )
            try {
                // 与语言包入口一致：PSI 建空文件 + Document 写内容，保证可撤销
                val psiDir = PsiManager.getInstance(project).findDirectory(baseDir)
                if (psiDir != null) {
                    val vf = psiDir.createFile("i18n.ts").virtualFile
                    if (vf != null) {
                        FileDocumentManager.getInstance().getDocument(vf)?.setText(content)
                    }
                }
            } catch (t: Throwable) {
                // 创建失败不阻断主流程
                LOG.warn("I18nBootstrap: 创建初始化文件 i18n.ts 失败", t)
            }
        }
        return entryVf
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
                    LOG.warn("I18nBootstrap: 读取默认语言所用初始化文件失败", t)
                    null
                }
                if (text != null) {
                    Regex("""(?:lng|locale)\s*:\s*['"]([^'"]+)['"]""")
                        .find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
        return "zh"
    }
}