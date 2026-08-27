package com.pan.extractor.analyzer

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * 符号来源分类 —— "t 是弱特征，不是语义证明" 的核心引擎。
 *
 * 把 [JSCallExpression] 的 callee 分解为形状（裸名 / 链式 / 其它），并沿链下钻到
 * 最深的接收者引用，用 **Reference Resolution（优先）+ 文件级结构证明（回退）** 解析
 * 该符号的**来源**：
 *
 *  - [SymbolOrigin.I18N_FRAMEWORK_IMPORT]：从 i18n 框架包（vue-i18n / react-i18next /
 *    i18next / @solid-primitives/i18n / @solid-hooks/i18n）import 的符号（含 `as` 别名）；
 *  - [SymbolOrigin.I18N_HOOK_OR_FACTORY]：已知 i18n hook / 工厂的调用产物
 *    （`useI18n()` / `useTranslation()` / `createI18n()` / `getI18n()` / `createAppI18n()`），
 *    含解构绑定 `const { t } = useI18n()`，以及链式接收者为约定实例名 `i18n` / `i18next`；
 *  - [SymbolOrigin.PLUGIN_DOLLAR_T]：插件注入的规范名 `$t` / `$tc`；
 *  - [SymbolOrigin.LOCAL_SHADOW]：本地声明的普通对象 / 函数（`const i18n = { t }` /
 *    `function t()` / `const t = fn` / `namespace ns`）—— 名字像 t 但**不是**翻译函数；
 *  - [SymbolOrigin.NON_I18N]：来自非 i18n 模块的 import（结构上明确非翻译）；
 *  - [SymbolOrigin.UNKNOWN]：无法证明任何一边 —— 调用方按「三态 UNKNOWN」保守处理
 *    （既不提取也不改写，零误改）。
 *
 * 设计原则：**只有证据才能证明「已翻译」**。证据分两层：
 *  1. resolve 命中（优先）：import 声明 / 本地变量初始化为对象、函数、hook 调用；
 *  2. 文件级结构扫描（resolve 失败时回退，覆盖测试环境 / 无真实模块的项目）：
 *     import 声明文本、`const { t } = <hook>()` 解构语句、本地同名声明。
 * 「t / tc」是进入证明的**弱特征**；没有任何结构证据的裸 `t()` 归 UNKNOWN。
 */
enum class SymbolOrigin {
    I18N_FRAMEWORK_IMPORT,
    I18N_HOOK_OR_FACTORY,
    PLUGIN_DOLLAR_T,
    LOCAL_SHADOW,
    NON_I18N,
    UNKNOWN,
}

/** callee 的语法形状。 */
enum class CalleeShape {
    /** 裸引用：`t(...)` / `$t(...)` —— callee 就是单个引用表达式。 */
    BARE_NAME,

    /** 链式访问：`i18n.t(...)` / `i18n.global.t(...)` / `foo.bar.t(...)`。 */
    CHAINED,

    /** 其它形态（复杂表达式等），无法分解。 */
    OTHER,
}

/** 一次 callee 分解 + 来源分析的结果。 */
data class CalleeAnalysis(
    val shape: CalleeShape,
    /** 最末引用名（`t` / `tc` / `\$t` / `\$tc` 或其它）。 */
    val name: String?,
    /** 链式访问时下钻到的最深接收者引用（裸名时为 method 本身）。 */
    val baseReference: JSReferenceExpression?,
    /** 解析到的声明（可能为 null）。 */
    val resolved: PsiElement?,
    /** 符号来源分类。 */
    val origin: SymbolOrigin,
)

/**
 * 符号来源证明引擎（无状态，线程安全）。
 *
 * 用法：
 * ```kotlin
 * val analysis = SymbolAnalyzer.analyze(call)          // 分析整个调用
 * val baseOrigin = SymbolAnalyzer.resolveOrigin(baseRef) // 只分析某个引用
 * ```
 */
object SymbolAnalyzer {

    /** 提供翻译函数（t/tc/\$t）的 i18n 框架包名。命中说明该 import 是"真实 i18n 框架"。 */
    private val I18N_FRAMEWORK_MODULES = setOf(
        "react-i18next", "vue-i18n", "i18next", "react-intl",
        "@solid-primitives/i18n", "@solid-hooks/i18n"
    )

    /** 通过解构 / 工厂调用生成翻译函数的已知 i18n 入口名。 */
    private val I18N_HOOK_OR_FACTORY_NAMES = setOf(
        "useI18n", "useTranslation", "useIntl", "getI18n", "createI18n", "createAppI18n", "initReactI18next"
    )

    /** 插件注入 / 约定的全局规范名（始终视为已翻译，因为它是本插件产出的形式）。 */
    private val PLUGIN_DOLLAR_T_NAMES = setOf("\$t", "\$tc")

    /** 链式接收者的**约定实例名**：vue-i18n / i18next 文档里的全局实例名。 */
    private val CONVENTIONAL_INSTANCE_NAMES = setOf("i18n", "i18next")

    /** 裸引用名若为此集合，才值得做来源证明；其它名字走「非翻译名 → 依赖证明」通道。 */
    private val TRANSLATION_LIKE_NAMES = setOf("t", "tc", "\$t", "\$tc")

    /** `from '<src>'` 模块源提取（固定模式，多处复用，避免每次 new Regex / Pattern.compile）。 */
    private val FROM_SOURCE_RE = Regex("""from\s*['"]([^'"]+)['"]""")

    /** `export * from '<src>'` 星号全量 re-export 提取（固定模式）。 */
    private val EXPORT_STAR_FROM_RE = Regex("""export\s*\*\s*from\s*['"]([^'"]+)['"]""")

    /** `resolve()` 返回 null 的哨兵（obj 为 VirtualFile 本体的占位，非冲突）。 */
    private object NO_RESOLVE

