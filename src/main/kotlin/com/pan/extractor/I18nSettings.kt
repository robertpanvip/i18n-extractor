package com.pan.extractor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 提取结果的输出去向（全局设置，默认「每次询问」）。
 *  - [CLIPBOARD]：明确选「剪贴板」→ 每次提取后直接把 JSON 复制到剪贴板，不再弹窗询问。
 *  - [FILE]：明确选「写入文件」→ 每次提取后自动写回入口多语言文件，不再弹窗询问。
 *  - [ASK]：每次弹窗时展示输出方式选项（默认，向后兼容）。
 */
enum class OutputDestination(val label: String) {
    CLIPBOARD("剪贴板"),
    FILE("写入文件"),
    ASK("每次询问");

    companion object {
        fun safeValueOf(raw: String?): OutputDestination = when (raw?.trim()) {
            "CLIPBOARD" -> CLIPBOARD
            "FILE" -> FILE
            else -> ASK
        }
    }
}

/**
 * 插件全局设置（应用级，跨项目共享）。
 * 目前存“目标语言集合”与“输出去向”（默认每次询问）。
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

    /** 当前输出去向（默认「每次询问」）。 */
    fun outputDestination(): OutputDestination =
        OutputDestination.safeValueOf(state.outputDestination)

    /** 更新输出去向。 */
    fun setOutputDestination(d: OutputDestination) {
        state.outputDestination = d.name
    }

    /** 最小提取长度：字符串长度小于该值的文案不提取（默认 1，即全部提取，向后兼容）。 */
    fun minStringLength(): Int = state.minStringLength.coerceAtLeast(1)

    /** 更新最小提取长度。 */
    fun setMinStringLength(v: Int) {
        state.minStringLength = v.coerceAtLeast(1)
    }

    /** 合并建议阈值：公共前后缀合计至少达到该字符数才生成合并建议（默认 2）。 */
    fun mergeAffixThreshold(): Int = state.mergeAffixThreshold.coerceAtLeast(1)

    /** 更新合并建议阈值。 */
    fun setMergeAffixThreshold(v: Int) {
        state.mergeAffixThreshold = v.coerceAtLeast(1)
    }

    /** 扫描时排除的目录名（默认 node_modules/.git/dist 等构建产物）。 */
    fun excludeDirs(): Set<String> = state.excludeDirs.orEmpty().toSet().filterTo(mutableSetOf()) { it.isNotBlank() }

    /** 更新排除目录名集合。 */
    fun setExcludeDirs(v: Collection<String>) {
        state.excludeDirs = v.filter { it.isNotBlank() }.toMutableSet()
    }

    /** 用户自定义的翻译资源目录名（追加到内置目录之上）。 */
    fun customTranslationDirs(): List<String> = state.customTranslationDirs.orEmpty().map { it.trim() }.filter { it.isNotBlank() }

    /** 更新自定义翻译资源目录名。 */
    fun setCustomTranslationDirs(v: Collection<String>) {
        state.customTranslationDirs = v.map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
    }

    /**
     * Vue 项目的数字占位符前缀（默认 `N`，生成 `{N0}`/`{N1}`，参数对象 `{ N0: xxx }`）。
     * vue-i18n 不支持数字 key，占位符必须是合法变量名，因此不允许为空；
     * 为空时回退默认 `N`，保证不生成非法占位符。
     */
    fun vuePlaceholderPrefix(): String {
        val p = state.vuePlaceholderPrefix.trim()
        return if (p.isEmpty()) "N" else p
    }

    /** 更新 Vue 占位符前缀（空值钳制回默认 `N`）。 */
    fun setVuePlaceholderPrefix(v: String) {
        val p = v.trim()
        state.vuePlaceholderPrefix = if (p.isEmpty()) "N" else p
    }

    /** $t() 折叠展示所用语言 id（默认 `zh`）。 */
    fun foldDisplayLanguage(): String {
        val id = state.foldDisplayLanguage
        return if (LanguageRegistry.byId(id) != null) id else DEFAULT_LANG
    }

    /** 更新 $t() 折叠展示语言 id。 */
    fun setFoldDisplayLanguage(id: String) {
        state.foldDisplayLanguage = if (LanguageRegistry.byId(id) != null) id else DEFAULT_LANG
    }
}

/** 可序列化的设置状态（XmlSerializerUtil 直接映射字段）。 */
class I18nSettingsState {
    var languageIds: MutableSet<String> = mutableSetOf("zh")
    var outputDestination: String = OutputDestination.ASK.name
    var minStringLength: Int = 1
    var mergeAffixThreshold: Int = 2
    var excludeDirs: MutableSet<String> =
        mutableSetOf("node_modules", ".git", "dist", "build", ".next", ".nuxt", "out")
    var customTranslationDirs: MutableList<String> = mutableListOf()
    var vuePlaceholderPrefix: String = "N"
    var foldDisplayLanguage: String = "zh"
}