package com.pan.extractor

import com.intellij.lang.javascript.JSTokenTypes
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecmal4.JSAttributeList
import com.intellij.lang.javascript.psi.ecma6.TypeScriptEnumField
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTupleTypeElement
import com.intellij.lang.javascript.psi.ecma6.TypeScriptTypeLiteral
import com.intellij.lang.javascript.psi.ecma6.TypeScriptUnionOrIntersectionType
import com.intellij.lang.javascript.psi.impl.JSPsiElementFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.PathUtilRt
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────
// 1. Model
// ─────────────────────────────────────────────────────────────

/**
 * 字面量归一化值：字符串按原样（长度保护），数字按 Long/Double + 负数标志。
 * 作为重复聚合的 Key。
 */
sealed interface NormalizedValue : Comparable<NormalizedValue> {
    data class Text(val raw: String) : NormalizedValue {
        override fun compareTo(other: NormalizedValue): Int =
            if (other is Text) raw.compareTo(other.raw) else javaClass.name.compareTo(other.javaClass.name)
        override fun toString(): String = "\"$raw\""
    }

    /**
     * 数字（含整数/浮点）。sign=true 表示"整体包含负号"（负数）——这部分我们替换的是整个
     * 负号前缀表达式，避免只把 NumericLiteral 改成引用得到 -CONST（语义没错但风格不统一）。
     */
    data class Number(val value: Double, val isWhole: Boolean, val sign: kotlin.NumberSign) : NormalizedValue {
        enum class kotlin.NumberSign { POSITIVE, NEGATIVE }
        override fun compareTo(other: NormalizedValue): Int {
            if (other !is Number) return javaClass.name.compareTo(other.javaClass.name)
            var c = value.compareTo(other.value); if (c != 0) return c
            c = isWhole.compareTo(other.isWhole); if (c != 0) return c
            return sign.compareTo(other.sign)
        }
        override fun toString(): String = buildString {
            if (sign == kotlin.NumberSign.NEGATIVE) append('-')
            if (isWhole) append(value.toLong().toString()) else append(value.toString())
        }
    }
}

/** 一个候选 site，值在某个文件里出现一次（ReadAction 里 capture，保存 SmartPointer 避免 PSI 失效） */
class LiteralCandidateSite(
    val value: NormalizedValue,
    /** 真正要替换的 PSI 元素（对负数就是整段 PrefixExpression(- + literal)） */
    val replaceRoot: SmartPsiElementPointer<PsiElement>,
    /** 源字符串原样，用于显示（含负号/引号） */
    val sourceText: String,
    val fileVirtual: VirtualFile,
    val startLine: Int,
    /** 上下文 1 行（变量命名启发） */
    val contextHint: String,
)

/** 一个可提取的"重复项"——归一化值 + N≥2 个 site */
class DuplicateLiteralGroup(
    val value: NormalizedValue,
    val sites: List<LiteralCandidateSite>,
    /** Dialog 里可编辑的变量名建议 */
    var suggestedName: String,
    /** 初始勾选 */
    var selected: Boolean = true,
)

// ─────────────────────────────────────────────────────────────
// 2. Collector
// ─────────────────────────────────────────────────────────────

class DuplicateLiteralCollector(private val project: Project) {

    private val allSites = mutableListOf<LiteralCandidateSite>()

    // ── 公共入口：对一批 PsiFile 收集候选（必须已经在 ReadAction 里） ──
    fun collectFromFiles(files: List<PsiFile>) {
        for (psiFile in files) {
            collectFromFile(psiFile)
        }
    }

    private fun collectFromFile(psiFile: PsiFile) {
        val vFile = psiFile.virtualFile ?: return
        // 翻译资源文件 / package.json / node_modules / tsconfig 之类 → 跳过
        if (Util.isTranslationResourceFile(psiFile)) return
        val baseName = psiFile.name
        if (baseName in SKIP_FILE_NAMES || baseName.startsWith(".")) return

        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
        PsiTreeUtil.processElements(psiFile) { element ->
            tryCandidate(element, vFile, document)
            true
        }
    }

