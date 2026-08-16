package com.pan.extractor

import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiElement

/**
 * 公共前后缀合并 + 汉字数字抽取（Tab2 候选）的共享逻辑。
 *
 * 供两个动作复用：
 *  - [AllI18nExtractorAction]（全项目）
 *  - [I18nExtractorAction]（单文件 / 目录右键）
 *
 * [factorizeSites] 负责把各 processor 采集到的 site 转成 SiteRef 并生成候选组；
 * [apply] 负责把用户勾选的合并计划写入：填 blockedSiteIds → 常规写入(跳过被阻塞句)
 * → 骨架重写为带 {N0} 的 \$t 调用 → 清理被合并承载的冗余 key。
 * 调用方必须已处于 WriteCommandAction 内。
 */
object MergeApplier {

    /** 纯数字字面量正则（含负数/小数），高频用于差异段/数字渲染，避免每次重复编译。 */
    private val NUMBER_RE = Regex("""-?\d+(?:\.\d+)?""")

    // ─────────────────────────────────────────────────────────────
    // 候选生成
    // ─────────────────────────────────────────────────────────────
    fun factorizeSites(
        processors: List<I18nProcessor>,
    ): Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>> {
        val siteRefs = mutableListOf<SiteRef>()
        for ((pIdx, proc) in processors.withIndex()) {
            for (site in proc.collectedSites) {
                siteRefs += SiteRef(
                    processorIndex = pIdx,
                    siteId = site.id,
                    originalMessage = site.originalMessage,
                    containingFile = site.containingFile,
                    isVue = site.isVue,
                    isReact = site.isReact,
                    line1 = site.startLine,
                )
            }
        }
        return CommonPrefixSuffixFactorizer.factorize(siteRefs)
    }

