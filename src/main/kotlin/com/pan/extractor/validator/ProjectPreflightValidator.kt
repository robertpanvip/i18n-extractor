package com.pan.extractor.validator

import com.pan.extractor.log.PluginLogBuffer
import com.pan.extractor.model.ExtractionSite
import com.pan.extractor.planner.ImportPlan
import com.pan.extractor.planner.ResourcePlan
import com.pan.extractor.planner.RewritePlan
import com.pan.extractor.resource.JsonWriter
import com.pan.extractor.messages.I18nExtractorBundle
import com.pan.extractor.staticparser.StaticObjectParser
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.nio.charset.StandardCharsets

/** 单条 preflight 校验结果：错误码 + 人类可读描述。 */
data class PreflightIssue(
    val code: String,
    val message: String,
    /** 是否阻断写入。false 表示「警告」：仅该资源回退剪贴板，不中止整批写入。 */
    val blocking: Boolean = true,
)

/**
 * 统一 Apply 前校验结果：**没有阻断性 issue** 才允许写入任何文件。
 *
 * 部分「资源语义」类 issue（如目标 TS/JS 无可解析导出、merge 冲突）标为 [非阻断][PreflightIssue.blocking]：
 * 真实写回层（TsResourceWriter / JsonResourceWriter）遇到这类文件本来就会优雅回退到剪贴板、不崩溃，
 * 因此不应因单个资源写不进就把整批 t() 组件改写一起回滚。
 *
 * `isValid == false` 时必须**零写入**（调用方在写出前 abort），避免留下
 * "部分文件已改、其余未改"的半完成状态（PROJECT_ANALYSIS §16）。
 */
data class PreflightResult(val issues: List<PreflightIssue>) {
    val isValid: Boolean get() = issues.none { it.blocking }
}

/**
 * ProjectPreflightValidator —— 统一 Apply 前 preflight（P0 A 组，§6 / §16）。
 *
 * 在写入任何文件之前，把 "Code Rewrite + Import + Resource" 三类修改作为一个完整
 * preflight 单元校验，任一类失效即整体失败（零写入）：
 *
 * ```
 * CollectedResult(RewritePlan) + ImportPlan + ResourcePlan
 *              ↓
 *      preflightValidate()
 *              ↓
 *  Rewrite/Import/Resource 目标解析&语义级全部有效？
 *      ├── 是 ──► Apply
 *      └── 否 ──► 抛出 / 返回［零写入］
 * ```
 *
 * 校验深度（事务级 Change Validation）：
 *  - Rewrite：processor 索引 / site 存在性 / 目标 pointer 有效性 / 替换后语法有效性（结构平衡）；
 *  - Import：目标可解析可写 + 语义级冲突（同名绑定 / alias / source 冲突 / specifier 重复）；
 *  - Resource：目标可解析可写 + 语义级（可解析 / 目标对象存在 / entries×dropKeys 矛盾 /
 *    扁平点式 key 与既有标量或数组祖先冲突）。
 *
 * 与 [ChangeValidator]（仅校验合并计划的 site 指针）不同，本对象把普通 [RewritePlan]、
 * [ImportPlan]、[ResourcePlan] 统一纳入校验。文件解析（路径 → [VirtualFile]）通过注入的
 * map 传入，职责单一、可纯单元测试；实际 Apply 路径应先在写入前调用
 * [requireValidWithActualFiles]，收到 [IllegalStateException] 即中止整批写入。
 */
object ProjectPreflightValidator {

    private val LOG = Logger.getInstance(ProjectPreflightValidator::class.java)

