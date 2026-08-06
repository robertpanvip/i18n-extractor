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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets

object Util {
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
     * 判断当前元素是否处于 React 上下文（用于决定插值占位符格式）
     * 仅依据 package.json 依赖判断：
     * 1. .vue 文件直接排除（Vue）
     * 2. 依赖 react 且不依赖 vue → React
     */
    fun isReact(element: PsiElement): Boolean {
        val containingFile = element.containingFile ?: return false

        // .vue 文件肯定是 Vue，不是 React
        if (containingFile.name.endsWith(".vue", ignoreCase = true)) {
            return false
        }

        // .jsx/.tsx 文件直接判定为 React
        val name = containingFile.name.lowercase()
        if (name.endsWith(".jsx") || name.endsWith(".tsx")) {
            return true
        }

        // 项目 package.json 依赖（react 且不依赖 vue）
        return hasReactDependency(containingFile)
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
                    val hasReact = content.contains(Regex(""""react"\s*:\s*""""))
                    val hasVue = content.contains(Regex(""""vue"\s*:\s*""""))
                    return hasReact && !hasVue
                } catch (e: Exception) {
                    return false
                }
            }
            dir = dir.parent
        }
        return false
    }

    fun getJsonContent(json: String): String {
        val content = json
            .trim()
            .removePrefix("{")
            .removeSuffix("}")
            .trim()
        return content
    }

    fun findFilesByIncludePatterns(
        project: Project,
        rawIncludePatterns: List<String>
    ): List<VirtualFile> {

        val basePath = project.basePath ?: return emptyList()
        val baseDir = File(basePath)

        if (!baseDir.exists()) return emptyList()

        // 预编译 glob -> regex
        val regexList = rawIncludePatterns.map { globToRegex(it) }

        val result = mutableSetOf<VirtualFile>()

        baseDir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach

            val relativePath = baseDir
                .toPath()
                .relativize(file.toPath())
                .toString()
                .replace("\\", "/")

            if (regexList.any { it.matches(relativePath) }) {
                LocalFileSystem.getInstance()
                    .findFileByIoFile(file)
                    ?.let { result.add(it) }
            }
        }

        return result.toList()
    }

    private fun globToRegex(glob: String): Regex {
        var pattern = glob.replace("\\", "/")

        pattern = pattern
            .replace(".", "\\.")
            .replace("**/", "(.*/)?")
            .replace("/**", "(/.*)?")
            .replace("**", ".*")
            .replace("*", "[^/]*")

        return Regex("^$pattern$")
    }

}
