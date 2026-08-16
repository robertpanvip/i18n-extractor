package com.pan.extractor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/**
 * i18n bootstrap 的实际执行器：
 * 在用户于最后确认弹框中选择「确定」后，真正落地三件事：
 *   1. 修改 package.json，添加缺的多语言依赖（React → i18next + react-i18next；Vue → vue-i18n）
 *   2. 自动创建中文语言包入口文件（src/locales/<defaultLocale>.ts），保证后续写回有目标
 *   3. 创建 i18n 初始化文件（src/i18n.ts），并自动填充中文入口 import
 *
 * 必须在 WriteCommandAction 内调用（会写 VFS）。
 * 返回本次创建/定位到的中文语言包入口文件；无需创建时返回 null。
 */
object I18nBootstrap {

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
            } catch (_: Throwable) {
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
        } catch (_: Throwable) {
            null
        }
        if (localesDir != null) {
            entryVf = localesDir.findChild(entryFileName)
            if (entryVf == null) {
                try {
                    entryVf = localesDir.createChildData(this, entryFileName)
                    entryVf?.setBinaryContent(
                        I18nBootstrapSupport.buildLocaleEntryFileContent().toByteArray(Charsets.UTF_8)
                    )
                } catch (_: Throwable) {
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
                val file = baseDir.createChildData(this, "i18n.ts")
                file.setBinaryContent(content.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                // 创建失败不阻断主流程
            }
        }
        return entryVf
    }

    /** 默认语言：优先取当前文件所在中文入口的 locale，否则回退 zh。 */
    private fun defaultLocaleOf(psiFile: PsiFile): String {
        val root = ProjectStructure.findProjectRoot(psiFile)
        if (root != null) {
            val init = ProjectStructure.findI18nInitFileInRoot(root)
            if (init != null) {
                val text = try {
                    String(init.contentsToByteArray(), Charsets.UTF_8)
                } catch (_: Throwable) {
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