package com.pan.extractor

import com.google.gson.GsonBuilder
import com.intellij.json.JsonFileType
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import javax.swing.table.DefaultTableModel

class ExtractedStringsDialog(
    private val project: Project,                          // 必須傳入 project
    private val extracted: Map<String, String>,
    private val affixGroups: List<AffixGroupCandidate> = emptyList(),
    private val digitGroups: List<DigitGroupCandidate> = emptyList(),
) : DialogWrapper(project) {                   // 正確傳給 super

    /** 用户确认 Dialog 后对外暴露的合并执行计划（勾选项） */
    data class MergePlan(
        val selectedAffix: List<AffixGroupCandidate>,
        val selectedDigit: List<DigitGroupCandidate>,
    )

    private var editor: Editor? = null
    var json: String? = null
    var mergePlan: MergePlan = MergePlan(emptyList(), emptyList())
        private set

    // Swing 默认列：Boolean / String / String / String / String / Int / String
    private class AffixModel(rows: Vector<Vector<Any?>>, cols: Vector<String>) : DefaultTableModel(rows, cols) {
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            5 -> java.lang.Integer::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = column in listOf(0, 2)
    }

    private class DigitModel(rows: Vector<Vector<Any?>>, cols: Vector<String>) : DefaultTableModel(rows, cols) {
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> java.lang.Boolean::class.java
            4 -> java.lang.Integer::class.java
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = column in listOf(0, 2)
    }

    private lateinit var affixTable: JTable
    private lateinit var digitTable: JTable
    private lateinit var affixModel: AffixModel
    private lateinit var digitModel: DigitModel

    init {
        title = "提取的中文字符串"
        isModal = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val prettyJson = GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(extracted)
        json = prettyJson
        val editorFactory = EditorFactory.getInstance()
        val document = editorFactory.createDocument(prettyJson)
        val editor = editorFactory.createEditor(
            document,
            project,
            JsonFileType.INSTANCE,
            false
        ) as EditorEx
        editor.isViewer = true
        editor.settings.isLineNumbersShown = true
        editor.settings.isUseSoftWraps = true
        editor.component.preferredSize = Dimension(900, 620)
        this.editor = editor

        val tabs = JTabbedPane()
        tabs.addTab("翻译 JSON 预览", editor.component)
        tabs.addTab("合并建议（公共前后缀 + 汉字+数字抽取）", buildMergeTab())

        return tabs
    }

    override fun doOKAction() {
        // 把勾选项 & 用户编辑后的骨架 key 写回每个 group 对象（mutable）+ 合并到 mergePlan
        val pickedAffix = mutableListOf<AffixGroupCandidate>()
        for (row in 0 until affixModel.rowCount) {
            val checked = affixModel.getValueAt(row, 0) as? Boolean == true
            val group = affixGroups[row]
            val editedKey = affixModel.getValueAt(row, 2).toString().trim()
            if (editedKey.isNotBlank()) group.skeletonKey = editedKey
            if (checked) pickedAffix += group
        }
        val pickedDigit = mutableListOf<DigitGroupCandidate>()
        for (row in 0 until digitModel.rowCount) {
            val checked = digitModel.getValueAt(row, 0) as? Boolean == true
            val group = digitGroups[row]
            val editedKey = digitModel.getValueAt(row, 2).toString().trim()
            if (editedKey.isNotBlank()) group.skeletonKey = editedKey
            if (checked) pickedDigit += group
        }
        mergePlan = MergePlan(pickedAffix, pickedDigit)
        super.doOKAction()
    }

    private fun buildMergeTab(): JComponent {
        val root = JPanel(BorderLayout(0, 12))
        root.preferredSize = Dimension(980, 680)

        // 1) 公共前后缀表
        val affixCols = Vector<String>().apply {
            add("选")
            add("骨架值")
            add("骨架key (可编辑)")
            add("差异段 (前5)")
            add("示例句子 (前3)")
            add("命中数")
        }
        val affixRows = Vector<Vector<Any?>>()
        for (g in affixGroups) {
            val row = Vector<Any?>()
            row += g.selected
            row += g.skeleton
            row += g.skeletonKey
            row += g.variants.take(5).joinToString("  |  ") { "\"${it.diff}\" ×${it.sites.size}" }
            row += g.variants.flatMap { it.sites.map { s -> s.originalMessage } }.distinct().take(3).joinToString("   ")
            row += g.siteCount
            affixRows += row
        }
        affixModel = AffixModel(affixRows, affixCols)
        affixTable = JTable(affixModel).apply {
            autoCreateRowSorter = true
            columnModel.getColumn(0).preferredWidth = 40
            columnModel.getColumn(1).preferredWidth = 280
            columnModel.getColumn(2).preferredWidth = 260
            columnModel.getColumn(3).preferredWidth = 220
            columnModel.getColumn(4).preferredWidth = 280
            columnModel.getColumn(5).preferredWidth = 60
        }
        val affixPane = JScrollPane(affixTable).apply {
            border = BorderFactory.createTitledBorder("公共前后缀合并（骨架中间 {N0} 留差）；≥2 字自动出候选")
            preferredSize = Dimension(960, 320)
        }

        // 2) 汉字+数字抽取表
        val digitCols = Vector<String>().apply {
            add("选")
            add("骨架值（{N0} = 数字占位）")
            add("骨架key (可编辑)")
            add("数字示例 (前5)")
            add("命中数")
        }
        val digitRows = Vector<Vector<Any?>>()
        for (g in digitGroups) {
            val row = Vector<Any?>()
            row += g.selected
            row += g.skeleton
            row += g.skeletonKey
            row += g.perSites.take(5).joinToString(" | ") { it.digitValues.firstOrNull().orEmpty() }
            row += g.siteCount
            digitRows += row
        }
        digitModel = DigitModel(digitRows, digitCols)
        digitTable = JTable(digitModel).apply {
            autoCreateRowSorter = true
            columnModel.getColumn(0).preferredWidth = 40
            columnModel.getColumn(1).preferredWidth = 380
            columnModel.getColumn(2).preferredWidth = 280
            columnModel.getColumn(3).preferredWidth = 220
            columnModel.getColumn(4).preferredWidth = 60
        }
        val digitPane = JScrollPane(digitTable).apply {
            border = BorderFactory.createTitledBorder("汉字 + 数字 抽取（特别处理：原句中数字抽成 {N0}，差异是数字字面量）")
            preferredSize = Dimension(960, 220)
        }

        val hint = JLabel("<html>提示：① 表中每行默认勾选，可去掉不想要的合并；②『骨架key』可编辑，对应翻译资源 JSON 里的键；③ OK 后，被选中的 site 会重写成 <code>$t('骨架{N0}', { N0: $t('差异') })</code> 形式。</html>")

        root.add(hint, BorderLayout.NORTH)
        root.add(affixPane, BorderLayout.CENTER)
        root.add(digitPane, BorderLayout.SOUTH)
        return root
    }

    override fun dispose() {
        super.dispose()
        this.editor?.let { EditorFactory.getInstance().releaseEditor(it) }
    }
}