    /**
     * 单次 PSI 遍历预收集的完整文件扫描数据，替代 5 次独立 [PsiTreeUtil.findChildrenOfType]。
     * 在 [fileScanGen] 创建新 [FileScanMemo] 时一次性构建，后续所有 per-name 查询命中
     * 预计算数据结构（Map/Set），无需再次遍历 PSI 树。
     */
    private class FileScanData(
        /** 本地变量（排除 import 绑定）：name → 同名声明列表（不同函数作用域可同名） */
        val localVariables: Map<String, List<JSVariable>>,
        /** 本地函数声明：name → 同名声明列表（不同函数作用域可同名） */
        val localFunctions: Map<String, List<JSFunction>>,
        /** 所有 import 声明 */
        val importDeclarations: List<ES6ImportDeclaration>,
        /** 所有 var 语句 */
        val varStatements: List<JSVarStatement>,
        /** 所有 shadow 声明名（文本级正则一次扫出） */
        val shadowNames: Set<String>,
    )

    private class FileScanMemo(val stamp: Long, val scanData: FileScanData) {
        /** #2 i18n 框架 import 检测缓存（含 barrel 跟随，开销较大） */
        val i18nImport = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /** #2 任意模块 import 检测缓存 */
        val anyModuleImport = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /** #4 `resolve()` 去重：键 = 引用表达式 offset（PSI 元素），值 = PsiElement | NO_RESOLVE */
        val resolveResult = java.util.concurrent.ConcurrentHashMap<Int, Any>()

        /** #3 同一 call 在 detect / collect 阶段被重复 analyze → 键 = call offset，值 = 完整分析结果 */
        val calleeAnalysis = java.util.concurrent.ConcurrentHashMap<Int, CalleeAnalysis>()

        /** 【TS 服务超时熔断】本文件此前 resolve 因平台超时抛 PCE → 后续引用不再尝试
         *  resolve（直接走文件级结构证明），避免同文件 N 个引用各等一次 20s 超时。 */
        @Volatile
        var resolveDegraded: Boolean = false
    }

    private val fileScanMemo = java.util.concurrent.ConcurrentHashMap<String, FileScanMemo>()

    /** #2 barrel/re-export 跟随结果缓存：key = "$path#$stamp#$localName"，跨调用复用，赢在消除对同一 barrel 链的重复递归。 */
    private val reexportCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** 取 [file] 对应缓存代。公式变时（WriteAction bump modificationStamp）整代重建，杜绝读陈旧 PSI。 */
    private fun fileScanGen(file: PsiFile): FileScanMemo? {
        val vf = file.virtualFile ?: return null   // 无虚拟文件（极个别内存/测试文件）→ 禁用缓存直接算
        val key = vf.path
        val stamp = vf.modificationStamp
        var memo = fileScanMemo[key]
        if (memo == null || memo.stamp != stamp) {
            val scanData = buildFileScanData(file)
            memo = FileScanMemo(stamp, scanData)
            fileScanMemo[key] = memo
            if (fileScanMemo.size > 2048) fileScanMemo.clear()   // 长期会话防无限增长
        }
        return memo
    }

    /**
     * 单次 PSI 遍历收集所有文件扫描数据，替代 5 次独立 [PsiTreeUtil.findChildrenOfType]。
     * 收集内容包括：本地变量、本地函数、import 声明、var 语句、shadow 声明名。
     * 该函数在文件修改时（modificationStamp 变化）重新执行，缓存命中后不再调用。
     */
    private fun buildFileScanData(file: PsiFile): FileScanData {
        val localVariables = mutableMapOf<String, MutableList<JSVariable>>()
        val localFunctions = mutableMapOf<String, MutableList<JSFunction>>()
        val importDeclarations = mutableListOf<ES6ImportDeclaration>()
        val varStatements = mutableListOf<JSVarStatement>()
        val shadowNames = buildShadowNames(file)

        fun scanNode(element: PsiElement) {
            when (element) {
                is JSVariable -> {
                    if (element.name != null &&
                        PsiTreeUtil.getParentOfType(element, ES6ImportDeclaration::class.java) == null
                    ) {
                        localVariables.getOrPut(element.name!!) { mutableListOf() }.add(element)
                    }
                }
                is JSFunction -> {
                    if (element.name != null && element.containingFile == file) {
                        localFunctions.getOrPut(element.name!!) { mutableListOf() }.add(element)
                    }
                }
                is ES6ImportDeclaration -> importDeclarations.add(element)
                is JSVarStatement -> varStatements.add(element)
            }
            var child = element.firstChild
            while (child != null) {
                scanNode(child)
                child = child.nextSibling
            }
        }
        scanNode(file)

        return FileScanData(
            localVariables = localVariables,
            localFunctions = localFunctions,
            importDeclarations = importDeclarations,
            varStatements = varStatements,
            shadowNames = shadowNames,
        )
    }

    /**
     * 【作用域可见性】声明 [declared] 对引用 [ref] 是否可见：
     * 取声明所在的最内层函数作用域（null = 模块顶层，全文件可见），
     * 引用必须位于该作用域内才可见。否则属于跨作用域污染 ——
     * 典型 bug：组件内 `const { t } = useTranslation()` 的 t 不能证明
     * **模块顶层** `t('等于')`（t 根本未定义）是翻译调用。
     */
    private fun isVisibleFrom(declared: PsiElement, ref: JSReferenceExpression?): Boolean {
        if (ref == null) return true
        // strict = true：从父级开始找 —— 否则函数声明（function t）自身就是 JSFunction，
        // 会被误当作「声明所在作用域」，导致顶层 function t 的真实作用域被误判。
        val scope = PsiTreeUtil.getParentOfType(declared, JSFunction::class.java, true)
            ?: return true // 模块级声明，文件内任意位置可见
        return PsiTreeUtil.isAncestor(scope, ref, false)
    }