    private fun tryCandidate(
        element: PsiElement,
        vFile: VirtualFile,
        document: com.intellij.openapi.editor.Document?,
    ) {
        // ── 数字/字符串字面量 ──
        if (element is JSLiteralExpression) {
            // 字符串
            if (element.isStringLiteral) {
                if (isExcludedStringPosition(element)) return
                val raw = element.stringValue() ?: return
                if (raw.isBlank()) return           // 空字符串不提取（通常无意义）
                addSite(
                    NormalizedValue.Text(raw),
                    replaceRoot = element,
                    sourceText = element.text,
                    vFile = vFile,
                    document = document,
                    anchor = element
                )
                return
            }
            // 数字（未被外层负号 PrefixExpression 包住才从这里收集；被包住的话走下面的 PrefixExpression 分支）
            val numeric = element.numericValue()
            if (numeric != null) {
                if (isExcludedNumericPosition(element)) return
                if (element.parent.let { it is JSPrefixExpression && it.operationSign == JSTokenTypes.MINUS }) return
                addSite(
                    NormalizedValue.Number(
                        numeric,
                        isWhole = (numeric == kotlin.math.floor(numeric)) && !numeric.isInfinite() && !numeric.isNaN(),
                        sign = NormalizedValue.Number.kotlin.NumberSign.POSITIVE
                    ),
                    replaceRoot = element,
                    sourceText = element.text,
                    vFile = vFile,
                    document = document,
                    anchor = element
                )
                return
            }
        }

        // ── 负数（整体包住，避免出现 -CONST 形式） ──
        if (element is JSPrefixExpression && element.operationSign == JSTokenTypes.MINUS) {
            val operand = element.expression as? JSLiteralExpression ?: return
            val numeric = operand.numericValue() ?: return
            if (isExcludedNumericPosition(operand)) return
            if (numeric == 0.0) return      // -0 没意义
            addSite(
                NormalizedValue.Number(
                    kotlin.math.abs(numeric),
                    isWhole = (numeric == kotlin.math.floor(numeric)),
                    sign = NormalizedValue.Number.kotlin.NumberSign.NEGATIVE
                ),
                replaceRoot = element,
                sourceText = element.text,
                vFile = vFile,
                document = document,
                anchor = element
            )
        }
    }

    private fun addSite(
        value: NormalizedValue,
        replaceRoot: PsiElement,
        sourceText: String,
        vFile: VirtualFile,
        document: com.intellij.openapi.editor.Document?,
        anchor: PsiElement,
    ) {
        val range = anchor.textRange
        val line = document?.getLineNumber(range.startOffset)?.let { it + 1 } ?: 0
        val contextHint = buildContextHint(anchor)
        val ptr = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(replaceRoot)
        allSites.add(
            LiteralCandidateSite(
                value = value,
                replaceRoot = ptr,
                sourceText = sourceText,
                fileVirtual = vFile,
                startLine = line,
                contextHint = contextHint
            )
        )
    }

    // ── 位置排除 ─────────────────────────────────────────────────
    // 字符串排除：import/require/动态 import 路径、索引键、switch case 标签、TS 类型字面量、对象属性名 key、enum 成员 key
    private fun isExcludedStringPosition(lit: JSLiteralExpression): Boolean {
        if (isImportOrRequireArgument(lit)) return true
        if (isInIndexKeyPosition(lit)) return true
        if (isSwitchLabel(lit)) return true
        if (isTypeLiteral(lit)) return true
        if (isObjectPropertyNameKey(lit)) return true
        if (isEnumFieldLiteralKey(lit)) return true
        if (isTemplateLiteralPart(lit)) return true
        return false
    }

    // 数字排除：数组下标 [0]、[1]、[10]；switch case 标签；TS 类型字面量；enum 字段初始值（视情况允许，这里不排除）
    private fun isExcludedNumericPosition(lit: JSLiteralExpression): Boolean {
        if (isArrayIndexPosition(lit)) return true
        if (isSwitchLabel(lit)) return true
        if (isTypeLiteral(lit)) return true
        return false
    }

