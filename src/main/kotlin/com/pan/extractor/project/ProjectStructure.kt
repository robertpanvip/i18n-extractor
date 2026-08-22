package com.pan.extractor.project

import com.pan.extractor.log.PluginLogBuffer
import com.pan.extractor.locate.I18nInstanceLocator
import com.pan.extractor.bootstrap.I18nBootstrapSupport
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReturnStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecma6.TypeScriptFunctionExpression
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.relativeToOrNull

/**
 * 项目/React/Vue 组件与 i18n 实例定位。
 * 从 [Util] 拆分而来，行为不变。
 */
object ProjectStructure {

    private val LOG = Logger.getInstance(ProjectStructure::class.java)

    /** 高频复用正则：避免每次在循环里重复编译（性能）。 */
    private val REACT_KEY_RE = Regex(""""react"\s*:\s*"""")
    private val PREACT_KEY_RE = Regex(""""preact"\s*:\s*"""")
    private val VUE_KEY_RE = Regex(""""vue"\s*:\s*"""")
    private val SOLID_KEY_RE = Regex(""""solid-js"\s*:\s*"""")
    private val SVELTE_KEY_RE = Regex(""""svelte"\s*:\s*"""")
    private val NGX_TRANSLATE_KEY_RE = Regex(""""@ngx-translate/core"\s*:\s*"""")
    private val ANGULAR_CORE_KEY_RE = Regex(""""@angular/core"\s*:\s*""")
    /** @jsverse/transloco（更名后）与 @ngneat/transloco（旧名）都识别为 Transloco。 */
    private val TRANSLOCLO_KEY_RE = Regex(""""@(?:jsverse|ngneat)/transloco"\s*:\s*""")

    /**
     * 提取 package.json 中所有依赖段（dependencies/devDependencies/peerDependencies/optionalDependencies）
     * 的原文，仅在其中判定框架依赖 key。（P1）否则 `"react": "build"` 等 scripts 名会被
     * [REACT_KEY_RE] 误判为 react 依赖。
     */
    private fun dependencyBlock(content: String): String {
        val sections = listOf(
            "dependencies", "devDependencies", "peerDependencies",
            "optionalDependencies", "bundledDependencies", "bundleDependencies"
        )
        val sb = StringBuilder()
        for (key in sections) {
            val m = Regex("\"$key\"\\s*:\\s*\\{").find(content) ?: continue
            val start = m.range.last + 1
            var depth = 1
            var inStr = false
            var esc = false
            var i = start
            while (i < content.length && depth > 0) {
                val c = content[i]
                when {
                    inStr -> when (c) {
                        '\\' -> esc = true
                        '"' -> inStr = false
                    }
                    c == '"' -> inStr = true
                    c == '{' -> depth++
                    c == '}' -> depth--
                }
                if (esc) esc = false
                i++
            }
            sb.append(content, start, (i - 1).coerceAtLeast(start)).append('\n')
        }
        return sb.toString()
    }

    fun isJSX(element: PsiElement): Boolean {
        // 第二步：向上遍历父节点，检查 JS 语法上下文（核心逻辑）
        var currentParent = element.parent
        while (currentParent != null) {
            // 场景1：函数返回值（return <div/>）- 所有版本都有 JSReturnStatement
            if (currentParent is JSReturnStatement) {
                return true
            }

            // 场景2：赋值表达式（a = <div/>）
            if (currentParent is JSAssignmentExpression) {
                return true
            }

            // 场景3：函数调用/参数（fn(<div/>)）- 所有版本都有 JSCallExpression
            if (currentParent is JSCallExpression) {
                return true
            }

            // 场景4：变量声明（let a = <div/>）- 通用判断（兼容所有版本）
            if (currentParent is JSVarStatement) {
                return true
            }

            // 场景5：三元表达式（flag ? <div/> : <span/>）- 所有版本都有 JSConditionalExpression
            if (currentParent is JSConditionalExpression) {
                return true
            }

            // 场景6：箭头函数（() => <div/>）- 所有版本都有 JSArrowFunctionExpression
            if (currentParent is TypeScriptFunctionExpression) {
                return true
            }

            // 场景7：对象属性（{ render: <div/> }）- 所有版本都有 JSProperty
            if (currentParent is JSProperty) {
                return true
            }

            // 场景8：数组元素（[<div/>, <span/>]）- 所有版本都有 JSArrayLiteralExpression
            if (currentParent is JSArrayLiteralExpression) {
                return true
            }

            // 场景9：export 导出（export default <div/>）
            if (currentParent is ES6ExportDefaultAssignment) {
                return true
            }
            currentParent = currentParent.parent
        }

        return false
    }

    /** package.json 依赖检测结果，支持解构。 */
    data class PackageDeps(
        val hasReact: Boolean,
        val hasVue: Boolean,
        val hasSolid: Boolean,
        // 注意：`parsed` 保持在位置 4 —— isReact/isVue/isSolid 仍用 4 位位置解构
        // （`(hasReact, hasVue, hasSolid, parsed)`），新字段须追加在最后，否则会错位。
        val parsed: Boolean,
        val hasSvelte: Boolean,
        val hasAngular: Boolean,
    )

    /**
     * 读取当前文件所在项目根（向上找最近 package.json）的依赖键值，
     * 返回 [PackageDeps] = (hasReact, hasVue, hasSolid, parsed)
     *
     * parsed=false 表示根本没找到 package.json，调用方需要 fallback 到其他策略。
     */
    private val sixFalse = PackageDeps(false, false, false, false, false, false)
    private fun readPackageJsonDependencies(psiFile: PsiFile): PackageDeps {
        var dir: VirtualFile? = psiFile.virtualFile?.parent ?: return sixFalse
        while (dir != null) {
            val pkgFile = dir.findChild("package.json")
            if (pkgFile != null) {
                return try {
                    val content = String(pkgFile.contentsToByteArray(), StandardCharsets.UTF_8)
                    val deps = dependencyBlock(content)
                    PackageDeps(
                        hasReact = deps.contains(REACT_KEY_RE) || deps.contains(PREACT_KEY_RE),
                        hasVue = deps.contains(VUE_KEY_RE),
                        hasSolid = deps.contains(SOLID_KEY_RE),
                        parsed = true,
                        hasSvelte = deps.contains(SVELTE_KEY_RE),
                        hasAngular = deps.contains(NGX_TRANSLATE_KEY_RE) || deps.contains(ANGULAR_CORE_KEY_RE),
                    )
                } catch (e: Exception) {
                    PluginLogBuffer.warn(LOG,"ProjectStructure: 读取 package.json 依赖失败，按未解析处理", e)
                    sixFalse
                }
            }
            dir = dir.parent
        }
        return sixFalse
    }

    /**
     * 判断当前元素是否处于 React 上下文（含 Preact）。
     * 仅依据 package.json 依赖判断：
     * 1. .vue 文件直接排除（Vue）
     * 2. 依赖 react 或 preact，且不依赖 vue/solid-js → React
     *    (若同时依赖两者=混合项目，优先级判定到 Vue，因为用户更常用 Vue)
     * 3. 找不到 package.json → 旧逻辑 fallback：hasReactDependency=原来的实现
     * 注意：Vue 项目中也可能有 .tsx 文件，因此不再通过文件后缀直接判断
     */
    fun isReact(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false

        // .vue 文件肯定是 Vue，不是 React
        if (containingFile.name.endsWith(".vue", ignoreCase = true)) {
            return false
        }

        val (hasReact, hasVue, hasSolid, parsed) = readPackageJsonDependencies(containingFile)
        return if (parsed) {
            hasReact && !hasVue && !hasSolid
        } else {
            // fallback：老逻辑（避免 IntelliJ 测试项目中没有 package.json 的场景）
            hasReact && !hasVue && !hasSolid // 没 parsed 两者默认 false，=false 正确
        }
    }

    /**
     * 判断当前元素是否处于 SolidJS 上下文（与 isReact 对称）。
     * 仅依据 package.json 依赖判断：
     * 1. .vue 文件直接排除（Vue）
     * 2. 依赖 solid-js 且不依赖 vue → SolidJS
     * 3. 找不到 package.json → false
     */
    fun isSolid(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false

        if (containingFile.name.endsWith(".vue", ignoreCase = true)) {
            return false
        }

        val (hasReact, hasVue, hasSolid, parsed) = readPackageJsonDependencies(containingFile)
        return if (parsed) {
            hasSolid && !hasVue
        } else {
            false
        }
    }

    /**
     * 判断当前元素是否处于 Svelte 上下文。
     *
     * 判定逻辑：
     * 1. `.svelte` 文件直接命中（Svelte SFC 后缀是强信号，优先于依赖判定）；
     * 2. 依赖 svelte（且不依赖 vue / solid-js）→ Svelte 项目里的 .ts/.js 等也按 Svelte 处理。
     *
     * 与 React/Solid/Vue 的"依赖像素级"判定保持一致；`.svelte` 后缀优先，
     * 避免 svelte+react 混合项目里 .svelte 文件被错判为其他框架。
     */
    fun isSvelte(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false

        if (containingFile.name.endsWith(".svelte", ignoreCase = true)) {
            return true
        }

        val (hasReact, hasVue, hasSolid, parsed, hasSvelte) =
            readPackageJsonDependencies(containingFile)
        return if (parsed) {
            hasSvelte && !hasVue && !hasSolid
        } else {
            false
        }
    }

    /**
     * 判断当前元素是否处于 Angular 上下文（面向 `ngx-translate`）。
     *
     * 判定逻辑（与 React/Solid/Svelte 的"依赖像素级"判定一致）：
     * 依赖 `@angular/core` 或 `@ngx-translate/core`，且不依赖 vue / solid-js / svelte
     * → 按 Angular 处理（.ts / .html 等）。找不到 package.json → false。
     */
    fun isAngular(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false
        if (containingFile.name.endsWith(".vue", ignoreCase = true) ||
            containingFile.name.endsWith(".svelte", ignoreCase = true)
        ) {
            return false
        }
        val (hasReact, hasVue, hasSolid, parsed, hasSvelte, hasAngular) =
            readPackageJsonDependencies(containingFile)
        return if (parsed) {
            hasAngular && !hasVue && !hasSolid && !hasSvelte
        } else {
            false
        }
    }

    /**
     * 判断当前元素是否处于 Transloco 上下文。
     *
     * 与 [isAngular] 同构，但面向 {@code @jsverse/transloco}（更名后）/{@code @ngneat/transloco}（旧名）：
     * 依赖 transloco，且不依赖 vue / solid-js / svelte → 按 Transloco 处理（.ts / .html 等）。
     * 找不到 package.json → false。
     */
    fun isTransloco(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false
        if (containingFile.name.endsWith(".vue", ignoreCase = true) ||
            containingFile.name.endsWith(".svelte", ignoreCase = true)
        ) {
            return false
        }
        var dir: VirtualFile? = containingFile.virtualFile?.parent ?: return false
        while (dir != null) {
            val pkgFile = dir.findChild("package.json")
            if (pkgFile != null) {
                return try {
                    val content = String(pkgFile.contentsToByteArray(), StandardCharsets.UTF_8)
                    val deps = dependencyBlock(content)
                    @Suppress("NAME_SHADOWING")
                    val hasTransloco = deps.contains(TRANSLOCLO_KEY_RE)
                    val hasVue = deps.contains(VUE_KEY_RE)
                    val hasSolid = deps.contains(SOLID_KEY_RE)
                    val hasSvelte = deps.contains(SVELTE_KEY_RE)
                    hasTransloco && !hasVue && !hasSolid && !hasSvelte
                } catch (e: Exception) {
                    PluginLogBuffer.warn(LOG,"ProjectStructure: 读取 package.json 依赖失败，按未解析处理（isTransloco）", e)
                    false
                }
            }
            dir = dir.parent
        }
        return false
    }

    /**
     * 判断当前元素是否处于 Vue 上下文（与 isReact 对称）。
     * 仅依据 package.json 依赖判断：
     * 1. .vue 文件直接命中 Vue
     * 2. 依赖 vue → Vue
     *    (若同时依赖 react+vue = 混合项目，仍判 Vue，符合上面 isReact 的"优先级 Vue"对称)
     * 3. 找不到 package.json 且 不是 React 项目 → 默认按 Vue 兜底（历史行为）
     */
    fun isVue(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false

        if (containingFile.name.endsWith(".vue", ignoreCase = true)) {
            return true
        }

        val (hasReact, hasVue, hasSolid, parsed) = readPackageJsonDependencies(containingFile)
        return if (parsed) {
            hasVue
        } else {
            // fallback：历史行为——"没判定成 React 就算 Vue"。
            !isReact(element)
        }
    }

    /**
     * 查找文件中的所有 React 组件函数（顶级作用域）
     * 判断标准（满足任一即可）：
     * 1. 函数名 PascalCase 或以 use 开头（hook）
     * 2. 函数体里有 return <JSX>
     * 3. 函数体最外层作用域有 use 开头的函数调用（hook 调用）
     */
    fun findReactComponentFunctions(file: PsiFile): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        // 1. 通过 JSFunction 查找（函数声明、箭头函数、函数表达式）
        val functions = PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java)
        for (func in functions) {
            if (!isTopLevelFunction(func, file)) continue
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            if (isReactFunction(func, body)) result.add(func)
        }
        // 2. 回退：通过 JSVarStatement 查找（const App = () => {} 可能不匹配 JSFunction）
        val varStatements = PsiTreeUtil.findChildrenOfType(file, JSVarStatement::class.java)
        for (varStmt in varStatements) {
            if (!isTopLevelFunction(varStmt, file)) continue
            val func = PsiTreeUtil.findChildOfType(varStmt, JSFunction::class.java) ?: continue
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            if (isReactFunction(func, body) && func !in result) result.add(func)
        }
        return result
    }

    /**
     * 查找文件中所有 use 开头的顶级 hook 函数（函数声明、箭头函数、函数表达式）。
     * 用于 Vue/React 项目中纯 .ts/.tsx 文件的自定义 hook：识别后可注入
     * useI18n / useTranslation，使 hook 内部的硬编码中文能被国际化。
     * 与 [findReactComponentFunctions] 不同，本函数只按函数名前缀匹配，
     * 不依赖 React 上下文（return JSX / hook 调用等），可安全用于 Vue 项目。
     */
    fun findHookFunctions(file: PsiFile): List<PsiElement> {
        val result = mutableListOf<PsiElement>()
        val seen = mutableSetOf<PsiElement>()
        // 1. 通过 JSFunction 查找（函数声明、箭头函数、函数表达式）
        PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java).forEach { func ->
            if (!isTopLevelFunction(func, file)) return@forEach
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: return@forEach
            val name = getFunctionName(func)
            if (name != null && name.startsWith("use") && func !in seen) {
                result.add(func)
                seen.add(func)
            }
        }
        // 2. 回退：通过 JSVarStatement 查找（const useXxx = () => {} 可能不匹配 JSFunction）
        PsiTreeUtil.findChildrenOfType(file, JSVarStatement::class.java).forEach { varStmt ->
            if (!isTopLevelFunction(varStmt, file)) return@forEach
            val func = PsiTreeUtil.findChildOfType(varStmt, JSFunction::class.java) ?: return@forEach
            PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: return@forEach
            val name = getFunctionName(func)
            if (name != null && name.startsWith("use") && func !in seen) {
                result.add(func)
                seen.add(func)
            }
        }
        return result
    }

    /**
     * 查找 .tsx / .jsx 文件中的「Vue 组件」（用于 Vue TSX 场景，对应 React 组件识别的对称实现）。
     *
     * 判断标准（满足任一即可判为"这个文件里有 Vue 组件"，因此不能把它当纯工具文件，
     * 不能用 needInjectGlobalDollarT=true 的全局 const $t 别名，必须走 useI18n hook）：
     *
     *  1. 顶级作用域存在对 defineComponent({ ... }) / Vue.defineComponent({ ... }) 的
     *     直接调用或赋值（const Xxx = defineComponent({}) / export default defineComponent({})）
     *  2. 顶级函数名 PascalCase 且函数体里有 return <JSX>（Vue 3 函数式组件写法，语法形态
     *     与 React 一致但属于 Vue 项目，此时依然用 useI18n 注入）
     *  3. 顶级存在 h('div', ...) / createVNode(...) 调用且外层包裹在 PascalCase 函数里
     *     （Vue 渲染函数组件）——可选，先不做，1+2 覆盖主流。
     */
    fun findVueComponentFunctions(file: PsiFile): List<PsiElement> {
        val result = mutableListOf<PsiElement>()

        // --- 场景 1：defineComponent 调用 -----------------------------------------------
        val defineComponentCalls = PsiTreeUtil.findChildrenOfType(file, JSCallExpression::class.java)
        for (call in defineComponentCalls) {
            val method = call.methodExpression
            val methodText = method?.text ?: continue
            if (methodText != "defineComponent" &&
                methodText != "Vue.defineComponent" &&
                methodText != "h") {
                continue
            }
            // defineComponent / h 必须在顶级作用域（或赋给顶级常量 / export default）
            var p: PsiElement? = call.parent
            var isTopLevel = false
            var depth = 0
            while (p != null && p !== file && depth < 10) {
                if (p is JSVarStatement || p is JSAssignmentExpression ||
                    p is JSReturnStatement /* export default defineComponent({}) 的 return 会先经过 export */) {
                    // 再看这个 var/assign 是否在顶层
                    var p2: PsiElement? = p!!.parent
                    var depth2 = 0
                    while (p2 != null && p2 !== file && depth2 < 10) {
                        if (p2 is JSFunction) {
                            isTopLevel = false
                            break
                        }
                        isTopLevel = true
                        p2 = p2.parent
                        depth2++
                    }
                    break
                }
                p = p.parent
                depth++
            }
            // 简化：只要 defineComponent/h 不被嵌套在 JSFunction 内部就算命中（避免误判在深层工具函数里）
            var pp: PsiElement? = call.parent
            var nestedInsideFunction = false
            var d2 = 0
            while (pp != null && pp !== file && d2 < 20) {
                if (pp is JSFunction) {
                    nestedInsideFunction = true
                    break
                }
                pp = pp.parent
                d2++
            }
            if (!nestedInsideFunction) {
                result.add(call)
            }
        }

        // --- 场景 2：Vue 3 函数式组件（PascalCase 顶级函数 + return JSX） -------------
        //   和 React 组件识别形态一致，只是语义上 Vue/React 混用。这里直接复用 findReactComponentFunctions
        //   的"函数组件"形态识别：函数名 PascalCase 且 return JSX，就认为"这里有组件"，不要走全局长别名。
        val functions = PsiTreeUtil.findChildrenOfType(file, JSFunction::class.java)
        for (func in functions) {
            if (!isTopLevelFunction(func, file)) continue
            val name = getFunctionName(func) ?: continue
            if (name.isEmpty() || !name[0].isUpperCase()) continue
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            if (hasReturnJSX(body)) {
                result.add(func)
            }
        }
        // 回退 const Xxx = () => <JSX>
        val varStmts = PsiTreeUtil.findChildrenOfType(file, JSVarStatement::class.java)
        for (varStmt in varStmts) {
            if (!isTopLevelFunction(varStmt, file)) continue
            val func = PsiTreeUtil.findChildOfType(varStmt, JSFunction::class.java) ?: continue
            val name = getFunctionName(func) ?: continue
            if (!name[0].isUpperCase()) continue
            val body = PsiTreeUtil.findChildOfType(func, JSBlockStatement::class.java) ?: continue
            if (hasReturnJSX(body)) {
                result.add(func)
            }
        }

        return result.distinct()
    }

    /** 函数是否在顶级作用域（不被其他函数嵌套） */
    private fun isTopLevelFunction(func: PsiElement, file: PsiFile): Boolean {
        var p = func.parent
        while (p != null && p !== file) {
            if (p is JSFunction) return false
            p = p.parent
        }
        return true
    }

    /** 判断是否为 React 组件/hook 函数 */
    private fun isReactFunction(func: JSFunction, body: JSBlockStatement): Boolean {
        // 条件1：函数名 PascalCase 或 use 开头
        val name = getFunctionName(func)
        if (name == null || name.isEmpty()) {
            return false
        }
        if (name.startsWith("use")) return true

        if (name[0].isUpperCase()){
            // 条件2：函数体里有 return <JSX>
            if (hasReturnJSX(body)) return true
            // 条件3：最外层作用域有 use 开头的函数调用
            if (hasTopLevelHookCall(body)) return true
        }
        return false
    }

    /** 函数体内是否有 return <JSX>（处理括号包裹的情况） */
    private fun hasReturnJSX(body: JSBlockStatement): Boolean {
        val returns = PsiTreeUtil.findChildrenOfType(body, JSReturnStatement::class.java)
        return returns.any { ret ->
            val expr = ret.children.firstOrNull { it is JSExpression }
            if (expr == null) return@any false
            // 处理 return (<div>...) 括号包裹和换行的情况
            var text = expr.text.trim()
            while (text.startsWith("(")) {
                text = text.removePrefix("(").trim()
            }
            text.startsWith("<")
        }
    }

    /** 函数体最外层作用域是否有 use 开头的函数调用（不递归进入嵌套函数） */
    private fun hasTopLevelHookCall(body: JSBlockStatement): Boolean {
        var found = false
        body.accept(object : PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                if (found) return
                // 不递归进入嵌套函数（箭头函数、内部函数）
                if (element is JSFunction) return
                if (element is JSCallExpression) {
                    val callee = element.methodExpression
                    if (callee != null && callee.text.startsWith("use")) {
                        found = true
                        return
                    }
                }
                super.visitElement(element)
            }
        })
        return found
    }

    /** 获取函数名（箭头函数向上遍历查找变量声明） */
    private fun getFunctionName(func: JSFunction): String? {
        func.name?.let { if (it.isNotEmpty()) return it }
        // 箭头函数/函数表达式：向上遍历查找最近的 JSVariable
        var p = func.parent
        while (p != null) {
            if (p is JSVariable) return p.name
            // 遇到函数体或另一个函数就停止
            if (p is JSBlockStatement || p is JSFunction) break
            p = p.parent
        }
        return null
    }

    /**
     * 检查项目 package.json 是否依赖 react 且不依赖 vue
     * 向上查找最近的 package.json，检查 dependencies / devDependencies / peerDependencies
     */
    private fun hasReactDependency(psiFile: PsiFile): Boolean {
        var dir: VirtualFile? = psiFile.virtualFile?.parent ?: return false
        while (dir != null) {
            val pkgFile = dir.findChild("package.json")
            if (pkgFile != null) {
                try {
                    val content = String(pkgFile.contentsToByteArray(), StandardCharsets.UTF_8)
                    // 精确匹配依赖键（排除 react-dom / vue-router 等派生包，仅在依赖段匹配避免 scripts 同名误判）
                    val deps = dependencyBlock(content)
                    val hasReact = deps.contains(REACT_KEY_RE)
                    val hasVue = deps.contains(VUE_KEY_RE)
                    return hasReact && !hasVue
                } catch (e: Exception) {
                    PluginLogBuffer.warn(LOG,"ProjectStructure: hasReactDependency 读取 package.json 失败，返回 false", e)
                    return false
                }
            }
            dir = dir.parent
        }
        return false
    }

    /**
     * 从 [currentPsiFile] 向上查找最近的 package.json 所在目录（即项目根）。
     * 找不到则返回 null。
     */
    fun findProjectRoot(currentPsiFile: PsiFile): VirtualFile? {
        var dir: VirtualFile? = currentPsiFile.virtualFile?.parent ?: return null
        while (dir != null) {
            if (dir.findChild("package.json") != null) return dir
            dir = dir.parent
        }
        return null
    }

    /**
     * 检测项目是否「缺 i18n 依赖且未初始化」（React 缺 i18next / Vue 缺 vue-i18n）。
     * 基于 [currentPsiFile] 向上定位项目根，读取 package.json 判定依赖，并检查是否已有初始化文件。
     *
     * @return 命中则返回需要补的框架与依赖；项目非 React/Vue、已装依赖、或已初始化时返回 null。
     */
    fun detectMissingI18nBootstrap(currentPsiFile: PsiFile): I18nBootstrapSupport.MissingBootstrap? {
        val root = findProjectRoot(currentPsiFile) ?: return null
        val pkg = root.findChild("package.json") ?: return null
        val text = try {
            String(pkg.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            PluginLogBuffer.warn(LOG,"ProjectStructure: 读取 package.json 失败，返回 null", e)
            return null
        }
        val (hasReact, hasVue, hasSolid, _) = readPackageJsonDependencies(currentPsiFile)
        // P0：必须把 project 传给 Locator，否则其内部 PSI 级复核被绕过、退回纯文本判断，
        //     使字符串/注释里的 "createI18n(" 字样被误当成已初始化。
        val hasInit = I18nInstanceLocator.findI18nInitFileInRoot(root, currentPsiFile.project) != null
        return I18nBootstrapSupport.detectMissing(text, hasInit, hasReact, hasVue, hasSolid)
    }

    /**
     * 按路径片段查找 [root] 下的子目录/文件，例如 "src/locales"。
     */
    internal fun findRelativeFile(root: VirtualFile, relPath: String): VirtualFile? {
        var current = root
        for (segment in relPath.split('/', '\\').filter { it.isNotEmpty() }) {
            current = current.findChild(segment) ?: return null
        }
        return current
    }

    /**
     * 使用 VirtualFile API 进行广度优先遍历（限制最大深度），
     * 对每个文件应用 [visitor]，返回第一个非 null 结果。
     *
     * @param root        起始目录
     * @param maxDepth    最大遍历深度（相对于 root，root 是深度 0）
     * @param enterFilter 进入子目录前的过滤器，返回 true 表示进入
     * @param visitor     文件处理函数，返回 null 表示继续
     */
    internal fun <T> walkVirtualFile(
        root: VirtualFile,
        maxDepth: Int,
        enterFilter: (VirtualFile) -> Boolean = { true },
        visitor: (VirtualFile) -> T?
    ): T? {
        data class Item(val vf: VirtualFile, val depth: Int)
        val queue = ArrayDeque<Item>()
        queue.add(Item(root, 0))
        while (queue.isNotEmpty()) {
            val (vf, depth) = queue.removeFirst()
            if (!vf.isValid) continue
            // 对非 root 的节点执行 visitor
            if (depth > 0) {
                val r = visitor(vf)
                if (r != null) return r
            }
            // 如果还能继续深入（并且是目录并且允许进入）
            if (vf.isDirectory && depth < maxDepth && (depth == 0 || enterFilter(vf))) {
                val children = try {
                    vf.children
                } catch (e: Exception) {
                    PluginLogBuffer.warn(LOG,"ProjectStructure: 读取子目录 children 失败，跳过", e)
                    continue
                }
                for (child in children) {
                    queue.addLast(Item(child, depth + 1))
                }
            }
        }
        return null
    }
}