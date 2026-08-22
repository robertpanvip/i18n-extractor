package com.pan.extractor.bootstrap

import com.pan.extractor.strategy.I18nFramework
import com.pan.extractor.strategy.VueI18nStrategy
import com.pan.extractor.strategy.ReactI18nextStrategy
import com.pan.extractor.strategy.SolidI18nStrategy
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger

/**
 * i18n 引导（Bootstrap）支持：
 * 当项目是 React/Vue 且尚未初始化 i18n 时，在最后确认阶段弹框提示用户，确认后自动：
 *   1. 在 package.json 添加依赖（React → i18next + react-i18next；Vue → vue-i18n）
 *   2. 自动创建 i18n 初始化文件（React → src/i18n.ts；Vue → src/i18n.ts）
 *
 * 框架差异（依赖列表 / 初始化文件模板）由 [I18nFramework] 策略提供，
 * 本类只负责编排：检测 → 生成 → 写入。
 *
 * 本类只包含纯函数（不依赖 IDE 平台），便于单元测试：
 *   - [detectMissing]：判定项目是否缺依赖且未初始化
 *   - [buildInitFileContent]：生成初始化文件内容（委托策略）
 *   - [addDepsToPackageJson]：更新 package.json 文本，追加依赖
 */
object I18nBootstrapSupport {

    private val LOG = Logger.getInstance(I18nBootstrapSupport::class.java)

    /** 检测结果：缺 bootstrap（未初始化）时描述需要补什么。 */
    data class MissingBootstrap(
        val framework: I18nFramework,
        val depsToAdd: List<String>,
    ) {
        val dependencyLabel: String
            get() = depsToAdd.joinToString(" + ")
    }

    /**
     * 检测项目是否"缺 i18n 依赖或未初始化"。
     *
     * 「未初始化」（[hasInitFile] 为 false）是触发提示的前提；依赖是否缺失仅决定
     * [MissingBootstrap.depsToAdd] 是否为空：
     *   - 依赖已安装 → 仍返回 [MissingBootstrap]（空 [depsToAdd]），仅提示并创建初始化文件；
     *   - 依赖缺失 → 返回 [MissingBootstrap]，提示补依赖 + 创建初始化文件。
     *
     * 框架判定与依赖列表均来自 [I18nFramework] 策略（Vue 优先，Solid 次之，React 最后）：
     *   - hasVueDep   → [VueI18nStrategy]（bootstrapDeps = vue-i18n）
     *   - hasSolidDep → [SolidI18nStrategy]（bootstrapDeps = @solid-primitives/i18n）
     *   - hasReactDep → [ReactI18nextStrategy]（bootstrapDeps = i18next + react-i18next）
     *   - 都没有      → null（Generic 无需引导）
     *
     * @param packageJsonText 项目根 package.json 文本；null 表示没有 package.json
     * @param hasInitFile     项目里是否已存在 i18n 初始化文件（createI18n / initReactI18next / i18n.init）
     * @param hasReactDep     项目是否依赖 react（由调用方从 package.json 判定）
     * @param hasVueDep       项目是否依赖 vue
     * @param hasSolidDep     项目是否依赖 solid-js
     *
     * @return 命中框架且未初始化则返回需补的依赖；已初始化或非 React/Vue/Solid 时返回 null
     */
    fun detectMissing(
        packageJsonText: String?,
        hasInitFile: Boolean,
        hasReactDep: Boolean,
        hasVueDep: Boolean,
        hasSolidDep: Boolean = false,
    ): MissingBootstrap? {
        if (hasInitFile) return null
        val strategy = when {
            hasVueDep -> VueI18nStrategy
            hasSolidDep -> SolidI18nStrategy
            hasReactDep -> ReactI18nextStrategy
            else -> return null
        }
        val deps = strategy.bootstrapDeps
        if (deps.isEmpty()) return null
        // 只计算真正缺失的依赖；依赖已装则 depsToAdd 为空——项目仍需初始化文件，仍应提示。
        val missingDeps = if (packageJsonText != null) {
            deps.filterNot { dep ->
                Regex(""""${Regex.escape(dep)}"\s*:""").containsMatchIn(packageJsonText)
            }
        } else {
            deps
        }
        return MissingBootstrap(strategy, missingDeps)
    }

    /**
     * 生成 i18n 初始化文件内容（委托给 [I18nFramework.buildInitFile]）。
     *
     * @param framework     框架策略
     * @param defaultLocale 默认语言（如 zh、zh-CN）
     * @param entryImport   用于填充 resources/messages 的中文入口文件名（不含扩展名），可为空
     */
    fun buildInitFileContent(
        framework: I18nFramework,
        defaultLocale: String,
        entryImport: String? = null,
    ): String = framework.buildInitFile(defaultLocale, entryImport)

    /** 生成一个最简可写回的中文语言包入口文件内容（export default 对象字面量）。 */
    fun buildLocaleEntryFileContent(): String =
        "export default {\n};\n"

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
        } catch (e: Exception) {
            LOG.warn("I18nBootstrapSupport: 解析 package.json 失败，返回原文", e)
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