    // 1) import 'x' / require('x') / import('x') / import.meta 的 arg 等
    private fun isImportOrRequireArgument(lit: JSLiteralExpression): Boolean {
        val parent = lit.parent ?: return false
        // ES6ImportDeclaration 的 import specifier path 字符串的直接父通常是 ES6ImportDeclaration
        if (parent is com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration ||
            parent is ES6ImportDeclaration ||
            parent.javaClass.name.contains("ImportDeclaration")
        ) return true
        // export from "x"
        if (parent is com.intellij.lang.ecmascript6.psi.ES6ExportSpecifier ||
            parent.javaClass.name.contains("ExportFromClause")
        ) return true
        // require('x')
        if (parent is JSArgumentList) {
            val call = parent.parent as? JSCallExpression ?: return false
            val callee = call.methodExpression
            if (callee is JSReferenceExpression && callee.referenceName in arrayOf("require", "import")) return true
        }
        return false
    }

    // 2) obj['k'] / arr[0] 的索引表达式的 index
    private fun isInIndexKeyPosition(ele: PsiElement): Boolean {
        val parent = ele.parent
        if (parent is JSIndexedPropertyAccessExpression) {
            return parent.indexExpression === ele
        }
        return false
    }

    // 3) arr[<这里>] 且数组索引的字面量是数字
    private fun isArrayIndexPosition(lit: JSLiteralExpression): Boolean = isInIndexKeyPosition(lit)

    // 4) switch case "x": / case 404:
    private fun isSwitchLabel(ele: PsiElement): Boolean {
        val switchLabel = PsiTreeUtil.getParentOfType(ele, com.intellij.lang.javascript.psi.JSSwitchLabelStatement::class.java) ?: return false
        // 枚举标签的第一个表达式（case 后面）=== ele 的 ancestor
        return true
    }

    // 5) TS 类型字面量：type X = "a" | "b"；const x: "foo" = ...
    private fun isTypeLiteral(ele: PsiElement): Boolean {
        // 典型：TypeScriptTypeLiteral/TypeScriptUnionOrIntersectionType/TypeScriptTupleTypeElement 等"类型层"祖先
        var p: PsiElement? = ele.parent
        val stopCls = listOf(
            JSStatement::class.java, JSFunction::class.java,
            JSProperty::class.java, JSVarStatement::class.java, JSExpression::class.java
        )
        while (p != null) {
            when (p) {
                is TypeScriptUnionOrIntersectionType,
                is TypeScriptTypeLiteral,
                is TypeScriptTupleTypeElement,
                is JSAttributeList -> {
                    // 可能还要再判断是否真在"类型语法层"——简单按名字也能命中大多数
                    if (p.javaClass.simpleName.endsWith("Type") || p.javaClass.name.contains("typescript", ignoreCase = true))
                        return true
                }
            }
            // 到 expression/statement 就停（再往上就出了这层局部类型了）
            for (cls in stopCls) { if (cls.isInstance(p)) return false }
            p = p.parent
        }
        return false
    }

    // 6) const o = { "key": 1 } 的属性名 key（非 value）
    private fun isObjectPropertyNameKey(lit: JSLiteralExpression): Boolean {
        val p = lit.parent as? JSProperty ?: return false
        return p.nameIdentifier === lit || p.name === lit
    }

    // 7) enum X { "k" = 1 } 的字段 key（字符串字面量当 enum 名）
    private fun isEnumFieldLiteralKey(lit: JSLiteralExpression): Boolean {
        val p = lit.parent as? TypeScriptEnumField ?: return false
        return p.nameIdentifier === lit
    }

    // 8) 已经在模板字面量内部？不应该（模板字面量是 JSTemplateLiteralExpression，不是 JSLiteralExpression，但保险留一个）
    private fun isTemplateLiteralPart(ele: PsiElement): Boolean {
        return PsiTreeUtil.getParentOfType(ele, com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression::class.java) != null
    }

