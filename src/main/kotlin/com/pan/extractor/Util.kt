package com.pan.extractor

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
    /** 常见 helper：文本中是否包含至少 1 个目标语言的字符。
     *  - 用于判断差异段是否要嵌套 `$t('差异')`（含目标语言→嵌套；纯英文/数字→直接写字符串字面量）。
     *  - 目标语言取决于全局设置（默认仅中文，向后兼容）。*/
    fun hasChinese(text: CharSequence?): Boolean = containsTargetLanguage(text)

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
    // 用户输出方式配置：拷贝到剪贴板 / 覆盖入口中文多语言文件
    // ==========================================================================
    enum class OutputMode {
        COPY_TO_CLIPBOARD,
        OVERWRITE_ENTRY_FILE;

        companion object {
            fun safeValueOf(raw: String?): OutputMode = when (raw?.trim()) {
                "OVERWRITE_ENTRY_FILE" -> OVERWRITE_ENTRY_FILE
                else -> COPY_TO_CLIPBOARD
            }
        }
    }

    private const val PREF_OUTPUT_MODE = "i18n-extractor.output-mode"
    private const val PREF_ENTRY_PATH = "i18n-extractor.entry-path"

    fun getOutputMode(project: Project): OutputMode =
        OutputMode.safeValueOf(PropertiesComponent.getInstance(project).getValue(PREF_OUTPUT_MODE))

    fun setOutputMode(project: Project, mode: OutputMode) {
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
    // 以下方法已迁移到 ProjectStructure（见同目录 ProjectStructure.kt）。
    // Util 作为对外门面，保留原签名并委托给 ProjectStructure。行为不变。
    // ==========================================================================
    fun isJSX(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isJSX(element)

    fun isReact(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isReact(element)

    fun isVue(element: com.intellij.psi.PsiElement): Boolean = ProjectStructure.isVue(element)

    fun findReactComponentFunctions(file: PsiFile): List<com.intellij.psi.PsiElement> =
        ProjectStructure.findReactComponentFunctions(file)

    fun findHookFunctions(file: PsiFile): List<com.intellij.psi.PsiElement> =
        ProjectStructure.findHookFunctions(file)

    fun findVueComponentFunctions(file: PsiFile): List<com.intellij.psi.PsiElement> =
        ProjectStructure.findVueComponentFunctions(file)

    fun findProjectRoot(currentPsiFile: PsiFile): VirtualFile? = ProjectStructure.findProjectRoot(currentPsiFile)

    fun findVueI18nInstanceFile(currentPsiFile: PsiFile): VirtualFile? = ProjectStructure.findVueI18nInstanceFile(currentPsiFile)

    fun findVueI18nInstanceFileInRoot(projectRoot: VirtualFile): VirtualFile? = ProjectStructure.findVueI18nInstanceFileInRoot(projectRoot)

    fun findI18nInitFileInRoot(projectRoot: VirtualFile): VirtualFile? = ProjectStructure.findI18nInitFileInRoot(projectRoot)

    fun findReactI18nInstanceFileInRoot(projectRoot: VirtualFile): VirtualFile? = ProjectStructure.findReactI18nInstanceFileInRoot(projectRoot)

    fun resolveVueI18nImportPath(currentPsiFile: PsiFile, i18nVFile: VirtualFile): String? =
        ProjectStructure.resolveVueI18nImportPath(currentPsiFile, i18nVFile)

    fun isVueI18nDefaultExport(i18nVFile: VirtualFile): Boolean = ProjectStructure.isVueI18nDefaultExport(i18nVFile)

    // ==========================================================================
    // 以下方法已迁移到 EntryFileLocator（见同目录 EntryFileLocator.kt）。
    // Util 作为对外门面，保留原签名并委托给 EntryFileLocator。行为不变。
    // ==========================================================================
    fun isTranslationResourceFile(fileName: String, filePath: String?): Boolean =
        EntryFileLocator.isTranslationResourceFile(fileName, filePath)

    fun isTranslationResourceFile(psiFile: PsiFile): Boolean = EntryFileLocator.isTranslationResourceFile(psiFile)

    fun isTranslationResourceFile(vf: VirtualFile): Boolean = EntryFileLocator.isTranslationResourceFile(vf)

    fun findChineseLocaleEntryFile(project: Project, contextPsiFile: PsiFile?): VirtualFile? =
        EntryFileLocator.findChineseLocaleEntryFile(project, contextPsiFile)

    fun findLocaleFileForLanguage(project: Project, contextPsiFile: PsiFile?, extractor: LanguageExtractor): VirtualFile? =
        EntryFileLocator.findLocaleFileForLanguage(project, contextPsiFile, extractor)

    fun findChineseEntryViaI18nConfig(root: VirtualFile): VirtualFile? =
        EntryFileLocator.findChineseEntryViaI18nConfig(root)

    // ==========================================================================
    // 以下方法已迁移到 TsFileEditor（见同目录 TsFileEditor.kt）。
    // Util 作为对外门面，保留原签名并委托给 TsFileEditor。行为不变。
    // ==========================================================================
    fun parseTsExportedObject(text: String): TsFileEditor.TsExportedObjectInfo? = TsFileEditor.parseTsExportedObject(text)

    fun parseObjectLiteralBody(raw: String): Map<String, Any?> = TsFileEditor.parseObjectLiteralBody(raw)

    fun mergeFlatIntoNested(existingNested: Map<String, Any?>, newFlat: Map<String, String>): Map<String, Any?> =
        TsFileEditor.mergeFlatIntoNested(existingNested, newFlat)

    fun regenerateObjectLiteralBody(oldObjBody: String, mergedNested: Map<String, Any?>): String =
        TsFileEditor.regenerateObjectLiteralBody(oldObjBody, mergedNested)

    fun regenerateTsFileWithNewJson(project: Project, entryVf: VirtualFile, newFlatJson: Map<String, String>): String? =
        TsFileEditor.regenerateTsFileWithNewJson(project, entryVf, newFlatJson)

    fun regenerateJsonFileWithNewJson(entryVf: VirtualFile, newFlatJson: Map<String, String>): String? =
        TsFileEditor.regenerateJsonFileWithNewJson(entryVf, newFlatJson)

    fun regenerateTsFileWithSpreadRouting(project: Project, entryVf: VirtualFile, newFlatJson: Map<String, String>): List<Pair<VirtualFile, String>>? =
        TsFileEditor.regenerateTsFileWithSpreadRouting(project, entryVf, newFlatJson)

    fun writeVirtualFileText(entryVf: VirtualFile, newText: String) = TsFileEditor.writeVirtualFileText(entryVf, newText)

    fun persistEntryPathIfNeeded(project: Project, entryVf: VirtualFile) = TsFileEditor.persistEntryPathIfNeeded(project, entryVf)
}