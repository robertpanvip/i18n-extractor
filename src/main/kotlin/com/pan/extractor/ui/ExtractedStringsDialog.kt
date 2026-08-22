package com.pan.extractor.ui

import com.pan.extractor.project.Util
import com.pan.extractor.project.ProjectStructure
import com.pan.extractor.strategy.VueI18nStrategy
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.merge.AffixGroupCandidate
import com.pan.extractor.merge.DigitGroupCandidate
import com.pan.extractor.locate.EntryFileLocator
import com.pan.extractor.bootstrap.I18nBootstrap
import com.pan.extractor.bootstrap.I18nBootstrapSupport
import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.*
import com.pan.extractor.messages.I18nExtractorBundle
import com.pan.extractor.analyzer.*

import com.google.gson.GsonBuilder
import com.intellij.json.JsonFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
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

    private val LOG = Logger.getInstance(ExtractedStringsDialog::class.java)

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

    /** 检测到的“缺 i18n 依赖且未初始化”状态（null 表示不需要引导） */
    private var bootstrapMissing: I18nBootstrapSupport.MissingBootstrap? = null

    /** 输出面板里的“自动初始化 i18n”勾选框（仅当项目缺 i18n 初始化时展示） */
    private var chkI18nBootstrap: JCheckBox? = null

    // UI 控件
    private lateinit var radioClipboard: JRadioButton
    private lateinit var radioOverwrite: JRadioButton
    private lateinit var entryPathField: JTextField
    private lateinit var btnPickEntry: JButton
    private lateinit var lblEntryStatus: JLabel

    // Swing 默认列：Boolean / String / String / String / String / Int / String
    private class AffixModel(rows: Vector<Vector<Any?>>, cols: Vector<String>) : DefaultTableModel(rows, cols) {
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            // 必须用 javaObjectType（java.lang.Boolean 包装类），否则 Kotlin 的 Boolean::class.java
            // 返回原始类型 boolean，Swing 找不到对应的勾选框渲染器/编辑器，会退化成文本 true/false
            0 -> Boolean::class.javaObjectType
            5 -> Int::class.javaObjectType
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = column in listOf(0, 2)
    }

    private class DigitModel(rows: Vector<Vector<Any?>>, cols: Vector<String>) : DefaultTableModel(rows, cols) {
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0 -> Boolean::class.javaObjectType
            4 -> Int::class.javaObjectType
            else -> String::class.java
        }

        override fun isCellEditable(row: Int, column: Int): Boolean = column in listOf(0, 2)
    }

    private lateinit var affixTable: JTable
    private lateinit var digitTable: JTable
    private lateinit var affixModel: AffixModel
    private lateinit var digitModel: DigitModel

    init {
        title = I18nExtractorBundle.message("extracted.strings.dialog.title")
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
        tabs.addTab(I18nExtractorBundle.message("tab.json.preview"), editor.component)
        tabs.addTab(I18nExtractorBundle.message("tab.merge.suggestions"), buildMergeTab())

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
        // 提前检测项目是否缺 i18n 依赖且未初始化（决定是否展示“自动初始化 i18n”勾选框）
        detectBootstrapState()
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
                    EntryFileLocator.findChineseLocaleEntryFile(project, contextPsiFile)
                } catch (e: Throwable) {
                    LOG.warn("ExtractedStringsDialog: 定位中文入口文件失败，回退为空", e)
                    null
                }
            }
        }
    }

    /** 检测项目是否“缺 i18n 依赖且未初始化”，结果存入 [bootstrapMissing]。 */
    private fun detectBootstrapState() {
        val psiFile = contextPsiFile ?: return
        bootstrapMissing = try {
            ProjectStructure.detectMissingI18nBootstrap(psiFile)
        } catch (e: Throwable) {
            LOG.warn("ExtractedStringsDialog: 检测 i18n 引导缺失状态失败，回退为 null", e)
            null
        }
    }

    /** 构造输出方式配置面板（底部）。 */
    private fun buildOutputConfigPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = BorderFactory.createTitledBorder(I18nExtractorBundle.message("output.panel.title"))

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
        val gbcBootstrap = GridBagConstraints().apply {
            gridx = 0; gridy = 2; gridwidth = 3; anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL; weightx = 1.0
            insets = Insets(0, 8, 6, 8)
        }

        // 行 0 左：单选按钮组
        val radioPanel = JPanel()
        radioPanel.layout = BoxLayout(radioPanel, BoxLayout.Y_AXIS)
        val group = ButtonGroup()
        radioClipboard = JRadioButton(I18nExtractorBundle.message("radio.clipboard"))
        radioOverwrite = JRadioButton(I18nExtractorBundle.message("radio.overwrite"))
        group.add(radioClipboard); group.add(radioOverwrite)
        radioPanel.add(radioClipboard)
        radioPanel.add(radioOverwrite)
        radioClipboard.addActionListener { syncEntryControlsEnabled() }
        radioOverwrite.addActionListener { syncEntryControlsEnabled() }
        panel.add(radioPanel, gbc0)

        // 行 0 中：入口文件路径文本框
        entryPathField = JTextField()
        entryPathField.toolTipText = I18nExtractorBundle.message("entry.file.tooltip")
        panel.add(entryPathField, gbc1)

        // 行 0 右：选择文件按钮
        btnPickEntry = JButton(I18nExtractorBundle.message("button.pick.entry"))
        btnPickEntry.addActionListener { onPickEntryFile() }
        panel.add(btnPickEntry, gbc2)

        // 行 1：状态/提示 Label
        lblEntryStatus = JLabel()
        lblEntryStatus.putClientProperty("html.disable", null)
        panel.add(lblEntryStatus, gbcStatus)

        // 行 2：项目缺 i18n 初始化时，展示“自动初始化 i18n”勾选框由用户选择
        val missing = bootstrapMissing
        if (missing != null) {
            val frameworkLabel = when (missing.framework.id) {
                ReactI18nextStrategy.id -> "React（i18next + react-i18next）"
                VueI18nStrategy.id -> "Vue（vue-i18n）"
                else -> missing.framework.id
            }
            val chk = JCheckBox(
                I18nExtractorBundle.message("bootstrap.checkbox.header", frameworkLabel) +
                    (if (missing.depsToAdd.isNotEmpty())
                        I18nExtractorBundle.message("bootstrap.checkbox.deps", missing.dependencyLabel)
                    else "") +
                    I18nExtractorBundle.message("bootstrap.checkbox.create.files")
            )
            chk.isSelected = true
            chkI18nBootstrap = chk
            panel.add(chk, gbcBootstrap)
        }

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
                EntryFileLocator.findChineseLocaleEntryFile(project, contextPsiFile)
            } catch (e: Throwable) {
                LOG.warn("ExtractedStringsDialog: 重新定位中文入口文件失败，回退为空", e)
                null
            }
        }
        if (candidate != null) {
            entryPathField.text = candidate.path
            lblEntryStatus.text = I18nExtractorBundle.message("status.auto.found", candidate.name, candidate.extension?.uppercase().orEmpty())
        } else {
            lblEntryStatus.text = I18nExtractorBundle.message("status.no.entry")
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
        descriptor.title = I18nExtractorBundle.message("file.chooser.title")
        descriptor.description = I18nExtractorBundle.message("file.chooser.description")

        val initialFile = entryPathField.text?.takeIf { it.isNotBlank() }
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?: EntryFileLocator.findChineseLocaleEntryFile(project, contextPsiFile)

        val chosen = FileChooser.chooseFile(descriptor, project, initialFile)
        if (chosen != null) {
            entryPathField.text = chosen.path
            val ext = chosen.extension?.lowercase()
            lblEntryStatus.text = if (ext in setOf("ts", "tsx", "js", "jsx")) {
                I18nExtractorBundle.message("status.selected.tsjs", chosen.name)
            } else if (ext == "json") {
                I18nExtractorBundle.message("status.selected.json", chosen.name)
            } else {
                I18nExtractorBundle.message("status.unsupported.extension", chosen.extension.orEmpty())
            }
        }
    }

    /**
     * 若用户在输出面板中勾选了“自动初始化 i18n”且未初始化，则执行 bootstrap。
     * 返回 bootstrap 创建的中文语言包入口文件（若无则为 null）。
     */
    private fun applyBootstrapIfChecked(): VirtualFile? {
        val chk = chkI18nBootstrap ?: return null
        if (!chk.isSelected) return null
        val missing = bootstrapMissing ?: return null
        val psiFile = contextPsiFile ?: return null
        var createdEntry: VirtualFile? = null
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                createdEntry = I18nBootstrap.maybeApply(project, psiFile, missing)
            }
        } catch (e: Throwable) {
            LOG.warn("ExtractedStringsDialog: 执行 i18n 引导失败，返回 null", e)
            return null
        }
        bootstrapPerformed = true
        return createdEntry
    }

    override fun doOKAction() {
        val destination = I18nSettings.getInstance().outputDestination()

        // ── ⓪ 初始化：确保写回目标入口文件在写入前已存在且可解析 ──
        //    勾选“自动初始化 i18n”会执行 bootstrap；此外，当用户最终要“写入文件”但
        //    项目需初始化却未勾选时，也自动先初始化（创建可解析的 zh-CN.ts + i18n.ts + 补依赖），
        //    从而保证写回目标是已经初始化的入口，而不是空/未初始化的文件 → 不再报
        //    RESOURCE_OBJECT_MISSING、也不用再把资源回退剪贴板。
        val wantFileWrite = when (destination) {
            OutputDestination.ASK -> radioOverwrite.isSelected
            else -> destination == OutputDestination.FILE
        }
        var bootstrapEntry: VirtualFile? = null
        if (destination == OutputDestination.ASK) bootstrapEntry = applyBootstrapIfChecked()
        if (bootstrapEntry == null && wantFileWrite && bootstrapMissing != null && contextPsiFile != null) {
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    bootstrapEntry = I18nBootstrap.maybeApply(project, contextPsiFile!!, bootstrapMissing!!)
                }
            } catch (t: Throwable) {
                LOG.warn("ExtractedStringsDialog: 写入文件前自动初始化失败，回退为 null", t)
                bootstrapEntry = null
            }
        }
        if (bootstrapEntry != null) {
            bootstrapPerformed = true
            selectedEntryFile = bootstrapEntry
            if (::entryPathField.isInitialized) {
                entryPathField.text = bootstrapEntry!!.path
            }
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
                if (pathText.isEmpty() && bootstrapEntry != null) {
                    pathText = bootstrapEntry!!.path
                    entryPathField.text = pathText
                }
                if (pathText.isEmpty()) {
                    JOptionPane.showMessageDialog(
                        this.contentPanel,
                        I18nExtractorBundle.message("error.no.entry.file"),
                        I18nExtractorBundle.message("error.no.entry.title"),
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                val f = LocalFileSystem.getInstance().findFileByPath(pathText)
                if (f == null || !f.isValid || f.isDirectory) {
                    JOptionPane.showMessageDialog(
                        this.contentPanel,
                        I18nExtractorBundle.message("error.invalid.entry", pathText),
                        I18nExtractorBundle.message("error.invalid.entry.title"),
                        JOptionPane.WARNING_MESSAGE
                    )
                    return
                }
                val ext = f.extension?.lowercase()
                if (ext !in setOf("ts", "tsx", "js", "jsx", "json")) {
                    JOptionPane.showMessageDialog(
                        this.contentPanel,
                        I18nExtractorBundle.message("error.unsupported.extension", f.extension.orEmpty()),
                        I18nExtractorBundle.message("error.unsupported.type.title"),
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
                    I18nExtractorBundle.message("error.file.mode.no.entry"),
                    I18nExtractorBundle.message("error.file.mode.no.entry.title"),
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
            TsFileEditor.persistEntryPathIfNeeded(project, entryFile)
        }

        super.doOKAction()
    }

    private fun buildMergeTab(): JComponent {
        val root = JPanel(BorderLayout(0, 12))
        root.preferredSize = Dimension(980, 680)

        // 1) 公共前后缀表
        val affixCols = Vector<String>().apply {
            add(I18nExtractorBundle.message("table.column.select"))
            add(I18nExtractorBundle.message("table.column.skeleton"))
            add(I18nExtractorBundle.message("table.column.skeleton.key"))
            add(I18nExtractorBundle.message("table.column.diffs"))
            add(I18nExtractorBundle.message("table.column.examples"))
            add(I18nExtractorBundle.message("table.column.count"))
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
            border = BorderFactory.createTitledBorder(I18nExtractorBundle.message("border.affix.merge"))
            preferredSize = Dimension(960, 320)
        }

        // 2) 汉字+数字抽取表
        val digitCols = Vector<String>().apply {
            add(I18nExtractorBundle.message("table.column.select"))
            add(I18nExtractorBundle.message("table.column.digit.skeleton"))
            add(I18nExtractorBundle.message("table.column.digit.key"))
            add(I18nExtractorBundle.message("table.column.digit.examples"))
            add(I18nExtractorBundle.message("table.column.digit.count"))
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
            border = BorderFactory.createTitledBorder(I18nExtractorBundle.message("border.digit.merge"))
            preferredSize = Dimension(960, 220)
        }

        val hint = JLabel(I18nExtractorBundle.message("merge.tab.hint"))

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
