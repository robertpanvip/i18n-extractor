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
    private fun isTargetLocaleBasename(stem: String, candidates: List<String>, extractors: List<LanguageExtractor>): Boolean {
        val lower = stem.lowercase()
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
        for (ex in extractors) {
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
        val settings = I18nSettings.getInstance()
        return findEntryFile(project, contextPsiFile, settings.activeLocaleCandidates(), settings.activeExtractors())
    }

    /**
     * 查找"指定语言"的翻译入口文件（用于 $t() 折叠展示）。
     * 复用 [findChineseLocaleEntryFile] 的定位逻辑，但只匹配给定语言（[extractor]）。
     * 若项目中找不到该语言的独立文件，回退到默认目标语言入口文件。
     */
    fun findLocaleFileForLanguage(project: Project, contextPsiFile: PsiFile?, extractor: LanguageExtractor): VirtualFile? {
        val candidates = extractor.localeNameCandidates()
        val hit = findEntryFile(project, contextPsiFile, candidates, listOf(extractor))
        if (hit != null) return hit
        return findChineseLocaleEntryFile(project, contextPsiFile)
    }

    private fun findEntryFile(
        project: Project,
        contextPsiFile: PsiFile?,
        candidates: List<String>,
        extractors: List<LanguageExtractor>,
    ): VirtualFile? {
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
                if (isTargetLocaleBasename(nameNoExt, candidates, extractors)) vf else null
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
            val baseLike = isTargetLocaleBasename(vf.nameWithoutExtension, candidates, extractors)
            if ((pathLike || baseLike) && isTargetLocalePathHit(vf, candidates, extractors)) {
                vf
            } else null
        }
    }

    /** 判断文件路径/基名是否严格命中指定语言集合的标识（locale 候选 或 `<tag><region>`）。 */
    private fun isTargetLocalePathHit(vf: VirtualFile, candidates: List<String>, extractors: List<LanguageExtractor>): Boolean {
        val nameNoExt = vf.nameWithoutExtension
        if (candidates.any { nameNoExt.contains(it, ignoreCase = true) }) return true
        // 路径段精确命中 locale 候选（如目录 zh-CN / en-US / ja-JP）
        val segments = vf.path.split('/').map { it.lowercase() }
        if (segments.any { seg -> candidates.any { it.lowercase() == seg } }) return true
        // 兜底：<tag><region>（zhCN / enUS / jaJP 等）
        for (ex in extractors) {
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
        val settings = I18nSettings.getInstance()
        val tags = settings.activeExtractors().map { it.langTagPrefix }
        val candidates = settings.activeLocaleCandidates()
        val extractors = settings.activeExtractors()
        return refs.firstOrNull { it.first == localeCode }
            ?: refs.firstOrNull { isTargetLocaleBasename(it.first, candidates, extractors) }
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

    fun readVirtualFileText(project: Project?, vf: VirtualFile): String? {
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

    // ==========================================================================
    // 以下方法已迁移到 TsFileEditor（见同目录 TsFileEditor.kt）。
    // Util 作为对外门面，保留原签名并委托给 TsFileEditor。行为不变。
    // ==========================================================================
    fun parseTsExportedObject(text: String): TsFileEditor.TsExportedObjectInfo? = TsFileEditor.parseTsExportedObject(text)

    fun parseObjectLiteralBody(raw: String): Map<String, Any?> = TsFileEditor.parseObjectLiteralBody(raw)

    fun mergeFlatIntoNested(existingNested: Map<String, Any?>, newFlat: Map<String, String>): Map<String, Any?> =
        TsFileEditor.mergeFlatIntoNested(existingNested, newFlat)

    fun regenerateObjectLiteralBody(oldObjBody: String, mergedNested: Map<String, Any?>): String =
        TsFileEditor.regenerateObjectLiteralBody(oldObjBody, mergedNested)

    fun regenerateTsFileWithNewJson(project: Project, entryVf: VirtualFile, newFlatJson: Map<String, String>): String? =
        TsFileEditor.regenerateTsFileWithNewJson(project, entryVf, newFlatJson)

    fun regenerateJsonFileWithNewJson(entryVf: VirtualFile, newFlatJson: Map<String, String>): String? =
        TsFileEditor.regenerateJsonFileWithNewJson(entryVf, newFlatJson)

    fun regenerateTsFileWithSpreadRouting(project: Project, entryVf: VirtualFile, newFlatJson: Map<String, String>): List<Pair<VirtualFile, String>>? =
        TsFileEditor.regenerateTsFileWithSpreadRouting(project, entryVf, newFlatJson)

    fun writeVirtualFileText(entryVf: VirtualFile, newText: String) = TsFileEditor.writeVirtualFileText(entryVf, newText)

    fun persistEntryPathIfNeeded(project: Project, entryVf: VirtualFile) = TsFileEditor.persistEntryPathIfNeeded(project, entryVf)

    // --- 仍被 Util 内其它方法（findVueEntryFromConfigText / findReactEntryFromConfigText /
    //     parseResourcesRefs / parseMessagesRefs）调用的私有辅助，保留私有委托。---
    private fun splitTopLevelProperties(body: String): List<String> = TsFileEditor.splitTopLevelProperties(body)
    private fun parseOneProperty(prop: String): Pair<String, String>? = TsFileEditor.parseOneProperty(prop)
    private fun stripValueSuffixes(expr: String): String = TsFileEditor.stripValueSuffixes(expr)
    private fun findBalancedCloseBrace(text: String, openIdx: Int): Int? = TsFileEditor.findBalancedCloseBrace(text, openIdx)
    private fun resolveLocalImportFile(fromFile: VirtualFile, spec: String): VirtualFile? = TsFileEditor.resolveLocalImportFile(fromFile, spec)

}
