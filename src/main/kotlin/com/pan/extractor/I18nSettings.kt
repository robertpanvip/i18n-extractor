package com.pan.extractor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 插件全局设置（应用级，跨项目共享）。
 * 目前只存“目标语言集合”——用户勾选要提取的语言，默认只启用中文（向后兼容）。
 */
@State(
    name = "I18nExtractorSettings",
    storages = [Storage("i18n-extractor-settings.xml")]
)
class I18nSettings : PersistentStateComponent<I18nSettingsState> {
    private var state = I18nSettingsState()

    companion object {
        private const val DEFAULT_LANG = "zh"

        fun getInstance(): I18nSettings =
            ApplicationManager.getApplication().getService(I18nSettings::class.java)
    }

    override fun getState(): I18nSettingsState = state

    override fun loadState(s: I18nSettingsState) {
        state = s
    }

    /** 当前选中的语言 id 集合（可为空）。 */
    fun languageIds(): Set<String> = state.languageIds.orEmpty().toSet()

    /** 更新选中的语言 id 集合。 */
    fun setLanguageIds(ids: Collection<String>) {
        state.languageIds = ids.toMutableSet()
    }

    /** 当前启用语言 id 集合。为空时回退为默认中文，保证任何情况下都有结果。 */
    fun activeLanguageIds(): Set<String> {
        val ids = languageIds()
        return if (ids.isEmpty()) setOf(DEFAULT_LANG) else ids
    }

    /** 当前启用的语言提取器（顺序与注册表一致）。 */
    fun activeExtractors(): List<LanguageExtractor> =
        LanguageRegistry.all.filter { it.id in activeLanguageIds() }

    /** 当前启用语言的全部 locale 命名候选（去重）。 */
    fun activeLocaleCandidates(): List<String> =
        activeExtractors().flatMap { it.localeNameCandidates() }.distinct()
}

/** 可序列化的设置状态（XmlSerializerUtil 直接映射字段）。 */
class I18nSettingsState {
    var languageIds: MutableSet<String> = mutableSetOf("zh")
}