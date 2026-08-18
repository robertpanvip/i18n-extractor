package com.pan.extractor.rewriter

import com.intellij.psi.PsiElement

/**
 * Rewriter 层 —— 修改源码 PSI（目标架构，迁移自 [com.pan.extractor.I18nProcessor] 的替换动作 /
 * [com.pan.extractor.JsStringCollector] 的 change 构造 / [com.pan.extractor.I18nImportInjector] 的注入）。
 *
 * 职责（PROJECT_ANALYSIS §3）：
 * > Rewriter 根据 Plan 修改源码 PSI；只有最终 Apply 阶段进入 Write Action。
 *
 * 后续迁移：把 recordChange 闭包内的 PSI 改写（createStringExpressionNode / replace /
 * setValue / import 注入）按框架拆分到各 Rewriter，I18nProcessor 只做编排。
 */
interface SourceRewriter {
    /** 把一个候选节点改写为翻译调用表达式。 */
    fun rewriteToTranslationCall(element: PsiElement, expression: String)
}

/** Vue 重写器：XmlText / XmlAttributeValue / 指令绑定。 */
object VueRewriter : SourceRewriter {
    override fun rewriteToTranslationCall(element: PsiElement, expression: String) {
        // TODO(迁移)：I18nProcessor.collectXmlText / collectXmlAttributeValueChange 的替换动作
    }
}

/** React 重写器：JSX 文本 / JSX 属性 / 模板字面量。 */
object ReactRewriter : SourceRewriter {
    override fun rewriteToTranslationCall(element: PsiElement, expression: String) {
        // TODO(迁移)：JSX 分支替换
    }
}

/** JS/TS 重写器：JS 字符串字面量 / 拼接。 */
object JsRewriter : SourceRewriter {
    override fun rewriteToTranslationCall(element: PsiElement, expression: String) {
        // TODO(迁移)：collectJSStringChange 的 JSChangeUtil 替换
    }
}

/** import 重写器：i18n import / hook / 全局 \$t 别名注入（迁移自 I18nImportInjector）。 */
object ImportRewriter : SourceRewriter {
    override fun rewriteToTranslationCall(element: PsiElement, expression: String) {
        // import 注入不是"改写单个节点"，见注入专用方法
    }

    /** 为文件注入 i18n import 语句（TODO(迁移)：I18nImportInjector.ensureI18nInstanceImported）。 */
    fun ensureInstanceImported(file: PsiElement) {
        // TODO(迁移)
    }
}
