package com.pan.extractor.locate

import com.pan.extractor.log.PluginLogBuffer
import com.pan.extractor.core.RegexCatalog
import com.pan.extractor.project.Util
import com.pan.extractor.project.ProjectStructure
import com.pan.extractor.staticparser.StaticObjectParser
import com.pan.extractor.lang.LanguageExtractor
import com.pan.extractor.editor.TsFileEditor
import com.pan.extractor.ui.*

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile

/**
 * 翻译入口文件定位（语言包/翻译资源文件识别、目标语言入口文件查找）。
 * 从 [Util] 拆分而来，行为不变。
 */
object EntryFileLocator {
    /** 两字母 ISO 639-1 语言码列表（覆盖绝大多数项目的命名习惯）。 */
    private val LOG = Logger.getInstance(EntryFileLocator::class.java)

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
    private fun translationDirs(): Set<String> {
        // 纯单元测试（无 IntelliJ Application）下读不到设置，回退仅用内置目录。
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        val custom = if (app != null && !app.isDisposed) {
            try {
                I18nSettings.getInstance().customTranslationDirs()
            } catch (e: Throwable) {
                LOG.debug("EntryFileLocator: 读取自定义翻译目录设置失败，回退空列表", e)
                emptyList()
            }
        } else emptyList()
        return TRANSLATION_DIRS_DEFAULT + custom.map { it.lowercase() }
    }

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
        val stored = Util.getStoredEntryPath(project)
        if (stored != null) {
            val f = resolveStoredEntryPath(stored)
            if (f != null && f.isValid && !f.isDirectory) return f
        }
        val root = if (contextPsiFile != null) ProjectStructure.findProjectRoot(contextPsiFile) else {
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
            val dir = ProjectStructure.findRelativeFile(root, rel) ?: continue
            if (!dir.isDirectory) continue
            val hit = ProjectStructure.walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isDirectory || !vf.isValid) return@walkVirtualFile null
                val ext = vf.extension?.lowercase() ?: return@walkVirtualFile null
                if (ext !in setOf("ts","tsx","js","jsx","json")) return@walkVirtualFile null
                val nameNoExt = vf.nameWithoutExtension
                if (isTargetLocaleBasename(nameNoExt, candidates, extractors)) vf else null
            }
            if (hit != null) return hit
        }
        // 3) 预设目录未命中：统一像 Vue / React 全局导入那样探测 i18n 初始化文件，再根据其配置项查目标语言入口
        findChineseEntryViaI18nConfig(root, project)?.let { if (it.isValid && !it.isDirectory) return it }
        // 4) 全项目 walk（深度 5，排除 node_modules/.git/dist/build）
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(root, maxDepth = 5, enterFilter = { it.name !in excludeDirs }) { vf ->
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
    fun findChineseEntryViaI18nConfig(root: VirtualFile, project: Project? = null): VirtualFile? {
        // P0：把 project 传入 Locator 以启用 PSI 级复核，避免字符串/注释里的 createI18n( 误判
        val initFile = I18nInstanceLocator.findI18nInitFileInRoot(root, project) ?: return null
        val text = try {
            String(initFile.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            PluginLogBuffer.warn(LOG,"EntryFileLocator: 读取 i18n 初始化文件失败，返回 null", e)
            return null
        }
        return if (text.contains("createI18n(") || text.contains("createI18n (")) {
            findVueEntryFromConfigText(initFile, text)
        } else {
            findReactEntryFromConfigText(initFile, text)
        }
    }

    /** 从 Vue createI18n 配置文本中解析中文入口。 */
    private fun findVueEntryFromConfigText(initFile: VirtualFile, text: String): VirtualFile? {
        // 1) 定位 createI18n( 的配置对象
        val createIdx = RegexCatalog.CREATE_I18N.find(text)?.range?.first ?: return null
        val brace = text.indexOf('{', createIdx)
        if (brace < 0) return null
        val optionsEnd = findBalancedCloseBrace(text, brace) ?: return null
        val options = text.substring(brace, optionsEnd)

        // 2) 读取 locale 配置值（如 'zh-CN'）
        val localeCode = RegexCatalog.LANGUAGE_CODE.find(options)?.groupValues?.get(1)

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

    /**
     * 写回入口重定位：把「写回目标」重定位到真实语言包入口文件。
     *
     * 背景：用户/自动探测有时把写回目标定到 i18n **初始化文件**（如 src/locales/i18n.ts，
     * 结构为 `i18n.use(initReactI18next).init({ resources: {...} })` + `export default i18n;`，
     * 顶层不是 export default 对象字面量），而写回解析器只认 export default/const 对象。
     * 此时应透过该文件的 i18n/vue-i18n config（resources / messages）解析出它引用的真实
     * 语言包文件（如 src/locales/zh.ts），把提取结果直接写入语言包 —— 满足「直接写入 zh.ts」。
     *
     * @return 可写回的语言包入口文件；[entryVf] 本身可解析（就是语言包）则原样返回；
     *         解析不出语言包时返回 null（由调用方回退剪贴板）。
     */
    fun relocateToLocaleEntryFile(project: Project, entryVf: VirtualFile): VirtualFile? {
        // 1) 入口本身已是语言包（顶层 export 对象可解析）→ 直接用，不重定向。
        val selfOk = try {
            StaticObjectParser.parseTsExportedObject(
                String(entryVf.contentsToByteArray(), Charsets.UTF_8)
            ) != null
        } catch (e: Exception) {
            LOG.debug("EntryFileLocator: 读取入口 ${entryVf.path} 失败", e)
            false
        }
        if (selfOk) return entryVf
        // 2) 视为初始化文件：透过其 config（resources/messages 引用）定位真实语言包。
        val parent = entryVf.parent
        if (parent != null) {
            val locale = findChineseEntryViaI18nConfig(parent, project)
            if (locale != null && locale != entryVf) return locale
        }
        return null
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
            } else if (t.matches(RegexCatalog.IDENTIFIER)) {
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

    // --- 供本 object 内 findVueEntryFromConfigText / findReactEntryFromConfigText /
    //     parseResourcesRefs / parseMessagesRefs 调用的私有辅助，委托给 TsFileEditor。---
    private fun splitTopLevelProperties(body: String): List<String> = TsFileEditor.splitTopLevelProperties(body)
    private fun parseOneProperty(prop: String): Pair<String, String>? = TsFileEditor.parseOneProperty(prop)
    private fun stripValueSuffixes(expr: String): String = TsFileEditor.stripValueSuffixes(expr)
    private fun findBalancedCloseBrace(text: String, openIdx: Int): Int? = TsFileEditor.findBalancedCloseBrace(text, openIdx)
    private fun resolveLocalImportFile(fromFile: VirtualFile, spec: String): VirtualFile? = TsFileEditor.resolveLocalImportFile(fromFile, spec)

}