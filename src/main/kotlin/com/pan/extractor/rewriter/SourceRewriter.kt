package com.pan.extractor.rewriter

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlText
import com.pan.extractor.I18nPsiTools
import com.pan.extractor.ProjectStructure
import com.pan.extractor.planner.HookInjectPlan
import com.pan.extractor.planner.HookTarget
import com.pan.extractor.planner.ImportPlan
import com.pan.extractor.planner.ImportPlanner

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

/** import 重写器：消费 [ImportPlan] 执行 i18n import / hook / 全局 \$t 别名注入（迁移自 ImportManager 编排层）。 */
object ImportRewriter : SourceRewriter {

    /**
     * 消费 [ImportPlan] 完成一次文件的全部注入。Apply 阶段在 Write Action 内调用。
     *
     * 迁移自 ImportManager 的 ensureI18nInstanceImported / ensureVueI18nImported /
     * ensureReactI18nImported / ensureVueHookI18nImported / ensureVueComponentI18nInjected /
     * ensureSolidUseI18nImported / ensureSolidGlobalDollarTImported 等命令式注入（行为 1:1）。
     *
     * 步骤：
     *  0. 若 [ImportPlan.rewriteI18nTCallsToT]：先把既有 `i18n.t` / `i18n.tc` 改写为 `t`；
     *  1. 注入 imports（.vue 进 <script> 内容，否则进文件顶部）；
     *  2. 注入 aliases（位于最后一个 import 之后）；
     *  3. 依 [HookInjectPlan] 逐类注入函数体 useI18n / useTranslation。
     */
    fun applyImportPlan(processor: com.pan.extractor.I18nProcessor, psiFile: PsiElement, plan: ImportPlan) {
        val containingFile = psiFile.containingFile ?: return

        if (plan.rewriteI18nTCallsToT) {
            processor.injector.rewriteExistingI18nTCallsToDollarT(psiFile)
        }

        val container = if (plan.injectIntoSfcScript)
            getScriptContent(processor, psiFile) ?: return
        else containingFile

        val injector = processor.injector

        // 1) imports
        if (plan.imports.isNotEmpty()) {
            val existing = PsiTreeUtil.findChildrenOfType(container, ES6ImportDeclaration::class.java)
            val anchor = existing.firstOrNull() ?: injector.findFirstNonWhitespaceChild(container)
            injectImports(container, plan.imports, anchor, processor)
        }
        // 2) aliases（放最后一个 import 之后）
        for (alias in plan.aliases) {
            val stmt = processor.createStringExpressionNode(alias, container)
            val latestImports = PsiTreeUtil.findChildrenOfType(container, ES6ImportDeclaration::class.java)
            if (latestImports.isNotEmpty()) {
                val lastImport = latestImports.last()
                lastImport.parent.addAfter(stmt, lastImport)
            } else {
                val firstStatement = injector.findFirstNonWhitespaceChild(container)
                if (firstStatement != null) container.addBefore(stmt, firstStatement) else container.add(stmt)
            }
        }
        // 3) hooks
        for (hook in plan.hooks) {
            injectHook(processor, psiFile, hook)
        }
    }

    /**
     * 单点注入入口：由 [ImportPlanner.buildImportPlan] 把 collect 阶段锁定的注入决策转为 [ImportPlan]，
     * 再由 [applyImportPlan] 统一执行。此方法取代旧 ImportManager.injectForFramework 的 when 编排
     * （行为 1:1）。
     */
    fun injectForFramework(
        processor: com.pan.extractor.I18nProcessor,
        psiFile: PsiElement,
        framework: com.pan.extractor.I18nFramework,
        decision: com.pan.extractor.ImportManager.InjectionDecision,
    ) {
        val plan = ImportPlanner.buildImportPlan(processor, psiFile, framework, decision)
        applyImportPlan(processor, psiFile, plan)
    }

    // ── 执行辅助 ─────────────────────────────────────────────────────────

    /** 取 .vue <script> 的内容节点（JSEmbeddedContent）；无则返回 null。 */
    private fun getScriptContent(
        processor: com.pan.extractor.I18nProcessor,
        psiFile: PsiElement,
    ): PsiElement? {
        val scriptTag = processor.getScriptTag() ?: return null
        return PsiTreeUtil.findChildOfType(scriptTag, JSEmbeddedContent::class.java)
    }

    /**
     * 以「不含内嵌换行的独立 Leaf」把 [importTexts] 注入到 [container]，置于首个非空白语句 [anchor]
     * 之前，保持语句顺序。语句正文与 `\n` 分开成两个节点注入，避免单 Leaf 内嵌换行在 undo/redo
     * 重排时被重新归一为空格（镜像旧 ensure* 的逐行 Leaf 空白幂等约定，P0/P1）。
     */
    private fun injectImports(
        container: PsiElement,
        importTexts: List<String>,
        anchor: PsiElement?,
        processor: com.pan.extractor.I18nProcessor,
    ) {
        var prev: PsiElement? = null
        for (text in importTexts) {
            val hasLineBreak = text.endsWith("\n")
            val body = if (hasLineBreak) text.removeSuffix("\n") else text
            val leaf = processor.createStringExpressionNode(body, container)
            prev = when {
                prev != null -> container.addAfter(leaf, prev)
                anchor != null -> container.addBefore(leaf, anchor)
                else -> container.add(leaf)
            }
            if (hasLineBreak) {
                prev = container.addAfter(processor.createStringExpressionNode("\n", container), prev)
            }
        }
    }

