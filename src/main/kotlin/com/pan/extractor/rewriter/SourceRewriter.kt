package com.pan.extractor.rewriter

import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.pan.extractor.I18nPsiTools

/**
 * Rewriter 层 —— 修改源码 PSI（迁移自 [com.pan.extractor.I18nProcessor] /
 * [com.pan.extractor.JsStringCollector] 的 recordChange 替换闭包）。
 *
 * 职责（PROJECT_ANALYSIS §3）：
 * > Rewriter 根据 Plan 修改源码 PSI；只有最终 Apply 阶段进入 Write Action。
 *
 * 所有方法只做「把一个已定案的表达式写入 PSI」，不负责决定提取 / 替换策略——
 * 策略（key 生成、占位符、是否 $t 包裹）由 Processor / Analyzer 层决定。
 * 迁移后原 recordChange 闭包改为调用此处方法，行为 1:1。
 */
interface SourceRewriter

/** Vue 重写器：XmlText 文本节点 / XmlAttributeValue 属性。 */
object VueRewriter : SourceRewriter {

    /**
     * 把一段（可能由多个仅被空白分隔的 XmlText 节点组成的）纯文本替换为翻译表达式。
     * 迁移自 I18nProcessor.collectTemplateTextChange 的 recordChange 闭包：
     * 第一个有效 token 替换为 [newContent]（如 `{{ $t('key') }}`），其余 token 删除。
     */
    fun rewriteXmlTextNodes(nodes: List<XmlText>, newContent: String) {
        var firstToken = true
        for (node in nodes) {
            if (!node.isValid) continue
            val textChild = I18nPsiTools.getCharactersText(node)
            val tokens = textChild.ifEmpty { listOf(node) }
            for (token in tokens) {
                if (!token.isValid) continue
                if (firstToken) {
                    val newElement = I18nPsiTools.createStringExpressionNode(newContent, token)
                    token.replace(newElement)
                    firstToken = false
                } else {
                    token.delete()
                }
            }
        }
    }

    /**
     * 把一个 XmlAttributeValue 改写为翻译调用表达式。
     * 迁移自 I18nProcessor.collectXmlAttributeValueChange 的 recordChange 闭包：
     *  - 非 JSX：`attr.setValue("${quote}${newText}${endQuote}")` + 补 `:` 前缀（Vue 绑定）；
     *  - JSX：`attr.setValue("{ $t(...) }")`（大括号表达式）。
     *
     * @param isJSX JSX 属性（大括号表达式形态）
     * @param isDirective Vue 指令（`:title` 等，不加 `:` 前缀）
     */
    fun rewriteAttribute(attrValue: XmlAttributeValue, newText: String, isJSX: Boolean, isDirective: Boolean) {
        val attr = attrValue.parent as? XmlAttribute ?: return
        var quote = if (attrValue.text.startsWith('"')) "" else "'"
        val prefix = if (isJSX || isDirective) "" else ":"
        var endQuote = quote
        if (isJSX) {
            quote = "{"
            endQuote = "}"
        }
        attr.setValue("${quote}${newText}${endQuote}")
        attr.name = "${prefix}${attr.name}"
    }
}

/** React 重写器：JSX 文本 / JSX 属性（当前与 Vue 共用 XmlText/Attribute 重写，保留形态占位）。 */
object ReactRewriter : SourceRewriter

/** Solid 重写器：与 React 同形态（当前占位）。 */
object SolidRewriter : SourceRewriter

/** JS/TS 重写器：JS 字符串字面量 / 模板 / 拼接的表达式替换。 */
object JsRewriter : SourceRewriter {

    /**
     * 用 JSChangeUtil 解析 [newExprText] 并替换 [element]。
     * 迁移自 JsStringCollector.collectJSStringChange 的 recordChange 闭包。
     */
    fun rewriteLiteral(element: PsiElement, newExprText: String, project: Project) {
        val newExpr = JSChangeUtil.tryCreateExpressionFromText(project, newExprText, null, false)
        if (newExpr != null) {
            element.replace(newExpr.psi)
        }
    }

    /**
     * 用纯文本 LeafPsiElement 替换 [element]（保留原始文本，无语法解析）。
     * 迁移自 JsStringCollector.collectJSStringTemplate 的 recordChange 闭包。
     */
    fun rewriteWithStringNode(element: PsiElement, text: String) {
        val newElement = I18nPsiTools.createStringExpressionNode(text, element)
        element.replace(newElement)
    }
}

/** import 重写器：i18n import / hook / 全局 \$t 别名注入（迁移自 I18nImportInjector 的编排层）。 */
object ImportRewriter : SourceRewriter {

    /** 为文件注入 i18n import 语句（TODO(迁移)：I18nImportInjector.ensureI18nInstanceImported 系）。 */
    fun ensureInstanceImported(file: PsiElement) {
        // TODO(迁移)
    }

    /**
     * P2 统一注入入口：按 [framework] 分发到 Vue/React/Solid 注入逻辑。
     * 迁移自 [com.pan.extractor.I18nImportInjector.injectForFramework] 的 when 编排（行为 1:1）。
     *
     * Solid 不再复用 React 分支——`@solid-primitives/i18n` 的 `useI18n(dict, () => locale)`
     * 与 react-i18next 的 `useTranslation()` API 形态完全不同（前者返回 `[t, { locale }]`，
     * 后者返回 `{ t }`），复用会注入错误的 import。Solid 走独立的 Solid 分支。
     *
     * 具体分支实现暂留在 [com.pan.extractor.I18nImportInjector]（internal 方法），
     * 后续按模块继续内迁。
     */
    fun injectForFramework(
        processor: com.pan.extractor.I18nProcessor,
        psiFile: PsiElement,
        framework: com.pan.extractor.I18nFramework,
        decision: com.pan.extractor.I18nImportInjector.InjectionDecision,
    ) {
        when (framework) {
            is com.pan.extractor.VueI18nStrategy ->
                processor.injector.injectVueBranch(processor, psiFile, decision)
            is com.pan.extractor.ReactI18nextStrategy ->
                processor.injector.injectReactBranch(processor, psiFile, decision)
            is com.pan.extractor.SolidI18nStrategy ->
                processor.injector.injectSolidBranch(processor, psiFile, decision)
            else -> { /* Generic 不注入 */ }
        }
    }
}
