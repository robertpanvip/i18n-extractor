package com.pan.extractor.merge

import com.pan.extractor.ui.*

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile

// ─────────────────────────────────────────────────────────────
// 候选分组模型
// ─────────────────────────────────────────────────────────────

/** 公共前后缀合并候选：骨架 = 前缀 + {N0} + 后缀；差异段 = 每句中间的不同片段 */
data class AffixGroupCandidate(
    val id: String,
    /** 骨架（资源文件里存入），含一个 {N0} */
    val skeleton: String,
    /** 前缀（仅展示用） */
    val prefix: String,
    /** 后缀（仅展示用） */
    val suffix: String,
    /** 命中的差异项（长度 >=2） */
    val variants: List<AffixVariant>,
    /** 初始勾选（≥2字公共前后缀全选） */
    val selected: Boolean = true,
    /** 骨架 key（Dialog 可编辑） */
    var skeletonKey: String,
    /**
     * 是否为"完全相同文本"的提示候选（id 前缀 AG_EXACT_DUP_）。
     * 这类组骨架里没有 {N0} 占位，若被勾选并按骨架重写，会错误生成
     * $t('全选', { N0: $t('全选') }) 这类自引用调用。应用阶段必须直接跳过：
     * 重复文本本就走普通 $t('全选') 单句替换（key 相同），无需额外重写。
     */
    val isExactDuplicate: Boolean = false,
) {
    val siteCount get() = variants.sumOf { it.sites.size }
}

data class AffixVariant(
    /** 差异段原始片段 */
    val diff: String,
    /** 命中的 site 列表（可跨 processor = 跨文件） */
    val sites: List<SiteRef>,
)

/** 汉字 ≥2 字 + 数字 抽取候选（用户特别要求）：骨架 = 原句去掉数字 → {N0}；差异是数字字面量 */
data class DigitGroupCandidate(
    val id: String,
    /** 骨架：原句中所有数字字面量被替换为 {N0}/{N1}...（按顺序），目前实现只处理首次 1 处数字（MVP），将来可扩多占位 */
    val skeleton: String,
    /** 数字片段列表，MVP 固定 1 个，按先后顺序占位 */
    val digits: List<DigitSlot>,
    /** 这个骨架命中的 sites（跨文件），每个 site 都要给出 digits[i].valueForSite */
    val perSites: List<DigitPerSite>,
    val selected: Boolean = true,
    var skeletonKey: String,
) {
    val siteCount get() = perSites.size
}

data class DigitSlot(
    /** 占位 index，MVP=0 → {N0}；将来扩多占位就 {N0}/{N1}... */
    val index: Int,
)

data class DigitPerSite(
    val site: SiteRef,
    /** 对应 digits 顺序的数字值（字符串，保留 0 前缀、小数、浮点） */
    val digitValues: List<String>,
    /** 数字是否全非中文（一般 true）——差异段非中文直接写字面量不嵌套 $t */
    val allNonChinese: Boolean,
)

/**
 * site 的跨 processor 引用。
 * - processorRef 用 identity index 在调用端索引，避免 Kotlin data class 对 processor 对象的比较语义。
 */
data class SiteRef(
    val processorIndex: Int,
    val siteId: String,
    val originalMessage: String,
    val containingFile: VirtualFile?,
    val isVue: Boolean,
    val isReact: Boolean,
    val line1: Int,
)

// ─────────────────────────────────────────────────────────────
// 因子化器
// ─────────────────────────────────────────────────────────────

object CommonPrefixSuffixFactorizer {

    private val HAN_RE = Regex("""[\u4e00-\u9fff]""")
    private val DIGIT_TOKEN_RE = Regex("""\d+(?:\.\d+)?""")

