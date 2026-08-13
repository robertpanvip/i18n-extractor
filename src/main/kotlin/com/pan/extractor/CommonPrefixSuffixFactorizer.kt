package com.pan.extractor

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
    private const val MIN_AFFIX_CHAR = 2

    fun factorize(allSites: List<SiteRef>): Pair<List<AffixGroupCandidate>, List<DigitGroupCandidate>> {
        val affix = buildAffixGroups(allSites)
        val digit = buildDigitGroups(allSites)
        return affix to digit
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
                    if (p.codePointCount(0, p.length) < MIN_AFFIX_CHAR && s.codePointCount(0, s.length) < MIN_AFFIX_CHAR) continue
                    // 前后缀至少一边≥2字（组合也算）——用户阈值：≥2字 + 全自动
                    val totalAffix = p.codePointCount(0, p.length) + s.codePointCount(0, s.length)
                    if (totalAffix < MIN_AFFIX_CHAR) continue
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
                    Triple(p, s, anchor.originalMessage.substring(p.length, anchor.originalMessage.length - s.length.takeIf { it > 0 }?.let { anchor.originalMessage.length - it } ?: anchor.originalMessage.length))
                }
                // 如果交集前后缀太短（合并过程中缩小），放弃这个分组
                val pLen = maxPrefix.codePointCount(0, maxPrefix.length)
                val sLen = maxSuffix.codePointCount(0, maxSuffix.length)
                if (pLen + sLen < MIN_AFFIX_CHAR) continue

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
        val s = longestCommonSuffix(a.substring(p.length), b.substring(p.length))
        val aa = a.substring(p.length, a.length - s.length.takeIf { it > 0 }?.let { a.length - it } ?: a.length)
        val bb = b.substring(p.length, b.length - s.length.takeIf { it > 0 }?.let { b.length - it } ?: b.length)
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
        for (site in allSites) {
            val msg = site.originalMessage
            val hanCount = HAN_RE.findAll(msg).count()
            if (hanCount < 2) continue
            val m = DIGIT_TOKEN_RE.find(msg) ?: continue
            val digitsHere = m.range.let { listOf(msg.substring(it)) }  // MVP 只 1 处
            val skel = msg.replaceRange(m.range, "{N0}")
            groups.getOrPut(skel) { mutableListOf() }.add(site to digitsHere)
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
