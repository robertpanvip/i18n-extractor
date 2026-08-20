package com.pan.extractor.core

import com.pan.extractor.lang.SiteKind
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.pan.extractor.planner.RewriteKind
import com.pan.extractor.planner.RewritePlan

/**
 * 收集/注入器访问到的「处理器契约」—— 窄接口。
 *
 * §21 目标：`JsStringCollector` / `ImportManager` 不应接收整个 `I18nProcessor`，
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

    fun containsTargetLanguage(text: String, site: SiteKind): Boolean

    fun extractPureStringContent(text: String): String?

    fun isJSTemplateLiteral(text: String): Boolean

    /**
     * 把一次站点改写登记为纯数据 [RewritePlan] 配方（取代旧的 `recordChange(message, …, closure)`
     * 闭包流）。收集期只描述「要改什么」，不执行任何写；Apply 阶段由编译器统一执行。
     *
     * @param replaceRoot 替换目标根（Analyzer 会据此建 site 与指针）。
     * @param kind        改写类型，决定 Apply 阶段采用哪种 PSI 写入原语。
     * @param newExpression 替换表达式/新文本（收集期已按 framework/tFunctionName 定案）。
     * @param xmlTextPointers XML_TEXT：命中该句的一组 XmlText 节点指针。
     * @return 已登记进 Analyzer 的 [RewritePlan]（含新分配的 siteId）。
     */
    fun recordRewrite(
        message: String,
        replaceRoot: PsiElement,
        anchor: PsiElement,
        kind: RewriteKind,
        newExpression: String,
        xmlTextPointers: List<SmartPsiElementPointer<XmlText>> = emptyList(),
        attributeForm: com.pan.extractor.planner.AttributeRenderForm = com.pan.extractor.planner.AttributeRenderForm.VUE_BINDING,
    ): RewritePlan

    /** 定位 SFC 的 `<script>` 标签；无则返回 null。 */
    fun getScriptTag(): XmlTag?

    fun createStringExpressionNode(text: String, context: PsiElement): PsiElement

    fun createJSStatementFromText(text: String, context: PsiElement): PsiElement

    /** 用文本构造一个 HTML 片段节点（如 `<script setup>`）。 */
    fun createHTMLTagFromText(text: String): PsiElement
}