    /** 合并建议阈值（公共前后缀合计至少达到该字符数才生成建议），来自全局设置，默认 2。 */
    private fun minAffixChar(): Int {
        // 纯单元测试（无 IntelliJ Application）下读不到设置，回退默认 2
        val app = ApplicationManager.getApplication()
        return if (app != null && !app.isDisposed) {
            try { I18nSettings.getInstance().mergeAffixThreshold() } catch (_: Throwable) { 2 }
        } else 2
    }

    fun factorize(allSites: List<SiteRef>): Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>> {
        val affix = buildAffixGroups(allSites)
        val digit = buildDigitGroups(allSites)
        // 修复 Bug5：简单声明 `const a = "测试数据1"; const b = "测试数据2"` 这种
        //   ——两句没有公共前后缀也没有数字骨架相似点 ——「合并建议」空是正确的算法行为，
        //   但用户误以为漏了。解决方案：
        //     · 完全相同文本出现多次 → 自动生成一条骨架 = {N0} = 原句、
        //       变体差异段也是原句（其实就是「同 key 自动合并去重」的可视化提示）；
        //     · 两句全不同但站点数>1 → 仍在 Tab 2 上显示"没有公共前后缀 / 没有数字抽
        //       样，暂无法合并"占位候选项（selected=false，避免误应用）。
        val exactDupes = buildExactDuplicateHintGroups(allSites)
        val finalAffix = (affix.asSequence() + exactDupes.asSequence())
            .distinctBy { it.skeleton to it.variants.map { v -> v.diff }.toSet() }
            .sortedByDescending { it.siteCount }
            .toList()
        val finalDigit = if (digit.isEmpty() && finalAffix.isEmpty() && allSites.size >= 2) {
            listOf(DigitGroupCandidate(
                id = "DG0",
                skeleton = "（没有可自动抽取的数字骨架，无法合并；手动编辑翻译即可）",
                digits = emptyList(),
                perSites = allSites.take(8).map { DigitPerSite(it, emptyList(), true) },
                selected = false,
                skeletonKey = "no-digit-groups-placeholder"
            ))
        } else digit
        return finalAffix to finalDigit
    }

    // ── 0. 完全相同文本多次出现：给 Tab 2 生成一条"提示性" AffixGroupCandidate
    //       （其实同一文本会生成同一 key，用户以为漏了合并 → 显式展示即可）
    private fun buildExactDuplicateHintGroups(allSites: List<SiteRef>): List<AffixGroupCandidate> {
        val buckets = allSites.groupBy { it.originalMessage.trim() }.filterValues { it.size >= 2 }
        var idSeq = 0
        return buckets.map { (msg, sites) ->
            val trimmed = msg
            AffixGroupCandidate(
                id = "AG_EXACT_DUP_${++idSeq}",
                skeleton = trimmed,
                prefix = trimmed,
                suffix = "",
                variants = listOf(AffixVariant(diff = trimmed, sites = sites.toList())),
                selected = false,
                skeletonKey = trimmed,
                isExactDuplicate = true,
            )
        }
    }

