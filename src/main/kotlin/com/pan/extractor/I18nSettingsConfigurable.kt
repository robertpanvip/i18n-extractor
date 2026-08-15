package com.pan.extractor

import com.intellij.openapi.options.Configurable
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
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

    override fun getDisplayName(): String = "I18n Extractor"

    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(12, 12))
        root.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)

        // ── 上半：目标语言多选 ──
        val langPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        langPanel.border = BorderFactory.createTitledBorder("目标语言（勾选要提取的语言）")
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
        center.border = BorderFactory.createTitledBorder("提取行为与目录")

        center.add(JLabel("最小提取长度（少于该字符数不提取）:"))
        minLengthSpinner = JSpinner(SpinnerNumberModel(settings.minStringLength(), 1, 100, 1))
        center.add(minLengthSpinner!!)

        center.add(JLabel("合并建议阈值（公共前后缀合计字符数）:"))
        mergeThresholdSpinner = JSpinner(SpinnerNumberModel(settings.mergeAffixThreshold(), 1, 100, 1))
        center.add(mergeThresholdSpinner!!)

        center.add(JLabel("扫描排除目录（逗号分隔目录名）:"))
        excludeDirsField = JTextField(settings.excludeDirs().joinToString(","))
        center.add(excludeDirsField!!)

        center.add(JLabel("自定义翻译目录（逗号分隔目录名）:"))
        customDirsField = JTextField(settings.customTranslationDirs().joinToString(","))
        center.add(customDirsField!!)

        root.add(center, BorderLayout.CENTER)

        // ── 下半：输出去向单选 ──
        val outPanel = JPanel()
        outPanel.border = BorderFactory.createTitledBorder("提取结果输出去向")
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
            JLabel("「剪贴板」/「写入文件」：提取时不弹窗询问，直接按此方式输出；「每次询问」则弹窗时展示选项。")
        )
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
        return langChanged || outChanged || numChanged || listChanged
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
    }

    override fun disposeUIResources() {
        panel = null
        boxes.clear()
        outputButtons.clear()
        minLengthSpinner = null
        mergeThresholdSpinner = null
        excludeDirsField = null
        customDirsField = null
    }

    /** 把逗号分隔的文本拆成去空白后的集合。 */
    private fun splitList(raw: String?): Set<String> =
        raw.orEmpty().split(',', '，').map { it.trim() }.filter { it.isNotBlank() }.toSet()
}