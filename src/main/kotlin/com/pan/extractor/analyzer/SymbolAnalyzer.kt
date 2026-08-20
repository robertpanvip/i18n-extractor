package com.pan.extractor.analyzer

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.ProjectScope
import com.intellij.psi.util.PsiTreeUtil

/**
 * 符号来源分类 —— 「t 是弱特征，不是语义证明」的核心引擎。
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

    /** 本地同名变量"不存在"的哨兵：ConcurrentHashMap.computeIfAbsent 存不了 null，用它占位。 */
    private object NO_LOCAL_VAR

    /** `resolve()` 返回 null 的哨兵（obj 为 VirtualFile 本体的占位，非冲突）。 */
    private object NO_RESOLVE

    private class FileScanMemo(val stamp: Long) {
        /** findLocalVariableNamed / resolve 等"可能为 null"结果的低成本缓存 */
        val localVar = java.util.concurrent.ConcurrentHashMap<String, Any>()   // JSVariable | NO_LOCAL_VAR
        val localFunction = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val i18nImport = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val anyModuleImport = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        val hookDestructure = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        /** #4 `resolve()` 去重：键 = 引用表达式 offset（PSI 元素），值 = PsiElement | NO_RESOLVE */
        val resolveResult = java.util.concurrent.ConcurrentHashMap<Int, Any>()

        /** #3 同一 call 在 detect / collect 阶段被重复 analyze → 键 = call offset，值 = 完整分析结果 */
        val calleeAnalysis = java.util.concurrent.ConcurrentHashMap<Int, CalleeAnalysis>()

        /** #5 本文件所有"声明名"集合（一次正则扫出全部，替代按名逐次对 file.text 正则） */
        val shadowNames = java.util.concurrent.atomic.AtomicReference<Set<String>?>(null)
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
            memo = FileScanMemo(stamp)
            fileScanMemo[key] = memo
            if (fileScanMemo.size > 2048) fileScanMemo.clear()   // 长期会话防无限增长
        }
        return memo
    }

    /** #4 按引用表达式 offset 缓存 resolve() 结果；同一引用在 detect/collect/祖先链里只解析一次。 */
    private fun resolvedOf(ref: JSReferenceExpression?): PsiElement? {
        if (ref == null) return null
        val memo = fileScanGen(ref.containingFile as? PsiFile ?: return ref.resolve())
            ?: return ref.resolve()
        return when (val v = memo.resolveResult.computeIfAbsent(ref.textRange.startOffset) { ref.resolve() ?: NO_RESOLVE }) {
            NO_RESOLVE -> null
            else -> v as PsiElement
        }
    }

    /** 找文件中名为 [name] 的本地变量声明（排除 import 绑定），带全文件级缓存。 */
    private fun findLocalVariableNamedCached(file: PsiFile, name: String): JSVariable? {
        val memo = fileScanGen(file)
        if (memo == null) return findLocalVariableNamed(file, name)
        return when (val v = memo.localVar.computeIfAbsent(name) { findLocalVariableNamed(file, it) ?: NO_LOCAL_VAR }) {
            NO_LOCAL_VAR -> null
            else -> v as JSVariable
        }
    }

    private fun findLocalFunctionNamedCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file)
        if (memo == null) return findLocalFunctionNamed(file, name)
        return memo.localFunction.computeIfAbsent(name) { findLocalFunctionNamed(file, it) }
    }

    private fun hasLocalShadowDeclarationTextCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file) ?: return hasLocalShadowDeclarationText(file, name)
        val set = memo.shadowNames.get() ?: buildShadowNames(file).also { memo.shadowNames.compareAndSet(null, it) }
        return name in set
    }

    private fun importedFromI18nFrameworkInCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file)
        if (memo == null) return importedFromI18nFrameworkIn(file, name)
        return memo.i18nImport.computeIfAbsent(name) { importedFromI18nFrameworkIn(file, it) }
    }

    private fun importedFromAnyModuleInCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file)
        if (memo == null) return importedFromAnyModuleIn(file, name)
        return memo.anyModuleImport.computeIfAbsent(name) { importedFromAnyModuleIn(file, it) }
    }

    private fun destructuredFromKnownHookInCached(file: PsiFile, name: String): Boolean {
        val memo = fileScanGen(file)
        if (memo == null) return destructuredFromKnownHookIn(file, name)
        return memo.hookDestructure.computeIfAbsent(name) { destructuredFromKnownHookIn(file, it) }
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
        kotlin.text.Regex("""\b(?:namespace|module|class|interface|type|function)\s+([A-Za-z_$][\w$]*)""")
            .findAll(text).forEach { names.add(it.groupValues[1]) }
        // C：const/let/var 后紧跟的名字，且后面必须是 = / : / , / ; / ) / 空白（与逐名正则语义一致）
        kotlin.text.Regex("""\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*(=|\b:|[,;)\s])""")
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
        if (resolved != null) {
            classifyResolved(resolved, name)?.let { return it }
        }

        // ── 第二层证据：文件级结构扫描（resolve 失败 / 无真实模块时）──
        val file = ref.containingFile ?: return SymbolOrigin.UNKNOWN

        // 1) 本地同名变量声明（const/let/var 名为 name，排除 import 绑定）→ 按 initializer 形状分类
        val localVar = findLocalVariableNamedCached(file, name)
        if (localVar != null) return classifyLocalDeclaration(localVar)

        // 2) 本地同名函数声明（function t() {}）→ 本地 shadow
        if (findLocalFunctionNamedCached(file, name)) return SymbolOrigin.LOCAL_SHADOW

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
        if (destructuredFromKnownHookInCached(file, name)) return SymbolOrigin.I18N_HOOK_OR_FACTORY

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

    /** 判断 var 语句中是否存在已知 i18n hook / 工厂调用。 */
    private fun varStatementHasKnownHook(varStmt: JSVarStatement): Boolean {
        return PsiTreeUtil.findChildrenOfType(varStmt, JSCallExpression::class.java).any { call ->
            (call.methodExpression as? JSReferenceExpression)?.referenceName in I18N_HOOK_OR_FACTORY_NAMES
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
        Regex("""([A-Za-z_$][\w$]*)\s+as\s+\Q$localName\E\b""")
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
        return FilenameIndex.getVirtualFilesByName(project, name, ProjectScope.getAllScope(project))
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
            val rawName = Regex("""^([A-Za-z_$][\w$]*)\s+as\s+\Q$localName\E\b""")
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

        // 形态 4：本地导出 i18n 实例（`export const i18n = createI18n(...)`）——
        // 名字为惯例实例名时视为 i18n 实例（跨文件 instance resolve，P1）。
        if (!result && localName in CONVENTIONAL_INSTANCE_NAMES &&
            Regex("""\bexport\s+const\s+\Q$localName\E\b\s*=\s*[^;\n]*?""")
                .containsMatchIn(text)
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
