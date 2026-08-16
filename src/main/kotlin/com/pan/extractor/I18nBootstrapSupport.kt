package com.pan.extractor

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * i18n 引导（Bootstrap）支持：
 * 当项目是 React/Vue 但既没安装对应多语言依赖、又没初始化 i18n 时，
 * 在最后确认阶段弹框提示用户，确认后自动：
 *   1. 在 package.json 添加依赖（React → i18next + react-i18next；Vue → vue-i18n）
 *   2. 自动创建 i18n 初始化文件（React → src/i18n.ts；Vue → src/i18n.ts）
 *
 * 本类只包含纯函数（不依赖 IDE 平台），便于单元测试：
 *   - [detectMissing]：判定项目是否缺依赖且未初始化
 *   - [buildInitFileContent]：生成初始化文件内容
 *   - [addDepsToPackageJson]：更新 package.json 文本，追加依赖
 */
object I18nBootstrapSupport {

    enum class Framework { VUE, REACT }

    /** 检测结果：缺 bootstrap 时描述需要补什么。 */
    data class MissingBootstrap(
        val framework: Framework,
        val depsToAdd: List<String>,
    ) {
        val dependencyLabel: String
            get() = depsToAdd.joinToString(" + ")
    }

    private val REACT_DEP = Regex(""""(?:i18next|react-i18next)"\s*:""")
    private val VUE_DEP = Regex(""""(?:vue-i18n)"\s*:""")

    /**
     * 检测项目是否“缺 i18n 依赖且未初始化”。
     *
     * @param packageJsonText 项目根 package.json 文本；null 表示没有 package.json
     * @param hasInitFile     项目里是否已存在 i18n 初始化文件（createI18n / initReactI18next / i18n.init）
     * @param hasReactDep     项目是否依赖 react（由调用方从 package.json 判定）
     * @param hasVueDep       项目是否依赖 vue
     *
     * @return 命中框架则返回缺的依赖；否则 null
     */
    fun detectMissing(
        packageJsonText: String?,
        hasInitFile: Boolean,
        hasReactDep: Boolean,
        hasVueDep: Boolean,
    ): MissingBootstrap? {
        if (hasInitFile) return null
        return when {
            hasReactDep -> {
                if (packageJsonText != null && REACT_DEP.containsMatchIn(packageJsonText)) null
                else MissingBootstrap(Framework.REACT, listOf("i18next", "react-i18next"))
            }
            hasVueDep -> {
                if (packageJsonText != null && VUE_DEP.containsMatchIn(packageJsonText)) null
                else MissingBootstrap(Framework.VUE, listOf("vue-i18n"))
            }
            else -> null
        }
    }

    /**
     * 生成 i18n 初始化文件内容。
     *
     * @param framework     VUE / REACT
     * @param defaultLocale 默认语言（如 zh、zh-CN）
     * @param entryImport   用于填充 resources/messages 的中文入口文件名（不含扩展名），可为空
     */
    fun buildInitFileContent(
        framework: Framework,
        defaultLocale: String,
        entryImport: String? = null,
    ): String = when (framework) {
        Framework.REACT -> buildReactInit(defaultLocale, entryImport)
        Framework.VUE -> buildVueInit(defaultLocale, entryImport)
    }

    private fun buildReactInit(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './locales/$entryImport';\n"
        } else ""
        val resourcesBlock = if (!entryImport.isNullOrBlank()) {
            "  resources: {\n    $defaultLocale: { translation: zh },\n  },\n"
        } else ""
        return """
            import i18n from 'i18next';
            import { initReactI18next } from 'react-i18next';
            $importLine
            i18n.use(initReactI18next).init({
              lng: '$defaultLocale',
              fallbackLng: '$defaultLocale',
            $resourcesBlock});

            export default i18n;
        """.trimIndent() + "\n"
    }

    private fun buildVueInit(defaultLocale: String, entryImport: String?): String {
        val importLine = if (!entryImport.isNullOrBlank()) {
            "import zh from './locales/$entryImport';\n"
        } else ""
        val messagesBlock = if (!entryImport.isNullOrBlank()) {
            "  messages: {\n    $defaultLocale: zh,\n  },\n"
        } else ""
        return """
            import { createI18n } from 'vue-i18n';
            $importLine
            const i18n = createI18n({
              legacy: false,
              locale: '$defaultLocale',
              fallbackLocale: '$defaultLocale',
            $messagesBlock});

            export default i18n;
        """.trimIndent() + "\n"
    }

    /**
     * 在 package.json 文本的 dependencies（或新增 dependencies）中加入给定依赖。
     * 使用宽松 JSON 解析 + 保留其余字段原文（仅精确改写依赖部分），
     * 避免重构整个 package.json 破坏格式/注释。
     *
     * 若 [depVersion] 为 null，则使用占位版本 "latest"。
     */
    fun addDepsToPackageJson(
        packageJsonText: String,
        deps: List<String>,
        depVersion: String? = null,
    ): String {
        val version = depVersion ?: "latest"
        // 尝试用 Gson 解析，失败则直接返回原文（不做破坏性修改）
        val root = try {
            JsonParser.parseString(packageJsonText)
        } catch (_: Exception) {
            return packageJsonText
        }
        if (!root.isJsonObject) return packageJsonText
        val obj = root.asJsonObject

        var depsObject: JsonObject? = obj.getAsJsonObject("dependencies")
        if (depsObject == null) {
            depsObject = JsonObject()
            obj.add("dependencies", depsObject)
        }
        for (dep in deps) {
            if (!depsObject.has(dep)) {
                depsObject.addProperty(dep, version)
            }
        }
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(obj)
    }
}