    /**
     * 校验三类修改目标，返回 [PreflightResult]（不抛异常，供调用方决定策略）。
     *
     * @param rewrites                全部待执行 [RewritePlan]。
     * @param sites                   收集期全部 [ExtractionSite]（用于按 siteId 定位目标）。
     * @param processorCount          处理器个数（[RewritePlan.processorIndex] 上界）。
     * @param importFiles             [ImportPlan] → 实际目标文件（null 表示无法解析路径）。
     * @param resourceFiles           [ResourcePlan] → 实际目标文件（null 表示无法解析路径）。
     */
    fun preflightValidate(
        rewrites: List<RewritePlan>,
        sites: List<ExtractionSite>,
        processorCount: Int,
        importFiles: Map<ImportPlan, VirtualFile?>,
        resourceFiles: Map<ResourcePlan, VirtualFile?>,
    ): PreflightResult {
        val issues = mutableListOf<PreflightIssue>()

        // ── A1：普通 RewritePlan 校验（processor 索引 / site 存在性 / 目标 pointer 有效性）──
        for (rp in rewrites) {
            if (rp.processorIndex < 0 || rp.processorIndex >= processorCount) {
                issues += PreflightIssue(
                    "REWRITE_PROCESSOR_IDX",
                    "RewritePlan[siteId=${rp.siteId}] 的 processor 索引 ${rp.processorIndex} 越界（共 $processorCount 个）"
                )
            }
            val site = sites.firstOrNull { it.id == rp.siteId }
            if (site == null) {
                issues += PreflightIssue(
                    "REWRITE_SITE_MISSING",
                    "RewritePlan[siteId=${rp.siteId}] 在 collectedSites 中缺失（可能已被外部清空）"
                )
            } else {
                val ptr = rp.target
                if (ptr == null) {
                    // 普通单站点改写一定有 target；无 target 说明计划不完整。
                    issues += PreflightIssue(
                        "REWRITE_TARGET_NULL",
                        "RewritePlan[siteId=${rp.siteId}] 缺少目标 pointer（计划不完整）"
                    )
                } else if (ptr.element == null || !ptr.element!!.isValid) {
                    issues += PreflightIssue(
                        "REWRITE_TARGET_INVALID",
                        "RewritePlan[siteId=${rp.siteId}] 的目标 PSI 已失效（文件可能已被外部修改）"
                    )
                } else {
                    // A1b：替换后语法有效性 —— 仅校验合成的 newExpression 自身结构完整。
                    checkRewritePostSyntax(rp, issues)
                }
            }
        }

        // ── A2：Import 校验（目标可解析 / 可写 / 无重复目标冲突）──
        val seenImportFiles = HashMap<String, String>()
        for ((plan, vf) in importFiles) {
            if (vf == null) {
                issues += PreflightIssue(
                    "IMPORT_TARGET_UNRESOLVED",
                    "ImportPlan[file=${plan.fileName}] 无法解析为目标文件"
                )
                continue
            }
            if (!vf.isWritable) {
                issues += PreflightIssue(
                    "IMPORT_TARGET_NOT_WRITABLE",
                    "ImportPlan[file=${plan.fileName}] 目标文件不可写"
                )
            }
            val prev = seenImportFiles.putIfAbsent(vf.path, plan.fileName)
            if (prev != null) {
                issues += PreflightIssue(
                    "IMPORT_TARGET_DUPLICATE",
                    "ImportPlan[file=${plan.fileName}] 与 ${prev} 同时写入同一文件 ${vf.path}（需合并或确认顺序）"
                )
            }
            // ── A2b：Import 语义级冲突 —— 同名绑定 / alias / source 冲突 / specifier 重复 ──
            val importText = readText(vf)
            if (importText != null) checkImportSemantics(plan, importText, issues)
        }

        // ── A3：Resource 校验（目标可解析 / 可写）──
        for ((plan, vf) in resourceFiles) {
            if (vf == null) {
                issues += PreflightIssue(
                    "RESOURCE_TARGET_UNRESOLVED",
                    "ResourcePlan[target=${plan.targetPath}] 无法解析为目标文件"
                )
                continue
            }
            if (!vf.isWritable) {
                issues += PreflightIssue(
                    "RESOURCE_TARGET_NOT_WRITABLE",
                    "ResourcePlan[target=${plan.targetPath}] 目标文件不可写"
                )
            }
            // ── A3b：Resource 语义级 —— 可解析 / 目标对象存在 / entry-drop 冲突 / 嵌套路径冲突 ──
            val text = readText(vf)
            if (text != null) checkResourceSemantics(plan, text, issues)
        }

        return PreflightResult(issues)
    }

