package com.pan.extractor

import com.pan.extractor.model.ExtractionContext
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.XmlElementFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

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

    /**
     * 一次提取命中的「改写动作」：收集期登记、应用期执行（可被骨架合并阻塞跳过）。
     * 驻留在此是因为它作为 [extract] 的返回类型与 [I18nAnalyzer] 的收集载体，全工程引用。
     */
    class CollectedChange(val siteId: String, private val runnable: () -> Unit) {
        fun run() = runnable()
    }

    // ─────────────────────────────────────────────────────────────
    // 依赖注入（薄编排器持有的全部「零件」）
    // ─────────────────────────────────────────────────────────────
    internal val factory: XmlElementFactory = XmlElementFactory.getInstance(project)

    /** 「翻译调用 import / i18n 实例注入」辅助类。 */
    internal val injector: I18nImportInjector by lazy { I18nImportInjector(this) }

    /** 「JS 字符串收集 与 $t 表达式生成」辅助类。 */
    internal val jsCollector: JsStringCollector by lazy { JsStringCollector(this) }

    /** 单文件收集与分析宿主：拥有收集期状态，是 [extractedStrings] / [tFunctionName] 等的事实来源。 */
    internal val analyzer: com.pan.extractor.analyzer.I18nAnalyzer by lazy {
        com.pan.extractor.analyzer.I18nAnalyzer(
            project = project,
            contract = this,
            jsCollector = jsCollector,
            injector = injector,
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 中央调度：仅把控制流交给 Orchestrator，自身不做任何业务决策
    // ─────────────────────────────────────────────────────────────
    /** 【Phase A：收集】Scanner/Analyzer 段（只读）。 */
    fun extract(context: ExtractionContext = ExtractionContext(project, psiFile)): MutableList<CollectedChange> =
        orchestrator.collect(this, context)

    /** 兼容入口：等价于 [extract]。 */
    fun collect(): MutableList<CollectedChange> = extract()

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
    // 结果数据（契约 + 下游消费所需的只读转发，见 §I18nAnalyzer）
    // ─────────────────────────────────────────────────────────────
    /** 新提取的 key -> 原文本（契约要求，委托 Analyzer）。 */
    override val extractedStrings: MutableMap<String, String> get() = analyzer.extractedStrings

    /** 已存在的 $t() 调用 key -> 原文本（仅展示，不替换，委托 Analyzer）。 */
    val existingStrings: MutableMap<String, String> get() = analyzer.existingStrings

    /** 检测到的翻译函数名（例如 $t / t / i18n.t），默认 $t（契约要求，委托 Analyzer）。 */
    override var tFunctionName: String
        get() = analyzer.tFunctionName
        set(value) { analyzer.tFunctionName = value }

    // ─────────────────────────────────────────────────────────────
    // I18nProcessorContract 覆写（收集器经此接口访问宿主的能力面）
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

    override fun recordChange(
        message: String,
        replaceRoot: PsiElement,
        anchor: PsiElement,
        changes: MutableList<CollectedChange>,
        replaceAction: () -> Unit
    ) {
        analyzer.recordChange(message, replaceRoot, anchor, changes, replaceAction)
    }

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