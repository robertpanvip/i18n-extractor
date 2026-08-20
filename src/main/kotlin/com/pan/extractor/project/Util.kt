package com.pan.extractor.project

import com.pan.extractor.lang.SiteKind
import com.pan.extractor.ui.*

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import java.io.File
import java.nio.charset.StandardCharsets

object Util {

    /**
     * 空白压缩正则：把连续空白（含空格 / Tab / 换行）整体替换为空串。
     *
     * P2（重复内联 Regex 收敛）：各策略做「宽松文本特征匹配」时先用它压缩空白
     * （`const $t = i18n.global.t` → `const$t=i18n.global.t`），避免在十余处散落
     * `"\\s+".toRegex()`。行为与旧内联用法完全一致。
     */
    val WS_COMPACT_RE: Regex = "\\s+".toRegex()

    // ────────────────────────────────────────────────────────────────
    // 全局 `$t` / `i18n` 别名「紧凑签名」常量表（P2 收敛硬编码文本特征）。
    //
    // 各策略检测「文件里是否已存在等价别名」时，会先把源码文本用 [WS_COMPACT_RE]
    // 压掉空白（`const $t = i18n.global.t` → 紧凑串）再做 contains 匹配。这些签名串
    // 集中在此，避免在 ImportManager / Vue / React / Solid 策略里散落重复字面量；
    // 若要新增一种别名写法，只改这一处即可（常量表语义，行为与旧内联一致）。
    // ────────────────────────────────────────────────────────────────
    /** `const $t = i18n.global.t`（Vue 全局别名）。 */
    const val SIGNATURE_VUE_GLOBAL_T = "const\$t=i18n.global.t"
    /** `const $t = …`（Solid 全局别名，宽松前缀）。 */
    const val SIGNATURE_SOLID_GLOBAL_T = "const\$t="
    /** `const t = getI18n().t`（React 回落别名）。 */
    const val SIGNATURE_REACT_GET_I18N_T = "constt=getI18n().t"
    /** `const $t = getI18n().t`（React 回落别名，\$t 变体）。 */
    const val SIGNATURE_REACT_GET_I18N_DOLLAR_T = "const\$t=getI18n().t"
    /** `const t = i18n.t`（React 直连别名）。 */
    const val SIGNATURE_REACT_I18N_T = "constt=i18n.t"
    /** `const i18n = getI18n()`（React i18n 全局别名）。 */
    const val SIGNATURE_REACT_GET_I18N_ALIAS = "consti18n=getI18n()"

    /** 常见 helper：文本中是否包含至少 1 个目标语言的字符。
     *  - 用于判断差异段是否要嵌套 `$t('差异')`（含目标语言→嵌套；纯英文/数字→直接写字符串字面量）。
     *  - 目标语言取决于全局设置（默认仅中文，向后兼容）。*/

    /** 文本是否包含任一“已启用目标语言”的字符（由全局设置决定，默认仅中文）。 */
    fun containsTargetLanguage(text: CharSequence?): Boolean =
        containsTargetLanguage(text, SiteKind.OTHER)

    /** 文本在该站点上下文下是否命中任一“已启用目标语言”（Approach A：先看上下文是否接受，再看字符判定）。 */
    fun containsTargetLanguage(text: CharSequence?, site: SiteKind): Boolean {
        if (text == null) return false
        return I18nSettings.getInstance().activeExtractors()
            .any { it.accepts(site) && it.judge(text) }
    }

    fun getJsonContent(json: String): String {
        val content = json
            .trim()
            .removePrefix("{")
            .removeSuffix("}")
            .trim()
        return content
    }

