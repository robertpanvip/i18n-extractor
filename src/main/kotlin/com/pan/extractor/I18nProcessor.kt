package com.pan.extractor

import com.pan.extractor.model.ExtractionContext
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.JSBinaryExpression
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSIndexedPropertyAccessExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnum
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.lang.javascript.psi.impl.JSPsiElementFactory
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.impl.source.tree.LeafPsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.*
import kotlin.collections.forEach
import kotlin.text.replace

class I18nProcessor @JvmOverloads constructor(
    override val project: Project,
    private var psiFile: PsiElement,
    /**
     * 单文件流水线调度器（DI 注入，§21.4 第 2 步）。
     * 默认使用 [I18nFileOrchestrator.Default]；测试或自定义管道可传入子类覆盖，保持行为可插拔。
     */
    private val orchestrator: com.pan.extractor.orchestrator.I18nFileOrchestrator =
        com.pan.extractor.orchestrator.I18nFileOrchestrator.Default,
) : I18nProcessorContract {
    /** 编排访问器：把私有文件根暴露给 [com.pan.extractor.orchestrator.I18nFileOrchestrator]。 */
    internal val rootElement: PsiElement get() = psiFile
    /** 从原始文本提取 $t/$tc/i18n.global.t 等调用（模板里 backtick 场景），对象级复用避免重复编译。
     *  BUG_ANALYSIS 3.4：去掉 [^
"'] 中的 \n 排除，支持跨行调用如
     *  $t(\n  'hello'\n) 和 i18n\n  .global\n  .t('hello') */
    private val T_CALL_PATTERN =
        Regex("(?:\\$(?:t|tc)|i18n\\.global\\.(?:t|tc)|i18n\\.(?:t|tc))\\(\\s*([`\"'])([^`\"']+)\\1\\s*[,)]")

    // ─────────────────────────────────────────────────────────────
    // 结构化 site（供跨文件公共前后缀合并 + 差异段嵌套 $t 重写使用）
    // ─────────────────────────────────────────────────────────────
    /**
     * 一次提取命中：要被替换为 $t(key) 的中文 site。
     * 目标架构 Phase 1：以领域模型 [com.pan.extractor.model.ExtractionSite] 表示，
     * 并代理到 [CollectedPlan.collectedSites]，消除原内嵌 CollectedSite 的重复建模。
     */
    /** 单文件收集与分析宿主（Analyzer 层，§21.4 第 3 步）：拥有收集期状态与收集业务；processor 对下游暴露薄转发。 */
    internal val analyzer: com.pan.extractor.analyzer.I18nAnalyzer by lazy {
        com.pan.extractor.analyzer.I18nAnalyzer(
            project = project,
            contract = this,
            jsCollector = jsCollector,
            injector = injector,
        )
    }

    /** 一次提取命中站点列表（领域模型 site，委托 Analyzer）。 */
    val collectedSites: MutableList<com.pan.extractor.model.ExtractionSite>
        get() = analyzer.collectedSites

    /** 原文包装：带 siteId，重写时可被 blockedSiteIds 跳过 */
    class CollectedChange(val siteId: String, private val runnable: () -> Unit) {
        fun run() = runnable()
    }

    /** 公开只读访问器，供进度条 UI 显示文件名（progress 线程里调用） */
    val targetPsiFile: PsiElement get() = psiFile

    /** 待应用的重写动作（collect 阶段收集，run 阶段逐个执行，委托 Analyzer）。 */
    var pendingChanges: MutableList<CollectedChange>
        get() = analyzer.pendingChanges
        set(value) { analyzer.pendingChanges = value }

    /** 被骨架合并承载、应跳过普通替换的 siteId（委托 Analyzer）。 */
    val blockedSiteIds: MutableSet<String> get() = analyzer.blockedSiteIds

    /** 新提取的 key -> 原文本（委托 Analyzer）。 */
    override val extractedStrings: MutableMap<String, String> get() = analyzer.extractedStrings

    /** 已存在的 $t() 调用 key -> 原文本（仅展示，不替换，委托 Analyzer）。 */
    val existingStrings: MutableMap<String, String> get() = analyzer.existingStrings

    val factory: XmlElementFactory = XmlElementFactory.getInstance(project)

    /** 拆分后的「翻译调用 import / i18n 实例注入」辅助类 */
    internal val injector: I18nImportInjector by lazy { I18nImportInjector(this) }

    /** 「JS 字符串收集 与 $t 表达式生成」辅助类 */
    internal val jsCollector: JsStringCollector by lazy { JsStringCollector(this) }

    /** 检测到的翻译函数名（例如 $t / t / i18n.t），默认 $t（委托 Analyzer）。 */
    override var tFunctionName: String
        get() = analyzer.tFunctionName
        set(value) { analyzer.tFunctionName = value }

    /** 当前文件检测到的框架策略，在 collect() 入口初始化（委托 Analyzer）。 */
    internal var framework: I18nFramework
        get() = analyzer.framework
        set(value) { analyzer.framework = value }

    /**
     * 全局 $t 别名注入标记（用户要求：全部统一用 $t，减少复杂度）。
     *
     * =============== Vue 版本 needInjectGlobalDollarT ===============
     * - true：run() 调 ensureI18nInstanceImported(isVue=true) 时，会在
     *   `import { i18n } from '...'` 之后追加 `const \$t = i18n.global.t;`
     *   （并去重保证只出现一次）。
     *
     * 预判："Vue 项目 + 非 .vue SFC（纯 .ts/.js 文件）+ 无自定义 hook"
     * → 这种场景没法用 useI18n 解构 $t，但只要在文件顶部把 i18n.global.t
     *   赋给全局 const $t，全文件仍然可以用 $t('xxx') 短写法。
     *
     * =============== React 版本 needInjectReactGlobalDollarT ===============
     * - true：run() 调 ensureI18nInstanceImported(isVue=false) 时，会在
     *   `import { getI18n } from 'react-i18next'` 之后追加
     *   `const t = getI18n().t;`（并去重保证只出现一次，React 约定统一用 t）。
     *
     * 预判："React 项目 + 既没有 React 组件也没有自定义 hook（纯工具函数）"
     * → 旧实现是切 `tFunctionName="i18n.t"` + 注入 `import i18n from 'i18next'`，
     *   用户觉得长调用 i18n.t('key') 麻烦，要求统一写短 t('key')：
     *   保持 tFunctionName 为 "t"，在顶部写 2 行代码：
     *       import { getI18n } from 'react-i18next';
     *       const t = getI18n().t;
     *   之后文件里所有替换都是短写法 t('xxx')，与组件/Hook 内部一致。
     */
    internal var needInjectGlobalDollarT: Boolean
        get() = analyzer.needInjectGlobalDollarT
        set(value) { analyzer.needInjectGlobalDollarT = value }
    internal var needInjectReactGlobalDollarT: Boolean
        get() = analyzer.needInjectReactGlobalDollarT
        set(value) { analyzer.needInjectReactGlobalDollarT = value }

    /**
     * Solid 纯工具 TS（无组件无 Hook）全局 `$t` 别名注入标记。（委托 Analyzer）
     */
    internal var needInjectSolidGlobalDollarT: Boolean
        get() = analyzer.needInjectSolidGlobalDollarT
        set(value) { analyzer.needInjectSolidGlobalDollarT = value }

    /**
     * React i18n.t 语义 + locale 初始化不可用 → 统一回退 getI18n 的 t 别名（委托 Analyzer）。
     */
    internal var reactI18nTFallbackToDollarT: Boolean
        get() = analyzer.reactI18nTFallbackToDollarT
        set(value) { analyzer.reactI18nTFallbackToDollarT = value }

    fun isMustache(text: String): Boolean = I18nPsiTools.isMustache(text)

    // 處理帶 Mustache 的 XmlText：獲取注入的 JS
    fun visitMustache(element: PsiElement, visitElement: (JSExpression) -> Unit) {

        val injected = InjectedLanguageManager.getInstance(project)
            .getInjectedPsiFiles(element)  // 或 getInjectedFragments
        injected?.forEach { pair ->
            val injectedRoot: PsiElement = pair.first     // 這才是注入的 PSI 根元素
            injectedRoot.accept(object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(e: PsiElement) {
                    if (e is JSLiteralExpression) {
                        visitElement(e)
                    }
                    if (e is JSBinaryExpression) {
                        visitElement(e)
                    }
                    super.visitElement(e)
                }
            })
        }
    }


    internal fun isVueFile(psiFile: PsiFile): Boolean =
        I18nPsiTools.isVueFile(psiFile)

    fun rm(element: PsiElement): String = I18nPsiTools.rm(element)

    /** 统一登记 site + 包装 change，返回新的 change 列表条目 */
    override fun recordChange(
        message: String,
        replaceRoot: PsiElement,
        anchor: PsiElement,
        changes: MutableList<CollectedChange>,
        replaceAction: () -> Unit
    ) {
        // 收集业务收敛至 Analyzer 层（§21.4 第 3 步）：统一登记 site + 包装 change。
        analyzer.recordChange(message, replaceRoot, anchor, changes, replaceAction)
    }

    internal fun collectFromPsi(psiFile: PsiElement): MutableList<CollectedChange> {
        // 候选发现（Scanner 遍历 + Analyzer 收集）收敛至 Analyzer 层（§21.4 第 3 步）。
        return analyzer.collectFromPsi(psiFile)
    }

    /**
     * 重置本 processor 的所有累积状态，保证 `collect()` 可安全重复执行（BUG_ANALYSIS 4.1）。
     *
     * 每次 collect 都应从"零状态"开始，否则重复执行时 collectedSites / existingStrings /
     * extractedStrings 会被重复追加，siteCounter 递增导致 siteId 不固定，进而跨文件合并
     * 与 blockedSiteIds 判定错位。framework / fallback 缓存标志一并重置，避免沿用上一次
     * 检测的框架策略。
     */
    internal fun resetState() {
        // 收集期状态重置收敛至 Analyzer 层（§21.4 第 3 步）：一次性替换 CollectedPlan 并清空
        // JsStringCollector 的 processedEnums，保证 collect() 幂等可重复执行（BUG_ANALYSIS 4.1）。
        analyzer.resetState()
    }

    /**
     * 【中央调度入口 · Phase A：收集】执行 Scanner/Analyzer 段，返回待应用改写列表。
     * 兼容壳：`collect()` 转发到这里。调用方（如 UI）在 collect 之后可中断进入合并计划确认。
     */
    fun extract(context: ExtractionContext = ExtractionContext(project, psiFile)): MutableList<CollectedChange> =
        orchestrator.collect(this, context)

    /** 兼容入口：等价于 [extract]。旧调用方（MergeApplier / 测试）继续使用。 */
    fun collect(): MutableList<CollectedChange> = extract()

    /**
     * 扫描文件中已有的 $t() / t() / i18n.global.t() / i18n.t() 调用，收集其 key 到 existingStrings。
     * 覆盖模板注入 JS 和 script/JS/TS 两种来源。
     * 同时检测文件使用的翻译函数名：
     * - Vue: i18n.global.t（vue-i18n 全局实例）
     * - React: i18n.t（i18next 全局实例）
     * - 默认: $t（useI18n / useTranslation 解构）
     * 注意：i18n.global.t 和 $t 可以在同一文件中共存，两者都识别为已翻译。
     *
     * P6：Vue mustache 专属遍历（注入 JS + 原始文本 backtick 提取）已委托给
     * [I18nFramework.collectExistingTKeysFromTemplate]，由 [VueI18nStrategy] 重写。
     * 通用 JSCallExpression 顶层遍历（detectTFunctionName + collectTKeyFromCall）保留在此处。
     */
    internal fun collectExistingTKeys() {
        // 现有 $t() 调用 key 收集 + tFunctionName 检测收敛至 Analyzer 层（§21.4 第 3 步）。
        analyzer.collectExistingTKeys(psiFile)
    }

    /**
     * 【中央调度入口 · Phase B：应用】执行 Rewriter/Injector 段，改写源码 + 注入 import/hook。
     * 兼容入口：`run()` 转发到这里。
     */
    fun apply(context: ExtractionContext = ExtractionContext(project, psiFile)) {
        orchestrator.run(this, context)
    }

    /** 兼容入口：等价于 [apply]。旧调用方（runWithUndo / 测试）继续使用。 */
    fun run() = apply()

    /** 处理整个 Vue/React 文件：包裹 Command + 写操作以支持 undo。 */
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

    override fun getScriptTag(): XmlTag? {
        return PsiTreeUtil.findChildrenOfType(psiFile, XmlTag::class.java)
            .firstOrNull { it.name == "script" }
    }

    // ───────────────────────────────────────────────
    // Vue import 去重 / 解构去重 工具函数（问题 4 修复）
    // ───────────────────────────────────────────────
    /**
     * 判断 [decl]（一个 ES6ImportDeclaration）是否已经从 [moduleName] 导入了 [wantedName]。
     *
     * 改用"文本级宽松匹配"而不是 IntelliJ PSI 内部 API：
     * 不同版本的 IntelliJ（2024/2025 EAP）对 ES6ImportDeclaration 的内部属性
     * 名字变化很大（importedModule / importedNamespaceBinding / importedName 等都不存在），
     * 但 `decl.text` 即源代码字符串是稳定的。
     *
     * 匹配规则：
     * - decl.text 必须包含 `from "...moduleName..."` 或 `from '...moduleName...'`
     *   （相对路径还允许 `/index` 尾缀）
     * - 然后看整个 import 里是否包含 wantedName：
     *     1) 命名导入：`{ useI18n }` / `{ useI18n as i18n }` / `{ foo, useI18n }`
     *     2) 命名空间导入：`import * as X from` → 视为"已经处理过"
     *     3) 默认导入：默认变量名 == wantedName 或 wantedName == "default"
     */
    private fun hasImportedSpecifier(decl: ES6ImportDeclaration, moduleName: String, wantedName: String): Boolean =
        injector.hasImportedSpecifier(decl, moduleName, wantedName)

    /**
     * 判断 [scope] 范围内是否已经存在"[callee]() 函数调用 + 指定解构"。
     * 用于避免 `const { t: \$t } = useI18n();` 在 ensureVueI18nImported 里重复插入。
     *
     * 文本级宽松匹配：
     * 1) scope 文本里出现 `useI18n(`（即调用过），
     * 2) 并且要么
     *    - const/let/var 解构文本中包含 `{ $destructureNameFrom: $destructureAlias }`
     *    - 或者 `useI18n(` 附近存在 `{$destructureNameFrom`（比如用户自己写 const { t, n } = useI18n()）
     *       就认为"已经处理过"，不重复塞。
     */
    private fun scopeHasDestructuredCall(
        scope: PsiElement,
        callee: String,
        destructureNameFrom: String,
        destructureAlias: String,
    ): Boolean = injector.scopeHasDestructuredCall(
        scope, callee, destructureNameFrom, destructureAlias
    )

    internal fun ensureVueI18nImported(psiFile: PsiElement) =
        injector.ensureVueI18nImported(psiFile)

    /** React i18n 导入 + useTranslation hook 注入 */
    internal fun ensureReactI18nImported(psiFile: PsiElement) =
        injector.ensureReactI18nImported(psiFile)

    /**
     * Vue 项目纯 .ts 文件中 use 开头自定义 hook 的 useI18n 注入。
     *
     * 场景：Vue 项目里独立的 .ts 文件（非 .vue SFC）写了 useXxx 自定义 hook，
     * 内部有硬编码中文。这类文件既不是 Vue SFC（无 <script> 标签）也不是 React，
     * 无法走 [ensureVueI18nImported]（依赖 <script>）或 [ensureReactI18nImported]。
     *
     * 处理：
     * 1. 缺少 vue-i18n 导入时，在文件顶部注入 `import { useI18n } from 'vue-i18n'`
     * 2. 给每个 use 开头的顶级 hook 函数体首行注入 `const { t: $t } = useI18n();`
     */
    internal fun ensureVueHookI18nImported(psiFile: PsiElement) =
        injector.ensureVueHookI18nImported(psiFile)

    /**
     * 【Vue TSX 组件的 useI18n 注入】（对应 ensureVueHookI18nImported 的"组件版"）
     *
     * 场景：Vue 项目中的 .tsx / .jsx 文件写了 defineComponent({...}) 或 PascalCase
     *       函数式组件，内部有硬编码中文需要替换为 $t('key')，此时需要：
     *       1) 顶部存在 `import { useI18n } from 'vue-i18n'`；
     *       2) defineComponent 的 setup() 函数体开头，或函数式组件函数体开头，
     *          注入 `const { t: $t } = useI18n()`；
     *       这样 $t 在组件作用域可用，不需要"全局 i18n 实例 + const $t = i18n.global.t"。
     *
     * 注入目标集合（allTargets，每一个都要在函数体开头插 const 解构）：
     *   A. defineComponent({ setup() { ... } }) 中找到的 setup() 函数体
     *   B. findVueComponentFunctions 返回的函数式组件（PascalCase + return JSX）
     *   C. findVueComponentFunctions 返回的 defineComponent 调用 → 定位 setup 属性
     *
     * 注意：和 ensureVueHookI18nImported 对称——如果文件里找不到任何组件函数，直接 return。
     */
    internal fun ensureVueComponentI18nInjected(psiFile: PsiElement) =
        injector.ensureVueComponentI18nInjected(psiFile)

    /**
     * 当文件使用 i18n.global.t / i18n.t / getI18n().t 但缺少 i18n 实例导入时，注入默认导入。
     *
     * - Vue:   查找项目中调用 createI18n 的文件（通常位于 @/locales 目录），
     *          根据该文件的实际路径与导出方式生成导入：
     *            `import { i18n } from '@/locales'`   （命名导出，别名路径）
     *            `import i18n from './locales/index'` （默认导出，相对路径）
     *          找不到 createI18n 文件时回退到从 vue-i18n 包导入。
     *          （可选 injectGlobalDollarT=true：追加 const \$t = i18n.global.t;）
     *
     * - React 旧模式：`import i18n from 'i18next'`（i18next 全局实例，兼容已存在导入场景）
     *
     * - React 新模式（用户要求）：统一用 \$t 减少复杂度，顶部写两行：
     *       import { getI18n } from 'react-i18next';
     *       const $t = getI18n().t;
     *   由 injectReactGlobalDollarT=true 开启。
     *
     * 注意：已有任意形式的 i18n / getI18n 导入时不重复注入。
     */
    /**
     * 确保文件顶部已经导入了 i18n 实例。
     *
     * @param injectGlobalDollarT —— 仅在 isVue=true 且"非 SFC 的纯 TS 文件"时为 true：
     *   在已经 `import { i18n } from '@/locales/xxx'` 之后，再追加一行
     *   `const $t = i18n.global.t;`（去重，用户要求"全部统一用 $t 减少复杂度"）。
     *
     * @param injectReactGlobalDollarT —— 仅在 isVue=false 且"React 纯工具 TS（无组件无 Hook）"时为 true：
     *   顶部注入 `import { getI18n } from 'react-i18next';`，并在其后追加
     *   `const $t = getI18n().t;`（去重）。
     */
    private fun ensureI18nInstanceImported(
        psiFile: PsiElement,
        isVue: Boolean,
        injectGlobalDollarT: Boolean = false,
        injectReactGlobalDollarT: Boolean = false
    ) = injector.ensureI18nInstanceImported(
        psiFile, isVue, injectGlobalDollarT, injectReactGlobalDollarT
    )

    /**
     * 为 Vue 全局 i18n 实例构造 import 语句。
     *
     * 流程：
     * 1. 通过 I18nInstanceLocator.findVueI18nInstanceFile 查找 createI18n 调用的文件
     * 2. 通过 resolveVueI18nImportPath 推断别名/相对路径（自动去掉扩展名和 /index 后缀）
     * 3. 通过 isVueI18nDefaultExport 判断命名 or 默认导入语法
     * 4. 任何一步失败都回退到 `import { i18n } from 'vue-i18n'`
     */
    private fun buildVueI18nInstanceImport(psiFile: PsiElement): String =
        injector.buildVueI18nInstanceImport(psiFile)

    /**
     * 为 React 全局 i18n 实例构造 import 语句（locale 优先，找不到时由调用方回退 getI18n）。
     *
     * 流程：
     * 1. 通过 I18nInstanceLocator.findReactI18nInstanceFileInRoot 查找"导出了 i18n"的 React 初始化文件
     * 2. 通过 I18nInstanceLocator.resolveVueI18nImportPath 推断别名/相对路径（自动去掉扩展名和 /index 后缀）
     * 3. 通过 I18nInstanceLocator.isVueI18nDefaultExport 判断命名 or 默认导入语法
     *
     * 返回 null 代表没有可用的 locale i18n 实例（无初始化文件 / 未导出 i18n / 路径无法推断）。
     */
    private fun buildReactI18nInstanceImport(psiFile: PsiElement): String? =
        injector.buildReactI18nInstanceImport(psiFile)

    companion object {
        /**
         * 找不到 createI18n / init 文件时的回退（P1：原 `import { i18n } from 'vue-i18n'` 无效——
         * vue-i18n 包并不导出命名 `i18n`，只导出 `createI18n`，运行时 i18n 恒为 undefined）。
         * 回退改为自行创建全局实例：createI18n 返回的实例带有 `global.t`，配合
         * `const $t = i18n.global.t;` 别名可正常工作。
         * 注意：平铺为两条语句，SFC/纯 TS 注入分支均需以「保留原文的节点」插入（见 ensureI18nInstanceImported），
         * 不能走 createJSStatementFromText（其只取首个语句，会丢 const i18n 行）。
         */
        internal const val FALLBACK_VUE_I18N_IMPORT =
            "import { createI18n } from 'vue-i18n';\nconst i18n = createI18n({ legacy: false });\n"
    }

    /**
     * 检查文件是否已导入 i18n 实例（命名导入、默认导入、namespace 导入均可）。
     * 匹配形式：
     * - `import { i18n } from '...'`            （命名导入）
     * - `import i18n from '...'`                （默认导入）
     * - `import * as i18n from '...'`           （namespace 导入）
     * - `import i18n, { other } from '...'`     （混合导入）
     *
     * —— 新规则（React 兼容）：若已存在 `import { getI18n } from 'react-i18next'` 也算
     * "已有全局 i18n 能力"，因为 getI18n() 就是 react-i18next 返回 i18next i18n 实例的
     * 官方 API，不应该重复再注入 `import i18n from 'i18next'`。
     */
    private fun hasI18nInstanceImported(root: PsiElement): Boolean =
        injector.hasI18nInstanceImported(root)

    /**
     * 检查文件是否已导入 react-i18next 的 getI18n（React 新模板）。
     * 匹配形式：
     *   - `import { getI18n } from 'react-i18next'`           （独立命名导入）
     *   - `import { useTranslation, getI18n } from ...'`      （和 useTranslation 混合）
     *   - 路径中含 `react-i18next`（容忍引号/反引号差异）
     */
    private fun hasReactGetI18nImported(root: PsiElement): Boolean =
        injector.hasReactGetI18nImported(root)


    fun getCharactersText(textNode: XmlElement): List<XmlToken> =
        I18nPsiTools.getCharactersText(textNode)

    /** 文本是否包含任一已启用目标语言的字符（由全局设置决定，默认仅中文）。 */
    fun containsTargetLanguage(text: String): Boolean = I18nPsiTools.containsTargetLanguage(text)

    /** 按站点上下文（Approach A）判定文本是否命中任一已启用目标语言。 */
    override fun containsTargetLanguage(text: String, site: SiteKind): Boolean = I18nPsiTools.containsTargetLanguage(text, site)


    override fun isJSTemplateLiteral(text: String): Boolean = I18nPsiTools.isJSTemplateLiteral(text)

    /**
     * 如果内容是纯字符串字面量（无插值的反引号、单引号、双引号字符串），
     * 返回去掉外层引号后的内容；否则返回 null。
     * 例如：`测试` -> "测试"，'hello' -> "hello"，"world" -> "world"
     */
    override fun extractPureStringContent(text: String): String? =
        I18nPsiTools.extractPureStringContent(text)

    fun isBlock(originalText: String): Boolean = I18nPsiTools.isBlock(originalText)

    fun isVueDirective(targetStr: String): Boolean = I18nPsiTools.isVueDirective(targetStr)

    override val templateVarRegex = """\$\{((?:[^{}]|\{(?:[^{}]|\{[^}]*\})*\})*)\}""".toRegex()

    /**
     * Vue-i18n 不支持数字占位符 `$t('默认模型配置{0}子', { '0': "123" })` 这种
     * 数字 key 对象写法，必须用命名插值。统一把 Vue 侧占位符改成 {<prefix>0} / {<prefix>1} ...，
     * 调用侧参数对象写成 `{ N0: "123" }`（无引号，合法 JS identifier）。
     * 前缀（默认 `N`）可在设置面板配置，但必须是非空变量名。
     * React i18next 的 `{{0}}` + `{ "0": val }` 原生支持，保持不变。
     */
    private fun vuePlaceholderKey(rawIndex: Int): String = jsCollector.vuePlaceholderKey(rawIndex)

    fun collectJSStringTemplate(
        raw: String,
        changes: MutableList<CollectedChange>,
        ele: PsiElement,
        creator: (String) -> String
    ) = jsCollector.collectJSStringTemplate(raw, changes, ele, creator)

    fun buildTFunctionExpr(message: String, paramsObject: String): String =
        jsCollector.buildTFunctionExpr(message, paramsObject)

    /**
     * 纯文本构建 t() 调用，不依赖当前 processor 已探测到的 tFunctionName 注入上下文，
     * 直接按 isVue/isReact 生成最稳妥形式（和现有探测一致：都用 \$t，减少复杂度）。
     * - 若 skeletonKeyOverride 非空：key 用这个覆盖而不是 message.trim()（供合并骨架时使用）
     */
    fun buildTExprForRawText(
        message: String,
        paramsObject: String,
        isVue: Boolean,
        isReact: Boolean,
        skeletonKeyOverride: String? = null,
    ): String = jsCollector.buildTExprForRawText(
        message, paramsObject, isVue, isReact, skeletonKeyOverride
    )

    /**
     * 从模板字面量文本直接构建嵌套 $t() 表达式（纯文本处理，不操作 PSI）
     * - Vue：资源文件占位 `{N0}`，调用侧 `{ N0: val }` 无引号键
     * - React：资源文件占位 `{{0}}`，调用侧 `{ "0": val }` 保持原样
     */
    fun buildNestedTExprFromText(raw: String, ele: PsiElement): String =
        jsCollector.buildNestedTExprFromText(raw, ele)

    override fun createStringExpressionNode(text: String, context: PsiElement): PsiElement =
        jsCollector.createStringExpressionNode(text, context)

    /**
     * 从文本创建 JS 语句（使用 PsiFileFactory 构造完整 PSI 语句节点）。
     * 相比直接操作 AST 节点，这种方式创建的语句结构完整，
     * 不会导致 Document is locked 异常。
     */
    override fun createJSStatementFromText(text: String, context: PsiElement): PsiElement =
        jsCollector.createJSStatementFromText(text, context)

    override fun createHTMLTagFromText(text: String): PsiElement =
        factory.createHTMLTagFromText(text)

    fun collectJSStringTemplateFromExpression(stringExpr: JSLiteralExpression, changes: MutableList<CollectedChange>) =
        jsCollector.collectJSStringTemplateFromExpression(stringExpr, changes)

    /**
     * 检查字符串字面量是否已经处于某一层 i18n 翻译调用的作用域内。
     *
     * 返回分档，收集/替换阶段走不同策略：
     *   - NONE             : 完全不在任何 t/$t/i18n.global.t 调用里 → 正常：加 key + 替换为 $t('key')
     *   - DIRECT_ARG       : 字符串字面量直接就是某条 t/$t(...) 的第一个参数 → 完全跳过（已有完整 $t('x')，不需再处理）
     *   - OUTER_T_EXPRESSION: 外层祖先有 t/$t/... 调用，但本字符串不是其**直接单字符串参数**，
     *                        而是嵌套在参数表达式里（典型：$t(isPinned ? '取消置顶' : '置顶') 三元分支内
     *                        的两个独立字符串）。
     *                        此时仍要提取到 extractedStrings（国际化字典要有「取消置顶 / 置顶」两条），
     *                        但替换时只替换字符串字面量为 'key'，不再包一层 $t('key')，
     *                        避免出现双重 $t：$t(isPinned ? $t(...) : $t(...))。
     *   - INSIDE_UNKNOWN   : 字面量位于**无法证明来源**的调用（三态 UNKNOWN）参数内部 → 保守跳过，
     *                        既不提取也不改写（零误改；「t 是弱特征，不是语义证明」的兜底行为）。
     */
    enum class TSem { NONE, DIRECT_ARG, OUTER_T_EXPRESSION, INSIDE_UNKNOWN }

    /**
     * 【Bug A10】排除「同名本地普通函数」的 t/tc 调用。
     * 若引用名 `t`/`tc` 解析到**本文件内**声明的普通函数（function t / function tc），
     * 说明它不是 i18n 翻译函数，其参数里的中文仍应被提取，而不是被当成「已翻译」跳过。
     * 仅对裸名 t/tc 生效；$t/$tc（插件统一的全局别名）与 i18n.t/tc 链式调用不受影响。
     */
    private fun isLocalFunctionNamedTCall(call: JSCallExpression): Boolean =
        jsCollector.isLocalFunctionNamedTCall(call)

    fun detectTSemantic(stringExpr: JSLiteralExpression): TSem =
        jsCollector.detectTSemantic(stringExpr)

    /** 旧名兼容：其他地方只需要「DIRECT_ARG 就跳过」——保留 true/false 语义：
     *  仅 DIRECT_ARG 返回 true（完全跳过）；OUTER_T_EXPRESSION 返回 false（仍然进入收集/替换分支，
     *  但在 collectJSStringChange 内部再走 key-text-only 替换分支）。 */
    fun isTransformedCalled(stringExpr: JSLiteralExpression): Boolean =
        jsCollector.isTransformedCalled(stringExpr)

    /**
     * 核心方法：提取 XmlText 中的纯文本（过滤注释、空白符、换行符）
     * 处理场景：<h1>123<!-- 注释 -->这是我的测试</h1> → 输出 "123这是我的测试"
     */
    private fun getPureXmlText(xmlText: XmlText): String =
        jsCollector.getPureXmlText(xmlText)

    fun collectExtractedStrings(ele: PsiElement): String? =
        jsCollector.collectExtractedStrings(ele)

    /** 用已合并好的 [pureText] 生成 key 并登记（供跨节点合并的文本段使用）。 */
    fun collectExtractedStrings(pureText: String, element: PsiElement): String? =
        jsCollector.collectExtractedStrings(pureText, element)

    fun hasEqInExpression(expr: PsiElement?): Boolean = I18nPsiTools.hasEqInExpression(expr)

    // ───────────────────────────────────────────────
    // 跳过：成员变量/数组下标/index 访问中的中文 key（用户需求）
    //   例：P['中文']、obj['姓名']、arr['第1个']、P['姓' + '名']、P[`中文键${suffix}`]
    //   只要元素在 JSIndexedPropertyAccessExpression 的 indexExpr 子树里（即 [...] 方括号内）
    //   就跳过 —— 并且严格只跳过"index 表达式内部"，不要误把 qualifier 里的中文也砍掉。
    //
    //   NOTE: JSIndexedPropertyAccessExpression 在 Vue SFC 指令表达式（如 v-if="obj['已启用']"）
    //   的原生 PSI 中也会被正确构造（见 VueJSEmbeddedExpressionContentImpl 内的 JS…Impl 子树），
    //   因此"标准路径"在 Vue SFC 场景下同样适用。
    // ───────────────────────────────────────────────
    private fun isInIndexKeyPosition(ele: PsiElement): Boolean =
        jsCollector.isInIndexKeyPosition(ele)

    /**
     * 判断 ele 是否是一个「指令属性值整体」的字符串字面量，即 `:title="'中文'"` 里的 `'中文'`。
     * 此时内层字符串字面量是属性值的唯一内容，应交给 collectXmlAttributeValueChange 统一处理，
     * 避免 collectJSStringChange 重复提取。
     */
    private fun isDirectiveSoleStringLiteral(ele: JSLiteralExpression): Boolean =
        jsCollector.isDirectiveSoleStringLiteral(ele)

    /**
     * 【Bug A1】判断 ele 是否位于「纯字符串拼接」内：自 ele 向上找到最顶层的 `+` 表达式，
     * 并递归检查整条拼接链的所有叶子操作数是否都是字符串字面量（无变量/引用/数字/嵌套调用）。
     * 若为 true，此时 collectJSBinaryExpressionChange 会把整条拼接合并成一个 key，
     * ele 应交给它而不再单独提取。
     */
    private fun isWithinPureStringConcat(ele: PsiElement): Boolean =
        jsCollector.isWithinPureStringConcat(ele)

    /** 判断某操作数是否为可被整体合并的纯字符串（字面量、纯模板，或嵌套的纯字符串拼接）。 */
    private fun isPureStringOperand(e: PsiElement?): Boolean =
        jsCollector.isPureStringOperand(e)

    // ───────────────────────────────────────────────
// JS 字符串字面量
// ───────────────────────────────────────────────
    private fun collectJSStringChange(ele: JSLiteralExpression, changes: MutableList<CollectedChange>) =
        jsCollector.collectJSStringChange(ele, changes)

    // ───────────────────────────────────────────────
// JS 字符串拼接 (+)
// ───────────────────────────────────────────────
    private fun collectJSBinaryExpressionChange(binaryExpr: JSBinaryExpression, changes: MutableList<CollectedChange>) =
        jsCollector.collectJSBinaryExpressionChange(binaryExpr, changes)

    private fun convertConcatTextToTemplate(binaryExpr: JSBinaryExpression): String =
        jsCollector.convertConcatTextToTemplate(binaryExpr)


    // ───────────────────────────────────────────────
// 生成 key：直接用中文（简单清理）
// ───────────────────────────────────────────────
    private fun generateKey(value: String, element: PsiElement): String =
        jsCollector.generateKey(value, element)

    private fun isInComment(element: PsiElement): Boolean = I18nPsiTools.isInComment(element)

    fun isComment(element: PsiElement): Boolean = I18nPsiTools.isComment(element)

    private fun isInStyleOrComment(element: PsiElement): Boolean =
        I18nPsiTools.isInStyleOrComment(element)
}