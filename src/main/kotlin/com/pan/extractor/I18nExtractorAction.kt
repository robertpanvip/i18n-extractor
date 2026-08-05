package com.pan.extractor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.pan.extractor.Util.getJsonContent
import java.awt.datatransfer.StringSelection

class I18nExtractorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val ins = I18nProcessor(project, psiFile)
        ins.collect();

        // 合并已有 key + 新提取 key，已有在前，新提取覆盖同名
        val allStrings = mutableMapOf<String, String>()
        allStrings.putAll(ins.existingStrings)
        allStrings.putAll(ins.extractedStrings)

        // 弹出模态框显示 JSON
        val dialog = ExtractedStringsDialog(project, allStrings);
        if (dialog.showAndGet()) {
            ins.execute();
            if (dialog.json !== null) {
                val content = getJsonContent(dialog.json!!)
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            }
        }
    }
}