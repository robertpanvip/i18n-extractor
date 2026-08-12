package com.pan.extractor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.pan.extractor.Util.getJsonContent
import java.awt.datatransfer.StringSelection

class I18nExtractorAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    private fun isSupportedFile(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".vue") ||
                lower.endsWith(".ts") ||
                lower.endsWith(".tsx") ||
                lower.endsWith(".js") ||
                lower.endsWith(".jsx")
    }

    /**
     * Bug 2：翻译资源文件（语言包）不应被提取或注入。
     * 典型：en-US.ts、locales/zh-CN.js、messages.ja.ts、src/i18n/en.ts 等。
     */
    private fun isTranslationResource(vf: VirtualFile): Boolean =
        Util.isTranslationResourceFile(vf)

    /**
     * Bug 2 重载：PsiFile 版本（single-file 流程使用）。
     */
    private fun isTranslationResource(psiFile: PsiFile): Boolean =
        Util.isTranslationResourceFile(psiFile)

    override fun update(e: AnActionEvent) {
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        if (virtualFile == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        if (virtualFile.isDirectory) {
            e.presentation.isEnabledAndVisible = true
            return
        }

        // Bug 2: 翻译资源文件上禁用菜单
        if (isTranslationResource(virtualFile)) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        e.presentation.isEnabledAndVisible = isSupportedFile(virtualFile.name)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        if (virtualFile.isDirectory) {
            processDirectory(project, virtualFile)
        } else {
            val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
            processSingleFile(project, psiFile)
        }
    }

    private fun processSingleFile(project: com.intellij.openapi.project.Project, psiFile: PsiFile) {
        // Bug 2（保险）：即便 update() 放过了，到这里仍要拦截语言包文件
        if (isTranslationResource(psiFile)) return

        // 线程规则：PSI 读取（findFile、遍历、collect）必须包在 runReadAction 中，
        // 否则 ActionUpdateThread.BGT 或进度线程（Application pooled thread）会抛：
        //   Read access is allowed from inside read-action only
        val triple = ApplicationManager.getApplication().runReadAction<Triple<Map<String, String>, Map<String, String>, I18nProcessor>> {
            val ins = I18nProcessor(project, psiFile)
            ins.collect()
            Triple(
                HashMap<String, String>(ins.existingStrings),
                HashMap<String, String>(ins.extractedStrings),
                ins
            )
        }
        val existing = triple.first
        val extracted = triple.second
        val processor = triple.third

        val allStrings = mutableMapOf<String, String>()
        allStrings.putAll(existing)
        allStrings.putAll(extracted)

        val dialog = ExtractedStringsDialog(project, allStrings)
        if (dialog.showAndGet()) {
            // WriteCommandAction.execute() 内部会自行持有 Read+Write 锁，无需再包
            processor.execute()
            if (dialog.json !== null) {
                val content = getJsonContent(dialog.json!!)
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            }
        }
    }

    /**
     * 递归收集文件夹内所有受支持的文件
     */
    private fun collectSupportedFiles(dir: VirtualFile): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        for (child in dir.children) {
            if (child.isDirectory) {
                result.addAll(collectSupportedFiles(child))
            } else if (isSupportedFile(child.name) && !isTranslationResource(child)) {
                // Bug 2: 目录批量扫描时直接排除翻译资源文件，避免后续被 Processor 处理
                result.add(child)
            }
        }
        return result
    }

    private fun processDirectory(project: com.intellij.openapi.project.Project, dir: VirtualFile) {
        val files = collectSupportedFiles(dir)
        if (files.isEmpty()) return

        val psiManager = PsiManager.getInstance(project)
        val processors = mutableListOf<I18nProcessor>()
        val extracted = mutableMapOf<String, String>()

        // 使用进度对话框，避免文件过多时 UI 冻结
        ProgressManager.getInstance().runProcessWithProgressSynchronously({
            val indicator = ProgressManager.getInstance().progressIndicator
            indicator.text = "Extracting i18n strings..."
            indicator.isIndeterminate = false
            for ((index, file) in files.withIndex()) {
                if (indicator.isCanceled) break
                indicator.fraction = (index + 1).toDouble() / files.size
                indicator.text2 = file.name

                // 🔴 线程合规：进度线程是 Application pooled thread（非 EDT），
                //    findFile + collect() 做大量 PSI 读遍历，必须包 runReadAction，
                //    否则抛 "Read access is allowed from inside read-action only"
                ApplicationManager.getApplication().runReadAction {
                    val psiFile = psiManager.findFile(file) ?: return@runReadAction
                    val processor = I18nProcessor(project, psiFile)
                    processor.collect()
                    extracted.putAll(processor.existingStrings)
                    extracted.putAll(processor.extractedStrings)
                    processors.add(processor)
                }
            }
        }, "I18n Extraction", true, project)

        if (processors.isEmpty()) return

        val dialog = ExtractedStringsDialog(project, extracted)
        if (dialog.showAndGet()) {
            CommandProcessor.getInstance().executeCommand(
                project,
                {
                    WriteCommandAction.runWriteCommandAction(project) {
                        processors.forEach { it.run() }
                    }
                },
                "I18n Extract",
                null
            )

            if (dialog.json !== null) {
                val content = getJsonContent(dialog.json!!)
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            }
        }
    }
}
