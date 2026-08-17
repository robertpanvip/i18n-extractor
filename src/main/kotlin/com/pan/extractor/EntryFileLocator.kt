package com.pan.extractor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.relativeToOrNull

/**
 * 翻译入口文件定位（语言包/翻译资源文件识别、目标语言入口文件查找）。
 * 从 [Util] 拆分而来，行为不变。
 */
object EntryFileLocator {
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
    private fun translationDirs(): Set<String> {
        // 纯单元测试（无 IntelliJ Application）下读不到设置，回退仅用内置目录。
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        val custom = if (app != null && !app.isDisposed) {
            try { I18nSettings.getInstance().customTranslationDirs() } catch (_: Throwable) { emptyList() }
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
        findChineseEntryViaI18nConfig(root)?.let { if (it.isValid && !it.isDirectory) return it }
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

    // --- 供本 object 内 findVueEntryFromConfigText / findReactEntryFromConfigText /
    //     parseResourcesRefs / parseMessagesRefs 调用的私有辅助，委托给 TsFileEditor。---
    private fun splitTopLevelProperties(body: String): List<String> = TsFileEditor.splitTopLevelProperties(body)
    private fun parseOneProperty(prop: String): Pair<String, String>? = TsFileEditor.parseOneProperty(prop)
    private fun stripValueSuffixes(expr: String): String = TsFileEditor.stripValueSuffixes(expr)
    private fun findBalancedCloseBrace(text: String, openIdx: Int): Int? = TsFileEditor.findBalancedCloseBrace(text, openIdx)
    private fun resolveLocalImportFile(fromFile: VirtualFile, spec: String): VirtualFile? = TsFileEditor.resolveLocalImportFile(fromFile, spec)

    // ==========================================================================
    // i18n 实例文件定位（从 ProjectStructure 迁入；与 EntryFileLocator 的入口文件
    // 定位职责本就重叠，合并到一处）。findRelativeFile / walkVirtualFile 仍由
    // ProjectStructure 提供（通用 VirtualFile 遍历工具）。
    // ==========================================================================

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
        val projectRoot = ProjectStructure.findProjectRoot(currentPsiFile) ?: return null
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
            val dir = ProjectStructure.findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = ProjectStructure.walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    if (vfContainsCreateI18n(vf)) vf else null
                } else null
            }
            if (result != null) return result
        }

        // 阶段 2：常见目录未命中，在项目根做 walk（最大深度 4，排除 node_modules）
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
            if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                if (vfContainsCreateI18n(vf)) vf else null
            } else null
        }
    }

    private val TS_JS_EXTS = setOf("ts", "tsx", "js", "jsx")

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
            val dir = ProjectStructure.findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = ProjectStructure.walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                    if (isI18nInitText(t)) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
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
            val dir = ProjectStructure.findRelativeFile(projectRoot, relPath) ?: continue
            if (!dir.isDirectory) continue
            val result = ProjectStructure.walkVirtualFile(dir, maxDepth = 2) { vf ->
                if (vf.isValid && !vf.isDirectory && vf.extension?.lowercase() in TS_JS_EXTS) {
                    val t = try { String(vf.contentsToByteArray(), Charsets.UTF_8) } catch (_: Exception) { return@walkVirtualFile null }
                    if (isReactI18nInitWithExport(t)) vf else null
                } else null
            }
            if (result != null) return result
        }
        val excludeDirs = I18nSettings.getInstance().excludeDirs()
        return ProjectStructure.walkVirtualFile(projectRoot, maxDepth = 4, enterFilter = { it.name !in excludeDirs }) { vf ->
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
        val projectRoot = ProjectStructure.findProjectRoot(currentPsiFile) ?: return null
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