    // ─────────────────────────────────────────────────────────────
    // 合并落盘
    // ─────────────────────────────────────────────────────────────
    fun apply(
        processors: List<I18nProcessor>,
        extracted: MutableMap<String, String>,
        mergePlan: ExtractedStringsDialog.MergePlan,
        indicator: ProgressIndicator? = null,
    ): MutableMap<String, String> {
        // ① 填 blockedSiteIds：被合并承载的句子不再走普通 $t 单句替换
        for (g in mergePlan.selectedAffix) {
            for (v in g.variants) for (ref in v.sites)
                processors[ref.processorIndex].blockedSiteIds.add(ref.siteId)
        }
        for (g in mergePlan.selectedDigit) {
            for (ps in g.perSites)
                processors[ps.site.processorIndex].blockedSiteIds.add(ps.site.siteId)
        }

        // ② 逐文件常规写入：import 注入 + 未被阻塞句替换为 $t
        indicator?.text = "写入 \$t：import 注入 + 硬编码替换（跳过被合并句）"
        for (processor in processors) processor.run()

        // ③ 预构建骨架重写任务
        indicator?.text = "生成骨架重写任务列表（公共前后缀/数字抽取）"
        val finalExtracted: MutableMap<String, String> = LinkedHashMap(extracted)
        val rewriteTasks = mutableListOf<Pair<String, () -> Unit>>()

        for (g in mergePlan.selectedAffix) {
            for (v in g.variants) for (ref in v.sites) {
                val proc = processors[ref.processorIndex]
                val site = proc.collectedSites.firstOrNull { it.id == ref.siteId } ?: continue
                val root = site.replaceRootPointer.element ?: continue
                if (!root.isValid) continue
                val diffExpr = if (Util.containsTargetLanguage(v.diff)) {
                    val diffKey = v.diff.trim()
                    finalExtracted.putIfAbsent(diffKey, v.diff)
                    proc.buildTExprForRawText(v.diff, "{}", site.isVue, site.isReact)
                } else {
                    renderLiteralValue(v.diff)
                }
                val label = (site.containingFile?.name ?: "file") + "@L" + site.startLine
                rewriteTasks += label to {
                    rewriteSiteToSkeleton(
                        rootPsi = root,
                        site = site,
                        skeletonValue = g.skeleton,
                        skeletonKey = g.skeletonKey.trim().ifBlank { g.skeleton },
                        paramPairs = listOf("N0" to diffExpr),
                        proc = proc,
                        finalExtracted = finalExtracted,
                    )
                }
            }
        }
        for (g in mergePlan.selectedDigit) {
            for (ps in g.perSites) {
                val ref = ps.site
                val proc = processors[ref.processorIndex]
                val site = proc.collectedSites.firstOrNull { it.id == ref.siteId } ?: continue
                val root = site.replaceRootPointer.element ?: continue
                if (!root.isValid) continue
                val digitText = renderDigitLiteral(ps.digitValues.firstOrNull() ?: "0")
                val label = (site.containingFile?.name ?: "file") + "@L" + site.startLine
                rewriteTasks += label to {
                    rewriteSiteToSkeleton(
                        rootPsi = root,
                        site = site,
                        skeletonValue = g.skeleton,
                        skeletonKey = g.skeletonKey.trim().ifBlank { g.skeleton },
                        paramPairs = listOf("N0" to digitText),
                        proc = proc,
                        finalExtracted = finalExtracted,
                    )
                }
            }
        }

        // ④ 逐个执行骨架重写
        indicator?.text = "应用骨架合并重写（生成带 {N0} 的 \$t 调用）"
        for (task in rewriteTasks) task.second()

        // ⑤ 清理：删除被合并承载的原句 key，回填 extracted
        //    以「站点」粒度判定，而不是按文本值：只有某个原句的所有 site 都被合并承载（blocked）时，
        //    才删除该句对应的 key；若仍存在未被合并的独立站点（同名文本），其 key 必须保留。
        indicator?.text = "整理最终翻译资源（移除被合并承载的冗余句子）"
        val consumedByMerge = HashSet<String>()          // 被合并承载的 siteId
        val messageToSiteIds = HashMap<String, MutableSet<String>>()  // 原句 trim → 命中该句的所有 siteId
        for (proc in processors) {
            for (site in proc.collectedSites) {
                messageToSiteIds.getOrPut(site.originalMessage.trim()) { mutableSetOf() }.add(site.id)
            }
        }
        for (g in mergePlan.selectedAffix) {
            for (v in g.variants) for (ref in v.sites) consumedByMerge.add(ref.siteId)
        }
        for (g in mergePlan.selectedDigit) {
            for (ps in g.perSites) consumedByMerge.add(ps.site.siteId)
        }
        // 只有「该文本的所有命中站点全部被合并」的句子才是真正冗余的
        val fullyConsumedMessages = messageToSiteIds.filterValues { ids -> ids.isNotEmpty() && ids.all { it in consumedByMerge } }.keys
        val iter = finalExtracted.entries.iterator()
        while (iter.hasNext()) {
            val (k, v) = iter.next()
            if (k.trim() in fullyConsumedMessages || v.trim() in fullyConsumedMessages) {
                iter.remove()
            }
        }
        return finalExtracted
    }

    // ─────────────────────────────────────────────────────────────
    // 骨架重写辅助（纯函数，供 AllI18n / I18n 两个动作共用）
    // ─────────────────────────────────────────────────────────────

    /** 构建参数表达式里的占位符到 (占位, 参数 key) 映射 */
    internal fun buildPlaceholderRewrite(
        isVue: Boolean,
        isReact: Boolean,
        pairs: List<Pair<String, String>>,
    ): Map<String, Pair<String, String>> {
        val result = mutableMapOf<String, Pair<String, String>>()
        // 纯函数：仅在存在 Application（插件运行上下文）时才读取设置里的前缀，
        // 否则回退默认 "N"，保证纯单元测试（无平台）也能运行。
        val app = com.intellij.openapi.application.ApplicationManager.getApplication()
        val vuePrefix = if (app != null) I18nSettings.getInstance().vuePlaceholderPrefix() else "N"
        pairs.forEachIndexed { i, (key, _) ->
            require(key.startsWith("N")) { "placeholder keys should be N0/N1 form" }
            val rawIndex = key.substring(1).toIntOrNull() ?: i
            when {
                isVue -> {
                    val k = "$vuePrefix$rawIndex"
                    result[key] = "{$k}" to k
                }
                isReact -> {
                    val k = rawIndex.toString()
                    result[key] = "{{$k}}" to "\"$k\""
                }
                else -> {
                    val k = rawIndex.toString()
                    result[key] = "{$k}" to "\"$k\""
                }
            }
        }
        return result
    }