    /**
     * 便捷入口：preflight 发现任何 issue 即抛出 [IllegalStateException]，调用方应在写入
     * 任何文件之前调用本方法以达成「失败零写入」。
     */
    fun requireValidWithActualFiles(
        rewrites: List<RewritePlan>,
        sites: List<ExtractionSite>,
        processorCount: Int,
        importFiles: Map<ImportPlan, VirtualFile?>,
        resourceFiles: Map<ResourcePlan, VirtualFile?>,
    ) {
        val result = preflightValidate(rewrites, sites, processorCount, importFiles, resourceFiles)
        if (!result.isValid) {
            throw IllegalStateException(
                "Apply 前 preflight 校验失败（未写入任何文件）—— ${result.issues.size} 处问题：\n" +
                    result.issues.joinToString("\n") { "  [${it.code}] ${it.message}" }
            )
        }
    }

    // ==========================================================================
    // A2b：Import 语义级冲突检测
    // 目标：同一文件（含已有 import 与本次计划）内不出现同名绑定 / alias / source /
    // specifier 的冲突或重复注入。纯文本扫描，与 ImportManager 的"文本级宽松匹配"思路一致。
    // ==========================================================================

    private data class ParsedImport(val source: String?, val bindings: Set<String>)

    /** 解析单条 import 语句文本 → (来源模块, 引入的局部绑定名集合)。 */
    private fun parseOneImport(line: String): ParsedImport {
        val source = Regex("""from\s*['"]([^'"]+)['"]""").find(line)?.groupValues?.get(1)
        val bindings = LinkedHashSet<String>()
        // namespace：import * as ns from 'x' → ns
        Regex("""import\s+\*\s+as\s+([\w$]+)""").find(line)?.let { bindings.add(it.groupValues[1]) }
        // default：import a from / import a, { ... } from → a
        Regex("""import\s+([\w$]+)\s*(?:,|from)""").find(line)?.let { bindings.add(it.groupValues[1]) }
        // named：{ a, b as c } → a, c
        Regex("""\{([^{}]*)\}""").find(line)?.groupValues?.get(1)?.let { inner ->
            for (seg in inner.split(',')) {
                val s = seg.trim()
                if (s.isEmpty()) continue
                val asIdx = s.indexOf(" as ")
                bindings.add(if (asIdx > 0) s.substring(asIdx + 4).trim() else s.trim())
            }
        }
        return ParsedImport(source, bindings)
    }