    fun findFilesByIncludePatterns(
        project: Project,
        rawIncludePatterns: List<String>
    ): List<VirtualFile> {

        val basePath = project.basePath ?: return emptyList()
        val baseDir = File(basePath)

        if (!baseDir.exists()) return emptyList()

        // 预编译 glob -> regex
        val regexList = rawIncludePatterns.map { globToRegex(it) }

        val result = mutableSetOf<VirtualFile>()

        baseDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach

            val relativePath = baseDir
                .toPath()
                .relativize(file.toPath())
                .toString()
                .replace("\\", "/")

            // 排除 node_modules 与构建产物目录，避免把依赖源码当成翻译源
            if (relativePath.split("/").any { it in I18nSettings.getInstance().excludeDirs() }) return@forEach

            // include 为空时视为"全项目扫描"（回退），否则按 glob 模式匹配
            if (regexList.isEmpty() || regexList.any { it.matches(relativePath) }) {
                LocalFileSystem.getInstance()
                    .findFileByIoFile(file)
                    ?.let { result.add(it) }
            }
        }

        return result.toList()
    }

    private fun globToRegex(glob: String): Regex {
        // 统一相对项目根：去掉开头的 ./ 与前导 /（A7：根目录型 include）
        var normalized = glob.trim().replace("\\", "/")
            .removePrefix("./")
            .removePrefix("/")
        if (normalized.isEmpty()) normalized = "**"

        // 结尾带斜杠 = 目录：附加通配以匹配其下所有文件
        if (normalized.endsWith("/")) normalized += "**"

        // 无通配符的裸路径：最后一段带扩展名按精确文件匹配；否则按目录匹配（含其下所有文件）（A8）
        val hasWildcard = normalized.contains('*')
        val lastSegmentExt = normalized.substringAfterLast('/').substringAfterLast('.', "")
        val isDirPath = !hasWildcard && lastSegmentExt.isEmpty()

        var pattern = normalized
            .replace(".", "\\.")
            .replace("**/", "(.*/)?")
            .replace("/**", "(/.*)?")
            .replace("**", ".*")
            .replace("*", "[^/]*")
        if (isDirPath) pattern = "$pattern(/.*)?"
        return Regex("^$pattern$")
    }

    // ==========================================================================
    // 用户输出方式配置：拷贝到剪贴板 / 覆盖入口中文多语言文件。
    // 与 [OutputDestination]（全局设置）统一为同一个枚举，此处仅负责
    // 对话框内“上次选择”的项目级记忆（只使用 CLIPBOARD / FILE 两值）。
    // ==========================================================================
    private const val PREF_OUTPUT_MODE = "i18n-extractor.output-mode"
    private const val PREF_ENTRY_PATH = "i18n-extractor.entry-path"

    /** 读取对话框上次选择的输出方式；ASK 视为未选择，回退剪贴板。 */
    fun getDialogOutputMode(project: Project): OutputDestination {
        val raw = OutputDestination.safeValueOf(
            PropertiesComponent.getInstance(project).getValue(PREF_OUTPUT_MODE)
        )
        return if (raw == OutputDestination.ASK) OutputDestination.CLIPBOARD else raw
    }

    fun setDialogOutputMode(project: Project, mode: OutputDestination) {
        PropertiesComponent.getInstance(project).setValue(PREF_OUTPUT_MODE, mode.name)
    }

    fun getStoredEntryPath(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(PREF_ENTRY_PATH)?.takeIf { it.isNotBlank() }

    fun setStoredEntryPath(project: Project, path: String?) {
        PropertiesComponent.getInstance(project).setValue(PREF_ENTRY_PATH, path)
    }

    fun readVirtualFileText(project: Project?, vf: VirtualFile): String? {
        return try {
            if (project != null) {
                val psi = ApplicationManager.getApplication().runReadAction<PsiFile?> {
                    PsiManager.getInstance(project).findFile(vf)
                }
                if (psi != null) psi.text else String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
            } else {
                String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ==========================================================================
    // isVue/isReact/isSolid：高频调用（recordSite 等热路径），保留门面以避免改动太多
    // 调用方。其他原转发方法已直接改用 ProjectStructure/EntryFileLocator/TsFileEditor。
    // ==========================================================================
    fun isReact(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isReact(element)

    fun isVue(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isVue(element)

    fun isSolid(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isSolid(element)

    fun isSvelte(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isSvelte(element)

    fun isAngular(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isAngular(element)
}