    internal fun buildParamsObjectString(isVue: Boolean, keyVals: List<Pair<String, String>>): String {
        if (keyVals.isEmpty()) return "{}"
        return keyVals.joinToString(prefix = "{ ", postfix = " }") { (k, vExpr) ->
            "$k: $vExpr"
        }
    }

    /** 把一个纯字符串渲染成 JS 字面量（差异段非中文时用；字符串加引号，数字不加） */
    internal fun renderLiteralValue(diff: String): String {
        if (diff.matches(NUMBER_RE)) return diff
        return quoteString(diff)
    }

    /** 数字抽取的占位值渲染：前导零（如 0755）会破坏 JS 字面量，必须加引号当字符串；纯数值保持数字 */
    internal fun renderDigitLiteral(d: String): String {
        val isPlainNumber = d.matches(NUMBER_RE)
        val hasLeadingZero = d.length > 1 && d.startsWith("0") && !d.startsWith("0.")
        if (!isPlainNumber || hasLeadingZero) return quoteString(d)
        return d
    }

    private fun quoteString(s: String): String {
        val quote = if ('\'' !in s) "'" else "\""
        return "$quote${s.replace(quote, "\\$quote")}$quote"
    }

    /** 把某个 site 重写为 \$t('骨架{N0}', { N0: <diff> }) 并回填骨架 key 到翻译资源 */
    internal fun rewriteSiteToSkeleton(
        rootPsi: PsiElement,
        site: I18nProcessor.CollectedSite,
        skeletonValue: String,
        skeletonKey: String,
        paramPairs: List<Pair<String, String>>,
        proc: I18nProcessor,
        finalExtracted: MutableMap<String, String>,
    ) {
        val placeholderMap = buildPlaceholderRewrite(site.isVue, site.isReact, paramPairs)
        val rewrittenSkeleton = paramPairs.fold(skeletonValue) { acc, (k, _) ->
            val (ph, _) = placeholderMap[k] ?: error("placeholder missing for $k")
            acc.replace("{$k}", ph)
        }
        // React 项目 key 中 {N0} → {{0}}，Vue 保留 {N0}
        val rewrittenSkeletonKey = paramPairs.fold(skeletonKey.trim()) { acc, (k, _) ->
            val (ph, _) = placeholderMap[k] ?: error("object key missing for $k")
            acc.replace("{$k}", ph)
        }
        val paramsObjStr = buildParamsObjectString(site.isVue, paramPairs.map { (k, vExpr) ->
            val (_, keyInObject) = placeholderMap[k] ?: error("object key missing for $k")
            keyInObject to vExpr
        })
        val callExprText = proc.buildTExprForRawText(
            rewrittenSkeleton.trim(), paramsObjStr, site.isVue, site.isReact,
            skeletonKeyOverride = rewrittenSkeletonKey.trim()
        )

        val replacement = when {
            rootPsi is com.intellij.psi.xml.XmlText -> proc.createStringExpressionNode(
                if (site.isReact || Util.isJSX(rootPsi)) "{ $callExprText }" else "{{ $callExprText }}",
                rootPsi
            )
            rootPsi is com.intellij.psi.xml.XmlAttributeValue -> {
                val attr = rootPsi.parent as? com.intellij.psi.xml.XmlAttribute
                val jsx = Util.isJSX(rootPsi)
                val vDir = proc.isVueDirective(attr?.name ?: "")
                val prefix = if (jsx || vDir) "" else ":"
                if (attr != null) {
                    attr.setValue(if (jsx) "{$callExprText}" else "\"$callExprText\"")
                    attr.name = "$prefix${attr.name}"
                }
                return
            }
            else -> JSChangeUtil.tryCreateExpressionFromText(
                rootPsi.project,
                callExprText,
                null,
                false
            )?.psi
        }
        if (replacement != null) {
            try { rootPsi.replace(replacement) } catch (_: Throwable) { }
        }

        finalExtracted[rewrittenSkeletonKey] = rewrittenSkeleton.trim()
    }
}