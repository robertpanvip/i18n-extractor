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
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.relativeToOrNull

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
                    val hasReact = content.contains(Regex(""""react"\s*:\s*""""))
                    val hasVue = content.contains(Regex(""""vue"\s*:\s*""""))
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

    // Bug 2: 判定一个文件是否属于"语言包/翻译资源文件"——这类文件本身就存着
    // 翻译后的 key/value，不应该再被提取或注入 useTranslation/useI18n/i18n。
    //
    // 典型场景：
    //   - locales/en-US.ts / i18n/zh-CN.js / translations/zh_TW.tsx
    //   - src/locales/messages.ja.ts / locale/ko.json（JSON 本身不在支持后缀里）
    //   - 命名直接是两字母语言码：en.ts / de.ts / ja.ts
    //   - 自定义前缀+locale：messages.en-US.ts / i18n.zh.js

    /** 两字母 ISO 639-1 语言码列表（覆盖绝大多数项目的命名习惯）。 */
    private val ISO_639_1 = setOf(
        "ab","aa","af","ak","sq","am","ar","an","hy","as","av","ae","ay","az","bm","ba","eu","be","bn","bh","bi",
        "bs","br","bg","my","ca","ch","ce","ny","zh","cv","kw","co","cr","hr","cs","da","dv","nl","dz","en","eo","et",
        "ee","fo","fj","fi","fr","ff","gl","ka","de","el","gn","gu","ht","ha","he","hz","hi","ho","hu","ia","id","ie",
        "ga","ig","ik","io","is","it","iu","ja","jv","kl","kn","ks","kk","km","ki","rw","ky","kv","kg","ko","ku","kj",
        "la","lb","lg","li","ln","lo","lt","lu","lv","gv","mk","mg","ms","ml","mt","mi","mr","mh","mn","na","nv","nb",
        "nd","ne","ng","nn","no","ii","nr","oc","oj","cu","om","or","os","pa","pi","fa","pl","ps","pt","qu","rm","rn",
        "ro","ru","sa","sc","sd","se","sm","sg","sr","gd","sn","si","sk","sl","so","st","es","su","sw","ss","sv","ta",
        "te","tg","th","ti","bo","tk","tl","tn","to","tr","ts","tt","tw","ug","uk","ur","uz","ve","vi","vo","wa","cy","wo",
        "fy","xh","yi","yo","za","zu","zhs","zht","cmn","yue"
    )

    /** 翻译资源常见目录名（全部小写，按路径片段匹配）。 */
    private val TRANSLATION_DIRS = setOf("locales","i18n","locale","lang","languages","translations")

    /** 常见的文件基名前缀（messages.en / i18n.zh-CN 这种）。 */
    private val TRANSLATION_BASE_PREFIXES = setOf("messages","i18n","translation","translations","strings","resources","lang","locale")

    /**
     * `en-US` / `zh_CN` / `en` / `zhs` 之类的 locale 标记匹配。
     * 组成：语言码(2~4字母) + (可选: _/- 区域码(2字母/2+字母))
     */
    private val LOCALE_SEGMENT_RE =
        Regex("^([a-z]{2,4})([-_][a-zA-Z0-9]{2,8})?$")

    /**
     * 将文件名去掉最后的扩展名后返回"基名 + 语言前缀候选"两部分，
     * 例如：
     *   messages.en-US.ts -> ("messages", "en-US")
     *   zh_CN.ts          -> ("zh_CN", null)
     *   i18n.zhs.js       -> ("i18n", "zhs")
     */
    private fun splitBasenameAndMaybeLocale(stem: String): Pair<String, String?> {
        val dotIdx = stem.lastIndexOf('.')
        return if (dotIdx >= 0) {
            val prefix = stem.substring(0, dotIdx)
            val suffix = stem.substring(dotIdx + 1)
            if (TRANSLATION_BASE_PREFIXES.contains(prefix.lowercase())) prefix to suffix
            else stem to null
        } else {
            stem to null
        }
    }

    private fun looksLikeLocaleCode(raw: String): Boolean {
        val token = raw.trim()
        if (token.isBlank()) return false
        val m = LOCALE_SEGMENT_RE.matchEntire(token) ?: return false
        val lang = m.groupValues[1].lowercase()
        // 语言码必须是已知的 ISO 639-1（或 zhs/zht/cmn/yue 扩展），避免误伤普通文件名
        if (lang !in ISO_639_1) return false
        return true
    }

    /**
     * 给定文件名（含扩展名）与文件路径（可用 VirtualFile path、canonicalPath、或 null），
     * 判定该文件是不是语言包/翻译资源文件。
     *
     * 规则（命中任意一条即视为翻译文件）：
     * 1. 路径中出现 `locales/`、`i18n/`、`locale/`、`lang/`、`languages/`、`translations/` 等目录段；
     * 2. 去掉扩展名后的"纯基名"本身就像 locale code（en / en-US / zh_CN / zhs / ...）；
     * 3. 去掉扩展名后是 `messages.en-US`、`i18n.zh_CN` 这类"翻译前缀 + locale code"组合。
     */
    fun isTranslationResourceFile(fileName: String, filePath: String?): Boolean {
        val name = fileName
        val lower = name.lowercase()

        // 快速剔除：只处理受支持的脚本后缀，避免误伤 index.d.ts 之类
        val knownExt = lower.endsWith(".ts") || lower.endsWith(".tsx") ||
            lower.endsWith(".js") || lower.endsWith(".jsx") ||
            lower.endsWith(".json")
        if (!knownExt) return false

        // 1) 路径目录段命中：locales / i18n / locale / lang / translations / ...
        if (filePath != null && filePath.isNotEmpty()) {
            val normalized = filePath.replace('\\', '/').lowercase()
            for (dir in TRANSLATION_DIRS) {
                // 精确匹配目录段，避免把 "mailing/" 之类误判成 "lang"
                if ("/$normalized/".contains("/$dir/")) return true
            }
        }

        // 去掉扩展名（最多去掉两层：.d.ts 保留 stem = index.d，不过翻译文件一般不会是 .d.ts）
        val extIdx = name.lastIndexOf('.')
        val stem = if (extIdx >= 0) name.substring(0, extIdx) else name

        // 2) "基名就是 locale code"：en.ts / zh-US.tsx / zh_CN.js
        if (looksLikeLocaleCode(stem)) return true

        // 3) "前缀.语言码"：messages.en-US.ts / i18n.zhs.js / strings.zh_TW.tsx
        val (maybePrefix, maybeLocale) = splitBasenameAndMaybeLocale(stem)
        if (maybeLocale != null && TRANSLATION_BASE_PREFIXES.contains(maybePrefix.lowercase())) {
            if (looksLikeLocaleCode(maybeLocale)) return true
        }

        // 兜底：常见的语言-region连写（如 zhHans、zhHant、ptBR、enGB）——不带 -/_ 分隔
        if (stem.length in 4..7) {
            val langPart = stem.take(2).lowercase()
            val regionPart = stem.drop(2)
            if (langPart in ISO_639_1 && regionPart.all { it.isLetter() || it.isDigit() }) {
                // zhHans / zhHant 明确视作 locale
                if (setOf("zhHans","zhHant","zhCN","zhTW","zhHK","enUS","enGB","enAU",
                        "enCA","deDE","deAT","deCH","frFR","frCA","jaJP","koKR",
                        "ptBR","ptPT","esES","esAR","esMX","ruRU","itIT","nlNL",
                        "nlBE","plPL","trTR","thTH","viVN","idID","msMY",
                        "arSA","heIL","hiIN","bnBD","svSE","nbNO","daDK",
                        "fiFI","csCZ","skSK","huHU","roRO","bgBG","srRS",
                        "hrHR","slSI","ukUA","elGR","caES","euES","glES")
                    .contains(stem)) return true
            }
        }

        return false
    }

    /** [isTranslationResourceFile] 的 PsiFile 便捷入口。 */
    fun isTranslationResourceFile(psiFile: PsiFile): Boolean {
        return isTranslationResourceFile(psiFile.name, psiFile.virtualFile?.path ?: psiFile.name)
    }

    /** [isTranslationResourceFile] 的 VirtualFile 便捷入口。 */
    fun isTranslationResourceFile(vf: VirtualFile): Boolean {
        return isTranslationResourceFile(vf.name, vf.path)
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
        val excludeDirs = setOf("node_modules", ".git", "dist", "build")
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
    private fun findRelativeFile(root: VirtualFile, relPath: String): VirtualFile? {
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
    private fun <T> walkVirtualFile(
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

    /**
     * 计算"从 filePsi（一个脚本文件所在 VFile）到 constantsVFile（常量库）"的相对 import specifier，
     * 格式符合 ESM `import ... from 'X'` 规则：
     * - 自动去掉 .ts / .tsx / .js 后缀（TS/JS bundler 惯例）；
     * - 同级目录加 `./` 前缀；
     * - `../` 层级按需要生成；
     * - 失败返回 null，调用方应退化为命名空间导入或提示用户。
     */
    fun computeRelativeImportPath(from: VirtualFile?, to: VirtualFile?): String? {
        if (from == null || to == null) return null
        try {
            val fromParentPath = File(from.path).parentFile?.toPath() ?: return null
            val toPath = File(to.path).toPath()
            val relative = toPath.relativeToOrNull(fromParentPath) ?: return null
            var spec = relative.toString()
                .replace('\\', '/')
                .removeSuffix(".ts")
                .removeSuffix(".tsx")
                .removeSuffix(".js")
                .removeSuffix("/index")
            if (!spec.startsWith(".")) spec = "./$spec"
            return spec
        } catch (_: Throwable) {
            return null
        }
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