    // ── 命名启发：根据最近的上下文（赋值左边、比较右边、函数名）生成建议名 ──
    private fun buildContextHint(anchor: PsiElement): String {
        val exprAncestor = PsiTreeUtil.getParentOfType(anchor, JSExpression::class.java, false)
            ?: return ""
        val parent = exprAncestor.parent ?: return ""
        return when {
            parent is JSProperty -> parent.name ?: ""
            parent is JSBinaryExpression && parent.operationSign == JSTokenTypes.EQ ->
                (parent.lOperand as? JSReferenceExpression)?.referenceName ?: ""
            parent is JSAssignmentExpression && parent.operationSign == JSTokenTypes.EQ ->
                (parent.lOperand as? JSReferenceExpression)?.referenceName ?: ""
            parent is JSVarStatement -> ""
            else -> ""
        }
    }

    // ── 聚合（过滤只保留重复 ≥2 次的 + 生成建议变量名） ──
    fun buildDuplicateGroups(): List<DuplicateLiteralGroup> {
        val byValue = allSites.groupBy { it.value }
            .filterValues { it.size >= 2 }
        return byValue.entries.sortedByDescending { it.value.size }
            .map { (value, sites) ->
                val suggested = suggestName(value, sites.first())
                DuplicateLiteralGroup(value, sites, suggestedName = suggested)
            }
    }

    private fun suggestName(value: NormalizedValue, site: LiteralCandidateSite): String {
        val fromCtx = site.contextHint
            .trim()
            .replace("\\W+".toRegex(), "_")
            .uppercase()
            .trim('_')
        val base = when (value) {
            is NormalizedValue.Text -> {
                // 取字符串里的英文/数字、路径尾段、域名关键字
                val slug = value.raw
                    .lowercase()
                    .replace("""[^a-z0-9]+""".toRegex(), "_")
                    .trim('_')
                    .ifBlank { "STR" }
                    .uppercase()
                slug.take(40)
            }
            is NormalizedValue.Number -> {
                val numPart = if (value.isWhole) {
                    val n = value.value.toLong()
                    val signPrefix = if (value.sign == NormalizedValue.Number.kotlin.NumberSign.NEGATIVE) "NEG_" else ""
                    "${signPrefix}N${if (n >= 0) n else -n}"
                } else {
                    val signPrefix = if (value.sign == NormalizedValue.Number.kotlin.NumberSign.NEGATIVE) "NEG_" else ""
                    "${signPrefix}F${value.value.toString().replace("\\W+".toRegex(), "_")}"
                }
                numPart
            }
        }
        val combined = if (fromCtx.isNotBlank()) {
            "${fromCtx}_$base"
        } else base
        // 去掉开头可能的数字（标识符不能数字开头）
        val safe = if (combined.firstOrNull()?.isDigit() == true) "C_$combined" else combined
        return safe.ifBlank { "LITERAL" }
    }

    companion object {
        private val SKIP_FILE_NAMES = setOf(
            "package.json", "package-lock.json", "pnpm-lock.yaml", "yarn.lock",
            "tsconfig.json", "tsconfig.app.json", "tsconfig.base.json", "vite.config.ts",
            "vite.config.js", "webpack.config.js", "webpack.config.ts",
            "README.md", "CHANGELOG.md", "CONTRIBUTING.md"
        )

        // ── JSLiteralExpression 值读取工具（兼容 Kotlin 2.4 + IntelliJ 2026.2） ──
        private fun JSLiteralExpression.stringValue(): String? {
            return try {
                // 优先用 API（版本不同名字可能变），fallback 剥引号自己取
                val text = this.text
                if (text.length >= 2 && (text.startsWith('"') || text.startsWith('\'') || text.startsWith('`'))) {
                    val first = text.first()
                    if (text.last() == first) {
                        text.substring(1, text.length - 1)
                    } else null
                } else null
            } catch (_: Throwable) { null }
        }

        private fun JSLiteralExpression.numericValue(): Double? {
            val text = this.text.trim()
            if (text.isBlank()) return null
            // Hex / Binary / Octal / BigInt（23n）—— 不处理为普通数字（避免误提）
            if (text.startsWith("0x", ignoreCase = true) ||
                text.startsWith("0b", ignoreCase = true) ||
                text.startsWith("0o", ignoreCase = true) ||
                text.endsWith('n')) return null
            return text.toDoubleOrNull()
        }
    }
}

// ─────────────────────────────────────────────────────────────
// 3. Writer
// ─────────────────────────────────────────────────────────────

