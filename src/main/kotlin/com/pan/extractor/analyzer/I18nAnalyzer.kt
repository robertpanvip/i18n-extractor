package com.pan.extractor.analyzer

import com.pan.extractor.*
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.*
import com.pan.extractor.analyzer.TranslationCallStatus

/**
 * Analyzer 层 —— 一次提取的「收集 + 语义分析」宿主（目标架构 Phase 1，PROJECT_ANALYSIS §21.4 第 3 步）。
 *
 * 承载原本内联在 [I18nProcessor] 的收集期状态与收集业务：
 *  - 拥有收集期产物容器 [plan]（[CollectedPlan]），是 collectedSites / extractedStrings /
 *    existingStrings / tFunctionName / framework / needInject* / pendingChanges 的唯一事实来源；
 *  - 实现 `collectFromPsi` / `collectExistingTKeys`（**Scanner/Analyzer 段**），只做语义判断，
 *    不修改项目；所有改写动作仍以 [I18nProcessor.CollectedChange] 的姿态收集，交给 Rewriter 期执行。
 *
 * 原则（PROJECT_ANALYSIS §4 / §21.2）：
 * > 分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。
 *
 * 依赖注入：通过 [contract]（[I18nProcessorContract]）获取模板/字符串原语与项目对象；通过
 * [jsCollector] / [injector] 复用字符串收集与 import 判断。自身不反向依赖 [I18nProcessor] 的业务。
 */
