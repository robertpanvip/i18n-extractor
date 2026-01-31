package com.pan.extractor

import com.google.gson.GsonBuilder
import com.intellij.json.JsonFileType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.JComponent

class ExtractedStringsDialog(
    private val project: Project,                          // 必須傳入 project
    private val extracted: Map<String, String>
) : DialogWrapper(project) {                   // 正確傳給 super

    init {
        title = "提取的中文字符串"
        isModal = true                             // 可選：設為 modal
        init()                                     // 必須呼叫
    }

    private var editor: Editor? = null   // 用成員變數保存

    override fun createCenterPanel(): JComponent {
        // 美化 JSON
        val prettyJson = GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(extracted)

        // 創建 document
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(prettyJson)
        // 創建 editor，使用 JsonFileType
        val editor = editorFactory.createEditor(
            document,
            project,                  // 這裡傳 project，必須非 null
            JsonFileType.INSTANCE,    // 正確的 FileType
            false                     // false = 可編輯（但我們設為唯讀）
        ) as EditorEx

        // 設定唯讀 + 美觀
        editor.isViewer = true                    // 設為唯讀模式
        editor.settings.isLineNumbersShown = true
        editor.settings.isUseSoftWraps = true
        editor.component.preferredSize = java.awt.Dimension(800, 600)
        this.editor = editor;
        return editor.component
    }

    // 可選：如果想在關閉時額外清理
    override fun dispose() {
        super.dispose()
        if (this.editor !== null) {
            EditorFactory.getInstance().releaseEditor(this.editor!!)
        }
    }
}