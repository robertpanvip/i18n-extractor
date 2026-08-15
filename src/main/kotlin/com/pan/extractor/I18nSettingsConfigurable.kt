package com.pan.extractor

import com.intellij.openapi.options.Configurable
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton

/**
 * 插件全局设置面板：Settings → Tools → I18n Extractor。
 * 提供：
 *  - “目标语言”多选（默认只勾选中文）
 *  - “输出去向”单选（剪贴板 / 写入文件 / 每次询问，默认每次询问）
 */
class I18nSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val boxes = LinkedHashMap<String, JCheckBox>()
    private val outputButtons = LinkedHashMap<OutputDestination, JRadioButton>()

    override fun getDisplayName(): String = "I18n Extractor"

    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(12, 12))
        root.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)

        // ── 上半：目标语言多选 ──
        val langPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        langPanel.border = BorderFactory.createTitledBorder("目标语言（勾选要提取的语言）")
        val langBox = JPanel(java.awt.GridLayout(0, 2, 8, 4))
        for (ex in LanguageRegistry.all) {
            val cb = JCheckBox("${ex.displayName} (${ex.id})")
            cb.isSelected = ex.id in I18nSettings.getInstance().activeLanguageIds()
            boxes[ex.id] = cb
            langBox.add(cb)
        }
        langPanel.add(langBox)
        root.add(langPanel, BorderLayout.NORTH)

        // ── 下半：输出去向单选 ──
        val outPanel = JPanel()
        outPanel.border = BorderFactory.createTitledBorder("提取结果输出去向")
        outPanel.layout = javax.swing.BoxLayout(outPanel, javax.swing.BoxLayout.Y_AXIS)
        val current = I18nSettings.getInstance().outputDestination()
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
        return langChanged || outChanged
    }

    override fun apply() {
        val settings = I18nSettings.getInstance()
        settings.setLanguageIds(boxes.filterValues { it.isSelected }.keys)
        outputButtons.entries.firstOrNull { it.value.isSelected }?.key?.let {
            settings.setOutputDestination(it)
        }
    }

    override fun reset() {
        val settings = I18nSettings.getInstance()
        val active = settings.activeLanguageIds()
        for ((id, cb) in boxes) cb.isSelected = id in active
        val currentOut = settings.outputDestination()
        for ((d, rb) in outputButtons) rb.isSelected = d == currentOut
    }

    override fun disposeUIResources() {
        panel = null
        boxes.clear()
        outputButtons.clear()
    }
}