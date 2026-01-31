package com.pan.extractor

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import javax.swing.*
import com.google.gson.Gson

class ExtractedStringsDialog(
    project: Project,
    private val extracted: Map<String, String>
) : DialogWrapper(project) {

    init {
        title = "提取的中文字符串"
        init()
    }

    override fun createCenterPanel(): JComponent? {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        val area = JTextArea(Gson().toJson(extracted, Map::class.java))
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        panel.add(JScrollPane(area))
        panel.preferredSize = java.awt.Dimension(400, 300)
        return panel
    }
}
