package com.pan.extractor

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlTag

/**
 * 收集/注入器访问到的「处理器契约」—— 窄接口。
 *
 * §21 目标：`JsStringCollector` / `I18nImportInjector` 不应接收整个 `I18nProcessor`，
 * 而应只依赖它们实际会用到的能力面。本接口即该能力面；[I18nProcessor] 实现它，
 * 两个收集器改为持有本接口（§21.4 第 3 步 · 拆宿主的第一步：接口隔离）。
 *
 * 后续把收集业务收敛进 `I18nAnalyzer`、注入业务收敛进 `ImportManager` 时，
 * 只要这些组件实现/依赖本面，`I18nProcessor` 即可被真正替换掉而不改收集器。
 */
interface I18nProcessorContract {
    val project: Project

    /** 模板字面量 `${...}` 的匹配正则（对象级复用）。 */
    val templateVarRegex: Regex

    /** 本次收集到的 key → 中文 资源映射（跨多次 collect 累积但每次 start 重置）。 */
    val extractedStrings: MutableMap<String, String>

    /** 探测到的翻译函数名（`t` / `$t` / `tc` …），写入替换文本时使用。 */
    var tFunctionName: String

    fun containsTargetLanguage(text: String, site: SiteKind): Boolean

    fun extractPureStringContent(text: String): String?

    fun isJSTemplateLiteral(text: String): Boolean

    fun recordChange(
        message: String,
        replaceRoot: PsiElement,
        anchor: PsiElement,
        changes: MutableList<I18nProcessor.CollectedChange>,
        replaceAction: () -> Unit,
    )

    /** 定位 SFC 的 `<script>` 标签；无则返回 null。 */
    fun getScriptTag(): XmlTag?

    fun createStringExpressionNode(text: String, context: PsiElement): PsiElement

    fun createJSStatementFromText(text: String, context: PsiElement): PsiElement

    /** 用文本构造一个 HTML 片段节点（如 `<script setup>`）。 */
    fun createHTMLTagFromText(text: String): PsiElement
}