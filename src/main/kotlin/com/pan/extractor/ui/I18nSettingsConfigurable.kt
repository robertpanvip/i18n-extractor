package com.pan.extractor.ui

import com.pan.extractor.lang.LanguageRegistry
import com.pan.extractor.*
import com.pan.extractor.analyzer.*
import com.pan.extractor.messages.I18nExtractorBundle

import com.intellij.openapi.options.Configurable
import java.awt.datatransfer.StringSelection
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.Messages
import com.pan.extractor.log.PluginLogBuffer
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/**
 * 插件全局设置面板：Settings → Tools → I18n Extractor。
 * 提供：
 *  - “目标语言”多选（默认只勾选中文）
 *  - “提取结果输出去向”单选（剪贴板 / 写入文件 / 每次询问，默认每次询问）
 *  - “最小提取长度”（默认 1，即全部提取）
 *  - “合并建议阈值”（默认 2）
 *  - “排除目录”（默认 node_modules/.git/dist 等）
 *  - “自定义翻译目录”（默认空，追加到内置目录之上）
 */
class I18nSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val boxes = LinkedHashMap<String, JCheckBox>()
    private val outputButtons = LinkedHashMap<OutputDestination, JRadioButton>()
    private var minLengthSpinner: JSpinner? = null
    private var mergeThresholdSpinner: JSpinner? = null
    private var excludeDirsField: JTextField? = null
    private var customDirsField: JTextField? = null
    private var vuePrefixField: JTextField? = null
    private var foldLangCombo: JComboBox<String>? = null
    private var reactLibraryCombo: JComboBox<String>? = null

    override fun getDisplayName(): String = "I18n Extractor"

    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(12, 12))
        root.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)

        // ── 上半：目标语言多选 ──
        val langPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        langPanel.border = BorderFactory.createTitledBorder(I18nExtractorBundle.message("settings.lang.panel.border"))
        val langBox = JPanel(GridLayout(0, 2, 8, 4))
        for (ex in LanguageRegistry.all) {
            val cb = JCheckBox("${ex.displayName} (${ex.id})")
            cb.isSelected = ex.id in I18nSettings.getInstance().activeLanguageIds()
            boxes[ex.id] = cb
            langBox.add(cb)
        }
        langPanel.add(langBox)
        root.add(langPanel, BorderLayout.NORTH)

        // ── 中：提取行为与目录设置 ──
        val settings = I18nSettings.getInstance()
        val center = JPanel(GridLayout(0, 2, 10, 6))
        center.border = BorderFactory.createTitledBorder(I18nExtractorBundle.message("settings.behavior.border"))

        center.add(JLabel(I18nExtractorBundle.message("settings.min.length.label")))
        minLengthSpinner = JSpinner(SpinnerNumberModel(settings.minStringLength(), 1, 100, 1))
        center.add(minLengthSpinner!!)

        center.add(JLabel(I18nExtractorBundle.message("settings.merge.threshold.label")))
        mergeThresholdSpinner = JSpinner(SpinnerNumberModel(settings.mergeAffixThreshold(), 1, 100, 1))
        center.add(mergeThresholdSpinner!!)

        center.add(JLabel(I18nExtractorBundle.message("settings.exclude.dirs.label")))
        excludeDirsField = JTextField(settings.excludeDirs().joinToString(","))
        center.add(excludeDirsField!!)

        center.add(JLabel(I18nExtractorBundle.message("settings.custom.dirs.label")))
        customDirsField = JTextField(settings.customTranslationDirs().joinToString(","))
        center.add(customDirsField!!)

        center.add(JLabel(I18nExtractorBundle.message("settings.vue.prefix.label")))
        vuePrefixField = JTextField(settings.vuePlaceholderPrefix())
        center.add(vuePrefixField!!)

        center.add(JLabel(I18nExtractorBundle.message("settings.fold.lang.label")))
        foldLangCombo = JComboBox(
            LanguageRegistry.all.map { "${it.displayName} (${it.id})" }.toTypedArray()
        )
        foldLangCombo!!.selectedItem = foldLangLabel(settings.foldDisplayLanguage())
        center.add(foldLangCombo!!)

        center.add(JLabel(I18nExtractorBundle.message("settings.react.library.label")))
        reactLibraryCombo = JComboBox(
            ReactLibrary.entries.map { it.label }.toTypedArray()
        )
        reactLibraryCombo!!.selectedIndex = ReactLibrary.entries.indexOf(settings.reactLibrary())
        center.add(reactLibraryCombo!!)

        root.add(center, BorderLayout.CENTER)

        // ── 下半：输出去向单选 ──
        val outPanel = JPanel()
        outPanel.border = BorderFactory.createTitledBorder(I18nExtractorBundle.message("settings.output.border"))
        outPanel.layout = javax.swing.BoxLayout(outPanel, javax.swing.BoxLayout.Y_AXIS)
        val current = settings.outputDestination()
        val group = ButtonGroup()
        for (d in OutputDestination.values()) {
            val rb = JRadioButton(d.label, d == current)
            group.add(rb)
            outputButtons[d] = rb
            outPanel.add(rb)
        }
        outPanel.add(
            JLabel(I18nExtractorBundle.message("settings.output.hint"))
        )

        // ── 诊断日志：一键复制到剪贴板，便于排查如"选了 react-intl 但 app.tsx 未提取"之类问题 ──
        val logRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        logRow.border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
        val copyLogBtn = JButton("复制诊断日志")
        copyLogBtn.toolTipText = "点击后把插件内存中的诊断日志（含 I18n detect 行）复制到剪贴板"
        copyLogBtn.addActionListener { copyDiagnosticLog() }
        logRow.add(copyLogBtn)
        val clearLogBtn = JButton("清空")
        clearLogBtn.toolTipText = "清空已收集的诊断日志"
        clearLogBtn.addActionListener {
            PluginLogBuffer.clear()
            Messages.showInfoMessage("诊断日志已清空。", "I18n Extractor")
        }
        logRow.add(clearLogBtn)
        outPanel.add(logRow)

        root.add(outPanel, BorderLayout.SOUTH)

        panel = root
        return root
    }

    override fun isModified(): Boolean {
        val settings = I18nSettings.getInstance()
        val langChanged = boxes.any { (id, cb) ->
            cb.isSelected != (id in settings.activeLanguageIds())
        }
        val outChanged = outputButtons.any { (d, rb) ->
            rb.isSelected != (d == settings.outputDestination())
        }
        val numChanged = minLengthSpinner?.value as? Int != settings.minStringLength() ||
            mergeThresholdSpinner?.value as? Int != settings.mergeAffixThreshold()
        val listChanged = splitList(excludeDirsField?.text) != settings.excludeDirs() ||
            splitList(customDirsField?.text) != settings.customTranslationDirs().toSet()
        val prefixChanged = vuePrefixField?.text?.trim()?.takeIf { it.isNotEmpty() } != settings.vuePlaceholderPrefix()
        val foldChanged = selectedFoldLangId() != settings.foldDisplayLanguage()
        val reactLibraryChanged = selectedReactLibrary() != settings.reactLibrary()
        return langChanged || outChanged || numChanged || listChanged || prefixChanged || foldChanged || reactLibraryChanged
    }

    override fun apply() {
        val settings = I18nSettings.getInstance()
        settings.setLanguageIds(boxes.filterValues { it.isSelected }.keys)
        outputButtons.entries.firstOrNull { it.value.isSelected }?.key?.let {
            settings.setOutputDestination(it)
        }
        (minLengthSpinner?.value as? Int)?.let { settings.setMinStringLength(it) }
        (mergeThresholdSpinner?.value as? Int)?.let { settings.setMergeAffixThreshold(it) }
        settings.setExcludeDirs(splitList(excludeDirsField?.text))
        settings.setCustomTranslationDirs(splitList(customDirsField?.text))
        vuePrefixField?.text?.let { settings.setVuePlaceholderPrefix(it) }
        selectedFoldLangId()?.let { settings.setFoldDisplayLanguage(it) }
        selectedReactLibrary()?.let { settings.setReactLibrary(it) }
    }

    override fun reset() {
        val settings = I18nSettings.getInstance()
        val active = settings.activeLanguageIds()
        for ((id, cb) in boxes) cb.isSelected = id in active
        val currentOut = settings.outputDestination()
        for ((d, rb) in outputButtons) rb.isSelected = d == currentOut
        minLengthSpinner?.model = SpinnerNumberModel(settings.minStringLength(), 1, 100, 1)
        mergeThresholdSpinner?.model = SpinnerNumberModel(settings.mergeAffixThreshold(), 1, 100, 1)
        excludeDirsField?.text = settings.excludeDirs().joinToString(",")
        customDirsField?.text = settings.customTranslationDirs().joinToString(",")
        vuePrefixField?.text = settings.vuePlaceholderPrefix()
        foldLangCombo?.selectedItem = foldLangLabel(settings.foldDisplayLanguage())
        reactLibraryCombo?.selectedIndex = ReactLibrary.entries.indexOf(settings.reactLibrary())
    }

    override fun disposeUIResources() {
        panel = null
        boxes.clear()
        outputButtons.clear()
        minLengthSpinner = null
        mergeThresholdSpinner = null
        excludeDirsField = null
        customDirsField = null
        vuePrefixField = null
        foldLangCombo = null
        reactLibraryCombo = null
    }

    /**
     * 把插件内存中的诊断日志（含 "I18n detect ..." 行）复制到系统剪贴板。
     * 便于在"选了 react-intl 但某文件未提取"等场景下，把日志直接粘给开发者排查。
     */
    private fun copyDiagnosticLog() {
        val lines = PluginLogBuffer.dump()
        val text = lines.ifEmpty { "（暂无日志）" }
        val cnt = lines.lineSequence().count()
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        Messages.showInfoMessage("诊断日志已复制到剪贴板（$cnt 行）。", "I18n Extractor")
    }

    /** 配置项 "显示名 (id)" → id。 */
    private fun foldLangLabel(id: String): String {
        val ex = LanguageRegistry.byId(id) ?: LanguageRegistry.all.firstOrNull()!!
        return "${ex.displayName} (${ex.id})"
    }

    /** 从下拉当前选中项解析出语言 id；无法解析返回 null。 */
    private fun selectedFoldLangId(): String? {
        val item = foldLangCombo?.selectedItem as? String ?: return null
        val m = Regex("""\(([a-z]{2})\)\s*$""").find(item)
        val id = m?.groupValues?.get(1) ?: return null
        return if (LanguageRegistry.byId(id) != null) id else null
    }

    /** 从下拉当前选中项解析出 React 多语言库；无法解析返回 null。 */
    private fun selectedReactLibrary(): ReactLibrary? {
        val idx = reactLibraryCombo?.selectedIndex ?: return null
        return ReactLibrary.entries.getOrNull(idx)
    }

    /** 把逗号分隔的文本拆成去空白后的集合。 */
    private fun splitList(raw: String?): Set<String> =
        raw.orEmpty().split(',', '，').map { it.trim() }.filter { it.isNotBlank() }.toSet()
}