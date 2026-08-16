package com.pan.extractor

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * i18n bootstrap 的实际执行器：
 * 在用户于最后确认弹框中选择「确定」后，真正落地两件事：
 *   1. 修改 package.json，添加缺的多语言依赖（React → i18next + react-i18next；Vue → vue-i18n）
 *   2. 创建 i18n 初始化文件（src/i18n.ts），并自动填充中文入口 import
 *
 * 必须在 WriteCommandAction 内调用（会写 VFS）。
 */
object I18nBootstrap {

    /**
     * 若 [missing] 命中，则执行 bootstrap。
     * 幂等：依赖已存在 / 初始化文件已存在时跳过对应写入。
     */
    fun maybeApply(
        project: Project,
        psiFile: PsiFile,
        missing: I18nBootstrapSupport.MissingBootstrap,
    ) {
        val root = ProjectStructure.findProjectRoot(psiFile) ?: return
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

        // 初始化文件：优先 src/i18n.ts，其次项目根 i18n.ts
        val srcDir = root.findChild("src")
        val targetDir = srcDir ?: root
        val initVf = targetDir.findChild("i18n.ts")
        if (initVf == null) {
            val defaultLocale = defaultLocaleOf(psiFile)
            val entryName = chineseEntryName(project, psiFile)
            val content = I18nBootstrapSupport.buildInitFileContent(
                missing.framework,
                defaultLocale,
                entryName
            )
            try {
                val file = targetDir.createChildData(this, "i18n.ts")
                file.setBinaryContent(content.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                // 创建失败不阻断主流程
            }
        }
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

    /** 中文入口文件名（不含扩展名），用于初始化文件 import；找不到返回 null。 */
    private fun chineseEntryName(project: Project, psiFile: PsiFile): String? {
        val entry = try {
            Util.findChineseLocaleEntryFile(project, psiFile)
        } catch (_: Throwable) {
            null
        } ?: return null
        val name = entry.nameWithoutExtension
        return name.takeIf { it.isNotBlank() }
    }
}