    // ── ① 公共前后缀合并（≥2字） ──────────────────────────────
    private fun buildAffixGroups(allSites: List<SiteRef>): List<AffixGroupCandidate> {
        val byMessageBucket = allSites.groupBy { it.originalMessage }
            .filterValues { it.size >= 2 }    // 先以"完全相同原句=桶"作为 1 级种子？不，我们要的是"相似但不同"，所以桶其实是"全量两两比较"前的分桶。

        // 简单算法：对原消息先按前缀长度分桶（按前 1 字 bucket，再 O(n^2) 比较，避免 1e4 条 OOM）
        val buckets: MutableMap<Char, MutableList<SiteRef>> = mutableMapOf()
        for (s in allSites) {
            val first = s.originalMessage.firstOrNull() ?: continue
            buckets.getOrPut(first) { mutableListOf() }.add(s)
        }

        val consumed = mutableSetOf<SiteRef>()
        val results = mutableListOf<AffixGroupCandidate>()
        var idSeq = 0

        for ((_, bucket) in buckets) {
            val list = bucket.toMutableList()
            list.sortByDescending { it.originalMessage.length }
            while (list.isNotEmpty()) {
                val anchor = list.removeFirst()
                if (anchor in consumed) continue
                val candidates = mutableListOf<Pair<SiteRef, Triple<String, String, String>>>() // siteRef -> (prefix,suffix,diff)
                for (other in list) {
                    if (other in consumed) continue
                    val (p, s, d) = longestCommonAffix(anchor.originalMessage, other.originalMessage) ?: continue
                    if (p.codePointCount(0, p.length) < minAffixChar() && s.codePointCount(0, s.length) < minAffixChar()) continue
                    // 前后缀至少一边≥2字（组合也算）——用户阈值：≥2字 + 全自动
                    val totalAffix = p.codePointCount(0, p.length) + s.codePointCount(0, s.length)
                    if (totalAffix < minAffixChar()) continue
                    candidates.add(other to Triple(p, s, d))
                }
                if (candidates.isEmpty()) continue
                // 选 anchor 的 (p,s)：所有 candidates 中最大交 (prefix,suffix) 对（第一个就是最长）
                val (maxPrefix, maxSuffix, anchorDiff) = candidates.maxBy { it.second.first.length + it.second.second.length }.second.let { c0 ->
                    var p = c0.first; var s = c0.second
                    for (c in candidates.drop(1)) {
                        p = longestCommonPrefix(p, c.second.first)
                        s = longestCommonSuffix(s, c.second.second)
                    }
                    val a = anchor.originalMessage
                    val end = if (s.isNotEmpty()) a.length - s.length else a.length
                    Triple(p, s, a.substring(p.length, end))
                }
                // 如果交集前后缀太短（合并过程中缩小），放弃这个分组
                val pLen = maxPrefix.codePointCount(0, maxPrefix.length)
                val sLen = maxSuffix.codePointCount(0, maxSuffix.length)
                if (pLen + sLen < minAffixChar()) continue

                // 【防呆】共享前后缀必须覆盖整句的足够比例，否则合并无意义且会生成垃圾骨架。
                // 例："当前职位仅能选择60人" 与某句只共享"当前"(2字/约18%)，
                //     其余整段"职位仅能选择60人"变成差异 → 写成
                //     $t('当前{N0}', { N0: $t('职位仅能选择60人') })，明显错误。
                // 规则：对组内所有命中句，前后缀总长(码点数) 不得低于句长(码点数)的 1/3。
                // 既有合法场景（测试1/测试2、AB测试1XY 等，共享段通常占 50%+）不受影响。
                val maxAffixLen = pLen + sLen
                val meaningful = (listOf(anchor) + candidates.map { it.first }).all {
                    val mlen = it.originalMessage.codePointCount(0, it.originalMessage.length)
                    mlen <= 0 || maxAffixLen * 3 >= mlen
                }
                if (!meaningful) continue

                val variants = mutableMapOf<String, MutableList<SiteRef>>()
                variants.getOrPut(anchorDiff) { mutableListOf() }.add(anchor); consumed.add(anchor)
                for ((sref, triple) in candidates) {
                    // 按最终 prefix/suffix 重新切 diff（避免中间交集缩小后不再对应）
                    val msg = sref.originalMessage
                    if (!msg.startsWith(maxPrefix) || !msg.endsWith(maxSuffix) || msg.length <= maxPrefix.length + maxSuffix.length) continue
                    val diff = msg.substring(maxPrefix.length, msg.length - maxSuffix.length)
                    variants.getOrPut(diff) { mutableListOf() }.add(sref); consumed.add(sref); list.remove(sref)
                }
                val skeleton = "$maxPrefix{N0}$maxSuffix"
                results += AffixGroupCandidate(
                    id = "AG${++idSeq}",
                    skeleton = skeleton,
                    prefix = maxPrefix,
                    suffix = maxSuffix,
                    variants = variants.map { (d, ss) -> AffixVariant(d, ss.toList()) },
                    skeletonKey = skeleton
                )
            }
        }
        return results.sortedByDescending { it.siteCount }
    }