    /** 依 [hook] 类型定位目标并注入 [HookInjectPlan.statement]。 */
    private fun injectHook(
        processor: com.pan.extractor.I18nProcessor,
        psiFile: PsiElement,
        hook: HookInjectPlan,
    ) {
        when (hook.target) {
            HookTarget.VUE_SFC_SCRIPT -> injectVueSfcScriptConst(processor, psiFile, hook.statement)
            HookTarget.VUE_HOOK -> injectIntoFunctionBodies(
                processor, psiFile,
                ProjectStructure.findHookFunctions(psiFile.containingFile).filterIsInstance<JSFunction>(),
                hook.statement,
            )
            HookTarget.VUE_COMPONENT -> injectIntoVueComponents(processor, psiFile, hook.statement)
            HookTarget.REACT, HookTarget.SOLID -> {
                val file = psiFile.containingFile ?: return
                val funcs = (ProjectStructure.findReactComponentFunctions(file).asSequence() +
                    ProjectStructure.findHookFunctions(file).asSequence())
                    .filterIsInstance<JSFunction>().distinct().toList()
                injectIntoFunctionBodies(processor, psiFile, funcs, hook.statement)
            }
        }
    }

    /** Vue .vue <script> 顶层注入一次 `const { t: \$t } = useI18n()`（镜像 ensureVueI18nImported 的 const 分支）。 */
    private fun injectVueSfcScriptConst(
        processor: com.pan.extractor.I18nProcessor,
        psiFile: PsiElement,
        statement: String,
    ) {
        val scriptContent = getScriptContent(processor, psiFile) ?: return
        val injector = processor.injector
        if (injector.scopeHasDestructuredCall(scriptContent, callee = "useI18n", destructureNameFrom = "t", destructureAlias = "\$t")) {
            return
        }
        val constNode = processor.createStringExpressionNode(statement, psiFile)
        // 保持 ensureVueI18nImported 的投放：优先在最后一个 import 之后；无 import 时首位 + 换行。
        val importStatements = PsiTreeUtil.findChildrenOfType(scriptContent, ES6ImportDeclaration::class.java)
        if (importStatements.isEmpty()) {
            val added = scriptContent.addAfter(constNode, scriptContent.firstChild)
            scriptContent.addAfter(processor.createStringExpressionNode("\n", psiFile), added)
        } else {
            val lastImport = importStatements.last()
            lastImport.parent.addAfter(constNode, lastImport)
        }
    }

    /** 给一组函数体首行注入 [statement]（从后往前插入，避免 offset 偏移；镜像 ensure* 的逐体注入）。 */
    private fun injectIntoFunctionBodies(
        processor: com.pan.extractor.I18nProcessor,
        psiFile: PsiElement,
        funcs: List<JSFunction>,
        statement: String,
    ) {
        for (func in funcs.asReversed()) {
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.any { it.text.contains("useTranslation") || it.text.contains("useI18n") }) continue
            val hookStmt = processor.createJSStatementFromText("\n    $statement", func)
            val openingBrace = body.firstChild ?: continue
            body.addAfter(hookStmt, openingBrace)
        }
    }

    /** Vue TSX 组件（defineComponent / setup / 函数式组件）函数体注入（镜像 ensureVueComponentI18nInjected）。 */
    private fun injectIntoVueComponents(
        processor: com.pan.extractor.I18nProcessor,
        psiFile: PsiElement,
        statement: String,
    ) {
        val file = psiFile.containingFile ?: return
        val targetBodies = mutableListOf<JSBlockStatement>()
        for (cand in ProjectStructure.findVueComponentFunctions(file)) {
            when {
                cand is JSFunction -> PsiTreeUtil.findChildOfType(cand, JSBlockStatement::class.java)?.let(targetBodies::add)
                cand is JSCallExpression -> {
                    val setupProp = PsiTreeUtil.findChildrenOfType(cand, JSProperty::class.java)
                        .firstOrNull { it.name == "setup" } ?: continue
                    val setupFunc = PsiTreeUtil.findChildOfType(setupProp, JSFunction::class.java)
                    val body: JSBlockStatement =
                        (if (setupFunc != null) findDirectBlockIn(setupFunc) else null)
                            ?: findDirectBlockIn(setupProp) ?: continue
                    targetBodies.add(body)
                }
            }
        }
        for (body in targetBodies.asReversed()) {
            val existingVars = PsiTreeUtil.findChildrenOfType(body, JSVarStatement::class.java)
            if (existingVars.any { it.text.contains("useI18n") }) continue
            val hookStmt = processor.createJSStatementFromText("\n    $statement", body)
            val openingBrace = body.firstChild ?: continue
            body.addAfter(hookStmt, openingBrace)
        }
    }

    /** 从 [ancestor] 的第一层直接后代路径里取 JSBlockStatement（避免深入 setup 内嵌回调）。 */
    private fun findDirectBlockIn(ancestor: PsiElement): JSBlockStatement? {
        for (child in ancestor.children) {
            if (child is JSBlockStatement) return child
            val found = findDirectBlockIn(child)
            if (found != null) return found
        }
        return null
    }
}
