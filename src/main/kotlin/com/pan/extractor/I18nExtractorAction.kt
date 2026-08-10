package com.pan.extractor

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.ide.CopyPasteManager
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
        val ins = I18nProcessor(project, psiFile)
        ins.collect()

        val allStrings = mutableMapOf<String, String>()
        allStrings.putAll(ins.existingStrings)
        allStrings.putAll(ins.extractedStrings)

        val dialog = ExtractedStringsDialog(project, allStrings)
        if (dialog.showAndGet()) {
            ins.execute()
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
            } else if (isSupportedFile(child.name)) {
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

        for (file in files) {
            val psiFile = psiManager.findFile(file) ?: continue
            val processor = I18nProcessor(project, psiFile)
            processor.collect()
            extracted.putAll(processor.existingStrings)
            extracted.putAll(processor.extractedStrings)
            processors.add(processor)
        }

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
