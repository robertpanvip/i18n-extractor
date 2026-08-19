package com.pan.extractor.scanner

import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.pan.extractor.I18nPsiTools

/**
 * Scanner 层 —— 候选站点发现（迁移自 [com.pan.extractor.I18nProcessor.collectFromPsi] 的
 * PsiRecursiveElementWalkingVisitor 遍历）。
 *
 * 职责（PROJECT_ANALYSIS §3）：
 * > Scanner 只负责发现候选 PSI 节点，不负责修改、不负责最终语义判断。
 *
 * [SourceScanner.scan] 遍历 PSI 树，把**值得分析**的候选节点交给 [sink] 回调
 * （XmlText / XmlAttributeValue / JSLiteralExpression / JSBinaryExpression），
 * 并做与旧实现 1:1 的过滤：
 *  - XmlText / XmlAttributeValue：跳过 style 标签内与注释内的节点
 *    （[I18nPsiTools.isInStyleOrComment]）；
 *  - JSLiteralExpression / JSBinaryExpression：跳过注释内的节点
 *    （[I18nPsiTools.isInComment]）。
 *
 * 是否提取 / 是否翻译调用等最终语义判断由 Analyzer 层（TranslationAnalyzer /
 * StringAnalyzer）负责，Scanner 不决策。
 */
interface SourceScanner {
    /**
     * 扫描 [root] 所在文件，发现候选节点并交给 [sink]。
     * 调用方（I18nProcessor）在 sink 内按节点类型执行收集逻辑。
     */
    fun scan(root: PsiElement, sink: (PsiElement) -> Unit)
}

/**
 * 模板 + JS 统一遍历（Vue 模板 / React JSX / Solid JSX / 纯 JS/TS 共用同一 PSI 遍历形态）。
 * 框架差异（占位符、站点形态、注入）由 [com.pan.extractor.I18nFramework] / Analyzer 层区分，
 * Scanner 只做节点发现 —— 与原 collectFromPsi 对所有文件类型遍历同一组节点类型行为 1:1 等价。
 */
abstract class NodeScanner : SourceScanner {
    override fun scan(root: PsiElement, sink: (PsiElement) -> Unit) {
        root.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                when (element) {
                    is XmlText -> if (!I18nPsiTools.isInStyleOrComment(element)) sink(element)
                    is XmlAttributeValue -> if (!I18nPsiTools.isInStyleOrComment(element)) sink(element)
                    is JSLiteralExpression -> if (!I18nPsiTools.isInComment(element)) sink(element)
                    is JSBinaryExpression -> if (!I18nPsiTools.isInComment(element)) sink(element)
                }
                super.visitElement(element)
            }
        })
    }
}

/** Vue 扫描器：.vue SFC 模板 + script 的候选节点。 */
object VueScanner : NodeScanner()

/** React 扫描器：.tsx/.jsx（JSX 文本走 XmlText，属性走 XmlAttributeValue）。 */
object ReactScanner : NodeScanner()

/** Solid 扫描器：与 React 同形态（PascalCase 组件 + JSX）。 */
object SolidScanner : NodeScanner()

/** Svelte 扫描器：.svelte SFC 模板（HTML 属性 + 文本）与 script 的候选节点，与 Vue 同形态。 */
object SvelteScanner : NodeScanner()

/** Angular 扫描器：.html 模板（HTML 属性 + 文本）与 component .ts 的候选节点。 */
object AngularScanner : NodeScanner()

/** 通用 JS/TS 扫描器：纯 .ts/.js 文件（无模板节点，自然只命中 JS 字面量/拼接）。 */
object JsScanner : NodeScanner()
