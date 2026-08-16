package com.pan.extractor

import com.google.gson.GsonBuilder
import com.intellij.json.JsonFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.*
import javax.swing.*
import javax.swing.table.DefaultTableModel

class ExtractedStringsDialog(
    private val project: Project,                          // 必須傳入 project
    private val extracted: Map<String, String>,
    private val affixGroups: List<AffixGroupCandidate> = emptyList(),
    private val digitGroups: List<DigitGroupCandidate> = emptyList(),
    /** 上下文 PSI 文件，用于推断项目根查找中文入口文件 */
    private val contextPsiFile: com.intellij.psi.PsiFile? = null,
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

    /** OK 后对外暴露：用户选择的输出方式（只使用 CLIPBOARD / FILE 两值） */
    var outputMode: OutputDestination = OutputDestination.CLIPBOARD
        private set
    /** OK 后对外暴露：用户选择的中文入口文件（若选择了覆盖） */
    var selectedEntryFile: VirtualFile? = null
        private set

    /** OK 后对外暴露：本次是否自动做了 i18n bootstrap（补依赖 + 建初始化文件） */
    var bootstrapPerformed: Boolean = false
        private set

    /** bootstrap 自动创建的中文语言包入口文件（若创建了则写回时默认使用它，避免「请选择入口文件」） */
    private var bootstrapEntryFile: VirtualFile? = null

    // UI 控件
    private lateinit var radioClipboard: JRadioButton
    private lateinit var radioOverwrite: JRadioButton
    private lateinit var entryPathField: JTextField
    private lateinit var btnPickEntry: JButton
    private lateinit var lblEntryStatus: JLabel

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
        editor.component.preferredSize = Dimension(900, 560)
        this.editor = editor

        val tabs = JTabbedPane()
        tabs.addTab("翻译 JSON 预览", editor.component)
        tabs.addTab("合并建议（公共前后缀 + 汉字+数字抽取）", buildMergeTab())

        // 外层：tabs 在 NORTH/CENTER，配置面板在 SOUTH（仅当输出去向为「每次询问」时展示）
        val root = JPanel(BorderLayout(0, 10))
        root.preferredSize = Dimension(1000, 760)
        root.add(tabs, BorderLayout.CENTER)
        initOutputByDestination(root)

        return root
    }

    /**
     * 根据全局输出去向决定是否展示输出面板：
     *  - [OutputDestination.ASK]：展示输出面板（默认，向后兼容，面板里记住上次选择）。
     *  - [OutputDestination.CLIPBOARD]：明确剪贴板 → 不展示面板，直接采用剪贴板输出。
     *  - [OutputDestination.FILE]：明确写文件 → 不展示面板，自动探测入口文件后写回。
     */
    private fun initOutputByDestination(root: JPanel) {
        when (I18nSettings.getInstance().outputDestination()) {
            OutputDestination.ASK -> {
                root.add(buildOutputConfigPanel(), BorderLayout.SOUTH)
                initConfigControls()
            }
            OutputDestination.CLIPBOARD -> {
                outputMode = OutputDestination.CLIPBOARD
            }
            OutputDestination.FILE -> {
                outputMode = OutputDestination.FILE
                selectedEntryFile = try {
                    Util.findChineseLocaleEntryFile(project, contextPsiFile)
                } catch (_: Throwable) { null }
            }
        }
    }

    /** 构造输出方式配置面板（底部）。 */
    private fun buildOutputConfigPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder("输出方式（偏好会记住到项目级别）")

        val gbc0 = GridBagConstraints().apply {
            gridx = 0; gridy = 0; anchor = GridBagConstraints.WEST
            insets = Insets(4, 8, 4, 8)
        }
        val gbc1 = GridBagConstraints().apply {
            gridx = 1; gridy = 0; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0; insets = Insets(4, 0, 4, 4)
        }
        val gbc2 = GridBagConstraints().apply {
            gridx = 2; gridy = 0; anchor = GridBagConstraints.WEST
            insets = Insets(4, 0, 4, 8)
        }
        val gbcStatus = GridBagConstraints().apply {
            gridx = 0; gridy = 1; gridwidth = 3; anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = Insets(0, 8, 6, 8)
        }

        // 行 0 左：单选按钮组
        val radioPanel = JPanel()
        radioPanel.layout = BoxLayout(radioPanel, BoxLayout.Y_AXIS)
        val group = ButtonGroup()
        radioClipboard = JRadioButton("拷贝到剪贴板（默认：复制 JSON key-value，需手动贴到语言包里）")
        radioOverwrite = JRadioButton("覆盖入口中文多语言文件（自动把新 key 合并写回 .ts/.json 入口）")
        group.add(radioClipboard); group.add(radioOverwrite)
        radioPanel.add(radioClipboard)
        radioPanel.add(radioOverwrite)
        radioClipboard.addActionListener { syncEntryControlsEnabled() }
        radioOverwrite.addActionListener { syncEntryControlsEnabled() }
        panel.add(radioPanel, gbc0)

        // 行 0 中：入口文件路径文本框
        entryPathField = JTextField()
        entryPathField.toolTipText = "中文语言包入口文件（zh-CN.ts / zh_CN.json / messages.zhs.ts 等）"
        panel.add(entryPathField, gbc1)

        // 行 0 右：选择文件按钮
        btnPickEntry = JButton("选择…")
        btnPickEntry.addActionListener { onPickEntryFile() }
        panel.add(btnPickEntry, gbc2)

        // 行 1：状态/提示 Label
        lblEntryStatus = JLabel()
        lblEntryStatus.putClientProperty("html.disable", null)
        panel.add(lblEntryStatus, gbcStatus)

        return panel
    }

    /** 初始化：读取用户偏好 + 自动探测中文入口文件。 */
    private fun initConfigControls() {
        // 1) 输出方式
        val savedMode = Util.getDialogOutputMode(project)
        radioClipboard.isSelected = savedMode == OutputDestination.CLIPBOARD
        radioOverwrite.isSelected = savedMode == OutputDestination.FILE

        // 2) 入口文件：先读持久化路径，其次自动探测
        val storedPath = Util.getStoredEntryPath(project)
        var candidate: VirtualFile? = storedPath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        if (candidate == null || !candidate.isValid) {
            candidate = try {
                Util.findChineseLocaleEntryFile(project, contextPsiFile)
            } catch (_: Throwable) { null }
        }
        if (candidate != null) {
            entryPathField.text = candidate.path
            lblEntryStatus.text = "<html><span style=\"color:#2e7d32\">✓ 已定位中文入口：${candidate.name}（${candidate.extension?.uppercase()}）</span></html>"
        } else {
            lblEntryStatus.text = "<html><span style=\"color:#c62828\">未自动找到中文入口文件，请点击「选择…」手动指定 zh-CN.ts / zh_CN.json</span></html>"
            if (radioOverwrite.isSelected) {
                // 没找到入口 → 自动退回 clipboard，避免用户误选覆盖
                radioClipboard.isSelected = true
            }
        }

        syncEntryControlsEnabled()
    }

    private fun syncEntryControlsEnabled() {
        val enable = radioOverwrite.isSelected
        entryPathField.isEnabled = enable
        btnPickEntry.isEnabled = enable
    }

    /** 用户手动选入口文件。 */
    private fun onPickEntryFile() {
        val descriptor = FileChooserDescriptor(
            true,   /* chooseFiles */
            false,  /* chooseFolders */
            false,  /* chooseJars */
            false,  /* chooseJarsAsFiles */
            false,  /* chooseLibraryContents */
            false   /* forSaving */
        ).withFileFilter { vf ->
            val ext = vf.extension?.lowercase()
            ext in setOf("ts", "tsx", "js", "jsx", "json")
        }
        descriptor.title = "选择中文语言包入口文件（zh-CN.ts / zh_CN.json 等）"
        descriptor.description = "只接受 .ts / .tsx / .js / .jsx / .json 后缀"

        val initialFile = entryPathField.text?.takeIf { it.isNotBlank() }
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?: Util.findChineseLocaleEntryFile(project, contextPsiFile)

        val chosen = FileChooser.chooseFile(descriptor, project, initialFile)
        if (chosen != null) {
            entryPathField.text = chosen.path
            val ext = chosen.extension?.lowercase()
            lblEntryStatus.text = if (ext in setOf("ts", "tsx", "js", "jsx")) {
                "<html><span style=\"color:#1565c0\">✓ 选中 TS/JS 入口：${chosen.name}（将解析 export default / export const 对象字面量，表达式部分原样保留）</span></html>"
            } else if (ext == "json") {
                "<html><span style=\"color:#2e7d32\">✓ 选中 JSON 入口：${chosen.name}（直接合并 JSON 写回）</span></html>"
            } else {
                "<html><span style=\"color:#c62828\">⚠ 文件后缀 ${chosen.extension} 不支持，仅支持 .ts/.tsx/.js/.jsx/.json</span></html>"
            }
        }
    }

    /**
     * 最后确认阶段的 i18n 引导检测 + 确认弹框 + 实际执行。
     *
     * 当项目（React/Vue）既没安装多语言依赖、又没初始化 i18n 时，弹确认框提示用户
     * 插件将自动在 package.json 添加依赖并创建初始化文件。用户选「确定」则执行，
     * 选「取消」则中止本次提取（返回 false）。
     *
     * @return true 表示可以继续后续提取；false 表示用户取消，中止。
     */
    private fun confirmAndDoBootstrap(): Boolean {
        val psiFile = contextPsiFile ?: return true
        val missing = try {
            Util.detectMissingI18nBootstrap(psiFile)
        } catch (_: Throwable) {
            null
        } ?: return true

        val frameworkLabel = when (missing.framework) {
            I18nBootstrapSupport.Framework.REACT -> "React"
            I18nBootstrapSupport.Framework.VUE -> "Vue"
        }
        val message = buildString {
            append("检测到 $frameworkLabel 项目尚未安装多语言依赖，且未初始化 i18n。\n\n")
            append("插件将自动为你：\n")
            append("  • 在 package.json 添加依赖：${missing.dependencyLabel}\n")
            append("  • 自动创建 i18n 初始化文件（src/i18n.ts）\n\n")
            append("是否继续执行提取并自动配置？")
        }
        val choice = JOptionPane.showConfirmDialog(
            this.contentPanel,
            message,
            "自动配置 i18n",
            JOptionPane.OK_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        if (choice != JOptionPane.OK_OPTION) return false

        // 用户确认：执行 bootstrap（写 package.json + 建中文入口文件 + 建初始化文件）
        var createdEntry: com.intellij.openapi.vfs.VirtualFile? = null
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                createdEntry = I18nBootstrap.maybeApply(project, psiFile, missing)
            }
        } catch (_: Throwable) {
            bootstrapPerformed = false
            return true
        }
        bootstrapPerformed = true
        val entry = createdEntry
        bootstrapEntryFile = entry
        // bootstrap 自动创建了中文语言包入口文件 → 直接作为本次写回目标，
        // 避免随后「请选择中文语言包入口文件」报错（全新项目本就没有该文件）
        if (entry != null) {
            selectedEntryFile = entry
            if (::entryPathField.isInitialized) {
                entryPathField.text = entry.path
            }
        }
        return true
    }

    override fun doOKAction() {
        val destination = I18nSettings.getInstance().outputDestination()

        // ── ⓪ 最后确认阶段：检测缺 i18n 依赖且未初始化 → 弹框确认并自动配置 ──
        //    用户取消则中止本次提取（不写任何文件）。
        if (!confirmAndDoBootstrap()) {
            return
        }

        // ── ① 确定输出模式 + 入口文件 ──
        val wantOverwrite: Boolean
        val entryFile: VirtualFile?
        if (destination == OutputDestination.ASK) {
            // 弹窗展示了输出面板：读取用户在面板里的选择
            wantOverwrite = radioOverwrite.isSelected
            entryFile = if (wantOverwrite) {
                var pathText = entryPathField.text?.trim().orEmpty()
                // bootstrap 刚自动创建了入口文件 → 用户未手填时用它兜底
                if (pathText.isEmpty() && bootstrapEntryFile != null) {
                    pathText = bootstrapEntryFile!!.path
                    entryPathField.text = pathText
                }
                if (pathText.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this.contentPanel,
                        "请选择中文语言包入口文件（zh-CN.ts / zh_CN.json 等），或切换为「拷贝到剪贴板」模式。",
                        "入口文件未指定",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                val f = LocalFileSystem.getInstance().findFileByPath(pathText)
                if (f == null || !f.isValid || f.isDirectory) {
                    JOptionPane.showMessageDialog(
                        this.contentPanel,
                        "入口文件不存在或无效：$pathText\n请重新选择，或切换为「拷贝到剪贴板」模式。",
                        "入口文件无效",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                val ext = f.extension?.lowercase()
                if (ext !in setOf("ts", "tsx", "js", "jsx", "json")) {
                    JOptionPane.showMessageDialog(
                        this.contentPanel,
                        "入口文件后缀 ${f.extension} 不支持，仅支持 .ts/.tsx/.js/.jsx/.json。",
                        "入口文件类型不支持",
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                f
            } else null
        } else {
            // 设置里已明确输出去向：不展示面板，直接按设置执行
            wantOverwrite = destination == OutputDestination.FILE
            entryFile = if (wantOverwrite) selectedEntryFile else null
            if (wantOverwrite && entryFile == null) {
                JOptionPane.showMessageDialog(
                    this.contentPanel,
                    "设置为「写入文件」但未自动定位到入口多语言文件。\n请先到 Settings → I18n Extractor 切回「每次询问」手动选择，或确认项目里存在 <locale>.ts/.json 入口文件。",
                    "未找到入口文件",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
        }

        // ── ② 写回勾选项 + 合并计划 ──
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

        // ── ③ 持久化用户偏好 & 暴露结果 ──
        val mode = if (wantOverwrite) OutputDestination.FILE else OutputDestination.CLIPBOARD
        Util.setDialogOutputMode(project, mode)
        outputMode = mode
        selectedEntryFile = entryFile
        if (entryFile != null) {
            Util.persistEntryPathIfNeeded(project, entryFile)
        }

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

        val hint = JLabel(
            "<html>" +
            "提示：① 表中每行默认勾选，可去掉不想要的合并；" +
            "② 『骨架key』可编辑，对应翻译资源 JSON 里的键；" +
            "③ OK 后，被选中的 site 会重写成 <code>\$t('骨架{N0}', { N0: \$t('差异') })</code> 形式。" +
            "</html>"
        )

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