class I18nAnalyzer(
    private val project: Project,
    private val contract: I18nProcessorContract,
    private val jsCollector: JsStringCollector,
    private val injector: ImportManager,
) : com.pan.extractor.CollectionState {
    /** 收集期产物容器（本 analyzer 拥有的唯一可变状态，reset 时整体替换清零）。 */
    private var plan = com.pan.extractor.planner.CollectedPlan()

    /** 从原始文本提取 $t/$tc/i18n.global.t 等调用（模板里 backtick 场景），对象级复用避免重复编译。
     *  BUG_ANALYSIS 3.4：去掉 [^
"'] 中的 \n 排除，支持跨行调用如
     *  $t(\n  'hello'\n) 和 i18n\n  .global\n  .t('hello') */
    private val T_CALL_PATTERN =
        Regex("(?:\\$(?:t|tc)|i18n\\.global\\.(?:t|tc)|i18n\\.(?:t|tc))\\(\\s*([`\"'])([^`\"']+)\\1\\s*[,)]")

    // ─────────────────────────────────────────────────────────────
    // 状态访问（供 I18nProcessor 薄转发 + 编排器/下游消费，禁止外部直接改 plan 之外的散落字段）
    // ─────────────────────────────────────────────────────────────

    /** 一次提取命中站点列表（领域模型 site）。 */
    val collectedSites: MutableList<com.pan.extractor.model.ExtractionSite> get() = plan.collectedSites

    /** 被骨架合并承载、应跳过普通替换的 siteId 集合。 */
    val blockedSiteIds: MutableSet<String> get() = plan.blockedSiteIds

    /** 待应用的重写动作（collect 阶段收集，run 阶段逐个执行）。 */
    var pendingChanges: MutableList<I18nProcessor.CollectedChange>
        get() = plan.pendingChanges
        set(value) {
            plan.pendingChanges.clear()
            plan.pendingChanges.addAll(value)
        }

    /** 新提取的 key -> 原文本。 */
    override val extractedStrings: MutableMap<String, String> get() = plan.extractedStrings

    /** 已存在的 $t() 调用 key -> 原文本（仅展示，不替换）。 */
    val existingStrings: MutableMap<String, String> get() = plan.existingStrings

    /** 检测到的翻译函数名（$t / t / i18n.t / i18n.global.t），默认 $t。 */
    override var tFunctionName: String
        get() = plan.tFunctionName
        set(value) { plan.tFunctionName = value }

    /** 当前文件检测到的框架策略。 */
    var framework: I18nFramework
        get() = plan.framework ?: GenericStrategy
        set(value) { plan.framework = value }

    var needInjectGlobalDollarT: Boolean
        get() = plan.needInjectGlobalDollarT
        set(value) { plan.needInjectGlobalDollarT = value }
    var needInjectReactGlobalDollarT: Boolean
        get() = plan.needInjectReactGlobalDollarT
        set(value) { plan.needInjectReactGlobalDollarT = value }
    var needInjectSolidGlobalDollarT: Boolean
        get() = plan.needInjectSolidGlobalDollarT
        set(value) { plan.needInjectSolidGlobalDollarT = value }
    var reactI18nTFallbackToDollarT: Boolean
        get() = plan.reactI18nTFallbackToDollarT
        set(value) { plan.reactI18nTFallbackToDollarT = value }
    private var reactFallbackChecked: Boolean
        get() = plan.reactFallbackChecked
        set(value) { plan.reactFallbackChecked = value }
    private var reactFallbackResult: Boolean
        get() = plan.reactFallbackResult
        set(value) { plan.reactFallbackResult = value }

    private var siteCounter: Int
        get() = plan.siteCounter
        set(value) { plan.siteCounter = value }
    private fun nextSiteId(): String = "S${++siteCounter}"

    /** 重置所有收集期状态（[CollectedPlan] 整体替换），保证 collect() 幂等可重复执行（BUG_ANALYSIS 4.1）。 */
    fun resetState() {
        plan = com.pan.extractor.planner.CollectedPlan()
        // P0：JsStringCollector 内的 processedEnums（去重通知的父节点集合）也必须在 collect 间清空，
        // 否则单文件重复 collect() 会因集合泄漏而不再触发后续的枚举跳过通知。
        jsCollector.clearProcessedEnums()
    }

    // ─────────────────────────────────────────────────────────────
    // 统一登记 site + 包装 change
    // ─────────────────────────────────────────────────────────────
    fun recordChange(
        message: String,
        replaceRoot: PsiElement,
        anchor: PsiElement,
        changes: MutableList<I18nProcessor.CollectedChange>,
        replaceAction: () -> Unit,
    ) {
        val id = nextSiteId()
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(replaceRoot)
        val f = (anchor.containingFile ?: (anchor as? PsiFile))
        // P7：站点形态由策略判定（O(1) 框架级常量），替代原 Util.isVue/isReact 二分。
        val form = framework.getSiteForm(anchor)
        val isVue = form == SiteForm.VUE_BINDING || form == SiteForm.VUE_MUSTACHE
        val isReact = !isVue && (form == SiteForm.JSX_ATTRIBUTE || form == SiteForm.TEMPLATE_LITERAL)
        val vf = f?.virtualFile
        collectedSites += com.pan.extractor.model.ExtractionSite(
            id = id,
            originalMessage = message.trim(),
            replaceRoot = ptr,
            location = com.pan.extractor.model.ExtractionSiteLocation(
                containingFile = vf,
                startLine = computeStartLine(ptr, vf),
            ),
            isVue = isVue,
            isReact = isReact,
            form = form,
        )
        changes += I18nProcessor.CollectedChange(id, replaceAction)
    }

    /** 给 Dialog/摘要展示用的起始行（1 基）。只读、失败返回 1。 */
    private fun computeStartLine(
        pointer: SmartPsiElementPointer<PsiElement>,
        file: VirtualFile?,
    ): Int = runCatching {
        val e = pointer.element ?: return@runCatching 1
        val doc = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
            .getDocument(file ?: return@runCatching 1) ?: return@runCatching 1
        val range = e.textRange ?: return@runCatching 1
        if (range.startOffset < 0 || range.startOffset > doc.textLength) return@runCatching 1
        doc.getLineNumber(range.startOffset) + 1
    }.getOrDefault(1)

    // ─────────────────────────────────────────────────────────────
    // Scanner/Analyzer 段：现有翻译 key + 候选发现
    // ─────────────────────────────────────────────────────────────
    /**
     * 扫描文件中已有的 $t() / t() / i18n.global.t() / i18n.t() 调用，收集其 key 到 existingStrings。
     * 覆盖模板注入 JS 和 script/JS/TS 两种来源。与 [I18nProcessor.collectExistingTKeys] 行为 1:1。
     */
    fun collectExistingTKeys(root: PsiElement) {
        val calls = PsiTreeUtil.findChildrenOfType(root, JSCallExpression::class.java)
        calls.forEach { call -> detectTFunctionName(call, root) }

        // 模板 {{ }} 中的注入 JS（Vue 专属，React/Solid/Generic 默认空实现）
        framework.collectExistingTKeysFromTemplate(
            root = root,
            onCall = { call ->
                collectTKeyFromCall(call)
                detectTFunctionName(call, root)
            },
            onRawText = { text -> collectTKeysFromRawText(text) },
        )

        calls.forEach { call -> collectTKeyFromCall(call) }
    }

    private fun collectTKeysFromRawText(text: String) {
        T_CALL_PATTERN.findAll(text).forEach { match ->
            val content = match.groupValues[2]
            existingStrings.putIfAbsent(content.trim(), content.trim())
        }
    }

    private fun collectTKeyFromCall(call: JSCallExpression) {
        val firstArg = call.arguments.firstOrNull() ?: return
        val text = I18nPsiTools.extractStringArgText(firstArg) ?: return
        val key = text.trim()
        val analyzed = TranslationAnalyzer.analyzeCall(call)
        when (analyzed.status) {
            TranslationCallStatus.TRANSLATION -> existingStrings.putIfAbsent(key, text.trim())
            TranslationCallStatus.NON_TRANSLATION,
            TranslationCallStatus.UNKNOWN,
            -> { /* 交给提取 / 保守跳过 */ }
        }
    }

    private fun detectTFunctionName(call: JSCallExpression, root: PsiElement) {
        if (needInjectGlobalDollarT || needInjectReactGlobalDollarT || needInjectSolidGlobalDollarT) {
            tFunctionName = if (needInjectReactGlobalDollarT) "t" else "\$t"
            return
        }
        val detected = framework.detectExistingTFunctionName(call) ?: return
        when (detected) {
            "i18n.global.t" -> tFunctionName = "i18n.global.t"
            "i18n.t" -> {
                if (tFunctionName != "\$t") return
                val defaultShort = framework.tFunctionName
                if (defaultShort == "t") {
                    tFunctionName = "t"
                } else if (reactFallsBackToGetI18n(root)) {
                    reactI18nTFallbackToDollarT = true
                    tFunctionName = "t"
                } else {
                    tFunctionName = "i18n.t"
                }
            }
        }
    }

    /**
     * React 文件 + 无任何 i18n 实例导入 + locale 初始化不可用 → 需要回退 getI18n。
     * 结果在 collect 阶段只算一次（避免对每个 i18n.t 调用都重复走项目目录扫描）。
     */
    private fun reactFallsBackToGetI18n(root: PsiElement): Boolean {
        if (reactFallbackChecked) return reactFallbackResult
        reactFallbackChecked = true
        reactFallbackResult = run {
            val rootFile = (root.containingFile ?: (root as? PsiFile))
            if (rootFile == null) return@run false
            // 与 I18nProcessor.reactFallsBackToGetI18n 保持 1:1：Vue 文件 / Vue 框架直接不回退。
            if (I18nPsiTools.isVueFile(rootFile) || framework is VueI18nStrategy) return@run false
            if (framework !is ReactI18nextStrategy) return@run false
            // 已有 i18n 实例导入（locale / ./i18n / i18next / getI18n）→ 直接用，不需回退
            if (injector.hasI18nInstanceImported(root)) return@run false
            // locale 初始化文件导出了 i18n 且路径可推断 → 走 locale，不回退
            val projectRoot = ProjectStructure.findProjectRoot(rootFile) ?: return@run true
            val initFile = I18nInstanceLocator.findReactI18nInstanceFileInRoot(projectRoot, project)
            if (initFile != null && I18nInstanceLocator.resolveVueI18nImportPath(rootFile, initFile) != null) return@run false
            true
        }
        return reactFallbackResult
    }

    // ─────────────────────────────────────────────────────────────
    // Scanner/Analyzer 段：候选发现（collectFromPsi）
    // ─────────────────────────────────────────────────────────────
    /** 从 [root] 发现候选节点并收集改写，返回待应用改写列表。与 [I18nProcessor.collectFromPsi] 行为 1:1。 */
    fun collectFromPsi(root: PsiElement): MutableList<I18nProcessor.CollectedChange> {
        val changes = mutableListOf<I18nProcessor.CollectedChange>()

        fun handle(element: PsiElement) {
            when (element) {
                is XmlText -> {
                    if (I18nPsiTools.isMustache(element.text)) {
                        collectXmlText(element, changes)
                    } else {
                        val run = I18nPsiTools.collectTextRun(element)
                        if (run.first() === element) {
                            collectTemplateTextChange(run, changes)
                        }
                    }
                }
                is XmlAttributeValue -> collectXmlAttributeValueChange(element, changes)
                is JSLiteralExpression -> jsCollector.collectJSStringChange(element, changes)
                is JSBinaryExpression -> jsCollector.collectJSBinaryExpressionChange(element, changes)
            }
        }

        // §11 收敛点：扫描器分发已下沉到框架策略（framework.scanner，
        // Vue/React/Solid/Generic 各自声明单例 Scanner），消除原 is Vue/React/Solid 三岔。
        framework.scanner.scan(root) { handle(it) }
        return changes
    }

    private fun collectXmlText(element: PsiElement, changes: MutableList<I18nProcessor.CollectedChange>) {
        if (I18nPsiTools.isComment(element)) return

        val quote = "`"
        val children = element.children.filter { it !is PsiWhiteSpace && !I18nPsiTools.isComment(it) && it !is PsiComment }
        if (children.isEmpty()) return

        val sb = StringBuilder()
        children.forEachIndexed { index, e ->
            val text = I18nPsiTools.rm(e)
            when (index) {
                0 -> sb.append(quote).append(text)
                children.lastIndex -> sb.append(text).append(quote)
                else -> sb.append(text)
            }
        }
        if (children.size == 1) sb.append(quote)
        val raw = sb.toString().trim()

        val compactRaw = raw.replace(Regex("\\s"), "")
        if (compactRaw.startsWith("`\${\$t(")) {
            return
        }

        val injected = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(element)
        if (injected != null && injected.isNotEmpty()) {
            var foundStrings = false
            injected.forEach { pair ->
                pair.first.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(e: PsiElement) {
                        if (e is JSLiteralExpression && !I18nPsiTools.isInComment(e)) {
                            if (PsiTreeUtil.getParentOfType(e, com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression::class.java) == null) {
                                if (!jsCollector.isTransformedCalled(e)) {
                                    jsCollector.collectJSStringChange(e, changes)
                                    foundStrings = true
                                }
                            }
                        }
                        if (e is JSBinaryExpression && !I18nPsiTools.isInComment(e)) {
                            val sizeBefore = changes.size
                            jsCollector.collectJSBinaryExpressionChange(e, changes)
                            if (changes.size > sizeBefore) foundStrings = true
                        }
                        super.visitElement(e)
                    }
                })
            }
            if (foundStrings) return
            if (raw.contains("\$t(") || raw.contains("i18n.global.t(") || raw.contains("i18n.t(")) return
            val contentOnly = raw.substring(1, raw.length - 1).trim()
            val strippedContent = contentOnly.replace(Regex("\\$\\{[^}]*\\}"), "").trim()
            if (strippedContent.startsWith("//") || (strippedContent.startsWith("/*") && strippedContent.endsWith("*/"))) return
        }

        jsCollector.collectJSStringTemplate(raw, changes, element) { value -> "{{${value}}}" }
    }

    private fun collectTemplateTextChange(nodes: List<XmlText>, changes: MutableList<I18nProcessor.CollectedChange>) {
        val first = nodes.first()
        val pureText = nodes.joinToString(" ") { jsCollector.getPureXmlText(it) }.trim()
        if (pureText.isEmpty()) return
        if (pureText.contains("\$t(") || pureText.contains("i18n.global.t(") || pureText.contains("i18n.t(")) return
        if (!contract.containsTargetLanguage(pureText, SiteKind.TEXT)) return

        val isJSX = ProjectStructure.isJSX(first) ||
            framework.getSiteForm(first) == SiteForm.SVELTE_BINDING
        val key = collectExtractedStrings(pureText, first) ?: return

        recordChange(
            message = pureText,
            replaceRoot = first,
            anchor = first,
            changes = changes,
        ) {
            // 调用形态由框架策略决定（CallExpressionStrategy）：Vue/react-i18next/Solid 沿用
            // `fn(`key`)`；react-intl 覆盖为 `formatMessage({ id: `key` })`。
            val callExpr = framework.buildCallExpression(tFunctionName, "`$key`", "{}")
            val newContent =
                if (!isJSX) "{{ $callExpr }}" else "{ $callExpr }"
            com.pan.extractor.rewriter.VueRewriter.rewriteXmlTextNodes(nodes, newContent)
        }
    }

    private fun collectXmlAttributeValueChange(attrValue: XmlAttributeValue, changes: MutableList<I18nProcessor.CollectedChange>) {
        val originalText = attrValue.value.trim()
        val isJSX = ProjectStructure.isJSX(attrValue) ||
            framework.getSiteForm(attrValue) == SiteForm.SVELTE_BINDING
        val isAngular = framework.getSiteForm(attrValue) == SiteForm.ANGULAR_BINDING
        if (isJSX && I18nPsiTools.isBlock(originalText)) return
        if (originalText.isEmpty()) return
        if (!contract.containsTargetLanguage(originalText, SiteKind.ATTRIBUTE)) return
        if (originalText.contains("\$t(") || originalText.contains("i18n.global.t(") || originalText.contains("i18n.t(")) return
        if (contract.isJSTemplateLiteral(originalText)) return

        val attr = attrValue.parent as? XmlAttribute ?: return
        val isDirective = I18nPsiTools.isVueDirective(attr.name)

        var newText = originalText

        if (!(isDirective && !originalText.startsWith("\"")
                    && !originalText.startsWith("'")
                    && !originalText.startsWith("`"))
        ) {
            val literal = I18nPsiTools.stripSurroundingQuotes(originalText)
            val extracted = collectExtractedStrings(literal, attrValue)
            if (extracted != null) {
                val escaped = if (extracted.contains("\n")) {
                    extracted.replace("`", "\\`")
                } else {
                    extracted.replace("'", "\\'")
                }
                // 调用形态由框架策略决定（CallExpressionStrategy）：默认 `fn('key')`；
                // react-intl 覆盖为 `formatMessage({ id: 'key' })`。
                newText = framework.buildCallExpression(tFunctionName, "'$escaped'", "{}")
            }
        }

        if (newText == originalText) return

        recordChange(
            message = originalText,
            replaceRoot = attrValue,
            anchor = attrValue,
            changes = changes,
        ) {
            com.pan.extractor.rewriter.VueRewriter.rewriteAttribute(
                attrValue = attrValue,
                newText = newText,
                isJSX = isJSX,
                isDirective = I18nPsiTools.isVueDirective(attr.name),
                isAngular = isAngular,
            )
        }
    }

    /** 用已合并好的 [pureText] 生成 key 并登记（供跨节点合并的文本段使用）。 */
    fun collectExtractedStrings(pureText: String, element: PsiElement): String? =
        jsCollector.collectExtractedStrings(pureText, element)

    fun collectExtractedStrings(ele: PsiElement): String? =
        jsCollector.collectExtractedStrings(ele)
}