package com.pan.extractor

import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier
import com.intellij.lang.javascript.psi.JSArrayLiteralExpression
import com.intellij.lang.javascript.psi.JSAssignmentExpression
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSConditionalExpression
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSReturnStatement
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecma6.TypeScriptFunctionExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.util.PropertiesComponent
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.relativeToOrNull

object Util {
    private val HAN_RE = Regex("""[\u4e00-\u9fff]""")

    /** 常见 helper：文本中是否包含至少 1 个汉字（UTF-16 BMP 范围的中日韩统一表意文字基本区）。
     *  - 用于判断差异段是否要嵌套 `$t('差异')`（含中文→嵌套；纯英文/数字→直接写字符串字面量）。*/
    fun hasChinese(text: CharSequence?): Boolean {
        if (text == null) return false
        return HAN_RE.containsMatchIn(text)
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

            // 排除 node_modules 与构建产物目录，避免把依赖源码当成翻译源
            if (relativePath.split("/").contains("node_modules")) return@forEach

            // include 为空时视为"全项目扫描"（回退），否则按 glob 模式匹配
            if (regexList.isEmpty() || regexList.any { it.matches(relativePath) }) {
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

    // ==========================================================================
    // 用户输出方式配置：拷贝到剪贴板 / 覆盖入口中文多语言文件
    // ==========================================================================
    enum class OutputMode {
        COPY_TO_CLIPBOARD,
        OVERWRITE_ENTRY_FILE;

        companion object {
            fun safeValueOf(raw: String?): OutputMode = when (raw?.trim()) {
                "OVERWRITE_ENTRY_FILE" -> OVERWRITE_ENTRY_FILE
                else -> COPY_TO_CLIPBOARD
            }
        }
    }

    private const val PREF_OUTPUT_MODE = "i18n-extractor.output-mode"
    private const val PREF_ENTRY_PATH = "i18n-extractor.entry-path"

    fun getOutputMode(project: Project): OutputMode =
        OutputMode.safeValueOf(PropertiesComponent.getInstance(project).getValue(PREF_OUTPUT_MODE))

    fun setOutputMode(project: Project, mode: OutputMode) {
        PropertiesComponent.getInstance(project).setValue(PREF_OUTPUT_MODE, mode.name)
    }

    fun getStoredEntryPath(project: Project): String? =
        PropertiesComponent.getInstance(project).getValue(PREF_ENTRY_PATH)?.takeIf { it.isNotBlank() }

    fun setStoredEntryPath(project: Project, path: String?) {
        PropertiesComponent.getInstance(project).setValue(PREF_ENTRY_PATH, path)
    }

    // ==========================================================================
    // 查找项目中的"中文多语言入口文件"（zh-CN / zhCN / zhs 等命名）
    // ==========================================================================
    /** 中文 locale 名候选（按优先级）。 */
    private val ZH_LOCALE_NAMES = listOf(
        "zh-CN", "zh_CN", "zhCN", "zhHans", "zh-Hans", "zhs",
        "zh", "zhcn", "cn", "zh-CHS", "zh-hans-cn", "zh-Hans-CN",
        "zh-SG", "zh_SG", "zhSG"
    )

    /** 找中文语言包入口文件的常见基名（不带扩展名）。 */
    private fun isChineseLocaleBasename(stem: String): Boolean {
        val lower = stem.lowercase()
        // 直接相等
        if (ZH_LOCALE_NAMES.any { it.equals(lower, ignoreCase = true) }) return true
        // messages.zh-CN / i18n.zhs / translations.zh_CN 这种
        val dotIdx = lower.lastIndexOf('.')
        if (dotIdx >= 0) {
            val prefix = lower.substring(0, dotIdx)
            val suffix = lower.substring(dotIdx + 1)
            if (TRANSLATION_BASE_PREFIXES.contains(prefix) &&
                ZH_LOCALE_NAMES.any { it.equals(suffix, ignoreCase = true) }) return true
        }
        // 兜底：zh 作为前缀 + 国家码（zhCN/zhHK 等）
        if (lower.length in 4..7 && lower.startsWith("zh")) {
            val rest = lower.drop(2)
            if (rest.all { it.isLetterOrDigit() } &&
                setOf("hans","hant","cn","tw","hk","sg","mo","my").any { rest.contains(it) })
                return true
        }
        return false
    }

    /**
     * 尝试定位"中文多语言入口文件"。
     * 优先级：
     *   1. 用户上次选择并持久化的路径（若文件仍存在）
     *   2. 项目根下常见 i18n 目录中匹配 ZH_LOCALE_NAMES 的文件（.ts/.tsx/.js/.json）
     *   3. 整个项目（排除 node_modules）按 isTranslationResourceFile + ZH basename 扫描
     * @return 命中的 VirtualFile 或 null
     */
    fun findChineseLocaleEntryFile(project: Project, contextPsiFile: PsiFile?): VirtualFile? {
        // 1) 用户持久化的路径
        val stored = getStoredEntryPath(project)
        if (stored != null) {
            val f = LocalFileSystem.getInstance().findFileByPath(stored)
            if (f != null && f.isValid && !f.isDirectory) return f
        }
        val root = if (contextPsiFile != null) findProjectRoot(contextPsiFile) else {
            project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        }
        if (root == null || !root.isDirectory) return null

        // 2) 常见目录优先精确匹配
        val commonDirs = listOf(
            "src/locales", "locales", "src/i18n", "i18n",
            "src/locale", "locale", "src/lang", "lang",
            "src/languages", "languages", "src/translations", "translations"
        )
        for (rel in commonDirs) {
            val dir = findRelativeFile(root, rel) ?: continue
            if (!dir.isDirectory) continue
            val hit = walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isDirectory || !vf.isValid) return@walkVirtualFile null
                val ext = vf.extension?.lowercase() ?: return@walkVirtualFile null
                if (ext !in setOf("ts","tsx","js","jsx","json")) return@walkVirtualFile null
                val nameNoExt = vf.nameWithoutExtension
                if (isChineseLocaleBasename(nameNoExt)) vf else null
            }
            if (hit != null) return hit
        }
        // 3) 全项目 walk（深度 5，排除 node_modules/.git/dist/build）
        val excludeDirs = setOf("node_modules", ".git", "dist", "build", ".next", ".nuxt", "out")
        return walkVirtualFile(root, maxDepth = 5, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isDirectory || !vf.isValid) return@walkVirtualFile null
            val ext = vf.extension?.lowercase() ?: return@walkVirtualFile null
            if (ext !in setOf("ts","tsx","js","jsx","json")) return@walkVirtualFile null
            // 目录段命中翻译目录 or 基名像中文 locale
            val pathLike = isTranslationResourceFile(vf.name, vf.path)
            val baseLike = isChineseLocaleBasename(vf.nameWithoutExtension)
            if ((pathLike || baseLike) && (vf.path.lowercase().contains("zh") ||
                    ZH_LOCALE_NAMES.any { vf.nameWithoutExtension.contains(it, ignoreCase = true) })) {
                vf
            } else null
        }
    }

    // ==========================================================================
    // TS 文件：解析 export default / export const 对象字面量 → 嵌套 Map
    //         遇到无法确定的表达式跳过（整条属性整条跳过，不抛错）
    // ==========================================================================
    /** 解析结果：带范围信息的（原对象在整个文件文本中的 [start,end) + 抽取出来的静态 KV map） */
    data class TsExportedObjectInfo(
        val objectRange: IntRange,   // 对象字面量 { ... } 在文件文本中的 [start, end)
        val staticKV: Map<String, Any?>,  // 静态可确定的 KV（嵌套 Map / List / String / Number / Boolean / null）
        val exportType: String,          // "default" / "named:<name>" / "module.exports"
        val indentUnit: String           // 推断的缩进（2 spaces / 4 spaces / tab），用于重新生成
    )

    /**
     * 从 TS/JS 文件内容（原始文本）中找到 export default / export const / module.exports 对应的
     * 对象字面量，并抽取其中的静态 key-value。
     *
     * PS：不直接拿 PSI 来改，是因为重新"合并生成"时，用户自定义表达式（动态、spread、函数调用）
     * 我们无法静态求值，需要整条保留在原位；而我们提取出的静态 KV 只用于和新 JSON 做 key 级别合并，
     * 最后再用"字符串片段替换"只替换对象字面量区域（其他 import / const / 注释一概不动）。
     */
    fun parseTsExportedObject(text: String): TsExportedObjectInfo? {
        // --- 1. 找对象字面量起点：对应 export default / export const / module.exports ---
        val (objStart, exportType, indentUnit) = findExportedObjectStart(text) ?: return null
        // --- 2. 括号平衡，匹配到对象结束位置 ---
        val objEnd = findBalancedCloseBrace(text, objStart) ?: return null
        val objBody = text.substring(objStart, objEnd)  // 包含 { }
        // --- 3. 解析对象字面量内部的静态 KV ---
        val staticKV = parseObjectLiteralBody(objBody)
        return TsExportedObjectInfo(
            objectRange = objStart until objEnd,
            staticKV = staticKV,
            exportType = exportType,
            indentUnit = indentUnit
        )
    }

    private data class ExportAnchor(
        val objBraceStart: Int,
        val exportType: String,
        val indentUnit: String
    )

    private fun findExportedObjectStart(text: String): ExportAnchor? {
        // 模式 1：export default {
        run {
            val re = Regex("""export\s+default\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.range.last  // { 的位置
                return ExportAnchor(braceIdx, "default", inferIndent(text, braceIdx))
            }
        }
        // 模式 2：export default <name> = { （非常少见，但兜底）
        run {
            val re = Regex("""export\s+default\s+[\w$][\w$]*\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "default", inferIndent(text, braceIdx))
            }
        }
        // 模式 3：export const <name> = { / export let / export var
        run {
            val re = Regex("""export\s+(const|let|var)\s+([\w$][\w$]*)\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                val name = m.groupValues[2]
                return ExportAnchor(braceIdx, "named:$name", inferIndent(text, braceIdx))
            }
        }
        // 模式 4：module.exports = {
        run {
            val re = Regex("""module\.exports\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "module.exports", inferIndent(text, braceIdx))
            }
        }
        // 模式 5：exports = {
        run {
            val re = Regex("""(^|;)\s*exports\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "exports", inferIndent(text, braceIdx))
            }
        }
        return null
    }

    private fun inferIndent(text: String, braceIdx: Int): String {
        // 找 { 所在行的起始空白作为参考；否则默认 2 spaces
        var lineStart = braceIdx
        while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
        val wsPrefix = text.substring(lineStart, braceIdx).takeWhile { it == ' ' || it == '\t' }
        if (wsPrefix.isNotEmpty()) return wsPrefix
        return "  "
    }

    private fun findBalancedCloseBrace(text: String, openIdx: Int): Int? {
        if (openIdx >= text.length || text[openIdx] != '{') return null
        var depth = 0
        var i = openIdx
        var inString: Char? = null
        var escapeNext = false
        while (i < text.length) {
            val c = text[i]
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * 解析对象字面量（形如 { a: 1, b: "x", c: { d: 2 } }）。
     * 遇到无法静态确定的表达式（spread、函数调用、引用、三元、运算、模板字符串带插值等）→ 该属性整条跳过。
     * 支持：
     *   - 嵌套对象字面量
     *   - 数组字面量（元素若有非静态的则整个元素跳过，其余保留；若全部被跳过则数组为空数组）
     *   - 字符串字面量（单/双/反引号无插值）
     *   - 数字 / true / false / null / undefined（undefined 写回时省略 → null）
     *   - 注释（忽略）
     *   - shorthand（如 { foo } → 跳过）
     *   - 方法简写（如 fn(){} → 跳过）
     */
    fun parseObjectLiteralBody(raw: String): Map<String, Any?> {
        if (raw.isBlank()) return emptyMap()
        val stripped = raw.trim()
        val body = if (stripped.startsWith("{") && stripped.endsWith("}")) {
            stripped.substring(1, stripped.length - 1)
        } else stripped
        val result = LinkedHashMap<String, Any?>()
        val props = splitTopLevelProperties(body)
        for (prop in props) {
            val (k, vExpr) = parseOneProperty(prop) ?: continue
            val value = tryParseStaticValue(vExpr) ?: continue
            result[k] = value
        }
        return result
    }

    /** 把 { ... } 内部按逗号拆成属性列表（注意处理嵌套 {} [] 字符串 注释）。 */
    private fun splitTopLevelProperties(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0   // {} [] 总层数
        var inString: Char? = null
        var escapeNext = false
        var inLineComment = false
        var inBlockComment = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            val next = body.getOrNull(i + 1)
            when {
                inLineComment -> {
                    if (c == '\n') inLineComment = false
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') { inBlockComment = false; i++ }
                }
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> {
                    if (c == '/' && next == '/') { inLineComment = true; i++ }
                    else if (c == '/' && next == '*') { inBlockComment = true; i++ }
                    else when (c) {
                        '"', '\'', '`' -> inString = c
                        '{', '[' -> depth++
                        '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
                        ',' -> if (depth == 0) {
                            parts += body.substring(start, i)
                            start = i + 1
                        }
                    }
                }
            }
            i++
        }
        if (start < body.length) parts += body.substring(start)
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 解析单个属性，返回 (key, valueExpr)；若解析不了返回 null。 */
    private fun parseOneProperty(prop: String): Pair<String, String>? {
        var inString: Char? = null
        var escapeNext = false
        var depth = 0
        var colonIdx = -1
        var i = 0
        while (i < prop.length) {
            val c = prop[i]
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    ':' -> if (depth == 0 && colonIdx == -1) {
                        // 方法简写（如 foo() { }）中，: 可能不出现 → colonIdx 还是 -1，返回 null
                        colonIdx = i
                    }
                }
            }
            i++
        }
        if (colonIdx < 0) return null  // shorthand property / 方法简写 → 跳过
        val keyPart = prop.substring(0, colonIdx).trim()
        val valuePart = prop.substring(colonIdx + 1).trim()
        val key = parsePropertyKey(keyPart) ?: return null
        return key to valuePart
    }

    private fun parsePropertyKey(keyPart: String): String? {
        // 形如：foo / 'foo' / "foo" / `foo` / [123] / [foo]
        if (keyPart.startsWith("[") && keyPart.endsWith("]")) {
            val inner = keyPart.substring(1, keyPart.length - 1).trim()
            // 仅支持字面量（字符串/数字）作为 computed key，其他（变量等）跳过
            return when {
                (inner.startsWith("\"") && inner.endsWith("\"")) ||
                        (inner.startsWith("'") && inner.endsWith("'")) ||
                        (inner.startsWith("`") && inner.endsWith("`")) ->
                    unquoteString(inner)
                inner.toIntOrNull() != null -> inner
                inner.toDoubleOrNull() != null -> inner
                else -> null
            }
        }
        if ((keyPart.startsWith("\"") && keyPart.endsWith("\"")) ||
            (keyPart.startsWith("'") && keyPart.endsWith("'")) ||
            (keyPart.startsWith("`") && keyPart.endsWith("`"))) {
            return unquoteString(keyPart)
        }
        // Identifier
        if (keyPart.matches(Regex("""[A-Za-z_$][\w$]*"""))) return keyPart
        return null
    }

    private fun unquoteString(s: String): String {
        if (s.length < 2) return s
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (val nc = inner[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    '0' -> sb.append('\u0000')
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    '`' -> sb.append('`')
                    else -> sb.append(nc)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** 尝试把一个表达式片段解析为静态值；非静态返回 null。 */
    private fun tryParseStaticValue(expr: String): Any? {
        val s = expr.trim()
        if (s.isEmpty()) return null
        // 字面量：null / undefined / true / false
        when (s) {
            "null" -> return null
            "undefined" -> return null  // 写回时用 null 占位
            "true" -> return true
            "false" -> return false
        }
        // 数字
        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let {
            // 避免整数被解析成科学计数的小数
            if (s.matches(Regex("""-?\d+"""))) return s.toLong()
            return it
        }
        // 字符串：单/双/反引号（反引号中无插值）
        if ((s.startsWith("\"") && s.endsWith("\"") && s.length >= 2) ||
            (s.startsWith("'") && s.endsWith("'") && s.length >= 2)) {
            return unquoteString(s)
        }
        if (s.startsWith("`") && s.endsWith("`") && s.length >= 2) {
            if (Regex("""\$\{""").containsMatchIn(s.substring(1, s.length - 1))) return null
            return unquoteString(s)
        }
        // 对象字面量
        if (s.startsWith("{") && s.endsWith("}")) {
            return parseObjectLiteralBody(s)
        }
        // 数组字面量
        if (s.startsWith("[") && s.endsWith("]")) {
            return parseArrayLiteralBody(s)
        }
        // 其他（引用、spread、函数调用、运算、三元、as const 等）→ 跳过
        return null
    }

    private fun parseArrayLiteralBody(raw: String): List<Any?> {
        val inner = raw.trim().let { if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it }
        val elements = splitTopLevelArrayElements(inner)
        val result = mutableListOf<Any?>()
        for (e in elements) {
            if (e.isBlank()) continue  // 稀疏数组 [1,,2] 空元素跳过
            // spread element [...arr] → 整条跳过
            if (e.trimStart().startsWith("...")) continue
            val v = tryParseStaticValue(e)
            if (v != null) result.add(v)
        }
        return result
    }

    private fun splitTopLevelArrayElements(body: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{', '[' -> depth++
                    '}', ']' -> depth = (depth - 1).coerceAtLeast(0)
                    ',' -> if (depth == 0) {
                        parts += body.substring(start, i)
                        start = i + 1
                    }
                }
            }
            i++
        }
        if (start < body.length) parts += body.substring(start)
        return parts
    }

    // ==========================================================================
    // 合并：existingKV + 新 JSON（都是扁平 key） → 新的嵌套 Map
    //         （为了简化写回，这里采用"深度合并 + 保留旧静态值 + 新 JSON key 若是嵌套的点式 key，先展开"）
    // ==========================================================================
    /**
     * 把扁平 Map<String, String> 的翻译资源合并到现有嵌套结构里。
     *  - 扁平 key 若含 "."（如 "common.confirm"）→ 尝试写入嵌套 Map；写不进去就退化为顶层带点的 key。
     *  - 冲突（新 val != 旧 val）：以新 JSON 为准。
     */
    fun mergeFlatIntoNested(
        existingNested: Map<String, Any?>,
        newFlat: Map<String, String>
    ): Map<String, Any?> {
        // 深拷贝一份 existing（mutable），避免修改入参
        val result = deepCloneMap(existingNested)
        for ((k, v) in newFlat) {
            // 判断 key 是否是"点式嵌套"
            if (k.contains('.')) {
                if (tryWriteNested(result, k, v)) continue
                // 写不进去（中间段冲突且不是对象）→ 退化直接写顶层 key
                result[k] = v
            } else {
                // 顶层：若是已有的对象且 v 不是对象 → 直接覆盖为字符串（翻译 key 对应 value 都是字符串）
                result[k] = v
            }
        }
        return result
    }

    private fun deepCloneMap(m: Map<String, Any?>): MutableMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in m) {
            result[k] = when (v) {
                is Map<*, *> -> deepCloneMap(v as Map<String, Any?>)
                is List<*> -> (v as List<Any?>).map {
                    when (it) {
                        is Map<*, *> -> deepCloneMap(it as Map<String, Any?>)
                        is List<*> -> (it as List<Any?>).toList()
                        else -> it
                    }
                }.toMutableList()
                else -> v
            }
        }
        return result
    }

    private fun tryWriteNested(root: MutableMap<String, Any?>, dottedKey: String, value: String): Boolean {
        val segments = dottedKey.split('.')
        var cur: MutableMap<String, Any?> = root
        for (i in 0 until segments.size - 1) {
            val seg = segments[i]
            when (val next = cur[seg]) {
                is MutableMap<*, *> -> cur = next as MutableMap<String, Any?>
                null -> {
                    val nm = LinkedHashMap<String, Any?>()
                    cur[seg] = nm
                    cur = nm
                }
                else -> return false  // 冲突：中间段已被其他类型（字符串/数组）占用
            }
        }
        cur[segments.last()] = value
        return true
    }

    // ==========================================================================
    // 重新生成对象字面量文本（TS 语法，带换行/缩进，支持嵌套 Map/List/原始值）
    // 策略：
    //   · 旧对象中存在"非静态表达式占位属性"（方法/spread/引用等）→ 需要保留原样
    //   · 我们的做法：**按行扫描原对象字面量**，识别"静态属性行"（可精确匹配 key）→ 按合并后的新值重写；
    //     非静态行/无法识别的行 → 原样保留；
    //     新 key（合并后新增、旧对象没有的）→ 追加到对象末尾（} 之前）。
    //   · 这样既不会把用户写的 spread / 函数 / 引用弄丢，也能完整合并新值。
    // ==========================================================================
    private data class StaticPropertyLine(
        val key: String,
        val fullKeyExpr: String,    // 引号包裹或 identifier：例如 "common" / common / 'a.b'
        val lineStartInObj: Int,    // 行起始相对 objBody（即 "{\n" 之后）的 offset
        val lineEndInObj: Int,      // 行尾（包含换行为止）
        val trailingComma: Boolean  // 末尾是否有逗号
    )

    /**
     * 将合并后的 nested Map 合并写回到旧的对象字面量文本里。
     * - 旧静态 KV：值相同 → 保留原行（避免格式漂移）；值不同 → 仅替换该行的 value 部分
     * - 旧非静态行（spread/方法/引用/表达式）→ 原样保留
     * - 新 key → 在 } 之前追加，按 key 字典序追加
     */
    fun regenerateObjectLiteralBody(oldObjBody: String, mergedNested: Map<String, Any?>): String {
        // 把 mergedNested 扁平化为 key→value 字符串形式的 "渲染结果"（对嵌套对象我们直接写嵌套对象字面量）
        // 即：map 每个 key → renderStaticValue(mergedNested[key], indent)
        // 这样可以避免"深度更新中间段"，直接按顶层 key 来做行级重写。
        // 先扫描旧对象，识别每个顶层静态属性的 (key, 行range)
        val oldBody = oldObjBody.trim().let {
            if (it.startsWith("{") && it.endsWith("}")) it.substring(1, it.length - 1) else it
        }
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in oldBody) {
            if (c == '\n') {
                lines.add(sb.toString())
                sb.clear()
            } else sb.append(c)
        }
        if (sb.isNotEmpty()) lines.add(sb.toString())

        // 扫描顶层静态属性：记录 (key, keyExpr, lineIdx, valueStartColonIdxInLine, trailingComma)
        data class PropRewriteInfo(
            val lineIdx: Int,
            val key: String,
            val keyExpr: String,
            val colonPosInLine: Int,  // ':' 在 line 中的位置（之后即 value）
            val trailingComma: Boolean,
            val indent: String       // 行前空白
        )
        val rewrites = ArrayList<PropRewriteInfo>()
        val processedLineIdxs = HashSet<Int>()

        for ((idx, rawLine) in lines.withIndex()) {
            val indentMatch = Regex("""^(\s*)""").find(rawLine)
            val indent = indentMatch?.groupValues?.get(1).orEmpty()
            val line = rawLine.trimStart()
            // 空行或纯注释行 → 跳过
            if (line.isBlank() || line.startsWith("//") || line.startsWith("/*")) continue
            // 找顶层 ':'（不在字符串/嵌套 {}[] 中）
            val colonPosInTrimmed = findTopLevelColon(line) ?: continue
            val keyExpr = line.substring(0, colonPosInTrimmed).trim()
            val key = parsePropertyKey(keyExpr) ?: continue
            // value 段：检查是否在同一行内有匹配的闭合（即 value 是单行静态值，无跨行对象/数组）
            // 对于跨行 value，我们就不尝试重写该行了（用户的多行对象/数组保留原样）。
            val valuePart = line.substring(colonPosInTrimmed + 1).trim()
            val isSingleLineStatic = isSingleLineStaticValue(valuePart)
            if (!isSingleLineStatic) {
                // 非单行静态（跨行对象 / 数组 / 表达式）→ 不重写，但标记该行已处理（避免尾部追加）
                // （因为这个 key 在旧文件里已有"内容占位"，尽管我们不改它）
                processedLineIdxs.add(idx)
                continue
            }
            // 末尾逗号？
            val trimmed = valuePart.trimEnd()
            val trailingComma = trimmed.endsWith(",")
            rewrites.add(
                PropRewriteInfo(
                    lineIdx = idx,
                    key = key,
                    keyExpr = keyExpr,
                    colonPosInLine = indent.length + colonPosInTrimmed,
                    trailingComma = trailingComma,
                    indent = indent
                )
            )
            processedLineIdxs.add(idx)
        }

        // 收集已在旧对象里出现过的顶层 key（避免重复追加）
        val existingTopKeys = rewrites.map { it.key }.toMutableSet()
        // 注意 processedLineIdxs 中也可能有非静态行，但它们没有 key，所以只从 rewrites 收集
        // （用户写了非静态属性，我们保留，无需关心其 key）

        // 推断默认缩进单位（首行有内容的 indent）
        val innerIndentUnit = rewrites.firstOrNull()?.indent
            ?: lines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
            .ifBlank { "  " }

        // 对每个 rewrite：计算新 value 字符串，替换 lines[lineIdx] 的 value 部分
        val mergedKeys = mergedNested.keys.toSet()
        for (rw in rewrites) {
            if (rw.key !in mergedKeys) {
                // 旧 key 在新合并结果里没有了？ → 保留旧值（不删，防止用户手动写的 key 被清）
                continue
            }
            val newVal = mergedNested[rw.key]
            val valueStr = renderStaticValue(newVal, innerIndentUnit, nestingDepth = 1)
            val line = lines[rw.lineIdx]
            val prefix = line.substring(0, rw.colonPosInLine + 1)  // "  key:"
            val suffix = if (rw.trailingComma) "," else ""
            // 把 value 段去掉（取原 line 在 colon 之后到最后非空白之前的内容）
            lines[rw.lineIdx] = "$prefix $valueStr$suffix"
        }

        // 追加新 key（mergedNested 里有，但 existingTopKeys 没有的）
        val newKeys = mergedKeys.filter { it !in existingTopKeys }.sorted()
        if (newKeys.isNotEmpty()) {
            // 找最后一行非空/非 } 行的位置，在其后面追加
            var insertAt = lines.size
            // 如果最后一行是 "}"（对象字面量被整段替换时，oldObjBody 可能是完整的 { ... }），先不考虑
            for (i in lines.indices.reversed()) {
                val t = lines[i].trim()
                if (t.isNotBlank()) {
                    insertAt = i + 1
                    // 如果当前最后一行非空行没有逗号，给它补一个逗号（更合法）
                    val last = lines[i]
                    val trimmed = last.trimEnd()
                    if (trimmed.isNotEmpty() && !trimmed.endsWith(",") && !trimmed.endsWith("{") && !trimmed.endsWith("[")) {
                        lines[i] = last.substring(0, trimmed.length) + "," + last.substring(trimmed.length)
                    }
                    break
                }
            }
            val additions = newKeys.map { k ->
                val keyExpr = if (k.matches(Regex("""[A-Za-z_$][\w$]*"""))) k else quoteForTs(k)
                val valueStr = renderStaticValue(mergedNested[k], innerIndentUnit, nestingDepth = 1)
                "$innerIndentUnit$keyExpr: $valueStr,"
            }
            lines.addAll(insertAt, additions)
        }

        // 重新组合对象字面量
        return "{" + lines.joinToString("\n") + "\n}"
    }

    private fun findTopLevelColon(line: String): Int? {
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        for ((i, c) in line.withIndex()) {
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                    ':' -> if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun isSingleLineStaticValue(expr: String): Boolean {
        // 1) 首先要是单行（没有 '\n'）— 调用方已经按行切过，所以一般成立
        // 2) 且表达式中没有"未闭合"的 { 或 [（这样就不会是跨行的对象/数组）
        // 3) 非静态开头：spread / 方法 / 引用 / 函数调用 / as const → 判 false
        val t = expr.trim().trimEnd(',')
        if (t.startsWith("...")) return false
        // 方法简写的情况：() => { ... } 或 function(){}
        if (t.contains("=>") || t.startsWith("function")) return false
        // 引用 / 调用：以 identifier 开头但不是 "true/false/null/undefined/数字/字符串"
        val isPrimitive = (t == "true" || t == "false" || t == "null" || t == "undefined" ||
                t.toDoubleOrNull() != null ||
                (t.startsWith("\"") && t.endsWith("\"")) ||
                (t.startsWith("'") && t.endsWith("'")) ||
                (t.startsWith("`") && t.endsWith("`") && !t.substring(1, t.length - 1).contains("\${")) ||
                (t.startsWith("{") && matchingBraces(t)) ||
                (t.startsWith("[") && matchingBraces(t)))
        return isPrimitive
    }

    private fun matchingBraces(s: String): Boolean {
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        for (c in s) {
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> when (c) {
                    '"', '\'', '`' -> inString = c
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
            }
        }
        return depth == 0
    }

    /** 把静态值渲染为 TS 字面量字符串（value 段）。nestingDepth = 当前对象嵌套层数（1 = 对象首层级）。 */
    private fun renderStaticValue(value: Any?, indentUnit: String, nestingDepth: Int): String {
        val indent = indentUnit.repeat(nestingDepth)
        val outerIndent = indentUnit.repeat((nestingDepth - 1).coerceAtLeast(0))
        return when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Number -> {
                if (value is Double && value.isNaN()) "null" else value.toString()
            }
            is String -> quoteForTs(value)
            is Map<*, *> -> {
                val m = value as Map<String, Any?>
                if (m.isEmpty()) return "{}"
                val inner = m.entries.joinToString(",\n") { (k, v) ->
                    val keyExpr = if (k.matches(Regex("""[A-Za-z_$][\w$]*"""))) k else quoteForTs(k)
                    val vStr = renderStaticValue(v, indentUnit, nestingDepth + 1)
                    "$indent$keyExpr: $vStr"
                }
                "{\n$inner,\n$outerIndent}"
            }
            is List<*> -> {
                if (value.isEmpty()) return "[]"
                val inner = value.joinToString(",\n") { v ->
                    val vStr = renderStaticValue(v, indentUnit, nestingDepth + 1)
                    "$indent$vStr"
                }
                "[\n$inner,\n$outerIndent]"
            }
            else -> "null"
        }
    }

    private fun quoteForTs(s: String): String {
        // 优先单引号，字符串中有单引号用双引号，两个都有则转义单引号
        val q = when {
            '\'' !in s -> "'"
            '"' !in s -> "\""
            else -> "'"
        }
        val escaped = s.flatMap { c ->
            when {
                c == '\\' -> listOf('\\', '\\')
                c == q.first() -> listOf('\\', q.first())
                c == '\n' -> listOf('\\', 'n')
                c == '\r' -> listOf('\\', 'r')
                c == '\t' -> listOf('\\', 't')
                c.code in 0..8 -> listOf('\\', 'u') + c.code.toString(16).padStart(4, '0').flatMap { listOf(it) }
                else -> listOf(c)
            }
        }.joinToString("")
        return "$q$escaped$q"
    }

    // ==========================================================================
    // 整合：给定入口 VirtualFile（.ts/.tsx/.js/.jsx）+ 新的扁平翻译 JSON，
    //       生成写回该文件所需的"新文本"。
    // 返回：Pair(newText, writtenEntryRange in newText) 或 null（无法解析，回退剪贴板）
    // ==========================================================================
    fun regenerateTsFileWithNewJson(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>
    ): String? {
        val psiFile = ApplicationManager.getApplication().runReadAction<PsiFile?> {
            PsiManager.getInstance(project).findFile(entryVf)
        }
        val text = if (psiFile != null) psiFile.text else try {
            String(entryVf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) { return null }
        val info = parseTsExportedObject(text) ?: return null
        val merged = mergeFlatIntoNested(info.staticKV, newFlatJson)
        val oldObjBody = text.substring(info.objectRange.first, info.objectRange.last)
        val newObjBody = regenerateObjectLiteralBody(oldObjBody, merged)
        return text.substring(0, info.objectRange.first) + newObjBody + text.substring(info.objectRange.last)
    }

    // ==========================================================================
    // JSON 文件：直接解析 + 合并扁平 JSON（点式 key 尝试展开嵌套，冲突以新为准）+ 重新生成
    // ==========================================================================
    fun regenerateJsonFileWithNewJson(
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>
    ): String? {
        val content = try {
            String(entryVf.contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) { return null }
        val rootJson: JsonElement = try {
            JsonParser.parseString(content)
        } catch (_: Exception) {
            // JSON 解析失败 → 兜底：把新 JSON 格式化返回（整个文件被新值覆盖）
            val g = GsonBuilder().setPrettyPrinting().create()
            return g.toJson(newFlatJson)
        }
        val existingMap = jsonElementToNestedMap(rootJson)
        val merged = mergeFlatIntoNested(existingMap, newFlatJson)
        val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        return gson.toJson(merged)
    }

    private fun jsonElementToNestedMap(el: JsonElement): Map<String, Any?> {
        if (!el.isJsonObject) return emptyMap()
        val obj = el.asJsonObject
        val result = LinkedHashMap<String, Any?>()
        for ((k, v) in obj.entrySet()) {
            result[k] = jsonElementToKotlin(v)
        }
        return result
    }

    private fun jsonElementToKotlin(el: JsonElement): Any? {
        return when {
            el.isJsonNull -> null
            el.isJsonPrimitive -> {
                val p = el.asJsonPrimitive
                when {
                    p.isBoolean -> p.asBoolean
                    p.isNumber -> {
                        val n = p.asNumber
                        if (n is Long || n is Int) n.toLong() else n.toDouble()
                    }
                    p.isString -> p.asString
                    else -> p.asString
                }
            }
            el.isJsonObject -> jsonElementToNestedMap(el)
            el.isJsonArray -> el.asJsonArray.map { jsonElementToKotlin(it) }.toList()
            else -> null
        }
    }

    // ==========================================================================
    // 把 VirtualFile 内容替换为新文本（Write 安全封装）。
    // 调用方需要自己包裹在 WriteCommandAction / invokeAndWait 中。
    // ==========================================================================
    fun writeVirtualFileText(entryVf: VirtualFile, newText: String) {
        val bytes = newText.toByteArray(StandardCharsets.UTF_8)
        entryVf.setBinaryContent(bytes, 0L, bytes.size.toLong(), null)
    }

    /** 把虚拟文件路径作为"候选"持久化，供下次优先命中。 */
    fun persistEntryPathIfNeeded(project: Project, entryVf: VirtualFile) {
        setStoredEntryPath(project, entryVf.path)
    }

}