    /** 把文件文本切成一段段以 `import` 开头的语句（按括号深度/分号/换行判定结尾）。 */
    private fun importChunks(text: String): List<String> {
        val chunks = ArrayList<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val st = text.indexOf("import", i)
            if (st < 0) break
            val prev = if (st > 0) text[st - 1] else ' '
            // "import" 作为词中片段（如 identifier/x'important'）跳过
            if (prev.isLetterOrDigit() || prev == '_' || prev == '$') { i = st + 6; continue }
            var j = st + 6
            var depth = 0
            var inStr: Char? = null
            var esc = false
            var end = -1
            while (j < n) {
                val c = text[j]
                when {
                    esc -> esc = false
                    inStr != null -> when (c) {
                        '\\' -> esc = true
                        inStr -> inStr = null
                    }
                    else -> when (c) {
                        '"', '\'', '`' -> inStr = c
                        '{' -> depth++
                        '}' -> depth--
                        ';' -> if (depth == 0) { end = j; break }
                        '\n', '\r' -> if (depth == 0) { end = j; break }
                    }
                }
                j++
            }
            if (end > st) chunks.add(text.substring(st, end))
            i = if (end > st) end + 1 else st + 6
        }
        return chunks
    }

    /**
     * 把 [plan] 待注入的 import 与目标文件已存在的 import 做语义级比对：
     *  - 同名绑定已绑定到【不同】来源 → IMPORT_BINDING_CONFLICT（alias/source 冲突）；
     *  - 同一绑定从【相同】来源再次注入，或计划内重复 → IMPORT_SPECIFIER_DUPLICATE。
     */
    private fun checkImportSemantics(plan: ImportPlan, text: String, issues: MutableList<PreflightIssue>) {
        val existingBindings = HashMap<String, String>()
        for (chunk in importChunks(text)) {
            val pi = parseOneImport(chunk)
            if (pi.source == null) continue
            for (b in pi.bindings) existingBindings.putIfAbsent(b, pi.source)
        }
        val seenInPlan = HashSet<String>()
        for (line in plan.imports) {
            val pi = parseOneImport(line)
            if (pi.source == null) continue
            for (b in pi.bindings) {
                if (!seenInPlan.add(b)) {
                    issues += PreflightIssue(
                        "IMPORT_SPECIFIER_DUPLICATE",
                        "ImportPlan[file=${plan.fileName}]：绑定名 \"$b\" 在同计划内出现多次（specifier 重复）"
                    )
                    continue
                }
                val prevSrc = existingBindings[b] ?: continue
                if (prevSrc == pi.source) {
                    issues += PreflightIssue(
                        "IMPORT_SPECIFIER_DUPLICATE",
                        "ImportPlan[file=${plan.fileName}]：\"$b\" 已从同模块 '$pi.source' 导入（specifier 重复注入）"
                    )
                } else {
                    issues += PreflightIssue(
                        "IMPORT_BINDING_CONFLICT",
                        "ImportPlan[file=${plan.fileName}]：\"$b\" 已在文件中绑定 '${prevSrc}'，再绑定 '${pi.source}' 会造成同名冲突（alias/source 冲突）"
                    )
                }
            }
        }
    }

    // ==========================================================================
    // A3b：Resource 语义级冲突检测
    // 目标：目标资源文件可解析 / 目标对象存在 / entries 与 dropKeys 不矛盾 /
    // 扁平点式 key 不与既有标量或数组祖先冲突（避免合并时静默退化为字面量点式 key）。
    // 复用与 apply 相同解析器（gson / StaticObjectParser），判定一致。
    // ==========================================================================

    private fun checkResourceSemantics(plan: ResourcePlan, text: String, issues: MutableList<PreflightIssue>) {
        when (plan.format.lowercase()) {
            "json" -> {
                val root = try {
                    JsonParser.parseString(text)
                } catch (_: Throwable) {
                    issues += PreflightIssue(
                        "RESOURCE_NOT_PARSEABLE",
                        "ResourcePlan[target=${plan.targetPath}] JSON 无法解析",
                        blocking = false,
                    )
                    return
                }
                if (!root.isJsonObject) {
                    issues += PreflightIssue(
                        "RESOURCE_OBJECT_MISSING",
                        "ResourcePlan[target=${plan.targetPath}] JSON 顶层不是对象，无法作为翻译资源结构",
                        blocking = false,
                    )
                    return
                }
                emitResourceMergeIssues(plan, JsonWriter.jsonElementToNestedMap(root.asJsonObject), issues)
            }
            "ts", "tsx", "js", "jsx" -> {
                val info = StaticObjectParser.parseTsExportedObject(text)
                if (info == null) {
                    // 【诊断】无法导出的入口多半是"还未初始化的空文件/占位/re-export"。
                    // 把准确目标与内容头部打出来，便于定位"为什么写不进 / 是不是没初始化"。
                    PluginLogBuffer.warn(LOG,
                        "RESOURCE_OBJECT_MISSING 诊断: target=${plan.targetPath} len=${text.length} head=${text.take(300).replace('\n', ' ')}"
                    )
                    issues += PreflightIssue(
                        "RESOURCE_OBJECT_MISSING",
                        I18nExtractorBundle.message("preflight.resource.object.missing.tsjs", plan.targetPath),
                        blocking = false,
                    )
                    return
                }
                emitResourceMergeIssues(plan, info.staticKV, issues)
            }
            else -> { /* 其他格式不做深语义校验 */ }
        }
    }

    private fun emitResourceMergeIssues(
        plan: ResourcePlan,
        existing: Map<String, Any?>,
        issues: MutableList<PreflightIssue>,
    ) {
        for (k in plan.entries.keys) {
            if (k in plan.dropKeys) {
                issues += PreflightIssue(
                    "RESOURCE_ENTRY_AND_DROP_CONFLICT",
                    "ResourcePlan[target=${plan.targetPath}]：key \"$k\" 同时出现在 entries 与 dropKeys，合并语义矛盾",
                    blocking = false,
                )
            }
        }
        for (k in plan.entries.keys) {
            val segments = k.split('.')
            if (segments.size <= 1 || segments.any { it.isBlank() }) continue
            var cur: Any? = existing
            for (i in 0 until segments.size - 1) {
                val seg = segments[i]
                val child = (cur as? Map<*, *>)?.get(seg) ?: break
                if (child is Map<*, *>) { cur = child; continue }
                issues += PreflightIssue(
                    "RESOURCE_NESTED_PATH_CONFLICT",
                    "ResourcePlan[target=${plan.targetPath}]：嵌套 key \"$k\" 的祖先段 \"$seg\" 现为${if (child is List<*>) "数组" else "标量"}，合并后会退化为字面量点式 key",
                    blocking = false,
                )
                break
            }
        }
    }

    // ==========================================================================
    // A1b：替换后语法有效性校验
    // 目标：在写入前核对本次合成的替换文本（newExpression）结构自洽（括号 / 引号成对），
    // 避免把半成品（未闭合的 $t( 等）写进源码。只校验合成表达式本身，不做整个文件扫描，
    // 以根除朴素扫描器在真实源码（正则/模板/JSX/注释）上的误报。
    // ==========================================================================

    /**
     * 替换后语法有效性校验。
     *
     * 只校验本次**合成的替换文本**（[plan.newExpression]）自身是否结构自洽（括号 / 引号闭合）。
     * 实际写入走真实 PSI 编辑器替换（结构感知），因此只要插入的表达式自身完整，目标文件必然合法；
     * 无需再对「替换后的整个文件」做文字平衡扫描。改用本细粒度校验以根除旧实现的误报——
     * 旧实现用朴素字符扫描器校验全文，真实源码里正则字面量、模板插值、JSX、泛型、注释等构造
     * 会让扫描器误判「不平衡」，连累同文件所有改写误报 REWRITE_RESULT_UNBALANCED 并中断整批写入。
     */
    private fun checkRewritePostSyntax(plan: RewritePlan, issues: MutableList<PreflightIssue>) {
        if (!isStructurallyBalanced(plan.newExpression)) {
            issues += PreflightIssue(
                "REWRITE_RESULT_UNBALANCED",
                I18nExtractorBundle.message("preflight.rewrite.unbalanced", plan.siteId, plan.newExpression)
            )
        }
    }

    /** 轻量结构自洽校验：扫描 `()[]{}` 与引号，忽略字符串与注释内部，判定括号成对 + 引号闭合。 */
    private fun isStructurallyBalanced(text: String): Boolean {
        val stack = ArrayDeque<Char>()
        var i = 0
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            if (quote != null) {
                if (c == '\\') i++
                else if (c == quote) quote = null
                i++
                continue
            }
            // 注释内部整体跳过（`//` 行注释 / `/* */` 块注释 / `<!-- -->` 模板注释），
            // 否则注释里的单引号/孤括号会污染全文平衡判定（如 `// it's` 被误当未闭合字符串，
            // 连累同文件所有 rewrite 误报 REWRITE_RESULT_UNBALANCED）。
            if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
                i += 2
                while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                continue
            }
            if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
                i += 2
                while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) i++
                i = (i + 2).coerceAtMost(text.length)
                continue
            }
            if (c == '<' && i + 3 < text.length && text[i + 1] == '!' && text[i + 2] == '-' && text[i + 3] == '-') {
                i += 4
                while (i < text.length && !(text[i] == '-' && i + 1 < text.length && text[i + 1] == '-' && i + 2 < text.length && text[i + 2] == '>')) i++
                i = (i + 3).coerceAtMost(text.length)
                continue
            }
            when (c) {
                '"', '\'', '`' -> quote = c
                '(', '[', '{' -> stack.addLast(c)
                ')', ']', '}' -> {
                    val open = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (stack.isEmpty() || stack.removeLast() != open) return false
                }
            }
            i++
        }
        return stack.isEmpty() && quote == null
    }

    /** 读取一个 [VirtualFile] 的 UTF-8 文本；失败返回 null（由调用方决定是否报读失败）。 */
    private fun readText(vf: VirtualFile): String? = try {
        String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
    } catch (_: Throwable) {
        null
    }
}