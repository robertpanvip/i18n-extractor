package com.pan.extractor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction

class VueI18nExtractorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val extracted = mutableMapOf<String, String>()

        WriteCommandAction.runWriteCommandAction(project) {
            val ins = VueI18nProcessor(project,psiFile)
            ins.processFile();
            extracted.putAll(ins.extractedStrings)
        }

        // 弹出模态框显示 JSON
        ExtractedStringsDialog(project, extracted).show()
    }
}