package com.pan.extractor

import com.intellij.openapi.options.Configurable
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 插件全局设置面板：Settings → Tools → I18n Extractor。
 * 目前提供“目标语言”多选（默认只勾选中文）。
 */
class I18nSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val boxes = LinkedHashMap<String, JCheckBox>()

    override fun getDisplayName(): String = "I18n Extractor"

    override fun createComponent(): JComponent {
        val root = JPanel(GridLayout(0, 1))
        root.border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
        root.add(JLabel("选择要提取的目标语言："))
        for (ex in LanguageRegistry.all) {
            val cb = JCheckBox("${ex.displayName} (${ex.id})")
            cb.isSelected = ex.id in I18nSettings.getInstance().activeLanguageIds()
            boxes[ex.id] = cb
            root.add(cb)
        }
        root.add(JLabel("提示：默认仅提取中文；勾选后按对应字符集判定并匹配对应 locale 入口。"))
        panel = root
        return root
    }

    override fun isModified(): Boolean {
        val active = I18nSettings.getInstance().activeLanguageIds()
        return boxes.any { (id, cb) -> cb.isSelected != (id in active) }
    }

    override fun apply() {
        val settings = I18nSettings.getInstance()
        settings.setLanguageIds(boxes.filterValues { it.isSelected }.keys)
    }

    override fun reset() {
        val active = I18nSettings.getInstance().activeLanguageIds()
        for ((id, cb) in boxes) cb.isSelected = id in active
    }

    override fun disposeUIResources() {
        panel = null
        boxes.clear()
    }
}