    /** #4 按引用表达式 offset 缓存 resolve() 结果；同一引用在 detect/collect/祖先链里只解析一次。
     *  项目仍在索引（dumb）时**跳过 resolve()**：让 IDE 自己完成后台索引与
     *  TypeScript Config Graph / import graph 的构建，插件绝不主动承担这些初始化成本
     *  （否则会在无 Job/进度上下文的线程上触发 runBlockingCancellable 而抛 IllegalStateException）。
     *  跳过时仅走文件级结构证明（第二层证据），零成本、零崩溃；等 IDE 分析就绪后再恢复精确 resolve。
     *  【TS 服务超时熔断】resolve 可能因 TypeScript 服务内部超时（20s TimeoutCancellationException
     *  包装成 CeProcessCanceledException）抛 PCE：用户主动取消（indicator.isCanceled）必须向上传播；
     *  平台内部超时则熔断为 resolve 失败（null），走文件级结构证明回退——单次 resolve 超时
     *  不再中断整文件分析（与 I18nExtractionOrchestrator 的 PCE 策略一致）。 */
    private fun resolvedOf(ref: JSReferenceExpression?): PsiElement? {
        if (ref == null) return null
        // dumb 模式下不触发 resolve（单元测试保留 resolve 以维持示例/断言完整语义）
        val containingFile = ref.containingFile as? PsiFile
        if (containingFile != null &&
            !ApplicationManager.getApplication().isUnitTestMode &&
            DumbService.isDumb(containingFile.project)
        ) return null
        val memo = if (containingFile != null) fileScanGen(containingFile) else null
        // 本文件此前已发生 resolve 超时熔断 → 不再尝试 resolve（避免 N 个引用各等 20s 超时）
        if (memo?.resolveDegraded == true) return null
        // 用 ReadAction.compute 包裹 resolve()，确保 IntelliJ 线程上下文中有 ProgressIndicator/Job，
        // 避免 TypeScript resolve 引擎中的 runBlockingCancellable 因缺少进度上下文而抛
        // "There is no ProgressIndicator or Job in this thread" 异常。
        fun resolveInReadAction(): PsiElement? = try {
            ReadAction.compute<PsiElement?, Throwable> { ref.resolve() }
        } catch (pce: ProcessCanceledException) {
            // 单元测试保持严格语义：PCE 一律上抛（测试环境无 TS 服务 20s 超时，出现即异常）
            if (ApplicationManager.getApplication().isUnitTestMode) throw pce
            // 用户主动取消（进度指示器已取消）→ 控制流异常必须传播，不得吞掉
            if (ProgressManager.getInstance().progressIndicator?.isCanceled == true) throw pce
            // 平台内部超时 → 熔断：本文件后续引用跳过 resolve，走文件级结构证明
            memo?.resolveDegraded = true
            null
        }
        if (memo == null) return resolveInReadAction()
        return when (val v = memo.resolveResult.computeIfAbsent(ref.textRange.startOffset) { resolveInReadAction() ?: NO_RESOLVE }) {
            NO_RESOLVE -> null
            else -> v as PsiElement
        }
    }

    /** 找文件中名为 [name] 的本地变量声明（排除 import 绑定），且对 [ref] 作用域可见。 */
    private fun findLocalVariableNamedCached(file: PsiFile, name: String, ref: JSReferenceExpression?): JSVariable? {
        val memo = fileScanGen(file)
            ?: return findLocalVariableNamed(file, name)?.takeIf { isVisibleFrom(it, ref) }
        // 最近作用域优先：同名声明多处可见时（如模块顶层别名 + 函数内 hook 解构），
        // 嵌套最深的声明才是引用的真实解析目标（JS 作用域遮蔽规则）。
        return memo.scanData.localVariables[name]
            ?.filter { isVisibleFrom(it, ref) }
            ?.maxByOrNull { scopeDepth(it) }
    }

    /** 声明的函数作用域嵌套深度（0 = 模块顶层）；越深作用域越近。 */
    private fun scopeDepth(element: PsiElement): Int {
        var depth = 0
        var p: PsiElement? = PsiTreeUtil.getParentOfType(element, JSFunction::class.java, true)
        while (p != null) {
            depth++
            p = PsiTreeUtil.getParentOfType(p, JSFunction::class.java, true)
        }
        return depth
    }

    private fun findLocalFunctionNamedCached(file: PsiFile, name: String, ref: JSReferenceExpression?): Boolean {
        val memo = fileScanGen(file)
            ?: return findLocalFunctionNamed(file, name) // 无缓存路径：resolve 已优先，回退语义保持
        return memo.scanData.localFunctions[name]?.any { isVisibleFrom(it, ref) } == true
    }

    private fun hasLocalShadowDeclarationTextCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file) ?: return hasLocalShadowDeclarationText(file, name)
        return name in memo.scanData.shadowNames
    }

    private fun importedFromI18nFrameworkInCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file)
        if (memo == null) return importedFromI18nFrameworkIn(file, name)
        return memo.i18nImport.computeIfAbsent(name) {
            memo.scanData.importDeclarations.any { decl -> importsFromI18nFramework(decl, it) }
        }
    }

    private fun importedFromAnyModuleInCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file)
        if (memo == null) return importedFromAnyModuleIn(file, name)
        return memo.anyModuleImport.computeIfAbsent(name) {
            memo.scanData.importDeclarations.any { decl -> importLocalNameMatches(decl.text, it) }
        }
    }

    private fun destructuredFromKnownHookInCached(file: PsiFile, name: String, ref: JSReferenceExpression?): Boolean {
        val memo = fileScanGen(file)
        if (memo == null) return destructuredFromKnownHookIn(file, name)
        // 作用域可见性过滤：hook 解构（const { t } = useTranslation()）不能跨函数作用域
        // 证明别处的 t 调用（如模块顶层的 t(...)）—— 否则属于作用域污染误判。
        return memo.scanData.varStatements
            .filter { stmt -> isVisibleFrom(stmt, ref) }
            .any { stmt ->
                val declaresName = stmt.text.contains("{") && stmt.text.contains(name)
                declaresName && varStatementHasKnownHook(stmt)
            }
    }

    /**
     * 【#5】一次扫描 [file] 文本，提取所有"类声明名"（namespace/module/class/interface/type、
     * function、const/let/var 关键字后紧跟的标识符）。与 [hasLocalShadowDeclarationText] 逐名正则
     * 语义完全一致（正则同样不跳过注释/字符串，故提取组也如此），只是改成"每文件一次"。
     */
    private fun buildShadowNames(file: PsiFile): Set<String> {
        val text = file.text
        val names = java.util.HashSet<String>()
        // A/B：namespace/module/class/interface/type/function 后紧跟的名字
        kotlin.text.Regex("""\b(?:namespace|module|class|interface|type|function)\s+([A-Za-z_\$][\w\$]*)""")
            .findAll(text).forEach { names.add(it.groupValues[1]) }
        // C：const/let/var 后紧跟的名字，且后面必须是 = / : / , / ; / ) / 空白（与逐名正则语义一致）
        kotlin.text.Regex("""\b(?:const|let|var)\s+([A-Za-z_\$][\w\$]*)\s*(=|\b:|[,;)\s])""")
            .findAll(text).forEach { names.add(it.groupValues[1]) }
        return names
    }

    /**
     * 分析一个翻译候选调用。
     *
     * @return 分解 + 来源分析；[CalleeAnalysis.origin] 为最终判定。
     */
    /** #3 同一 call 在 detect / collect 阶段被重复 analyze → 按 call offset 缓存完整分析结果。 */
    fun analyze(call: JSCallExpression): CalleeAnalysis {
        val file = call.containingFile as? PsiFile
        val memo = if (file != null) fileScanGen(file) else null
        if (memo == null) return analyzeUncached(call)
        return memo.calleeAnalysis.computeIfAbsent(call.textRange.startOffset) { analyzeUncached(call) }
    }

    private fun analyzeUncached(call: JSCallExpression): CalleeAnalysis {
        val method = call.methodExpression
        if (method !is JSReferenceExpression) {
            // 复杂 callee（如 `(cond ? t : f)('x')`、`obj[method]('x')`）无法分解 → 保守 UNKNOWN
            return CalleeAnalysis(
                shape = CalleeShape.OTHER,
                name = null,
                baseReference = null,
                resolved = null,
                origin = SymbolOrigin.UNKNOWN,
            )
        }

        val name = method.referenceName
        val qualifier = method.qualifier
        if (qualifier == null) {
            // ── 裸名形态：`t(...)` / `$t(...)` / `translate(...)` ──
            if (name in PLUGIN_DOLLAR_T_NAMES) {
                return CalleeAnalysis(
                    shape = CalleeShape.BARE_NAME, name = name,
                    baseReference = method, resolved = resolvedOf(method),
                    origin = SymbolOrigin.PLUGIN_DOLLAR_T,
                )
            }
            // 裸名（无论是否 t/tc 形状）：来源证明决定一切。
            //  - `import { t as translate }; translate('x')` 名字非 t → 由框架 import 证明兜住；
            //  - `foo('x')` 名字非 t 且无 import 证明 → 非翻译通道 / UNKNOWN。
            val resolved = resolvedOf(method)
            return CalleeAnalysis(
                shape = CalleeShape.BARE_NAME, name = name,
                baseReference = method, resolved = resolved,
                origin = resolveOrigin(method, resolved, instanceTrust = false),
            )
        }

        // ── 链式形态：`X.t(...)` / `i18n.global.t(...)` ──
        val baseRef = deepestBaseReference(method)
        val resolvedBase = resolvedOf(baseRef)
        return CalleeAnalysis(
            shape = CalleeShape.CHAINED,
            name = name,
            baseReference = baseRef,
            resolved = resolvedBase,
            origin = resolveOrigin(baseRef, resolvedBase, instanceTrust = true),
        )
    }

    /**
     * 沿 qualifier 链下钻到最深的接收者引用。
     * `i18n.global.t` → `i18n.global` → `i18n`（返回 `i18n` 那个 JSReferenceExpression）。
     */
    fun deepestBaseReference(method: JSReferenceExpression): JSReferenceExpression? {
        var qualifier: JSReferenceExpression? = method.qualifier as? JSReferenceExpression
        var base: JSReferenceExpression? = null
        while (qualifier != null) {
            base = qualifier
            qualifier = qualifier.qualifier as? JSReferenceExpression
        }
        return base
    }

    /**
     * 解析一个引用并分类其来源。resolve 优先，失败时回退文件级结构证明。
     *
     * @param instanceTrust 仅链式接收者置 true：允许「约定实例名 i18n/i18next」作为弱结构证明。
     */
    fun resolveOrigin(
        ref: JSReferenceExpression?,
        resolved: PsiElement? = resolvedOf(ref),
        instanceTrust: Boolean = false,
    ): SymbolOrigin {
        if (ref == null) return SymbolOrigin.UNKNOWN
        val name = ref.referenceName ?: return SymbolOrigin.UNKNOWN

        // ── 第一层证据：Reference Resolution ──
        // ⚠️ JS 解析器对「未定义引用」可能宽松命中文件内同名声明（顶层 t → 组件内
        // hook 解构的 t），故本地声明类 resolve 结果同样要做作用域可见性过滤，
        // 不可见的命中视为无效证据，继续走文件级回退。
        if (resolved != null) {
            val origin = classifyResolved(resolved, name)
            if (origin != null) {
                val scopeViolation = (resolved is JSVariable || resolved is JSFunction) &&
                    !isVisibleFrom(resolved, ref)
                if (!scopeViolation) return origin
            }
        }

        // ── 第二层证据：文件级结构扫描（resolve 失败 / 无真实模块时）──
        // ⚠️ 本层所有「本地声明」证据均带作用域可见性过滤（isVisibleFrom）：
        // 组件内 `const { t } = useTranslation()` 只能证明**同组件内**的 t(...) 调用，
        // 不能证明模块顶层 t(...)（t 未定义，运行时本就会崩）—— 否则属作用域污染。
        val file = ref.containingFile ?: return SymbolOrigin.UNKNOWN

        // 1) 本地同名变量声明（const/let/var 名为 name，排除 import 绑定）→ 按 initializer 形状分类
        val localVar = findLocalVariableNamedCached(file, name, ref)
        if (localVar != null) return classifyLocalDeclaration(localVar)

        // 2) 本地同名函数声明（function t() {}）→ 本地 shadow
        if (findLocalFunctionNamedCached(file, name, ref)) return SymbolOrigin.LOCAL_SHADOW

        // 3) 本地声明文本证明（namespace/class/module/interface/type/const 开头声明）→ 本地 shadow。
        //    覆盖 TS namespace（`namespace ns { export function t }`）等 PSI 类型不易枚举的场景。
        if (hasLocalShadowDeclarationTextCached(file, name)) return SymbolOrigin.LOCAL_SHADOW

        // 4) 文件级 import 扫描：name 出现在 i18n 框架模块 import（含别名）→ 框架
        if (importedFromI18nFrameworkInCached(file, name)) return SymbolOrigin.I18N_FRAMEWORK_IMPORT

        // 5) 链式接收者的约定实例名（仅 instanceTrust）：i18n / i18next。
        //    放在「非框架 import」之前：`import i18n from '@/locales/i18n'`（自定义 locale 文件，
        //    内部 createI18n 再导出）是 vue-i18n 生态的强约定，应视为已确认实例而非普通模块。
        if (instanceTrust && name in CONVENTIONAL_INSTANCE_NAMES) {
            return SymbolOrigin.I18N_HOOK_OR_FACTORY
        }

        // 6) 文件级 import 扫描：name 从任何非框架模块 import → 结构上非 i18n
        if (importedFromAnyModuleInCached(file, name)) return SymbolOrigin.NON_I18N

        // 7) 文件级解构扫描：`const { name } = <knownHook>()` → hook 产物
        if (destructuredFromKnownHookInCached(file, name, ref)) return SymbolOrigin.I18N_HOOK_OR_FACTORY

        // 无任何证据 → 三态 UNKNOWN（既不提取也不改写）
        return SymbolOrigin.UNKNOWN
    }

    // ───────────────────────────────────────────────
    // 第一层：resolve 命中后的分类
    // ───────────────────────────────────────────────

    /** 对 resolve 到的声明分类；无法分类时返回 null（让调用方走文件级回退）。 */
    private fun classifyResolved(resolved: PsiElement, name: String): SymbolOrigin? {
        // import 声明：`import { t } from 'vue-i18n'` / `import i18n from 'react-i18next'`
        if (resolved is ES6ImportDeclaration) {
            return if (importsFromI18nFramework(resolved, name)) {
                SymbolOrigin.I18N_FRAMEWORK_IMPORT
            } else {
                SymbolOrigin.NON_I18N
            }
        }

        // 本地函数声明：`function t() {}` → 本地 shadow
        if (resolved is JSFunction) return SymbolOrigin.LOCAL_SHADOW

        // 本地变量声明：`const i18n = ...` / `const t = ...`
        if (resolved is JSVariable) return classifyLocalDeclaration(resolved)

        // 其它声明（参数、属性等）→ 无法证明 → 走文件级回退
        return null
    }

    /** 分类一个 JSVariable（本地声明或 import 绑定）。 */
    private fun classifyLocalDeclaration(variable: JSVariable): SymbolOrigin {
        // import 绑定（`import { t } from ...` 中 t 的 specifier 绑定）挂在 import 声明下
        val importDecl = PsiTreeUtil.getParentOfType(variable, ES6ImportDeclaration::class.java)
        if (importDecl != null) {
            return if (importsFromI18nFramework(importDecl, variable.name)) {
                SymbolOrigin.I18N_FRAMEWORK_IMPORT
            } else {
                SymbolOrigin.NON_I18N
            }
        }

        val initializer = variable.initializer

        // 本地对象字面量：`const i18n = { t: ... }`（issue #38 根因场景）→ 本地 shadow
        if (initializer is JSObjectLiteralExpression) return SymbolOrigin.LOCAL_SHADOW

        // 本地普通函数 / 箭头函数：`const t = () => ...` / `const t = function(){}`
        if (initializer is JSFunction) return SymbolOrigin.LOCAL_SHADOW

        // 已知 i18n hook / 工厂调用产物：`const i18n = createI18n(...)` / `const t = getI18n()...`
        if (initializer is JSCallExpression) {
            val calleeName = (initializer.methodExpression as? JSReferenceExpression)?.referenceName
            if (calleeName != null && calleeName in I18N_HOOK_OR_FACTORY_NAMES) {
                return SymbolOrigin.I18N_HOOK_OR_FACTORY
            }
            // 其它函数调用产物（`const i18n = makeI18n()`）→ 无法证明 → UNKNOWN
            return SymbolOrigin.UNKNOWN
        }

        // 引用链产物：`const t = getI18n().t` / `const t = useI18n().t` —— initializer 是引用链，
        // 下钻到最深接收者：若是已知 hook/工厂调用（getI18n()/useI18n()/...），则为翻译函数别名。
        if (initializer is JSReferenceExpression) {
            var q: JSExpression? = initializer
            while (q is JSReferenceExpression) {
                val receiver = q.qualifier
                if (receiver is JSCallExpression) {
                    val calleeName = (receiver.methodExpression as? JSReferenceExpression)?.referenceName
                    if (calleeName != null && calleeName in I18N_HOOK_OR_FACTORY_NAMES) {
                        return SymbolOrigin.I18N_HOOK_OR_FACTORY
                    }
                }
                q = receiver as? JSReferenceExpression
            }
            // 别名链尾（最深接收者）按链式调用的实例信任规则判定：
            //   `const t = i18n.t`（React locale 实例别名）/ `const $t = i18n.global.t`（vue-i18n
            //   全局别名）的链尾 i18n 经 resolve / 框架 barrel / 约定实例名证明 → 翻译函数别名。
            // 本地 shadow（`const i18n = { t }` → LOCAL_SHADOW / UNKNOWN）不在此列。
            val baseRef = deepestBaseReference(initializer)
            if (baseRef != null) {
                val baseOrigin = resolveOrigin(baseRef, instanceTrust = true)
                if (baseOrigin == SymbolOrigin.I18N_HOOK_OR_FACTORY ||
                    baseOrigin == SymbolOrigin.I18N_FRAMEWORK_IMPORT
                ) {
                    return SymbolOrigin.I18N_HOOK_OR_FACTORY
                }
            }
            // `const t = someObj.t` / `const i18n = otherI18n` 等 → 无法证明 → UNKNOWN
            return SymbolOrigin.UNKNOWN
        }

        // 解构绑定：`const { t } = useI18n()` —— initializer 为 null，查引入语句的 hook 调用
        if (initializer == null) {
            val varStmt = PsiTreeUtil.getParentOfType(variable, JSVarStatement::class.java)
            if (varStmt != null && varStatementHasKnownHook(varStmt)) {
                return SymbolOrigin.I18N_HOOK_OR_FACTORY
            }
            return SymbolOrigin.UNKNOWN
        }

        // 其它初始化（`const t = something`）→ 无法证明 → UNKNOWN
        return SymbolOrigin.UNKNOWN
    }

    // ───────────────────────────────────────────────
    // 第二层：文件级结构证明（不依赖 resolve）
    // ───────────────────────────────────────────────

    /** 找文件中名为 [name] 的本地变量声明（排除 import 绑定）。 */
    private fun findLocalVariableNamed(file: PsiFile, name: String): JSVariable? {
        return PsiTreeUtil.findChildrenOfType(file, JSVariable::class.java).firstOrNull { v ->
            v.name == name &&
                PsiTreeUtil.getParentOfType(v, ES6ImportDeclaration::class.java) == null
        }
    }

    /** 找文件中名为 [name] 的函数声明（`function t() {}`）。 */
    private fun findLocalFunctionNamed(file: PsiFile, name: String): Boolean {
        return PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java).any { f ->
            f.name == name && f.containingFile == file
        }
    }

    /**
     * 本地声明文本证明：name 以 `namespace/class/module/interface/type/function` 声明，
     * 或以 `const/let/var <name> =`（含 `:` 类型标注）开头声明。
     * 覆盖 TS namespace（`namespace ns { export function t }`）等 PSI 类型不易枚举的场景。
     * 仅当调用发生在本文件内时可信（shadow 语义：本地同名声明遮蔽任何外部 i18n 符号）。
     */
    private fun hasLocalShadowDeclarationText(file: PsiFile, name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("""\b(namespace|module|class|interface|type)\s+$escaped\b""").containsMatchIn(file.text) ||
            Regex("""\b(function)\s+$escaped\b""").containsMatchIn(file.text) ||
            Regex("""\b(const|let|var)\s+$escaped\b\s*(=|\b:|[,;)\s])""").containsMatchIn(file.text)
    }

    /** 文件级 import 扫描：name 是否从 i18n 框架模块导入（含别名 / 默认 / namespace）。 */
    private fun importedFromI18nFrameworkIn(file: PsiFile, name: String): Boolean {
        return PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java).any { decl ->
            importsFromI18nFramework(decl, name)
        }
    }

    /** 文件级 import 扫描：name 是否从任何模块导入（用于证明"非 i18n 来源"）。 */
    private fun importedFromAnyModuleIn(file: PsiFile, name: String): Boolean {
        return PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java).any { decl ->
            importLocalNameMatches(decl.text, name)
        }
    }

    /** 文件级解构扫描：`const { name } = <knownHook>()` / `const name = <knownHook>().t`。 */
    private fun destructuredFromKnownHookIn(file: PsiFile, name: String): Boolean {
        return PsiTreeUtil.findChildrenOfType(file, JSVarStatement::class.java).any { stmt ->
            // 语句必须解构/赋出了 [name]
            val declaresName = stmt.text.contains("{") && stmt.text.contains(name)
            declaresName && varStatementHasKnownHook(stmt)
        }
    }

    /**
     * 判断 var 语句中是否存在已知 i18n hook / 工厂调用。
     *
     * 【作用域约束】hook 调用必须与本 var 语句处于**同一函数作用域**：
     * `getParentOfType(call, JSFunction) == getParentOfType(varStmt, JSFunction)`。
     * 否则 `const Comp = () => { const {t} = useTranslation() }` 这条**顶层**语句里的
     * 组件内部 hook 调用，会"证明"顶层任意 `t(...)`（t 实际未定义）来自 hook ——
     * 属作用域污染（issue：模块顶层 t('等于') 被组件内 useTranslation 误证）。
     */
    private fun varStatementHasKnownHook(varStmt: JSVarStatement): Boolean {
        val stmtScope = PsiTreeUtil.getParentOfType(varStmt, JSFunction::class.java)
        return PsiTreeUtil.findChildrenOfType(varStmt, JSCallExpression::class.java).any { call ->
            (call.methodExpression as? JSReferenceExpression)?.referenceName in I18N_HOOK_OR_FACTORY_NAMES &&
                PsiTreeUtil.getParentOfType(call, JSFunction::class.java) == stmtScope
        }
    }

    // ───────────────────────────────────────────────
    // import 文本解析（跨 IntelliJ 版本稳定）
    // ───────────────────────────────────────────────

    /** 判断 import 声明是否从 i18n 框架包导入了 [localName]（含别名 `as` / 默认 / namespace）。 */
    private fun importsFromI18nFramework(decl: ES6ImportDeclaration, localName: String?): Boolean {
        if (localName == null) return false
        val text = decl.text
        val src = FROM_SOURCE_RE.find(text)
            ?.groupValues?.get(1)?.trim()?.lowercase() ?: return false
        if (src in I18N_FRAMEWORK_MODULES) {
            return importLocalNameMatches(text, localName)
        }
        // 非框架模块：可能是 barrel / re-export 中转（`@/i18n` 再 `export { t } from 'vue-i18n'`）。
        // 跟随以【被导入的原始导出名】（`X as localName` 的 X）为准，而非本地别名 localName，
        // 否则 `import { t as midT }` / `import { i18n as l10n }` 的本地别名名会因不在候选集而漏判。
        return importIsBarrelReExportFromFramework(decl, localName)
    }

    /** 判断 [importText] 是否把某个 specifier 绑定成了本地名 [name]（含 `X as <name>` 别名与直接 `X`）。 */
    private fun importLocalNameMatches(importText: String, name: String): Boolean {
        if (Regex("""\bas\s+\Q$name\E\b""").containsMatchIn(importText)) return true
        val curlyIdxS = importText.indexOf('{')
        val curlyIdxE = importText.lastIndexOf('}')
        if (curlyIdxS in 0 until curlyIdxE) {
            val inner = importText.substring(curlyIdxS + 1, curlyIdxE)
            if (Regex("""(^|[,\s])\Q$name\E(\s+as\b|$|[,\s])""").containsMatchIn(inner)) return true
        }
        return false
    }

    /** 供测试 / 调试：判断模块名是否属于已知 i18n 框架。 */
    fun isI18nFrameworkModule(moduleName: String): Boolean =
        moduleName.trim().lowercase() in I18N_FRAMEWORK_MODULES

    // ───────────────────────────────────────────────
    // barrel / re-export 跟随
    // ───────────────────────────────────────────────

    /** 从非框架 import 出发，跟随 barrel / re-export 链，判定其是否最终来自 i18n 框架。 */
    private fun importIsBarrelReExportFromFramework(decl: ES6ImportDeclaration, localName: String): Boolean {
        val srcMatch = FROM_SOURCE_RE.find(decl.text)
            ?.groupValues?.get(1)?.trim() ?: return false
        val target = resolveSourceFile(decl.containingFile, srcMatch) ?: return false
        // namespace import（`import * as ns from '@/i18n'`）：namespace 的任意导出来自框架，
        // 或本地导出 i18n 实例 → 整个命名空间即 i18n。
        if (decl.text.contains("* as")) {
            return fileExportsAnythingFromFramework(target, mutableSetOf())
        }
        // 具名 import：还原被导入的原始导出名（`X as localName` → X），再沿 barrel 递归证明
        // （枚举 `* as` 在此之上，故这里的 default 导入 `import i18n from '@/x'` rawName 即 i18n）。
        val rawName = rawImportedName(decl.text, localName) ?: return false
        return fileReExportsNameFromFramework(target, rawName, mutableSetOf())
    }

    /** 从 import 文本取被导入的原始导出名：`X as localName` → X；直接 `X` → X；无则返回 null。 */
    private fun rawImportedName(importText: String, localName: String): String? {
        Regex("""([A-Za-z_\$][\w\$]*)\s+as\s+\Q$localName\E\b""")
            .find(importText)?.groupValues?.get(1)?.let { return it }
        return localName
    }

    /** 【#2】barrel/re-export 缓存键：path + 文件版本 + 收敛名，文件修改后整键失效。 */
    private fun barrelCacheKey(barrel: PsiFile, localName: String): String {
        val vf = barrel.virtualFile
        return if (vf != null) "${vf.path}#${vf.modificationStamp}#$localName"
        else "${barrel.name}#x#$localName"
    }

    /** 【#2】把 barrel 跟随结果写入缓存；超大时整表清空防长期会话内存膨胀。 */
    private fun cacheBarrelResult(key: String, value: Boolean) {
        reexportCache[key] = value
        if (reexportCache.size > 8192) reexportCache.clear()
    }

    /** barrel 是否从框架导出【任意】符号，或本地导出 i18n 惯例实例（namespace import 专用证明）。 */
    private fun fileExportsAnythingFromFramework(barrel: PsiFile, visited: MutableSet<String>): Boolean {
        val cacheKey = barrelCacheKey(barrel, "*")
        reexportCache[cacheKey]?.let { return it }
        if (!visited.add(barrel.virtualFile?.path ?: barrel.name)) return false // 环形跟随：剪枝（不落缓存，避免污染）
        val text = barrel.text
        var result = false
        // 直接/星号 re-export 自 i18n 框架包
        if (MODULES_PROVIDING_NATIVE_T_RESOLVED.any {
                Regex("""from\s*['"]\Q$it\E['"]""").containsMatchIn(text)
            }
        ) {
            result = true
        } else if (CONVENTIONAL_INSTANCE_NAMES.any { n ->
                Regex("""\bexport\s+const\s+\Q$n\E\b""").containsMatchIn(text) &&
                    Regex("""create(I18n|I18next|AppI18n)\s*\(""").containsMatchIn(text)
            }
        ) {
            result = true
        } else {
            // 递归：跟随嵌套 barrel（每个嵌套子问题同样经缓存收敛）
            for (m in EXPORT_STAR_FROM_RE.findAll(text)) {
                resolveSourceFile(barrel, m.groupValues[1])?.let {
                    if (fileExportsAnythingFromFramework(it, visited)) result = true
                }
                if (result) break
            }
            if (!result) {
                for (m in Regex("""export\s*\{[^}]*\}\s*from\s*['"]([^'"]+)['"]""").findAll(text)) {
                    resolveSourceFile(barrel, m.groupValues[1])?.let {
                        if (fileExportsAnythingFromFramework(it, visited)) result = true
                    }
                    if (result) break
                }
            }
        }
        cacheBarrelResult(cacheKey, result)
        return result
    }

    /**
     * 把一个模块引用字符串解析为项目内的 PSI 文件。
     * 支持 `./` / `../`（相对声明文件目录）与 `@/`（相对源码根）形态；
     * 失败时回退到全目录后缀扫描（覆盖别名差异分发到不同源码根的项目）。
     */
    private fun resolveSourceFile(baseFile: PsiFile?, rawSrc: String): PsiFile? {
        val project = baseFile?.project ?: return null
        val norm = rawSrc.trim()
        if (norm.lowercase() in I18N_FRAMEWORK_MODULES) return null // 框架包不是本地文件
        val baseDir = baseFile?.virtualFile?.parent
        val suffixes: List<String>
        if (norm.startsWith("./") || norm.startsWith("../")) {
            val rel = norm.removePrefix("./")
            suffixes = listOf(rel, "$rel.ts", "$rel.ts.tsx", "$rel.ts.ts", "$rel.ts.js")
            val local = baseDir?.findFileByRelativePath(rel)
            if (local != null) return toPsi(project, local)
        } else {
            val rel = norm.removePrefix("@/")
            suffixes = listOf(rel, "$rel.ts", "$rel.tsx", "$rel.js", "$rel/index.ts")
        }
        // 后缀扫描（覆盖 @/ 与 './' 的多种扩展名差异）
        for (suffix in suffixes) {
            var file: VirtualFile? = null
            file = ReadAction.compute<VirtualFile?, Throwable> {
                findSiblingSuffix(project, suffix)
            } ?: continue
            return toPsi(project, file)
        }
        return toPsi(project, baseDir?.findFileByRelativePath(suffixes.first()) ?: return null) as? PsiFile
    }

    private fun toPsi(project: Project, vf: VirtualFile?): PsiFile? =
        if (vf == null) null else PsiManager.getInstance(project).findFile(vf)

    /**
     * 【#1】用项目**文件名索引**查以 [suffix]（如 `src/i18n.ts`）结尾的文件，替代原先对
     * 所有内容根的整目录 `walkFind` 递归遍历。索引按 basename 定位后仅用后缀收尾校验，
     * 把「全项目扫一次」降为「索引 O(1) + 少量候选过滤」。
     */
    private fun findSiblingSuffix(project: Project, suffix: String): VirtualFile? {
        val name = suffix.substringAfterLast('/')
        return FilenameIndex.getVirtualFilesByName(name, ProjectScope.getAllScope(project))
            .asSequence()
            .firstOrNull { it.path.endsWith(suffix) }
    }

    /**
     * 判定 barrel 文件是否把 localName（可能已是别名，如 `t` 别名成 `midT`）的来源收敛到
     * i18n 框架（可跨一层或多层、可重命名）。覆盖四种形态：
     *  1. `export { localName } from '<src>'`（具名直转）；
     *  2. `export { X as localName } from '<src>'`（**别名直转**——递归时把 X 还原，
     *     否则在 src 里找不到 localName 会误判 NON_TRANSLATION）；
     *  3. `export * from '<framework>'`（星号全量）；
     *  4. `export const X = createI18n(...)` / `export const i18n = createI18n(...)`
     *     （本地导出 i18n 实例）——仅在 X 为惯例实例名时收敛到框架（跨文件 instance resolve）。
     *  5. `export { localName }`（无 from）—— 该名字需在本文件 import 自框架再导出。
     *
     * 别名链是关键：`import { midT } from '@/i18n-mid'`，而 `@/i18n-mid` 内
     * `export { t as midT } from '@/i18n-core'` —— 跟随必须把 `midT` 还原为 `t`，
     * 再沿 `@/i18n-core → vue-i18n` 收敛。
     */
    private fun fileReExportsNameFromFramework(
        barrel: PsiFile,
        localName: String,
        visited: MutableSet<String>,
    ): Boolean {
        val cacheKey = barrelCacheKey(barrel, localName)
        reexportCache[cacheKey]?.let { return it }
        if (!visited.add(barrel.virtualFile?.path ?: barrel.name)) return false // 环形跟随：剪枝（不落缓存）
        val text = barrel.text
        val name = Regex.escape(localName)
        var result = false

        val namedFrom = Regex("""export\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]""")

        // 形态 1 / 2：具名直转（含别名）。逐个 specifier 判断，别名时还原原始导出名跟随。
        for (m in namedFrom.findAll(text)) {
            if (result) break
            val src = m.groupValues[2].trim()
            val specifiers = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val hit = specifiers.firstOrNull { spec ->
                // 直接 `localName` 或 `X as localName`
                spec == localName || Regex("""\bas\s+\Q$localName\E\b""").containsMatchIn(spec)
            } ?: continue
            // 别名：取 `X as localName` 的 X；否则即 localName
            val rawName = Regex("""^([A-Za-z_\$][\w\$]*)\s+as\s+\Q$localName\E\b""")
                .find(hit)?.groupValues?.get(1) ?: localName
            if (isTranslationLikeOrConventional(rawName)) {
                if (src.lowercase() in MODULES_PROVIDING_NATIVE_T_RESOLVED) { result = true; break }
                val nested = resolveSourceFile(barrel, src) ?: continue
                if (fileReExportsNameFromFramework(nested, rawName, visited)) { result = true; break }
            }
        }

        // 形态 3：星号全量 re-export，且 localName 是翻译候选/惯例名（保守限定，避免全项目误命中）
        if (!result && isTranslationLikeOrConventional(localName)) {
            for (m in EXPORT_STAR_FROM_RE.findAll(text)) {
                val src = m.groupValues[1].trim()
                if (src.lowercase() in MODULES_PROVIDING_NATIVE_T_RESOLVED) { result = true; break }
                val nested = resolveSourceFile(barrel, src) ?: continue
                if (fileReExportsNameFromFramework(nested, localName, visited)) { result = true; break }
            }
        }

        // 形态 4：本地导出 i18n 实例（`export const i18n = createI18n(...)`，vue-i18n 惯例；
        // 或 React locale 的 `export default i18n`）—— 名字为惯例实例名时视为 i18n 实例
        // （跨文件 instance resolve，P1）。
        if (!result && localName in CONVENTIONAL_INSTANCE_NAMES &&
            (
                Regex("""\bexport\s+const\s+\Q$localName\E\b\s*=""").containsMatchIn(text) ||
                    Regex("""\bexport\s+default\s+\Q$localName\E\b""").containsMatchIn(text)
                )
        ) {
            result = true
        }

        // 形态 5：`export { localName }`（无 from）—— 该名字需在本文件 import 自框架再导出
        if (!result && Regex("""export\s*\{[^}]*\b$name\b[^}]*\}""").containsMatchIn(text)) {
            for (imp in PsiTreeUtil.findChildrenOfType(barrel, ES6ImportDeclaration::class.java)) {
                if (!importLocalNameMatches(imp.text, localName)) continue
                val impSrc = FROM_SOURCE_RE.find(imp.text)
                    ?.groupValues?.get(1)?.trim() ?: continue
                if (impSrc.lowercase() in MODULES_PROVIDING_NATIVE_T_RESOLVED) { result = true; break }
                val nested = resolveSourceFile(barrel, impSrc) ?: continue
                if (fileReExportsNameFromFramework(nested, localName, visited)) { result = true; break }
            }
        }
        cacheBarrelResult(cacheKey, result)
        return result
    }

    /** 名字是翻译候选或惯例实例名（用于限定是否需要跨模块跟随，避免对普通 import 全项目误扫）。 */
    private fun isTranslationLikeOrConventional(name: String): Boolean =
        name in TRANSLATION_LIKE_NAMES ||
            name in I18N_HOOK_OR_FACTORY_NAMES ||
            name in CONVENTIONAL_INSTANCE_NAMES

    private val MODULES_PROVIDING_NATIVE_T_RESOLVED: Set<String> by lazy { I18N_FRAMEWORK_MODULES }
}