    /** 返回 (commonPrefix, commonSuffix, anchor diff) 或 null（完全不包含非空前缀或后缀） */
    private fun longestCommonAffix(a: String, b: String): Triple<String, String, String>? {
        if (a === b) return null
        val p = longestCommonPrefix(a, b)
        val aTail = a.substring(p.length)
        val bTail = b.substring(p.length)
        val s = longestCommonSuffix(aTail, bTail)
        val endA = if (s.isNotEmpty()) a.length - s.length else a.length
        val endB = if (s.isNotEmpty()) b.length - s.length else b.length
        val aa = a.substring(p.length, endA)
        val bb = b.substring(p.length, endB)
        if (aa.isEmpty() || bb.isEmpty()) return null   // 其中一句没有差异（完全重合的情况另处理）
        return Triple(p, s, aa)
    }

    private fun longestCommonPrefix(a: String, b: String): String {
        val maxLen = minOf(a.length, b.length)
        var i = 0
        while (i < maxLen && a[i] == b[i]) i++
        return a.substring(0, i)
    }

    private fun longestCommonSuffix(a: String, b: String): String {
        val maxLen = minOf(a.length, b.length)
        var i = 0
        while (i < maxLen && a[a.length - 1 - i] == b[b.length - 1 - i]) i++
        return if (i == 0) "" else a.substring(a.length - i)
    }

    // ── ② 汉字≥2字 + 数字字面量抽取（MVP 单占位 {N0}；多数字同句先忽略，留 digitValues[0] 第一个命中的数字段） ──
    private fun buildDigitGroups(allSites: List<SiteRef>): List<DigitGroupCandidate> {
        val result = mutableListOf<DigitGroupCandidate>()
        var idSeq = 0
        // 按骨架（原句中第一个数字段替换为 {N0}）做 group
        val groups = mutableMapOf<String, MutableList<Pair<SiteRef, List<String>>>>()
        val noDigitSites = mutableListOf<SiteRef>()
        for (site in allSites) {
            val msg = site.originalMessage
            val hanCount = HAN_RE.findAll(msg).count()
            if (hanCount < 2) continue
            val m = DIGIT_TOKEN_RE.find(msg)
            if (m == null) {
                noDigitSites.add(site)
                continue
            }
            val digitsHere = m.range.let { listOf(msg.substring(it)) }  // MVP 只 1 处
            val skel = msg.replaceRange(m.range, "{N0}")
            groups.getOrPut(skel) { mutableListOf() }.add(site to digitsHere)
        }
        // 无数字站点若恰好等于某骨架去占位后的文本（如 你好hello{N0} → 你好hello），
        // 作为「空数字」变体并入对应组，让 你好hello / 你好hello2 合并成同一个翻译。
        if (noDigitSites.isNotEmpty() && groups.isNotEmpty()) {
            for (site in noDigitSites) {
                val msg = site.originalMessage
                val skel = groups.keys.firstOrNull { it.replace("{N0}", "") == msg }
                if (skel != null) {
                    groups[skel]!!.add(site to listOf(""))
                }
            }
        }
        for ((skeleton, entries) in groups) {
            if (entries.size < 2) continue     // 只抽重复的：骨架在多句里命中（与用户特别要求一致）
            result += DigitGroupCandidate(
                id = "DG${++idSeq}",
                skeleton = skeleton,
                digits = listOf(DigitSlot(0)),
                perSites = entries.map { (s, ds) ->
                    DigitPerSite(
                        site = s,
                        digitValues = ds,
                        allNonChinese = ds.all { !HAN_RE.containsMatchIn(it) }
                    )
                },
                skeletonKey = skeleton
            )
        }
        // 按命中数排
        result.sortByDescending { it.siteCount }
        return result
    }
}
