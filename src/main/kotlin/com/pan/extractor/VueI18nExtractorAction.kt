package com.pan.extractor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import java.awt.datatransfer.StringSelection

class VueI18nExtractorAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val extracted = mutableMapOf<String, String>()
        val ins = VueI18nProcessor(project, psiFile)
        ins.collect();
        extracted.putAll(ins.extractedStrings)

        // 弹出模态框显示 JSON
        val dialog = ExtractedStringsDialog(project, extracted);
        if (dialog.showAndGet()) {
            ins.execute();
            if (dialog.json !== null) {
                val content = getJsonContent(dialog.json!!)
                CopyPasteManager.getInstance().setContents(StringSelection(content))
            }
        }
    }

    fun getJsonContent(json: String): String {
        val content = json
            .trim()
            .removePrefix("{")
            .removeSuffix("}")
            .trim()
        return content
    }
}