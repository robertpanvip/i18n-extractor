package com.pan.extractor.core

import com.pan.extractor.project.I18nPsiTools
import com.pan.extractor.lang.SiteKind
import com.pan.extractor.model.ExtractionContext
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import com.pan.extractor.planner.RewriteKind
import com.pan.extractor.planner.RewritePlan

/**
 * 【薄 Orchestrator】单文件 i18n 处理入口。
 *
 * 本类只做三件事：
 *  1. 注入依赖（[orchestrator] / [analyzer] / [jsCollector] / [injector] / [factory]）；
 *  2. 实现窄契约 [I18nProcessorContract]（两个收集器经此访问宿主的能力面）；
 *  3. 转发中央调度：[collect] → Orchestrator.collect（Scanner/Analyzer 段，只读），
 *     [run] → Orchestrator.run（Rewriter/Injector 段，写）。
 *
 * 所有收集期状态与业务逻辑都不在本类：
 *   - 收集期状态（collectedSites / pendingChanges / framework / needInject* …）→ [analyzer]；
 *   - JS 字符串收集与 $t 生成 → [jsCollector]（经契约 [I18nProcessorContract] 访问本宿主）；
 *   - import / hook 注入 → [injector]；
 *   - 无状态文本工具 → [I18nPsiTools]。
 * 因此 [analyzer] 是本类对外暴露读写的唯一状态面，其余仅保留结果数据
 * （[extractedStrings] / [existingStrings]，契约与下游消费所需）。
 */
class I18nProcessor @JvmOverloads constructor(
    override val project: Project,
    private var psiFile: PsiElement,
    /**
     * 单文件流水线调度器（DI 注入）。默认 [com.pan.extractor.orchestrator.I18nFileOrchestrator.Default]；
     * 测试或自定义管道可传入子类覆盖。
     */
    private val orchestrator: com.pan.extractor.orchestrator.I18nFileOrchestrator =
        com.pan.extractor.orchestrator.I18nFileOrchestrator.Default,
) : I18nProcessorContract {

    /** 编排 / 重写器定位文件根用。 */
    internal val rootElement: PsiElement get() = psiFile

    // ─────────────────────────────────────────────────────────────
    // 依赖注入（薄编排器持有的全部「零件」）
    // ─────────────────────────────────────────────────────────────
    internal val factory: XmlElementFactory = XmlElementFactory.getInstance(project)

    /** 收集期容器：分析器 / 收集器 / 注入器共享的**同一个实例**（消除 lateinit 回填，打破构造循环）。 */
    internal val plan = com.pan.extractor.planner.CollectedPlan()

    /** 「翻译调用 import / i18n 实例注入」辅助类。 */
    internal val injector: ImportManager by lazy { ImportManager(this, plan) }

    /** 「JS 字符串收集 与 $t 表达式生成」辅助类。 */
    internal val jsCollector: JsStringCollector by lazy { JsStringCollector(this, plan) }

    /** 单文件收集与分析宿主：拥有收集期状态，是 collectedSites / extractedStrings / tFunctionName 等的唯一事实来源。
     *  能力面经构造函数注入，状态面（[plan]）与分析器/收集器/注入器共享同一实例 —— 收集期在**构造时**即已连通，
     *  无需事后回填（消除 lateinit 的脆弱初始化顺序）。 */
    internal val analyzer: com.pan.extractor.analyzer.I18nAnalyzer by lazy {
        com.pan.extractor.analyzer.I18nAnalyzer(
            project = project,
            contract = this,
            jsCollector = jsCollector,
            injector = injector,
            plan = plan,
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 中央调度：仅把控制流交给 Orchestrator，自身不做任何业务决策
    // ─────────────────────────────────────────────────────────────
    /** 【Phase A：收集】Scanner/Analyzer 段（只读），产出纯数据改写配方。 */
    fun extract(context: ExtractionContext = ExtractionContext(project, psiFile)): List<RewritePlan> =
        orchestrator.collect(this, context)

    /** 兼容入口：等价于 [extract]。 */
    fun collect(): List<RewritePlan> = extract()

    /** 【Phase B：应用】Rewriter/Injector 段（写）。 */
    fun apply(context: ExtractionContext = ExtractionContext(project, psiFile)) {
        orchestrator.run(this, context)
    }

    /** 兼容入口：等价于 [apply]。 */
    fun run() = apply()

    /** 处理整个 Vue/React 文件：包裹 Command + 写操作以支持 undo（单 command 原子）。 */
    fun runWithUndo() {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                WriteCommandAction.runWriteCommandAction(project) {
                    this.run();
                }
            },
            "Vue i18n Extract",
            null
        )
    }

    // ─────────────────────────────────────────────────────────────
    // I18nProcessorContract 覆写（收集器经此接口访问宿主的能力面）
    // 结果/状态（extractedStrings / existingStrings / tFunctionName）不在本类，统一经 [analyzer] 读取。
    // ─────────────────────────────────────────────────────────────
    override fun getScriptTag(): XmlTag? {
        return PsiTreeUtil.findChildrenOfType(psiFile, XmlTag::class.java)
            .firstOrNull { it.name == "script" }
    }

    override val templateVarRegex = """\$\{((?:[^{}]|\{(?:[^{}]|\{[^}]*\})*\})*)\}""".toRegex()

    override fun containsTargetLanguage(text: String, site: SiteKind): Boolean =
        I18nPsiTools.containsTargetLanguage(text, site)

    override fun isJSTemplateLiteral(text: String): Boolean = I18nPsiTools.isJSTemplateLiteral(text)

    /** 纯字符串字面量剥离引号；非纯字符串返回 null。 */
    override fun extractPureStringContent(text: String): String? =
        I18nPsiTools.extractPureStringContent(text)

    override fun recordRewrite(
        message: String,
        replaceRoot: PsiElement,
        anchor: PsiElement,
        kind: RewriteKind,
        newExpression: String,
        xmlTextPointers: List<SmartPsiElementPointer<XmlText>>,
        attributeForm: com.pan.extractor.planner.AttributeRenderForm,
    ): RewritePlan = analyzer.recordPlan(
        message, replaceRoot, anchor, kind, newExpression,
        xmlTextPointers, attributeForm,
    )

    override fun createStringExpressionNode(text: String, context: PsiElement): PsiElement =
        jsCollector.createStringExpressionNode(text, context)

    override fun createJSStatementFromText(text: String, context: PsiElement): PsiElement =
        jsCollector.createJSStatementFromText(text, context)

    override fun createHTMLTagFromText(text: String): PsiElement =
        factory.createHTMLTagFromText(text)

    companion object {
        /**
         * 找不到 createI18n / init 文件时的回退（vue-i18n 包并不导出命名 `i18n`，只导出 `createI18n`；
         * 自行创建全局实例并平铺为两条语句，SFC/纯 TS 注入分支均需以「保留原文的节点」插入）。
         */
        internal const val FALLBACK_VUE_I18N_IMPORT =
            "import { createI18n } from 'vue-i18n';\nconst i18n = createI18n({ legacy: false });\n"
    }
}