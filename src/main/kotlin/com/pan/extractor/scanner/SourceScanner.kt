package com.pan.extractor.scanner

import com.intellij.psi.PsiElement

/**
 * Scanner 层 —— 候选站点发现（目标架构，迁移自 [com.pan.extractor.I18nProcessor.collectFromPsi] /
 * [com.pan.extractor.JsStringCollector] 的收集入口）。
 *
 * 职责（PROJECT_ANALYSIS §3）：
 * > Scanner 只负责发现候选 PSI 节点，不负责修改、不负责最终语义判断。
 *
 * 每个框架一个 Scanner（Vue / React / Solid / JS 通用），通过遍历 PSI 树找出
 * 值得分析的字面量 / 文本 / 属性 / 模板节点，交给 Analyzer 判定是否提取。
 * 后续迁移：I18nProcessor.collectFromPsi 的 XmlText / XmlAttributeValue /
 * JSLiteralExpression / JSBinaryExpression 四类遍历分别对应 Vue/React/JS Scanner。
 */
interface SourceScanner {
    /** 扫描 [root] 所在文件，发现候选节点。 */
    fun scan(root: PsiElement, sink: (PsiElement) -> Unit)
}

/** Vue 扫描器：XmlText（mustache/纯文本）、XmlAttributeValue（指令/属性）、script 内 JS 字面量。 */
object VueScanner : SourceScanner {
    override fun scan(root: PsiElement, sink: (PsiElement) -> Unit) {
        // TODO(迁移)：I18nProcessor.collectFromPsi 的 Vue 分支（XmlText + XmlAttributeValue + 注入 JS）
    }
}

/** React 扫描器：JSX 文本、JSX 属性、模板字面量、script 内 JS 字面量。 */
object ReactScanner : SourceScanner {
    override fun scan(root: PsiElement, sink: (PsiElement) -> Unit) {
        // TODO(迁移)：I18nProcessor.collectFromPsi 的 React/JSX 分支
    }
}

/** Solid 扫描器：与 React 同形态（PascalCase 组件 + JSX），复用遍历逻辑。 */
object SolidScanner : SourceScanner {
    override fun scan(root: PsiElement, sink: (PsiElement) -> Unit) {
        // TODO(迁移)：复用 React 遍历（Solid 站点形态由 FrameworkAnalyzer 区分）
    }
}

/** 通用 JS/TS 扫描器：JSLiteralExpression / JSBinaryExpression（纯字符串拼接）。 */
object JsScanner : SourceScanner {
    override fun scan(root: PsiElement, sink: (PsiElement) -> Unit) {
        // TODO(迁移)：I18nProcessor.collectFromPsi 的 JS 字面量 + 拼接分支
    }
}
