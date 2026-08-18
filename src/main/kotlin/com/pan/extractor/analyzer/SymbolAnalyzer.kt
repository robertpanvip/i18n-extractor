package com.pan.extractor.analyzer

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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
        "react-i18next", "vue-i18n", "i18next",
        "@solid-primitives/i18n", "@solid-hooks/i18n"
    )

    /** 通过解构 / 工厂调用生成翻译函数的已知 i18n 入口名。 */
    private val I18N_HOOK_OR_FACTORY_NAMES = setOf(
        "useI18n", "useTranslation", "getI18n", "createI18n", "createAppI18n", "initReactI18next"
    )

    /** 插件注入 / 约定的全局规范名（始终视为已翻译，因为它是本插件产出的形式）。 */
    private val PLUGIN_DOLLAR_T_NAMES = setOf("\$t", "\$tc")

    /** 链式接收者的**约定实例名**：vue-i18n / i18next 文档里的全局实例名。 */
    private val CONVENTIONAL_INSTANCE_NAMES = setOf("i18n", "i18next")

    /** 裸引用名若为此集合，才值得做来源证明；其它名字走「非翻译名 → 依赖证明」通道。 */
    private val TRANSLATION_LIKE_NAMES = setOf("t", "tc", "\$t", "\$tc")

    /**
     * 分析一个翻译候选调用。
     *
     * @return 分解 + 来源分析；[CalleeAnalysis.origin] 为最终判定。
     */
    fun analyze(call: JSCallExpression): CalleeAnalysis {
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
                    baseReference = method, resolved = method.resolve(),
                    origin = SymbolOrigin.PLUGIN_DOLLAR_T,
                )
            }
            // 裸名（无论是否 t/tc 形状）：来源证明决定一切。
            //  - `import { t as translate }; translate('x')` 名字非 t → 由框架 import 证明兜住；
            //  - `foo('x')` 名字非 t 且无 import 证明 → 非翻译通道 / UNKNOWN。
            val resolved = method.resolve()
            return CalleeAnalysis(
                shape = CalleeShape.BARE_NAME, name = name,
                baseReference = method, resolved = resolved,
                origin = resolveOrigin(method, resolved, instanceTrust = false),
            )
        }

        // ── 链式形态：`X.t(...)` / `i18n.global.t(...)` ──
        val baseRef = deepestBaseReference(method)
        val resolvedBase = baseRef?.resolve()
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
        resolved: PsiElement? = ref?.resolve(),
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
        val localVar = findLocalVariableNamed(file, name)
        if (localVar != null) return classifyLocalDeclaration(localVar)

        // 2) 本地同名函数声明（function t() {}）→ 本地 shadow
        if (findLocalFunctionNamed(file, name)) return SymbolOrigin.LOCAL_SHADOW

        // 3) 本地声明文本证明（namespace/class/module/interface/type/const 开头声明）→ 本地 shadow。
        //    覆盖 TS namespace（`namespace ns { export function t }`）等 PSI 类型不易枚举的场景。
        if (hasLocalShadowDeclarationText(file, name)) return SymbolOrigin.LOCAL_SHADOW

        // 4) 文件级 import 扫描：name 出现在 i18n 框架模块 import（含别名）→ 框架
        if (importedFromI18nFrameworkIn(file, name)) return SymbolOrigin.I18N_FRAMEWORK_IMPORT

        // 5) 链式接收者的约定实例名（仅 instanceTrust）：i18n / i18next。
        //    放在「非框架 import」之前：`import i18n from '@/locales/i18n'`（自定义 locale 文件，
        //    内部 createI18n 再导出）是 vue-i18n 生态的强约定，应视为已确认实例而非普通模块。
        if (instanceTrust && name in CONVENTIONAL_INSTANCE_NAMES) {
            return SymbolOrigin.I18N_HOOK_OR_FACTORY
        }

        // 6) 文件级 import 扫描：name 从任何非框架模块 import → 结构上非 i18n
        if (importedFromAnyModuleIn(file, name)) return SymbolOrigin.NON_I18N

        // 7) 文件级解构扫描：`const { name } = <knownHook>()` → hook 产物
        if (destructuredFromKnownHookIn(file, name)) return SymbolOrigin.I18N_HOOK_OR_FACTORY

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
        val src = Regex("""from\s*['"]([^'"]+)['"]""").find(text)
            ?.groupValues?.get(1)?.trim()?.lowercase() ?: return false
        if (src !in I18N_FRAMEWORK_MODULES) return false
        return importLocalNameMatches(text, localName)
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
}
