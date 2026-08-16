package com.pan.extractor

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
    /** 高频复用正则：避免每次在循环里重复编译（性能）。 */
    private val REACT_KEY_RE = Regex(""""react"\s*:\s*"""")
    private val VUE_KEY_RE = Regex(""""vue"\s*:\s*"""")

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

    /**
     * 读取当前文件所在项目根（向上找最近 package.json）的依赖键值，
     * 返回 Triple = (hasReactDep: Boolean, hasVueDep: Boolean, hasAnyDepsParsed: Boolean)
     *
     * hasAnyDepsParsed=false 表示根本没找到 package.json，调用方需要 fallback 到其他策略。
     */
    private fun readPackageJsonDependencies(psiFile: PsiFile): Triple<Boolean, Boolean, Boolean> {
        var dir: VirtualFile? = psiFile.virtualFile?.parent ?: return Triple(false, false, false)
        while (dir != null) {
            val pkgFile = dir.findChild("package.json")
            if (pkgFile != null) {
                return try {
                    val content = String(pkgFile.contentsToByteArray(), StandardCharsets.UTF_8)
                    val hasReact = content.contains(REACT_KEY_RE)
                    val hasVue = content.contains(VUE_KEY_RE)
                    Triple(hasReact, hasVue, true)
                } catch (e: Exception) {
                    Triple(false, false, false)
                }
            }
            dir = dir.parent
        }
        return Triple(false, false, false)
    }

    /**
     * 判断当前元素是否处于 React 上下文。
     * 仅依据 package.json 依赖判断：
     * 1. .vue 文件直接排除（Vue）
     * 2. 依赖 react 且不依赖 vue → React
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

        val (hasReact, hasVue, parsed) = readPackageJsonDependencies(containingFile)
        return if (parsed) {
            hasReact && !hasVue
        } else {
            // fallback：老逻辑（避免 IntelliJ 测试项目中没有 package.json 的场景）
            hasReact && !hasVue // 没 parsed 两者默认 false，=false 正确
        }
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

        val (hasReact, hasVue, parsed) = readPackageJsonDependencies(containingFile)
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
                    // 精确匹配依赖键（排除 react-dom / vue-router 等派生包）
                    val hasReact = content.contains(REACT_KEY_RE)
                    val hasVue = content.contains(VUE_KEY_RE)
                    return hasReact && !hasVue
                } catch (e: Exception) {
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
        } catch (_: Exception) {
            return null
        }
        val (hasReact, hasVue, _) = readPackageJsonDependencies(currentPsiFile)
        val hasInit = findI18nInitFileInRoot(root) != null
        return I18nBootstrapSupport.detectMissing(text, hasInit, hasReact, hasVue)
    }

    /**
     * 在 Vue 项目中查找调用了 `createI18n(` 的文件（通常是 @/locales/index.ts 之类）。
     *
     * 查找顺序：
     * 1. 优先在项目根下的常见目录查找（src/locales, locales, src/i18n, i18n），
     *    只在这些目录下做文件内文本匹配，避免遍历 whole repo 太慢。
     * 2. 如果这些目录都没有命中（或都不存在），再在项目根做 walk 扫描（限制深度 4）。
     *
     * 注意：使用 IntelliJ VirtualFile API 遍历（而不是 java.io.File），
     *       这样既能在真实项目中工作，也能在内存测试 Fixture 中工作。
     *
     * @return 命中的文件（VirtualFile），未找到返回 null
     */
    fun findVueI18nInstanceFile(currentPsiFile: PsiFile): VirtualFile? {
        val projectRoot = findProjectRoot(currentPsiFile) ?: return null
        return findVueI18nInstanceFileInRoot(projectRoot)
    }

    /** [findVueI18nInstanceFile] 的 root 版本：给定项目根，查找调用了 createI18n( 的文件。 */
    fun findVueI18nInstanceFileInRoot(projectRoot: VirtualFile): VirtualFile? {
        val commonDirs = listOf(
            "src/locales",
            "locales",
            "src/i18n",
            "i18n",
            "src/locale",
            "locale"
        )

        // 阶段 1：常见目录内精确匹配 .ts/.tsx/.js/.jsx 文件（最大深度 2）
        for (relPath in commonDirs) {
            val dir = findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    if (vfContainsCreateI18n(vf)) vf else null
                } else null
            }
            if (result != null) return result
        }

        // 阶段 2：常见目录未命中，在项目根做 walk（最大深度 4，排除 node_modules）
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                if (vfContainsCreateI18n(vf)) vf else null
            } else null
        }
    }

    private val TS_JS_EXTS = setOf("ts", "tsx", "js", "jsx")

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
                } catch (_: Exception) {
                    continue
                }
                for (child in children) {
                    queue.addLast(Item(child, depth + 1))
                }
            }
        }
        return null
    }

    /**
     * 读取 VirtualFile 内容并检测是否包含 createI18n( 调用。
     */
    private fun vfContainsCreateI18n(vf: VirtualFile): Boolean {
        val text = try {
            String(vf.contentsToByteArray(), Charsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        return text.contains("createI18n(") || text.contains("createI18n (")
    }

    /** 判断文本是否是一个 i18n 初始化文件（Vue 的 createI18n 或 React 的 i18n/i18next.init）。 */
    private fun isI18nInitText(text: String): Boolean {
        if (text.contains("createI18n(") || text.contains("createI18n (")) return true              // Vue
        if (text.contains("initReactI18next")) return true                                          // React (react-i18next)
        return Regex("""\b(?:i18n|i18next)\s*\.\s*init\s*\(""").containsMatchIn(text)                 // React / CJS
    }

    /** 给定项目根，查找初始化了 i18n 的文件（createI18n 或 i18n/i18next.init），Vue 与 React 通用。 */
    fun findI18nInitFileInRoot(projectRoot: VirtualFile): VirtualFile? {
        val commonDirs = listOf(
            "src/locales", "locales", "src/i18n", "i18n",
            "src/locale", "locale", "src/lang", "lang"
        )
        for (relPath in commonDirs) {
            val dir = findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                    if (isI18nInitText(t)) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                if (isI18nInitText(t)) vf else null
            } else null
        }
    }

    /**
     * React 专用：查找"导出了 i18n 实例"的初始化文件。
     *
     * 与 [findI18nInitFileInRoot] 的区别：只匹配 React 初始化文件（initReactI18next /
     * i18n.init），且文件必须导出了 i18n（`export default i18n` / `export const i18n` /
     * `export { i18n }`）。这样避免混合项目里命中 Vue 的 createI18n 文件，也满足
     * "如果 locale 初始化导出了 i18n 才用它"的语义——未导出 i18n 的初始化文件视为不可用。
     */
    fun findReactI18nInstanceFileInRoot(projectRoot: VirtualFile): VirtualFile? {
        val commonDirs = listOf(
            "src/locales", "locales", "src/i18n", "i18n",
            "src/locale", "locale", "src/lang", "lang"
        )
        for (relPath in commonDirs) {
            val dir = findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                    if (isReactI18nInitWithExport(t)) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                if (isReactI18nInitWithExport(t)) vf else null
            } else null
        }
    }

    /** 判断文本是否是一个"React 初始化且导出了 i18n"的文件。 */
    private fun isReactI18nInitWithExport(text: String): Boolean {
        val isReactInit = text.contains("initReactI18next") ||
            Regex("""\b(?:i18n|i18next)\s*\.\s*init\s*\(""").containsMatchIn(text)
        if (!isReactInit) return false
        return Regex("""export\s+(const|let|var)\s+i18n\b""").containsMatchIn(text) ||
            Regex("""export\s*\{[^}]*\bi18n\b[^}]*\}""").containsMatchIn(text) ||
            Regex("""export\s+default\s+i18n\b""").containsMatchIn(text)
    }

    /**
     * 构造从当前文件 [currentPsiFile] 导入 Vue i18n 实例文件 [i18nVFile] 的路径。
     *
     * 优先级：
     * 1. 如果 i18n 实例文件在项目根的 `src/` 下，且当前文件也在 `src/` 下，使用 `@/xxx` 别名。
     *    此时会检查是否是目录 index 文件，从而省略 `/index` 后缀。
     * 2. 否则使用相对路径（以 `./` 或 `../` 开头）。
     *
     * 返回值为不含引号的路径字符串，例如 `"@/locales"` 或 `"./locales/index"`。
     * 返回 null 代表无法推断路径（fallback 由调用方处理）。
     */
    fun resolveVueI18nImportPath(currentPsiFile: PsiFile, i18nVFile: VirtualFile): String? {
        val projectRoot = findProjectRoot(currentPsiFile) ?: return null
        val rootPath = File(projectRoot.path).toPath()
        val i18nPath = File(i18nVFile.path).toPath()
        val currentPath = currentPsiFile.virtualFile?.let { File(it.path).toPath() } ?: return null

        val srcDir = rootPath.resolve("src")

        // 1) 别名路径：两个文件都在 src/ 下
        if (i18nPath.startsWith(srcDir) && currentPath.startsWith(srcDir)) {
            val i18nRel = i18nPath.relativeToOrNull(srcDir)?.toString()?.replace("\\", "/")
                ?: return null
            val noExt = stripTsJsExtension(i18nRel)
            val clean = if (noExt.endsWith("/index")) noExt.removeSuffix("/index") else noExt
            return "@/$clean"
        }

        // 2) 相对路径
        val currentDir = currentPath.parent ?: return null
        val relative = i18nPath.relativeToOrNull(currentDir)?.toString()?.replace("\\", "/")
            ?: return null
        val noExt = stripTsJsExtension(relative)
        val clean = if (noExt.endsWith("/index")) noExt.removeSuffix("/index") else noExt
        return if (!clean.startsWith(".")) "./$clean" else clean
    }

    /**
     * 检测 createI18n 文件中的导出方式：
     * - 命名导出：`export const i18n = createI18n(...)` / `export { i18n }`
     * - 默认导出：`export default i18n` / `export default createI18n(...)`
     *
     * 默认认为是命名导出（与用户习惯一致），仅当文件文本中存在默认导出而无命名导出时才返回 true。
     */
    fun isVueI18nDefaultExport(i18nVFile: VirtualFile): Boolean {
        val content = try {
            String(i18nVFile.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return false
        }
        val hasNamedExport =
            content.contains(Regex("export\\s+(const|let|var)\\s+i18n\\b")) ||
                content.contains(Regex("export\\s*\\{[^}]*\\bi18n\\b[^}]*\\}"))
        val hasDefaultExport =
            content.contains(Regex("export\\s+default\\s+i18n\\b")) ||
                content.contains(Regex("export\\s+default\\s+createI18n\\s*\\("))
        return hasDefaultExport && !hasNamedExport
    }

    private fun stripTsJsExtension(path: String): String {
        val lc = path.lowercase()
        return when {
            lc.endsWith(".tsx") -> path.substring(0, path.length - 4)
            lc.endsWith(".ts") -> path.substring(0, path.length - 3)
            lc.endsWith(".jsx") -> path.substring(0, path.length - 4)
            lc.endsWith(".js") -> path.substring(0, path.length - 3)
            else -> path
        }
    }
}