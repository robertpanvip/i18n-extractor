package com.pan.extractor.model

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement

/**
 * 一次 i18n 提取的输入封装（§21 目标形态）。
 *
 * 中央调度 [I18nProcessor.extract] / [I18nProcessor.apply] 都基于本对象传参，
 * 而非把整个 [com.pan.extractor.I18nProcessor] 作为回调宿主到处传递。
 * 未来独立的 `I18nAnalyzer` / `ImportManager` 也依赖本对象（而非 processor）取输入，
 * 从而打破「收集/注入器反向依赖 processor」的循环，真正的业务收敛才有落点。
 */
class ExtractionContext(
    val project: Project,
    val psiFile: PsiElement,
)