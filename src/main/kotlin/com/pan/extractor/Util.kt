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
import com.intellij.openapi.vfs.VirtualFileManager
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
    /** 高频复用正则：避免每次在循环里重复编译（性能）。 */
    private val REACT_KEY_RE = Regex(""""react"\s*:\s*"""")
    private val VUE_KEY_RE = Regex(""""vue"\s*:\s*"""")

    /** 常见 helper：文本中是否包含至少 1 个目标语言的字符。
     *  - 用于判断差异段是否要嵌套 `$t('差异')`（含目标语言→嵌套；纯英文/数字→直接写字符串字面量）。
     *  - 目标语言取决于全局设置（默认仅中文，向后兼容）。*/
    fun hasChinese(text: CharSequence?): Boolean = containsTargetLanguage(text)

    /** 文本是否包含任一“已启用目标语言”的字符（由全局设置决定，默认仅中文）。 */
    fun containsTargetLanguage(text: CharSequence?): Boolean =
        containsTargetLanguage(text, SiteKind.OTHER)

    /** 文本在该站点上下文下是否命中任一“已启用目标语言”（Approach A：先看上下文是否接受，再看字符判定）。 */
    fun containsTargetLanguage(text: CharSequence?, site: SiteKind): Boolean {
        if (text == null) return false
        return I18nSettings.getInstance().activeExtractors()
            .any { it.accepts(site) && it.judge(text) }
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

    /** 翻译资源常见目录名（全部小写，按路径片段匹配）；内置 + 用户自定义。 */
    private val TRANSLATION_DIRS_DEFAULT = setOf("locales","i18n","locale","lang","languages","translations")

    /** 用于路径片段匹配的翻译目录集合（内置目录 + 设置里自定义的目录）。 */
    private fun translationDirs(): Set<String> =
        TRANSLATION_DIRS_DEFAULT + I18nSettings.getInstance().customTranslationDirs().map { it.lowercase() }

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
            for (dir in translationDirs()) {
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
            if (relativePath.split("/").any { it in I18nSettings.getInstance().excludeDirs() }) return@forEach

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
        // 统一相对项目根：去掉开头的 ./ 与前导 /（A7：根目录型 include）
        var normalized = glob.trim().replace("\\", "/")
            .removePrefix("./")
            .removePrefix("/")
        if (normalized.isEmpty()) normalized = "**"

        // 结尾带斜杠 = 目录：附加通配以匹配其下所有文件
        if (normalized.endsWith("/")) normalized += "**"

        // 无通配符的裸路径：最后一段带扩展名按精确文件匹配；否则按目录匹配（含其下所有文件）（A8）
        val hasWildcard = normalized.contains('*')
        val lastSegmentExt = normalized.substringAfterLast('/').substringAfterLast('.', "")
        val isDirPath = !hasWildcard && lastSegmentExt.isEmpty()

        var pattern = normalized
            .replace(".", "\\.")
            .replace("**/", "(.*/)?")
            .replace("/**", "(/.*)?")
            .replace("**", ".*")
            .replace("*", "[^/]*")
        if (isDirPath) pattern = "$pattern(/.*)?"
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
    // 查找项目的"目标语言多语言入口文件"（zh-CN / ja-JP / ko_KR 等命名）
    // 目标语言取决于全局设置（默认仅中文，向后兼容）。
    // ==========================================================================

    /** 找目标语言语言包入口文件的常见基名（不带扩展名）。 */
    private fun isTargetLocaleBasename(stem: String): Boolean {
        val lower = stem.lowercase()
        val candidates = I18nSettings.getInstance().activeLocaleCandidates()
        // 直接相等
        if (candidates.any { it.equals(lower, ignoreCase = true) }) return true
        // messages.zh-CN / i18n.ja / translations.ko_KR 这种
        val dotIdx = lower.lastIndexOf('.')
        if (dotIdx >= 0) {
            val prefix = lower.substring(0, dotIdx)
            val suffix = lower.substring(dotIdx + 1)
            if (TRANSLATION_BASE_PREFIXES.contains(prefix) &&
                candidates.any { it.equals(suffix, ignoreCase = true) }) return true
        }
        // 兜底：<langtag><region>（zhCN / jaJP / koKR 等）
        for (ex in I18nSettings.getInstance().activeExtractors()) {
            val tag = ex.langTagPrefix
            if (lower.length in 4..7 && lower.startsWith(tag)) {
                val rest = lower.drop(tag.length)
                if (rest.all { it.isLetterOrDigit() } && ex.regionCodes.any { rest.contains(it) })
                    return true
            }
        }
        return false
    }

    /**
     * 尝试定位"目标语言多语言入口文件"。
     * 优先级：
     *   1. 用户上次选择并持久化的路径（若文件仍存在）
     *   2. 项目根下常见 i18n 目录中匹配目标语言 locale 命名的文件（.ts/.tsx/.js/.json）
     *   3. 整个项目（排除 node_modules）按 isTranslationResourceFile + 目标语言 basename 扫描
     * @return 命中的 VirtualFile 或 null
     */
    fun findChineseLocaleEntryFile(project: Project, contextPsiFile: PsiFile?): VirtualFile? {
        // 1) 用户持久化的路径
        val stored = getStoredEntryPath(project)
        if (stored != null) {
            val f = resolveStoredEntryPath(stored)
            if (f != null && f.isValid && !f.isDirectory) return f
        }
        val root = if (contextPsiFile != null) findProjectRoot(contextPsiFile) else {
            project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        }
        if (root == null || !root.isDirectory) return null

        // 2) 常见目录优先精确匹配
        val commonDirs = mutableListOf(
            "src/locales", "locales", "src/i18n", "i18n",
            "src/locale", "locale", "src/lang", "lang",
            "src/languages", "languages", "src/translations", "translations"
        )
        // 追加用户自定义的翻译目录（如 src/assets/lang、assets/lang）
        for (custom in I18nSettings.getInstance().customTranslationDirs()) {
            commonDirs += "src/$custom"
            commonDirs += custom
        }
        for (rel in commonDirs) {
            val dir = findRelativeFile(root, rel) ?: continue
            if (!dir.isDirectory) continue
            val hit = walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isDirectory || !vf.isValid) return@walkVirtualFile null
                val ext = vf.extension?.lowercase() ?: return@walkVirtualFile null
                if (ext !in setOf("ts","tsx","js","jsx","json")) return@walkVirtualFile null
                val nameNoExt = vf.nameWithoutExtension
                if (isTargetLocaleBasename(nameNoExt)) vf else null
            }
            if (hit != null) return hit
        }
        // 3) 预设目录未命中：统一像 Vue / React 全局导入那样探测 i18n 初始化文件，再根据其配置项查目标语言入口
        findChineseEntryViaI18nConfig(root)?.let { if (it.isValid && !it.isDirectory) return it }
        // 4) 全项目 walk（深度 5，排除 node_modules/.git/dist/build）
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return walkVirtualFile(root, maxDepth = 5, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isDirectory || !vf.isValid) return@walkVirtualFile null
            val ext = vf.extension?.lowercase() ?: return@walkVirtualFile null
            if (ext !in setOf("ts","tsx","js","jsx","json")) return@walkVirtualFile null
            // 目录段命中翻译目录 or 基名像目标语言 locale
            val pathLike = isTranslationResourceFile(vf.name, vf.path)
            val baseLike = isTargetLocaleBasename(vf.nameWithoutExtension)
            if ((pathLike || baseLike) && isTargetLocalePathHit(vf)) {
                vf
            } else null
        }
    }

    /** 判断文件路径/基名是否严格命中任一已启用语言的标识（locale 候选 或 `<tag><region>`）。 */
    private fun isTargetLocalePathHit(vf: VirtualFile): Boolean {
        val nameNoExt = vf.nameWithoutExtension
        val candidates = I18nSettings.getInstance().activeLocaleCandidates()
        if (candidates.any { nameNoExt.contains(it, ignoreCase = true) }) return true
        // 路径段精确命中 locale 候选（如目录 zh-CN / en-US / ja-JP）
        val segments = vf.path.split('/').map { it.lowercase() }
        if (segments.any { seg -> candidates.any { it.lowercase() == seg } }) return true
        // 兜底：<tag><region>（zhCN / enUS / jaJP 等）
        for (ex in I18nSettings.getInstance().activeExtractors()) {
            val lower = nameNoExt.lowercase()
            val tag = ex.langTagPrefix
            if (lower.length in 4..7 && lower.startsWith(tag)) {
                val rest = lower.drop(tag.length)
                if (rest.all { it.isLetterOrDigit() } && ex.regionCodes.any { rest.contains(it) })
                    return true
            }
        }
        return false
    }

    /**
     * 解析用户持久化的入口路径。
     * 兼容：
     *   · URL（含 file://、temp:// 等 scheme）→ 用 VirtualFileManager 解析（任意 VFS 均可命中）
     *   · 真实本地路径 → LocalFileSystem（先 refresh 以识别新建文件）
     */
    private fun resolveStoredEntryPath(stored: String): VirtualFile? {
        if (stored.contains("://")) {
            VirtualFileManager.getInstance().findFileByUrl(stored)?.let { return it }
        }
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(stored)
    }

    /**
     * 统一像 Vue / React 全局导入那样探测：找到 i18n 初始化文件（createI18n 或 i18n.init），
     * 然后根据其配置项查出实际的中文 message 来源文件。
     *
     * Vue 示例：
     *   import zhLocales from '../config/messages/zh-locales'
     *   createI18n({ legacy: false, locale: 'zh-CN', messages: { 'zh-CN': zhLocales, en: enLocales } })
     *
     * React (react-i18next) 示例：
     *   import zh from '../locales/zh-CN'
     *   i18n.use(initReactI18next).init({ lng: 'zh-CN', resources: { 'zh-CN': { translation: zh } } })
     */
    fun findChineseEntryViaI18nConfig(root: VirtualFile): VirtualFile? {
        val initFile = findI18nInitFileInRoot(root) ?: return null
        val text = try { String(initFile.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return null }
        return if (text.contains("createI18n(") || text.contains("createI18n (")) {
            findVueEntryFromConfigText(initFile, text)
        } else {
            findReactEntryFromConfigText(initFile, text)
        }
    }

    /** 从 Vue createI18n 配置文本中解析中文入口。 */
    private fun findVueEntryFromConfigText(initFile: VirtualFile, text: String): VirtualFile? {
        // 1) 定位 createI18n( 的配置对象
        val createIdx = Regex("""createI18n\s*\(""").find(text)?.range?.first ?: return null
        val brace = text.indexOf('{', createIdx)
        if (brace < 0) return null
        val optionsEnd = findBalancedCloseBrace(text, brace) ?: return null
        val options = text.substring(brace, optionsEnd)

        // 2) 读取 locale 配置值（如 'zh-CN'）
        val localeCode = Regex("""locale\s*:\s*['"]([^'"]+)['"]""").find(options)?.groupValues?.get(1)

        // 3) 解析 messages: { ... } 里的语言->引用 映射
        val messagesMatch = Regex("""messages\s*:\s*\{""").find(options) ?: return null
        val mBrace = messagesMatch.range.last
        val mEnd = findBalancedCloseBrace(options, mBrace) ?: return null
        val refs = parseMessagesRefs(options.substring(mBrace, mEnd))
        if (refs.isEmpty()) return null

        // 4) 选目标：优先 locale 配置对应的引用，其次 zh 风味的语言 key，最后第一个
        val target = pickChineseRef(refs, localeCode) ?: return null
        val valueExpr = target.second.trim()
        if (valueExpr.isEmpty() || valueExpr.startsWith("{") || valueExpr.startsWith("[")) return null

        // 5) 把引用名解析成 import 路径并定位文件
        val importPath = resolveImportPathForIdentifier(text, valueExpr) ?: return null
        return resolveLocalImportFile(initFile, importPath)
    }

    /** 从 React i18n.init / i18next.init 配置文本中解析中文入口。 */
    private fun findReactEntryFromConfigText(initFile: VirtualFile, text: String): VirtualFile? {
        // 1) 定位 resources: { ... }（React 的 messages 结构，语言下再包一层 namespace）
        val resourcesMatch = Regex("""\bresources\s*:\s*\{""").find(text) ?: return null
        val rBrace = resourcesMatch.range.last
        val rEnd = findBalancedCloseBrace(text, rBrace) ?: return null
        val refs = parseResourcesRefs(text.substring(rBrace, rEnd))
        if (refs.isEmpty()) return null

        // 2) 读取 lng 配置值（如 'zh-CN'）
        val localeCode = Regex("""\blng\s*:\s*['"]([^'"]+)['"]""").find(text)?.groupValues?.get(1)

        // 3) 选目标
        val target = pickChineseRef(refs, localeCode) ?: return null
        val valueExpr = target.second.trim()
        if (valueExpr.isEmpty() || valueExpr.startsWith("{") || valueExpr.startsWith("[")) return null

        // 4) 把引用名解析成 import 路径并定位文件
        val importPath = resolveImportPathForIdentifier(text, valueExpr) ?: return null
        return resolveLocalImportFile(initFile, importPath)
    }

    /** 从引用列表中选出目标：优先 locale 配置对应的语言，其次命中目标语言 locale 命名，再其次语言前缀，最后第一个。 */
    private fun pickChineseRef(refs: List<Pair<String, String>>, localeCode: String?): Pair<String, String>? {
        val tags = I18nSettings.getInstance().activeExtractors().map { it.langTagPrefix }
        return refs.firstOrNull { it.first == localeCode }
            ?: refs.firstOrNull { isTargetLocaleBasename(it.first) }
            ?: refs.firstOrNull { ref -> tags.any { ref.first.lowercase().startsWith(it) } }
            ?: refs.firstOrNull()
    }

    /** 解析 React resources 对象体，返回 [(语言 key, 引用表达式)]（跳过内联对象/数组）。 */
    private fun parseResourcesRefs(resourcesBody: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (langProp in splitTopLevelProperties(resourcesBody)) {
            val (langKey, langValue) = parseOneProperty(langProp.trim()) ?: continue
            val langClean = stripValueSuffixes(langValue).trim()
            if (!(langClean.startsWith("{") && langClean.endsWith("}"))) continue
            val nsBody = langClean.substring(1, langClean.length - 1)
            for (nsProp in splitTopLevelProperties(nsBody)) {
                val (_, nsValue) = parseOneProperty(nsProp.trim()) ?: continue
                val v = stripValueSuffixes(nsValue).trim()
                if (v.startsWith("{") || v.startsWith("[")) continue
                result.add(langKey to v)
            }
        }
        return result
    }

    /** 解析 messages 对象体，返回 [(语言 key, 引用表达式)]，兼容 keyed 与 shorthand 写法。 */
    private fun parseMessagesRefs(mBody: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        for (prop in splitTopLevelProperties(mBody)) {
            val t = prop.trim()
            if (t.isEmpty()) continue
            val kv = parseOneProperty(t)
            if (kv != null) {
                val v = stripValueSuffixes(kv.second).trim()
                if (v.startsWith("{") || v.startsWith("[")) continue // 内联对象/数组，非文件引用
                result.add(kv.first to v)
            } else if (t.matches(Regex("""[A-Za-z_$][\w$]*"""))) {
                result.add(t to t) // shorthand：`zh,` → key 与引用同名
            }
        }
        return result
    }

    /** 在文本中查找导入指定标识符的 import 语句，返回其模块路径。 */
    private fun resolveImportPathForIdentifier(text: String, identifier: String): String? {
        val re = Regex("""import\s+(${Regex.escape(identifier)})\s*(?:,\s*\{[^}]*\})?\s+from\s*['"]([^'"]+)['"]""")
        return re.find(text)?.groupValues?.get(2)
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
        // 模式 3：export const <name> = { / export let / export var。
        // 兼容可选类型标注：export const <name>: <T> = {（T 内不含顶层 '=' 且单行，覆盖 Record<>/接口名/内联对象类型等）。
        run {
            val re = Regex("""export\s+(const|let|var)\s+([\w$][\w$]*)\s*(?::[^=\n]+)?\s*=\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val name = m.groupValues[2]
                // 定位 '=' 之后第一个 '{'：类型标注里可能含 '{'（内联对象类型），
                // 必须用「= 之后第一个 {」而不是「最后一个 {」来定位对象字面量起点。
                val eqLocal = m.value.lastIndexOf('=')
                var i = m.range.first + eqLocal + 1
                while (i < text.length && text[i] != '{') i++
                if (i >= text.length) return@run
                return ExportAnchor(i, "named:$name", inferIndent(text, i))
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
        // 模式 6：export default defineXxx({ ... }) —— 支持 i18n 常用包裹函数
        // （defineI18nConfig / defineMessages / defineConfig / createI18n 等），对象字面量在函数括号内。
        run {
            val re = Regex("""export\s+default\s+([A-Za-z_$][\w$]*)\s*\(\s*\{""")
            val m = re.find(text)
            if (m != null) {
                val braceIdx = m.value.indexOfLast { it == '{' } + m.range.first
                return ExportAnchor(braceIdx, "default:${m.groupValues[1]}", inferIndent(text, braceIdx))
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
        var inLineComment = false
        var inBlockComment = false
        while (i < text.length) {
            val c = text[i]
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> {
                    if (c == '*' && i + 1 < text.length && text[i + 1] == '/') {
                        inBlockComment = false
                        i++
                    }
                }
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                c == '/' && i + 1 < text.length && text[i + 1] == '/' -> inLineComment = true
                c == '/' && i + 1 < text.length && text[i + 1] == '*' -> {
                    inBlockComment = true
                    i++
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
        // 【Bug A6 修复】属性片段可能以注释开头（splitTopLevelProperties 只按顶层逗号切分，
        // 注释行会和紧随其后的属性拼在一起）。先剥离前导注释，避免把注释当 key 解析失败。
        val body = stripLeadingComments(prop)
        var inString: Char? = null
        var escapeNext = false
        var depth = 0
        var colonIdx = -1
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
        val keyPart = body.substring(0, colonIdx).trim()
        val valuePart = body.substring(colonIdx + 1).trim()
        val key = parsePropertyKey(keyPart) ?: return null
        return key to valuePart
    }

    /** 【Bug A6】剥离属性片段前导的 // 行注释或 /* */ 块注释（可含多行）。 */
    private fun stripLeadingComments(s: String): String {
        var text = s.trimStart()
        var changed = true
        while (changed && text.isNotEmpty()) {
            changed = false
            if (text.startsWith("//")) {
                val nl = text.indexOf('\n')
                text = if (nl < 0) "" else text.substring(nl + 1).trimStart()
                changed = true
            } else if (text.startsWith("/*")) {
                val end = text.indexOf("*/")
                text = if (end < 0) "" else text.substring(end + 2).trimStart()
                changed = true
            }
        }
        return text
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

    /**
     * 去掉值表达式尾部与静态判定无关的 TS 后缀，以便 `{ ... } as const`、`x satisfies Foo`
     * 等常见写法也能落到对象/原始字面量解析。只剥除末尾的 ` as const` 与 ` satisfies <Type>`。
     */
    private fun stripValueSuffixes(expr: String): String {
        var t = expr.trim()
        if (t.endsWith(" as const")) t = t.removeSuffix(" as const").trim()
        t = t.replace(Regex("""\s+satisfies\s+[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$"""), "").trim()
        return t
    }

    /** 尝试把一个表达式片段解析为静态值；非静态返回 null。 */
    private fun tryParseStaticValue(expr: String): Any? {
        val s = stripValueSuffixes(expr)
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
        // 方式：先扫描旧对象，识别每个顶层属性；单行静态值 → 行内重写；
        // 多行对象/数组块且 key 在合并结果中 → 整块重写（从而能合入"点式"新增的嵌套子 key）；
        // 非静态行（spread/方法/引用）→ 原样保留。最后追加全新顶层 key。
        val oldBody = oldObjBody.trim().let {
            if (it.startsWith("{") && it.endsWith("}")) it.substring(1, it.length - 1) else it
        }
        val lines = oldBody.split("\n").toMutableList()
        val mergedKeys = mergedNested.keys.toSet()

        // 推断默认缩进单位（首行有内容的 indent）
        val innerIndentUnit = lines.firstOrNull { it.isNotBlank() }?.takeWhile { it == ' ' || it == '\t' }.orEmpty()
            .ifBlank { "  " }

        // 第一遍扫描：
        //  - singleRewrites: 单行静态值可重写（记录原始 lineIdx）
        //  - blockRewrites: 多行对象/数组块（key 在 mergedNested 中才重写）
        //  - consumed[i]: 该行属于某个多行块，不应再被当作独立顶层行处理（避免嵌套行被误判为顶层属性）
        data class SingleRewrite(
            val lineIdx: Int,
            val key: String,
            val colonPosInLine: Int,
            val trailingComma: Boolean,
            val trailingComment: String   // 已有尾注释（如 " // 说明"），重写时保留
        )
        val singleRewrites = ArrayList<SingleRewrite>()
        data class BlockRewrite(
            val start: Int,
            val end: Int,
            val key: String,
            val keyRowPrefix: String,
            val trailingComment: String
        )
        val blockRewrites = ArrayList<BlockRewrite>()
        val parsedTopKeys = HashSet<String>()
        val consumed = BooleanArray(lines.size)

        var idx = 0
        while (idx < lines.size) {
            if (consumed[idx]) { idx++; continue }
            val rawLine = lines[idx]
            val indent = Regex("""^(\s*)""").find(rawLine)?.groupValues?.get(1).orEmpty()
            val line = rawLine.trimStart()
            // 空行或纯注释行 → 跳过
            if (line.isBlank() || line.startsWith("//") || line.startsWith("/*")) { idx++; continue }
            // 找顶层 ':'（不在字符串/嵌套 {}[] 中）
            val colonPosInTrimmed = findTopLevelColon(line) ?: run { idx++; continue }
            val keyExpr = line.substring(0, colonPosInTrimmed).trim()
            val key = parsePropertyKey(keyExpr) ?: run { idx++; continue }
            parsedTopKeys.add(key)
            val valuePartRaw = line.substring(colonPosInTrimmed + 1).trim()
            // 分离值末尾的尾注释（// xxx 或 /* xxx */），使：
            //   1) 静态判定不受尾注释干扰（否则 `key: 'a', // note` 会被误判为不可重写而漏改）
            //   2) 重写 value 时能保留原有尾注释，避免格式漂移
            val (valuePart, trailingComment) = splitTrailingComment(valuePartRaw)

            if (isSingleLineStaticValue(valuePart)) {
                val trailingComma = valuePart.trimEnd().endsWith(",")
                singleRewrites.add(SingleRewrite(idx, key, indent.length + colonPosInTrimmed, trailingComma, trailingComment))
            } else {
                // 多行对象/数组块：始终定位其范围并"消费"，避免嵌套行被误判为顶层属性；
                // 只有 key 在合并结果中才整块重写（从而合入新增的嵌套子 key）。
                val isBlock = valuePart.startsWith("{") || valuePart.startsWith("[")
                val blockEnd = if (isBlock) findBlockEndIndex(lines, idx) else idx
                for (j in idx + 1..blockEnd) consumed[j] = true
                val blockText = lines.subList(idx, blockEnd + 1).joinToString("\n")
                val staticBlock = isBlock && !containsNonStaticCollection(blockText)
                if (staticBlock && key in mergedKeys) {
                    val keyColonInRow = indent.length + colonPosInTrimmed
                    val keyRowPrefix = rawLine.substring(0, keyColonInRow + 1)  // "  key:"
                    blockRewrites.add(BlockRewrite(idx, blockEnd, key, keyRowPrefix, trailingComment))
                }
            }
            idx++
        }

        // 第二遍：按序重建输出；块重写整体替换，单行重写行内替换，其余原样保留
        val blockByStart = blockRewrites.associateBy { it.start }
        val out = ArrayList<String>()
        var i = 0
        while (i < lines.size) {
            val block = blockByStart[i]
            if (block != null) {
                val rendered = renderStaticValue(mergedNested[block.key], innerIndentUnit, nestingDepth = 2)
                val rLines = rendered.split("\n")
                val comment = block.trailingComment
                if (rLines.size == 1) {
                    // 标量：整块替换成单行
                    out.add("${block.keyRowPrefix} $rendered,$comment")
                } else {
                    out.add("${block.keyRowPrefix} ${rLines.first()}")
                    out.addAll(rLines.subList(1, rLines.lastIndex))
                    out.add(rLines.last() + ",$comment")
                }
                i = block.end + 1
                continue
            }
            // 单行重写
            val rw = singleRewrites.firstOrNull { it.lineIdx == i }
            if (rw != null && rw.key in mergedKeys) {
                val valueStr = renderStaticValue(mergedNested[rw.key], innerIndentUnit, nestingDepth = 1)
                val prefix = lines[i].substring(0, rw.colonPosInLine + 1)  // "  key:"
                val suffix = if (rw.trailingComma) "," else ""
                out.add("$prefix $valueStr$suffix${rw.trailingComment}")
                i++
                continue
            }
            out.add(lines[i])
            i++
        }

        // 追加新 key（mergedNested 里有，但旧对象没有的）
        val newKeys = mergedKeys.filter { it !in parsedTopKeys }.sorted()
        if (newKeys.isNotEmpty()) {
            // 找最后一行非空行的位置，在其后面追加
            var insertPos = out.size
            for (k in out.indices.reversed()) {
                if (out[k].isNotBlank()) {
                    insertPos = k + 1
                    val last = out[k]
                    val lastTrimmed = last.trimEnd()
                    val endsWithOpen = lastTrimmed.endsWith(",") || lastTrimmed.endsWith("{") || lastTrimmed.endsWith("[")
                    if (lastTrimmed.isNotEmpty() && !endsWithOpen) {
                        out[k] = last.substring(0, lastTrimmed.length) + "," + last.substring(lastTrimmed.length)
                    }
                    break
                }
            }
            val additions = newKeys.map { k ->
                val keyExpr = if (k.matches(Regex("""[A-Za-z_$][\w$]*"""))) k else quoteForTs(k)
                val valueStr = renderStaticValue(mergedNested[k], innerIndentUnit, nestingDepth = 1)
                "$innerIndentUnit$keyExpr: $valueStr,"
            }
            out.addAll(insertPos, additions)
        }

        // 去掉首尾空行（来自对象字面量首尾换行产生的空元素），避免 } 前出现多余空行
        while (out.isNotEmpty() && out.first().isBlank()) out.removeAt(0)
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.lastIndex)

        // 重新组合对象字面量
        return "{" + out.joinToString("\n") + "\n}"
    }

    /** 从 startIdx 行开始，找到与行首开括号匹配的闭合行索引（含）。 */
    private fun findBlockEndIndex(lines: List<String>, startIdx: Int): Int {
        var depth = 0
        var inString: Char? = null
        var escapeNext = false
        var inBlockComment = false
        for (i in startIdx until lines.size) {
            val row = lines[i]
            var j = 0
            while (j < row.length) {
                val c = row[j]
                when {
                    inBlockComment -> {
                        if (c == '*' && j + 1 < row.length && row[j + 1] == '/') { inBlockComment = false; j++ }
                    }
                    escapeNext -> escapeNext = false
                    inString != null -> when (c) {
                        '\\' -> escapeNext = true
                        inString -> inString = null
                    }
                    c == '/' && j + 1 < row.length && row[j + 1] == '/' -> break  // 行注释：忽略本行剩余
                    c == '/' && j + 1 < row.length && row[j + 1] == '*' -> { inBlockComment = true; j++ }
                    else -> when (c) {
                        '"', '\'', '`' -> inString = c
                        '{', '[' -> depth++
                        '}', ']' -> { depth--; if (depth == 0) return i }
                    }
                }
                j++
            }
        }
        return startIdx
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

    /**
     * 从属性值片段末尾剥离尾注释（行注释或块注释），返回 (纯值, 含前导空白的注释)。
     * 找不到注释时返回 (原值, "")。逐字符扫描以避开字符串内的注释标记。
     */
    private fun splitTrailingComment(expr: String): Pair<String, String> {
        var inString: Char? = null
        var escapeNext = false
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            val next = expr.getOrNull(i + 1)
            when {
                escapeNext -> escapeNext = false
                inString != null -> when (c) {
                    '\\' -> escapeNext = true
                    inString -> inString = null
                }
                else -> {
                    if (c == '/' && next == '/') {
                        return expr.substring(0, i).trimEnd() to expr.substring(i)
                    } else if (c == '/' && next == '*') {
                        return expr.substring(0, i).trimEnd() to expr.substring(i)
                    } else when (c) {
                        '"', '\'', '`' -> inString = c
                    }
                }
            }
            i++
        }
        return expr to ""
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
                (t.startsWith("{") && matchingBraces(t) && !containsNonStaticCollection(t)) ||
                (t.startsWith("[") && matchingBraces(t) && !containsNonStaticCollection(t)))
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

    /**
     * 判断一个对象/数组字面量是否含非静态内容（spread `...`、方法、引用、调用等）。
     * 含则返回 true：这类值重新生成会丢失原始表达式（如 `sub: { ...deeper }` 会被压成 `{}`），
     * 必须原样保留，不能走静态重写。
     */
    private fun containsNonStaticCollection(text: String): Boolean {
        var v = text.trim().trimEnd(',')
        v = if (v.endsWith("as const")) v.removeSuffix("as const").trim() else v
        var t = v
        // 带 key 前缀的整行/整块（如 "sub: { ...deeper }"）→ 剥掉 key 部分只分析值，
        // 否则会被误判为静态而把动态表达式压成 {}（见 testTargetWithOwnNestedSpreadIsResilient）。
        val colon = findTopLevelColon(t)
        if (colon != null) {
            val valPart = t.substring(colon + 1).trim()
            if (valPart.startsWith("{") || valPart.startsWith("[")) t = valPart
        }
        // 不是 {…}/{…} 集合字面量（如纯字符串/数字/引用）→ 不在此判定范围，按静态处理
        val inner = if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]")))
            t.substring(1, t.length - 1) else return false
        if (inner.isBlank()) return false
        for (prop in splitTopLevelProperties(inner)) {
            val p = prop.trim()
            if (p.startsWith("...")) return true
            val colon = findTopLevelColon(p)
            if (colon == null) {
                // 数组元素：无冒号不是对象属性
                if (!isSingleLineStaticValue(p)) return true
                continue
            }
            val pv = p.substring(colon + 1).trim()
            if (pv.startsWith("...") || pv.contains("=>") || pv.startsWith("function")) return true
            if (pv.startsWith("{") || pv.startsWith("[")) {
                if (containsNonStaticCollection(pv)) return true
            } else if (!isSingleLineStaticValue(pv)) {
                return true
            }
        }
        return false
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
        // objectRange 是 exclusive 区间 [objStart, objEnd)，endExclusive 指向闭合 } 的后一位。
        // 必须包含闭合 }，regenerateObjectLiteralBody 才能正确去掉外层大括号重写。
        val oldObjBody = text.substring(info.objectRange.first, info.objectRange.endExclusive)
        val newObjBody = regenerateObjectLiteralBody(oldObjBody, merged)
        return text.substring(0, info.objectRange.first) + newObjBody + text.substring(info.objectRange.endExclusive)
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
    // Spread 引用解析：export default { ...common } 中 common 指向同文件 const 或本地 import。
    // 支持：同文件 const 对象、本地 import 的 TS/JS、本地 import 的 JSON（非 node_modules）。
    // 路由规则：新 key 写进 spread 变量指向的文件，入口对象只更新自身已有的 key。
    // ==========================================================================
    private data class ResolvedSpreadTarget(
        val file: VirtualFile,        // 要写入的文件（同文件 const 时 = 入口文件）
        val objRangeInText: IntRange, // 目标对象在 file 文本中的区间（JSON 目标占位 0..-1）
        val existingKeys: Map<String, Any?>,
        val kind: String,             // "const" | "ts" | "json"
        val readOnly: Boolean = false // node_modules 等只读目标：识别内容但不写盘
    )

    /** 一个 spread 引用：`...varName`，path 为它被展开所在的容器对象路径（顶层为空列表）。 */
    private data class SpreadRef(val varName: String, val path: List<String>)

    /**
     * 从对象字面量文本中递归提取 spread 引用（含嵌套对象里的 spread，如 `nav: { ...common }`）。
     * path 记录每个 spread 所在的容器路径，用于把新 key 精确路由到对应目标文件。
     */
    private fun findSpreadRefs(objBody: String, path: List<String>, depth: Int = 0): List<SpreadRef> {
        // 深度防护：字面嵌套极其罕见会超过此深度，防止病态递归导致栈溢出。
        if (depth > 32) return emptyList()
        val result = mutableListOf<SpreadRef>()
        val body = objBody.trim().let {
            if (it.startsWith("{") && it.endsWith("}")) it.substring(1, it.length - 1) else it
        }
        for (prop in splitTopLevelProperties(body)) {
            val t = prop.trim()
            if (t.startsWith("...")) {
                val name = t.removePrefix("...").trim()
                if (name.matches(Regex("""[A-Za-z_$][\w$]*"""))) result.add(SpreadRef(name, path))
                continue
            }
            // 值本身是对象字面量 → 递归进入（识别嵌套 spread）
            val (k, v) = parseOneProperty(prop) ?: continue
            val vClean = stripValueSuffixes(v)
            if (vClean.startsWith("{") && vClean.endsWith("}")) {
                result.addAll(findSpreadRefs(vClean, path + k, depth + 1))
            }
        }
        return result
    }

    /** 判断 key 是否位于 path 容器之下（path 为空 → 恒 true）。 */
    private fun isUnder(path: List<String>, key: String): Boolean {
        if (path.isEmpty()) return true
        val prefix = path.joinToString(".")
        return key == prefix || key.startsWith("$prefix.")
    }

    /** 把 key 转成相对 path 容器下的相对 key；key 不在 path 容器下则返回 null。 */
    private fun relativeKey(path: List<String>, key: String): String? {
        if (path.isEmpty()) return key
        val prefix = path.joinToString(".")
        return if (key.startsWith("$prefix.")) key.removePrefix("$prefix.") else null
    }

    /** 把容器路径 + 相对 key 拼成入口扁平 key。 */
    private fun joinPath(path: List<String>, k: String): String {
        return if (path.isEmpty()) k else path.joinToString(".") + "." + k
    }

    private fun readVirtualFileText(project: Project?, vf: VirtualFile): String? {
        return try {
            if (project != null) {
                val psi = ApplicationManager.getApplication().runReadAction<PsiFile?> {
                    PsiManager.getInstance(project).findFile(vf)
                }
                if (psi != null) psi.text else String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
            } else {
                String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 解析一个 spread 变量指向的目标对象。 */
    private fun resolveSpreadTarget(
        project: Project,
        entryVf: VirtualFile,
        entryText: String,
        varName: String,
        path: List<String> = emptyList(),
        visited: MutableSet<String> = HashSet()
    ): ResolvedSpreadTarget? {
        // 循环防护：const a = {...b} / const b = {...a} 相互 spread 时避免无限递归。
        if (!visited.add(varName)) return null
        // 1) 同文件 const：const <varName> = { ... }（兼容可选类型标注 const <varName>: T = { ... }）
        val constRe = Regex("""\bconst\s+${Regex.escape(varName)}\s*(?::[^=\n]+)?\s*=\s*\{""")
        val cm = constRe.find(entryText)
        if (cm != null) {
            // 定位 '=' 之后第一个 '{'（类型标注里可能含 '{'，不能直接用最后一个 '{'）
            val eqLocal = cm.value.lastIndexOf('=')
            var bi = cm.range.first + eqLocal + 1
            while (bi < entryText.length && entryText[bi] != '{') bi++
            if (bi >= entryText.length) return null
            val objEnd = findBalancedCloseBrace(entryText, bi) ?: return null
            val objBody = entryText.substring(bi, objEnd)
            val constKeys = parseObjectLiteralBody(objBody)
            val constTarget = ResolvedSpreadTarget(entryVf, bi until objEnd, constKeys, "const")
            // 多级递归：仅当 const 是「纯转发光束」（无自身静态 key，如 `const common = {...deeper}`）时，
            // 才继续下钻到更深的「非入口文件」可写目标，使新 key 写到真正归属的模块文件，
            // 而不是堆积在入口文件里这个本地 const 块。
            if (constKeys.isEmpty()) {
                val inner = findSpreadRefs(objBody, path)
                for (ref in inner) {
                    val deeper = resolveSpreadTarget(project, entryVf, entryText, ref.varName, ref.path, visited)
                    if (deeper != null && !deeper.readOnly && deeper.file.path != entryVf.path) {
                        return deeper
                    }
                }
            }
            return constTarget
        }
        // 2) import：import <varName> from '...'、import * as <varName> from '...'、import <varName>, { ... } from '...'
        val importRe = Regex("""import\s+(?:\*\s+as\s+)?(${Regex.escape(varName)})\s*(?:,\s*\{[^}]*\})?\s+from\s*['"]([^'"]+)['"]""")
        val im = importRe.find(entryText) ?: return null
        val spec = im.groupValues[2]
        // 本地相对/绝对路径优先；裸包名（node_modules）作为只读识别
        val localVf = resolveLocalImportFile(entryVf, spec)
        val targetVf = localVf ?: resolveNodeModulesFile(entryVf, spec) ?: return null
        val readOnly = localVf == null // node_modules → 只读（识别内容但不可写盘）
        val targetText = readVirtualFileText(project, targetVf) ?: return null
        return when (targetVf.extension?.lowercase()) {
            "json" -> {
                val root = try { JsonParser.parseString(targetText) } catch (_: Exception) { return null }
                val existing = jsonElementToNestedMap(if (root.isJsonObject) root else JsonParser.parseString("{}"))
                ResolvedSpreadTarget(targetVf, 0 until 0, existing, "json", readOnly)
            }
            else -> {
                val info = parseTsExportedObject(targetText) ?: return null
                ResolvedSpreadTarget(targetVf, info.objectRange, info.staticKV, "ts", readOnly)
            }
        }
    }

    /** 把相对/绝对导入路径解析为本地 VirtualFile；裸包名（node_modules）等非本地返回 null。 */
    private fun resolveLocalImportFile(fromFile: VirtualFile, spec: String): VirtualFile? {
        val clean = spec.trim()
        if (clean.isEmpty() || !(clean.startsWith(".") || clean.startsWith("/"))) return null
        val base = fromFile.parent ?: return null
        val rel = if (clean.startsWith("/")) clean.removePrefix("/") else clean
        val candidates = buildList {
            add(rel)
            if (!rel.substringAfterLast('/').contains('.')) {
                add("$rel.ts"); add("$rel.tsx"); add("$rel.js"); add("$rel.jsx"); add("$rel.json")
            }
            add("$rel/index.ts"); add("$rel/index.js"); add("$rel/index.json")
        }.distinct()
        for (p in candidates) {
            val vf = base.findFileByRelativePath(p) ?: continue
            if (vf.isDirectory) continue
            return vf
        }
        return null
    }

    /**
     * 把裸包名（node_modules）导入解析为实际文件：向上找最近的 node_modules，
     * 用 package.json 的 main 字段优先，否则退化到 index.js/index.json/dist/index.js。
     * 仅用于「识别内容」，返回的文件会被标记为只读，不会写盘。
     */
    private fun resolveNodeModulesFile(fromFile: VirtualFile, spec: String): VirtualFile? {
        val clean = spec.trim()
        if (clean.isEmpty() || clean.startsWith(".") || clean.startsWith("/")) return null
        var dir: VirtualFile? = fromFile.parent
        while (dir != null) {
            val nm = dir.findChild("node_modules")
            if (nm != null) {
                val pkg = nm.findFileByRelativePath(clean)
                if (pkg != null) {
                    if (pkg.isDirectory) {
                        val pkgJson = pkg.findChild("package.json")
                        var main: String? = null
                        if (pkgJson != null) {
                            main = try {
                                val root = JsonParser.parseString(String(pkgJson.contentsToByteArray(), StandardCharsets.UTF_8))
                                root.takeIf { it.isJsonObject }?.asJsonObject?.get("main")
                                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                            } catch (_: Exception) { null }
                        }
                        val candidates = buildList {
                            if (!main.isNullOrEmpty()) add(main)
                            add("index.js"); add("index.json"); add("dist/index.js")
                        }.distinct()
                        for (c in candidates) {
                            val vf = pkg.findFileByRelativePath(c) ?: continue
                            if (vf.isDirectory) continue
                            return vf
                        }
                    } else if (pkg.extension in setOf("js", "json", "mjs", "cjs")) {
                        return pkg
                    }
                }
            }
            dir = dir.parent
        }
        return null
    }

    /** 计算某个对象区间在给定文本中的新文本（基于合并后的扁平 key）。 */
    private fun newRegionText(text: String, objRange: IntRange, newFlat: Map<String, String>, existing: Map<String, Any?>): String {
        val merged = mergeFlatIntoNested(existing, newFlat)
        val oldObjBody = text.substring(objRange.first, objRange.endExclusive)
        return regenerateObjectLiteralBody(oldObjBody, merged)
    }

    /** 对同一文本应用多处区间替换（按区间从后往前，避免偏移漂移）。 */
    private fun applyRangeReplacements(text: String, replacements: List<Pair<IntRange, String>>): String {
        var result = text
        for ((range, newText) in replacements.sortedByDescending { it.first.last }) {
            result = result.substring(0, range.first) + newText + result.substring(range.endExclusive)
        }
        return result
    }

    /**
     * 识别入口 TS/JS 对象里的 spread 引用（如 `...common`），并把新 key 路由写到该变量指向的文件。
     * 返回要写盘的 (VirtualFile, newText) 列表；返回 null 表示未处理（无 spread 或无可解析目标），
     * 调用方应回退到 regenerateTsFileWithNewJson。
     */
    fun regenerateTsFileWithSpreadRouting(
        project: Project,
        entryVf: VirtualFile,
        newFlatJson: Map<String, String>
    ): List<Pair<VirtualFile, String>>? {
        val entryText = readVirtualFileText(project, entryVf) ?: return null
        val entryInfo = parseTsExportedObject(entryText) ?: return null
        val entryObjBody = entryText.substring(entryInfo.objectRange.first, entryInfo.objectRange.endExclusive)
        val spreadRefs = findSpreadRefs(entryObjBody, emptyList())
        if (spreadRefs.isEmpty()) return null
        val entryKeys = entryInfo.staticKV.keys.toSet()

        // 解析每个 spread 引用指向的目标（含 node_modules 只读识别）。
        // 共享 visited 集合防止 const 相互 spread 造成重复解析/循环依赖。
        val visited = HashSet<String>()
        val resolved = spreadRefs.mapNotNull { ref ->
            resolveSpreadTarget(project, entryVf, entryText, ref.varName, ref.path, visited)?.let { ref to it }
        }
        if (resolved.isEmpty()) return null // 全部无法解析 → 回退旧逻辑
        // 所有目标已识别的 key（按各自容器路径展开成入口扁平 key），用于避免重复写入
        val covered = resolved.flatMap { (ref, target) ->
            target.existingKeys.keys.map { joinPath(ref.path, it) }
        }.toSet()
        val writableResolved = resolved.filter { !it.second.readOnly }

        // 只有只读（node_modules）目标 → 识别内容，真正新增的 key 写入口对象
        if (writableResolved.isEmpty()) {
            val entryAll = newFlatJson.filterKeys { it in entryKeys || it !in covered }
            return listOf(entryVf to applyRangeReplacements(entryText, listOf(
                entryInfo.objectRange to newRegionText(entryText, entryInfo.objectRange, entryAll, entryInfo.staticKV)
            )))
        }

        // 为每个真正新增的 key 决定去向：优先最深的可写容器 spread 目标，否则入口
        data class WriteUnit(val target: ResolvedSpreadTarget, val relative: MutableMap<String, String>)
        val targetWrites = linkedMapOf<String, WriteUnit>() // key = 目标文件 path
        val entryNew = mutableMapOf<String, String>()

        for ((k, v) in newFlatJson) {
            if (k in entryKeys) { entryNew[k] = v; continue }
            if (k in covered) continue // 已被某个 spread 提供的 key 覆盖
            val best = writableResolved
                .filter { isUnder(it.first.path, k) }
                .maxByOrNull { it.first.path.size }
            if (best == null) { entryNew[k] = v; continue }
            val rel = relativeKey(best.first.path, k) ?: run { entryNew[k] = v; continue }
            targetWrites.getOrPut(best.second.file.path) { WriteUnit(best.second, linkedMapOf()) }
                .relative[rel] = v
        }

        // 组装入口写盘（含同文件 const 目标范围）
        val entryReplacements = mutableListOf<Pair<IntRange, String>>(entryInfo.objectRange to
                newRegionText(entryText, entryInfo.objectRange, entryNew, entryInfo.staticKV))
        val separateWrites = mutableListOf<Pair<VirtualFile, String>>()
        for ((_, unit) in targetWrites) {
            val target = unit.target
            when (target.kind) {
                "json" -> {
                    val newTarget = regenerateJsonFileWithNewJson(target.file, unit.relative) ?: return null
                    separateWrites.add(target.file to newTarget)
                }
                "ts" -> {
                    val targetText = readVirtualFileText(project, target.file) ?: return null
                    val newTarget = applyRangeReplacements(targetText, listOf(
                        target.objRangeInText to newRegionText(targetText, target.objRangeInText, unit.relative, target.existingKeys)
                    ))
                    separateWrites.add(target.file to newTarget)
                }
                else -> { // const：与入口同文件，合并进同一文本替换
                    entryReplacements.add(
                        target.objRangeInText to newRegionText(entryText, target.objRangeInText, unit.relative, target.existingKeys)
                    )
                }
            }
        }
        val entryCombined = applyRangeReplacements(entryText, entryReplacements)
        return listOf(entryVf to entryCombined) + separateWrites
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