class DuplicateLiteralWriter(
    private val project: Project,
    private val groups: List<DuplicateLiteralGroup>,
    private val constantsPathRelative: String, // e.g. "src/constants.ts"
) {
    private val smartMgr = SmartPointerManager.getInstance(project)
    private val psiMgr = PsiManager.getInstance(project)

    /** 一次性写出：创建/更新 constants.ts，site 所在文件注入 import + 替换 literal 为引用 */
    fun executeAllInOneUndo(commandName: String = "提取重复字面量为常量") {
        CommandProcessor.getInstance().executeCommand(
            project,
            {
                WriteCommandAction.runWriteCommandAction(project) {
                    doExecute()
                }
            },
            commandName,
            null
        )
    }

    private fun doExecute() {
        // ── Step 1: 确保 constants.ts 存在并拿到 PsiFile ──
        val projectDir = project.guessProjectDir() ?: return
        val constantsVFile: VirtualFile = ensureConstantsVirtualFile(projectDir, constantsPathRelative)
        val constantsPsi: PsiFile = psiMgr.findFile(constantsVFile)
            ?: throw IllegalStateException("constants.ts PSI 初始化失败")

        // ── Step 2: 给每个 group 分配最终变量名（考虑 constants.ts 已有 export / group 内部冲突） ──
        val existingNames = collectExistingTopLevelConstNames(constantsPsi).toMutableSet()
        val groupFinalName: Map<DuplicateLiteralGroup, String> = groups.associateWith { g ->
            var candidate = g.suggestedName.trim().ifBlank { "LITERAL" }
                .replace("\\W+".toRegex(), "_")
                .trim('_')
            if (candidate.first().isDigit()) candidate = "C_$candidate"
            var final = candidate
            var i = 2
            while (final in existingNames) {
                final = "${candidate}_$i"
                i++
            }
            existingNames.add(final)
            final
        }

        // ── Step 3: 把 export const X = value 追加到 constants.ts 末尾 ──
        appendConstExports(constantsPsi, groups.mapNotNull { g ->
            val name = groupFinalName[g] ?: return@mapNotNull null
            val valueText = renderInitializer(g.value)
            name to valueText
        })

        // ── Step 4: 对每个 site 的 file 注入 import + 替换引用 ──
        // 按 file 分组
        val byFile: Map<VirtualFile, List<Pair<DuplicateLiteralGroup, LiteralCandidateSite>>> =
            groups.flatMap { g -> g.sites.map { site -> g to site } }
                .groupBy { (_, s) -> s.fileVirtual }

        byFile.forEach { (vFile, entries) ->
            val filePsi = psiMgr.findFile(vFile) ?: return@forEach
            if (filePsi.virtualFile?.canonicalPath == constantsVFile.canonicalPath) return@forEach // constants.ts 自己不 import 自己
            val namesForFile = entries.mapNotNull { (g, _) -> groupFinalName[g] }.distinct()
            // 注入 import（去重）
            val importAlias = ensureImportFromConstants(filePsi, constantsVFile, namesForFile)
            // 替换
            for ((g, site) in entries) {
                val finalName = groupFinalName[g] ?: continue
                val refText = if (importAlias != null) "${importAlias}.${finalName}" else finalName
                val root = site.replaceRoot.element ?: continue
                if (!root.isValid) continue
                try {
                    val replacement = JSPsiElementFactory.createJSExpression(refText, root) ?: continue
                    root.replace(replacement)
                } catch (_: Throwable) { /* skip broken sites */ }
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────
    private fun renderInitializer(value: NormalizedValue): String {
        return when (value) {
            is NormalizedValue.Text -> {
                // 字符串：根据内部含单双引号选择引号；简单优先单引号，含 ' 就用双，含 " 就用模板字符串
                val raw = value.raw
                when {
                    '\'' !in raw -> "'$raw'"
                    '"' !in raw -> "\"$raw\""
                    else -> "`${raw.replace("`", "\\`")}`"
                }
            }
            is NormalizedValue.Number -> {
                val signPrefix = if (value.sign == NormalizedValue.Number.kotlin.NumberSign.NEGATIVE) "-" else ""
                if (value.isWhole) {
                    signPrefix + value.value.toLong().toString()
                } else {
                    signPrefix + value.value.toString()
                }
            }
        }
    }

    private fun ensureConstantsVirtualFile(projectDir: VirtualFile, relPath: String): VirtualFile {
        val clean = PathUtilRt.toSystemIndependentName(relPath).trimStart('/')
        var current = projectDir
        val parts = clean.split('/')
        for (i in parts.indices) {
            val part = parts[i]
            if (i == parts.size - 1) {
                // file
                var f = current.findChild(part)
                if (f == null || !f.isValid) {
                    f = ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                        current.createChildData(this, part)
                    }
                    // 初始化一个空 TS 文件内容
                    VfsUtil.saveText(f, "// 自动生成的全局常量文件（ProjectDuplicateLiteralExtractor）\nexport {}\n")
                }
                return f
            } else {
                var dir = current.findChild(part)
                if (dir == null || !dir.isDirectory) {
                    dir = ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                        current.createChildDirectory(this, part)
                    }
                }
                current = dir
            }
        }
        throw IllegalStateException("constants path empty")
    }

    /** 收集 constants.ts 顶层已存在的 const/let/var 名称，避免重名 */
    private fun collectExistingTopLevelConstNames(file: PsiFile): Set<String> {
        val result = mutableSetOf<String>()
        for (child in file.children) {
            when (child) {
                is JSVarStatement -> result.addAll(child.variables.mapNotNull { it.name })
                is com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration -> {
                    val inner = child.firstChild
                    if (inner is JSVarStatement) result.addAll(inner.variables.mapNotNull { it.name })
                }
            }
        }
        return result
    }

    /**
     * 向 filePsi 注入 `import { X, Y } from '<relative path to constants.ts>'`
     * - 已有同名 import 条目时合并 names；
     * - 已存在的名字不再重复加入。
     * 返回：如果存在 `import * as X from 'constants.ts'` 命名空间导入，则返回别名 X，
     *       否则返回 null（使用命名导入引用）。
     */
    private fun ensureImportFromConstants(
        filePsi: PsiFile,
        constantsVFile: VirtualFile,
        names: List<String>
    ): String? {
        if (names.isEmpty()) return null
        // 计算相对路径
        val rel = Util.computeRelativeImportPath(filePsi.virtualFile, constantsVFile)
            ?: run {
                // 退化：直接按常量名全量生成命名空间导入
                val alias = findUnusedLocalAlias(filePsi, "CONSTANTS")
                injectNewImport(filePsi, "import * as $alias from '$relConstantsFallback(constantsVFile)';")
                return alias
            }

        // 遍历 import 声明找匹配 from 的
        val imports = PsiTreeUtil.getChildrenOfTypeAsList(filePsi, ES6ImportDeclaration::class.java)
        var namespaceAlias: String? = null
        var matchedDecl: ES6ImportDeclaration? = null
        var existingNamesInDecl: MutableSet<String> = mutableSetOf()
        for (imp in imports) {
            val impText = imp.text.replace("\\s+".toRegex(), " ")
            val fromMatch = Regex("""from\s*['"]([^'"]+)['"]""").find(impText) ?: continue
            val from = fromMatch.groupValues[1].trim().lowercase().removeSuffix("/index").removeSuffix(".ts").removeSuffix(".js")
            val target = rel.trim().lowercase().removeSuffix("/index").removeSuffix(".ts").removeSuffix(".js")
            if (from != target) continue
            matchedDecl = imp
            // 检测 namespace: import * as XXX from
            if (Regex("""import\s*\*\s*as\s+(\w+)""").containsMatchIn(impText)) {
                namespaceAlias = Regex("""import\s*\*\s*as\s+(\w+)""").find(impText)!!.groupValues[1]
            }
            // 检测命名导入列表
            Regex("""\{([^}]*)\}""").find(impText)?.let { m ->
                m.groupValues[1].split(',').mapNotNull { s ->
                    val core = s.trim().substringBefore(' ').trim()
                    if (core.isNotBlank()) core else null
                }.let { existingNamesInDecl.addAll(it) }
            }
            break
        }

        if (namespaceAlias != null) return namespaceAlias

        // 计算还需加入的 names
        val toAdd = names.filter { it !in existingNamesInDecl }
        if (toAdd.isEmpty()) return null

        if (matchedDecl != null) {
            // 合并到已有命名导入中
            val oldText = matchedDecl.text
            val braceMatch = Regex("""\{([^}]*)\}""").find(oldText)
            val newDeclText = if (braceMatch != null) {
                val inside = braceMatch.groupValues[1].trim().trim(',')
                val newInside = (if (inside.isNotBlank()) inside + ", " else "") + toAdd.joinToString(", ")
                oldText.replaceRange(braceMatch.range, "{ $newInside }")
            } else {
                // 没有命名导入，比如默认导入：import Foo from 'x' → 改成 import Foo, { A,B } from 'x'
                val mFrom = Regex("""(from\s*['"][^'"]+['"]\s*;?\s*$)""").find(oldText)
                if (mFrom != null) {
                    val prefix = oldText.substring(0, mFrom.range.first)
                    val namesTxt = "{ ${toAdd.joinToString(", ")} }"
                    val sep = if (prefix.trim().endsWith(',')) " " else ", "
                    val rest = mFrom.value
                    "$prefix$namesTxt$sep$rest"
                } else oldText
            }
            try {
                val newDecl = JSPsiElementFactory.createJSElementFromText<ES6ImportDeclaration>(
                    project, newDeclText,
                    com.intellij.lang.javascript.JavaScriptSupportLoader.TYPESCRIPT,
                    matchedDecl.parent
                )
                if (newDecl != null) matchedDecl.replace(newDecl)
            } catch (_: Throwable) { /* fallback 新建一个 import */ }
            return null
        }

        // 新建一条 import 声明
        val spec = "{ ${toAdd.joinToString(", ")} }"
        val newImportText = "import $spec from '$rel';\n"
        injectNewImport(filePsi, newImportText)
        return null
    }

    private fun injectNewImport(filePsi: PsiFile, text: String) {
        val firstChild = filePsi.firstChild
        val newDecl = JSPsiElementFactory.createJSStatement(text, filePsi)
        // 放在文件最开头（或第一个 import 之前？简单放开头即可，Format 之后会整理）
        if (firstChild != null) {
            filePsi.addBefore(newDecl, firstChild)
        } else {
            filePsi.add(newDecl)
        }
    }

    private fun findUnusedLocalAlias(filePsi: PsiFile, base: String): String {
        val existing = mutableSetOf<String>()
        PsiTreeUtil.processElements(filePsi) { e ->
            if (e is JSReferenceExpression) e.referenceName?.let { existing.add(it) }
            true
        }
        var cand = base
        var i = 2
        while (cand in existing) { cand = "${base}$i"; i++ }
        return cand
    }

    private fun relConstantsFallback(constantsVFile: VirtualFile): String {
        // 退化路径（计算失败时）：按 workspace 相对路径，一般都不是项目里真实可用的 import specifier，
        // 但作为"保底"保证文件结构正确；用户可手动修。
        val projectDir = project.guessProjectDir()?.canonicalPath ?: return "./constants"
        val c = constantsVFile.canonicalPath ?: return "./constants"
        val rel = VfsUtil.findRelativePath(projectDir, constantsVFile, '/') ?: "./constants"
        return if (!rel.startsWith(".")) "./$rel" else rel
    }

    private fun appendConstExports(constantsPsi: PsiFile, pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        // 找到文件末尾的最后一个有效语句（跳过末尾 \n、export {} 空），逐个追加
        val anchor = constantsPsi.lastChild
        val text = buildString {
            pairs.forEach { (name, init) ->
                append("\nexport const $name = $init;\n")
            }
        }
        val statements = JSPsiElementFactory.createJSStatement(text, constantsPsi)
        // 移除空 export {} 占位（为了首建的文件美观）
        constantsPsi.children.firstOrNull {
            it is com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration &&
                it.text.replace("\\s+".toRegex(), "") == "export{}"
        }?.delete()
        if (anchor != null) {
            constantsPsi.addAfter(statements, constantsPsi.lastChild)
        } else {
            constantsPsi.add(statements)
        }
    }
}
