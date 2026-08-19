package com.pan.extractor

import com.pan.extractor.ui.*

import com.intellij.lang.javascript.psi.impl.JSChangeUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

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

    /**
     * 【P0 多文件原子性】应用前的完整性校验：确认所有将被改写 / 将作为骨架重写目标的
     * site（跨任意数量文件）所指向的 [CollectedSite.replaceRootPointer] 仍然有效。
     *
     * 委托 [com.pan.extractor.validator.ChangeValidator]（目标架构 Validator 层），行为 1:1。
     *
     * @throws IllegalStateException 存在失效站点时抛出，message 列出 FQN 描述。
     */
    @JvmStatic
    fun validateAllModifiableSites(
        processors: List<I18nProcessor>,
        mergePlan: ExtractedStringsDialog.MergePlan,
    ) = com.pan.extractor.validator.ChangeValidator.validateAllModifiableSites(processors, mergePlan)

    // ─────────────────────────────────────────────────────────────
    // 候选生成
    // ─────────────────────────────────────────────────────────────
    fun factorizeSites(
        processors: List<I18nProcessor>,
    ): Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>> {
        val siteRefs = mutableListOf<SiteRef>()
        for ((pIdx, proc) in processors.withIndex()) {
            for (site in proc.analyzer.collectedSites) {
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
        /**
         * 【问题 1：写入无进度】允许调用方向本函数注入"EDT 执行器"。
         *
         * 原因：apply 内部要写大量 PSI（逐文件 processor.run() + 逐站点骨架重写），
         * 若一次性塞进同一个 WriteCommandAction 在 EDT 上跑，进度条在写完成前无法重绘，
         * 表现为"点了确定后没有进度反馈"。
         *
         * 解法：由调用方传入 edtRunner，apply 每写一个文件 / 一个重写任务就调用它一次；
         * 调用方用 `invokeAndWait { WriteCommandAction { ... } }` 实现，让背景线程在
         * 两次写之间更新 indicator，并让 EDT 有机会重绘进度条。
         *
         * 值为 null 时（纯单元测试、或已处于 WCA 内）直接同步执行，行为与旧版一致。
         */
        edtRunner: ((() -> Unit) -> Unit)? = null,
        /**
         * 被因子化合并"承载"的原句 key（整句，如 "请输入搜索关键词"）。
         * 若入口翻译文件（zh.ts）的历史提取里已保留这些整句 key，写回时应一并删除，
         * 否则会出现"整句 key + 骨架 {N0} key"的重复（Bug：生成的 zh.ts 与原来的 key 重复）。
         * 与 finalExtracted 里的清理不同：这是面向入口文件已存在的旧 key 的清理。
         * 为 null 时（纯单元测试）不收集。
         */
        dropExistingKeysOut: MutableSet<String>? = null,
    ): MutableMap<String, String> {
        // 0️⃣ 【P0 多文件原子性】在写入任何文件之前做完整校验：
        //    所有被选中改写 / 骨架重写的 site 目标必须仍有效，否则整体中止，不留半完成状态。
        validateAllModifiableSites(processors, mergePlan)

        // ① 填 blockedSiteIds：被合并承载的句子不再走普通 $t 单句替换
        //    完全相同文本的提示组（isExactDuplicate）骨架里没有 {N0} 占位，勾选也不做骨架重写，
        //    其站点本就由普通 $t('全选') 单句替换承载，因此既不阻塞也不重写，直接跳过以免自引用。
        //    （Planner 层决定：由 ExtractionPlanner.computeBlockedSiteIds 纯函数产出，
        //    见 ExtractionPlan.blockedSiteIds。）
        val blockedByMerge = com.pan.extractor.planner.ExtractionPlanner.computeBlockedSiteIds(mergePlan)
        for (g in mergePlan.selectedAffix) {
            if (g.isExactDuplicate) continue
            for (v in g.variants) for (ref in v.sites)
                processors[ref.processorIndex].analyzer.blockedSiteIds.add(ref.siteId)
        }
        for (g in mergePlan.selectedDigit) {
            for (ps in g.perSites)
                processors[ps.site.processorIndex].analyzer.blockedSiteIds.add(ps.site.siteId)
        }

        // ② 逐文件常规写入：import 注入 + 未被阻塞句替换为 $t
        indicator?.text = "写入 \$t：import 注入 + 硬编码替换（跳过被合并句）"
        val writeTotal = processors.size
        processors.forEachIndexed { idx, processor ->
            val pf = (processor.rootElement as? PsiFile)
            indicator?.text2 = pf?.name
                ?: (processor.rootElement.containingFile?.name ?: "文件 ${idx + 1}")
            indicator?.fraction = 0.02 + (idx.toDouble() / writeTotal.coerceAtLeast(1)) * 0.58
            indicator?.checkCanceled()
            val r = { processor.run() }
            if (edtRunner != null) edtRunner.invoke(r) else r()
        }

        // ③ 预构建骨架重写任务
        //    计划由 Planner 层产出（ExtractionPlanner.buildRewritePlans）—— 差分占位表达式已按
        //    每个 site 的 Vue/React 形态渲染好；此处把计划解析为「标签 → 待执行重写」的映射。
        indicator?.text = "生成骨架重写任务列表（公共前后缀/数字抽取）"
        val finalExtracted: MutableMap<String, String> = LinkedHashMap(extracted)
        val rewriteTasks = mutableListOf<Pair<String, () -> Unit>>()

        val skeletonPlans = com.pan.extractor.planner.ExtractionPlanner
            .buildRewritePlans(mergePlan, processors, finalExtracted)
        for (plan in skeletonPlans) {
            val proc = processors[plan.processorIndex]
            val site = proc.analyzer.collectedSites.firstOrNull { it.id == plan.siteId } ?: continue
            val root = site.replaceRootPointer?.element ?: continue
            if (!root.isValid) continue
            val label = (site.containingFile?.name ?: "file") + "@L" + site.startLine
            rewriteTasks += label to {
                rewriteSiteToSkeleton(
                    rootPsi = root,
                    site = site,
                    skeletonValue = plan.skeleton.orEmpty(),
                    skeletonKey = plan.skeletonKey.orEmpty().ifBlank { plan.skeleton.orEmpty() },
                    paramPairs = plan.params,
                    proc = proc,
                    finalExtracted = finalExtracted,
                )
            }
        }

        // ④ 逐个执行骨架重写（每个重写任务经 edtRunner 走 EDT，写入间更新进度）
        indicator?.text = "应用骨架合并重写（生成带 {N0} 的 \$t 调用）"
        val taskTotal = rewriteTasks.size
        rewriteTasks.forEachIndexed { idx, task ->
            indicator?.fraction = 0.6 + (idx.toDouble() / taskTotal.coerceAtLeast(1)) * 0.32
            indicator?.text2 = task.first
            indicator?.checkCanceled()
            val r = task.second
            if (edtRunner != null) edtRunner.invoke(r) else r()
        }

        // ⑤ 清理：删除被合并承载的原句 key，回填 extracted
        //    以「站点」粒度判定，而不是按文本值：只有某个原句的所有 site 都被合并承载（blocked）时，
        //    才删除该句对应的 key；若仍存在未被合并的独立站点（同名文本），其 key 必须保留。
        indicator?.text = "整理最终翻译资源（移除被合并承载的冗余句子）"
        // 被合并承载的 siteId 集合由 Planner 层已算出（见 ①，同一份 blockedByMerge）。
        // 「某原句的所有命中站点全部被合并」这一整句冗余判定也由 Planner 纯函数完成：
        // 整理 finalExtracted / 写回入口文件时，删除被完全承载的整句 key。
        val messageToSiteIds = HashMap<String, MutableSet<String>>()  // 原句 trim → 命中该句的所有 siteId
        for (proc in processors) {
            for (site in proc.analyzer.collectedSites) {
                messageToSiteIds.getOrPut(site.originalMessage.trim()) { mutableSetOf() }.add(site.id)
            }
        }
        val fullyConsumedMessages = com.pan.extractor.planner.ExtractionPlanner
            .computeFullyConsumedMessages(messageToSiteIds, blockedByMerge)
        // 供写回入口文件时清理历史整句 key（Bug：zh.ts 整句 key 与骨架 key 重复）
        dropExistingKeysOut?.addAll(fullyConsumedMessages)
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
    // 目标架构 Planner 层：实现已迁入 com.pan.extractor.planner.SkeletonPlanner，此处委托。
    // ─────────────────────────────────────────────────────────────

    /** 构建参数表达式里的占位符到 (占位, 参数 key) 映射。 */
    internal fun buildPlaceholderRewrite(
        isVue: Boolean,
        isReact: Boolean,
        pairs: List<Pair<String, String>>,
    ): Map<String, Pair<String, String>> =
        com.pan.extractor.planner.SkeletonPlanner.buildPlaceholderRewrite(isVue, isReact, pairs)

    internal fun buildParamsObjectString(isVue: Boolean, keyVals: List<Pair<String, String>>): String =
        com.pan.extractor.planner.SkeletonPlanner.buildParamsObjectString(isVue, keyVals)

    /** 把一个纯字符串渲染成 JS 字面量（差异段非中文时用；字符串加引号，数字不加）。 */
    internal fun renderLiteralValue(diff: String): String =
        com.pan.extractor.planner.SkeletonPlanner.renderLiteralValue(diff)

    /** 数字抽取的占位值渲染：前导零（如 0755）会破坏 JS 字面量，必须加引号当字符串；纯数值保持数字。 */
    internal fun renderDigitLiteral(d: String): String =
        com.pan.extractor.planner.SkeletonPlanner.renderDigitLiteral(d)

    /** 把某个 site 重写为 \$t('骨架{N0}', { N0: <diff> }) 并回填骨架 key 到翻译资源 */
    internal fun rewriteSiteToSkeleton(
        rootPsi: PsiElement,
        site: com.pan.extractor.model.ExtractionSite,
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
        val callExprText = I18nPsiTools.buildTExprForRawText(
            rewrittenSkeleton.trim(), paramsObjStr, site.isVue, site.isReact,
            skeletonKeyOverride = rewrittenSkeletonKey.trim(), framework = proc.analyzer.framework)

        val replacement = when {
            rootPsi is com.intellij.psi.xml.XmlText -> proc.createStringExpressionNode(
                if (site.isReact || ProjectStructure.isJSX(rootPsi)) "{ $callExprText }" else "{{ $callExprText }}",
                rootPsi
            )
            rootPsi is com.intellij.psi.xml.XmlAttributeValue -> {
                val attr = rootPsi.parent as? com.intellij.psi.xml.XmlAttribute
                val jsx = ProjectStructure.isJSX(rootPsi)
                val vDir = I18nPsiTools.isVueDirective(attr?.name ?: "")
                val prefix = if (jsx || vDir) "" else ":"
                if (attr != null) {
                    // 非 JSX（Vue 普通/指令属性）：直接写入表达式文本即可，XmlAttributeValue
                    // 本身会带外层引号。若这里再拼上字面双引号，会被底层转义成 &quot; 而生成
                    // :placeholder="&quot;$t(...)&quot;"，见 Bug（Vue placeholder 属性因子化）。
                    attr.setValue(if (jsx) "{$callExprText}" else callExprText)
                    attr.name = "$prefix${attr.name}"
                    // 属性值已就地改写成功 → 必须登记 finalExtracted（原实现提前 return 遗漏了
                    // 这步登记，导致该站点在后续资源写回时被当成「未应用」，造成源码/资源不一致，P1）。
                    finalExtracted[rewrittenSkeletonKey] = rewrittenSkeleton.trim()
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
            try {
                rootPsi.replace(replacement)
            } catch (_: Throwable) {
                // P0：替换失败时不再登记 finalExtracted —— 否则源码未改，但资源文件仍为它写入 key，
                // 造成源码/资源不一致、运行时查不到翻译。失败即视为该站点未应用。
                return
            }
        }

        finalExtracted[rewrittenSkeletonKey] = rewrittenSkeleton.trim()
    }
}