package com.pan.extractor.ui

import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.messages.LocaleMessages
import com.pan.extractor.*
import com.pan.extractor.analyzer.*

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * $t() 折叠：
 * 把 `$t('key')` / `t('key')`（含 Vue 的 `{{ $t('key') }}` 脚本表达式、React 的 `t('key')`）
 * 折叠为指定语言（[I18nSettings.foldDisplayLanguage]）的翻译文案。
 *
 * - 折叠占位文本 = 翻译值，编辑器内 Ctrl+F 可直接搜到翻译文案。
 * - 带插值参数的调用会将 {N0}/{0} 等占位符替换为实际参数值，同时支持 Vue（{N0}）和 React（{0}）格式。
 * - 仅在指定语言资源中查得到 key 时才折叠，避免误折叠。
 *
 * 性能：buildFoldRegions 被平台在 EDT 上同步调用，但本实现**立即返回空**，
 * 将 PSI 遍历 + Symbol 解析移至后台线程（pooled），计算完成后通过 invokeLater
 * 把折叠注入到各编辑器的 FoldingModel。避免打开大文件时 UI 卡死。
 */
class I18nFoldingBuilder : FoldingBuilderEx() {

    companion object {
        /** 折叠占位文本末尾的折叠切换提示符，提醒用户此处可展开。 */
        const val TOGGLE_HINT = " \u21A9"

        /** 单次折叠建立超过该阈值(ms)记 info，作为「打开/折叠慢」的基准告警；以下仅记 debug。 */
        private const val SLOW_FOLD_MS = 100L
    }

    private val logger = Logger.getInstance(I18nFoldingBuilder::class.java)

    init {
        logger.warn("I18nFoldingBuilder 类已加载（实例化时触发）")
    }

    /** 立即返回空，后台线程计算完成后通过 [applyFoldsToEditors] 注入折叠。
     *  测试模式下同步计算，便于断言直接检查返回值。 */
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        if (quick) return FoldingDescriptor.EMPTY_ARRAY
        val project = root.project ?: return FoldingDescriptor.EMPTY_ARRAY
        val containingFile = root.containingFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val contextFile = InjectedLanguageManager.getInstance(project).getTopLevelFile(containingFile) ?: containingFile

        val messages = LocaleMessages.loadCached(project, contextFile)
        if (messages.isEmpty()) {
            logger.warn("I18nFoldingBuilder: 未找到翻译文件，跳过折叠。文件=${contextFile.name} displayLang=${I18nSettings.getInstance().foldDisplayLanguage()}")
            return FoldingDescriptor.EMPTY_ARRAY
        }

        if (ApplicationManager.getApplication().isUnitTestMode) {
            return computeFoldsSync(root, contextFile, messages)
        }

        // ReadAction.nonBlocking 在后台池线程上以「可取消 read action + coroutine Job」语义执行，
        // 提供 TS resolve 引擎（JSGraphBuildExecutor.runBlockingCancellable）所需的 Job/进度上下文。
        // 此前用 executeOnPooledThread + runProcess(EmptyProgressIndicator) 只注入进度指示器、
        // 不安装 coroutine Job；无该 Job 时 runBlockingCancellable 抛
        // "There is no ProgressIndicator or Job in this thread"，导致折叠与 inlay 全部失效。
        ReadAction.nonBlocking<Array<FoldingDescriptor>> {
            computeFolds(root, contextFile, messages)
        }
            .inSmartMode(project)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { descriptors ->
                if (descriptors.isEmpty()) return@onSuccess
                ApplicationManager.getApplication().invokeLater {
                    applyFoldsToEditors(project, document, descriptors.toList())
                }
            }
            .onError(java.util.function.Consumer<Throwable> { t ->
                logger.warn("I18nFoldingBuilder: 计算折叠失败 file=${contextFile.name}", t)
            })

        return FoldingDescriptor.EMPTY_ARRAY
    }

    /** 同步计算折叠描述符（仅测试模式使用，生产环境走 [computeFolds] 异步路径）。 */
    private fun computeFoldsSync(root: PsiElement, contextFile: PsiFile, messages: Map<String, String>): Array<FoldingDescriptor> {
        val t0 = System.nanoTime()
        // 宿主树（== 折叠目标文档）上的 $t/t() 调用：坐标即宿主坐标，可直接折叠。
        // 模板表达式 {{ }} / 指令绑定是注入语言，注入 JSCall 坐标不可靠，统一交由 addRawFolds
        // 兜底，故此处仅当 root 属于宿主 contextFile 时才扫宿主树（root 为注入 PSI 时跳过）。
        val descriptors = mutableListOf<FoldingDescriptor>()
        if (root is PsiFile && root.containingFile == contextFile) {
            for (call in collectJSCallExpressions(root)) {
                addFoldingDescriptor(call, messages, descriptors)
            }
        }
        // 模板表达式（{{ $t('..') }} 等）注入坐标不可靠，按宿主原始文本兜底折叠。
        addRawFolds(contextFile, messages, descriptors)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        logger.debug("I18nFoldingBuilder[测试/折叠] file=${contextFile.name} size=${contextFile.textLength}B folded=${descriptors.size} elapsed=${elapsedMs}ms")
        return descriptors.toTypedArray()
    }

    /** 后台(read-action)线程：全量 PSI 遍历 + Symbol 解析，产出折叠描述符。
     *  由 [ReadAction.nonBlocking] 调用（其已持有可取消 read action + coroutine Job）；
     *  计算完成后由调用方在 EDT 上 [applyFoldsToEditors] 注入。 */
    private fun computeFolds(
        root: PsiElement,
        contextFile: PsiFile,
        messages: Map<String, String>,
    ): Array<FoldingDescriptor> {
        val t0 = System.nanoTime()
        val descs = mutableListOf<FoldingDescriptor>()
        if (root is PsiFile && root.containingFile == contextFile) {
            for (call in collectJSCallExpressions(root)) {
                addFoldingDescriptor(call, messages, descs)
            }
        }
        // 模板表达式（{{ $t('..') }} 等）注入坐标不可靠，按宿主原始文本兜底折叠。
        addRawFolds(contextFile, messages, descs)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        if (elapsedMs >= SLOW_FOLD_MS) {
            logger.info("I18nFoldingBuilder[打开/折叠] file=${contextFile.name} size=${contextFile.textLength}B folded=${descs.size} elapsed=${elapsedMs}ms")
        } else {
            logger.debug("I18nFoldingBuilder[打开/折叠] file=${contextFile.name} size=${contextFile.textLength}B folded=${descs.size} elapsed=${elapsedMs}ms")
        }
        return descs.toTypedArray()
    }

    /** EDT：将计算好的折叠描述符注入到所有打开该文件的编辑器。 */
    private fun applyFoldsToEditors(
        project: Project,
        document: Document,
        descriptors: List<FoldingDescriptor>,
    ) {
        if (project.isDisposed) return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return
        val vf = file.virtualFile ?: return
        val editors = FileEditorManager.getInstance(project).allEditors
            .filterIsInstance<TextEditor>()
            .filter { it.file == vf }
        for (textEditor in editors) {
            val editor = textEditor.editor
            if (editor.isDisposed) continue
            val fm = editor.foldingModel
            fm.runBatchFoldingOperation {
                for (desc in descriptors) {
                    val range = desc.range
                    if (range.isEmpty) continue
                    val fold = fm.addFoldRegion(range.startOffset, range.endOffset, desc.placeholderText ?: "")
                    fold?.isExpanded = false
                }
            }
        }
    }

    /** 打开文件时 $t() 调用默认全部折叠，便于直接看到翻译文案。 */
    override fun isCollapsedByDefault(node: ASTNode): Boolean = true

    /** 兜底占位文本：descriptor 已在构造时携带占位文本，此方法通常不会被调用。 */
    override fun getPlaceholderText(node: ASTNode): String? {
        val psi = node.psi ?: return null
        val call = psi as? JSCallExpression ?: return null
        val project = call.project ?: return null
        val messages = LocaleMessages.loadCached(project, call.containingFile)
        val key = extractKey(call) ?: return null
        val rawValue = messages[key] ?: return null
        val params = extractInterpolationParams(call, messages)
        return I18nFrameworkRegistry.detect(call).interpolatePlaceholders(rawValue, params) + TOGGLE_HINT
    }

    /** 为单个调用创建折叠描述符（若 key 在翻译资源中存在）。 */
    private fun addFoldingDescriptor(
        call: JSCallExpression,
        messages: Map<String, String>,
        descriptors: MutableList<FoldingDescriptor>,
    ) {
        // 注意：此方法仅由宿主树循环调用（调用方的 root.containingFile == contextFile），
        // 传进来的 call 都是坐标可靠的宿主树调用（.ts/.tsx 属性绑定、Vue <script> 块等）。
        // 因此**不被** Vue 反引号注入调用（那是注入子树，走 addRawFolds 兜底）。原本这里
        // 用 isBacktickKeyCall 跳过所有反引号调用，会误伤 React 等的 `t(\`key\`)` 宿主调用，
        // 使其既不走本路径、又因 raw 正则只认 $t/i18n 前缀而不兜底 → 反引号调用完全不折叠。
        // 去掉该跳过：宿主树反引号调用坐标可靠，直接折叠；注入场景继续由 addRawFolds 兜底。
        val key = extractKey(call) ?: return
        val rawValue = messages[key] ?: return
        val params = extractInterpolationParams(call, messages)
        val value = I18nFrameworkRegistry.detect(call).interpolatePlaceholders(rawValue, params) + TOGGLE_HINT
        val range = call.textRange
        if (!range.isEmpty()) {
            descriptors.add(
                FoldingDescriptor(
                    call.node,
                    TextRange(range.startOffset, range.endOffset),
                    null,
                    value
                )
            )
        }
    }

    /** 从 `$t('key')` / `t('key')` / `xxx.t('key')` 调用中提取 key；非翻译调用返回 null。 */
    private fun extractKey(call: JSCallExpression): String? =
        I18nFrameworkRegistry.detect(call).extractKey(call)

    /**
     * 模板表达式 `$t('x')` / `$t("x")` / `$t(\`x\`)` 兜底折叠：Vue 对这类表达式注入出的
     * [JSCallExpression] 的 textRange 是注入文件坐标，直接套用会产生错位折叠（打断正常文字）。
     * 因此统一按顶层文件注入宿主的**原始文本**正则识别，锚定宿主元素、使用宿主文档绝对偏移，
     * 与已有描述符按起始偏移去重，仅命中翻译资源中存在的 key。
     */
    private fun addRawFolds(
        contextFile: PsiFile,
        messages: Map<String, String>,
        out: MutableList<FoldingDescriptor>,
    ) {
        val occupiedStarts = out.mapTo(mutableSetOf()) { it.range.startOffset }
        val fw = I18nFrameworkRegistry.detect(contextFile)
        for (raw in collectRawTCalls(contextFile)) {
            if (raw.range.startOffset in occupiedStarts) continue
            val rawValue = messages[raw.key] ?: continue
            // 模板兜底同样支持插值参数（React {0} / Vue {N0}），与宿主树 t() 折叠行为一致。
            val params = extractParamsFromText(raw.text, messages)
            val value = fw.interpolatePlaceholders(rawValue, params)
            occupiedStarts.add(raw.range.startOffset)
            out.add(FoldingDescriptor(raw.element.node, raw.range, null, value + TOGGLE_HINT))
        }
    }

    /**
     * 从 t() 调用的第二个参数（对象字面量）中提取插值参数映射，如 `{"0": "xxx"}` → `{"0": "xxx"}`。
     *
     * 支持两种参数值（因子化产物常见第二种）：
     *  - 字面量：`N0: '搜索关键词'` / `"0": 2`
     *  - 内层翻译调用：`N0: $t('搜索关键词')` —— 用该内层 key 的译文作为参数值，使
     *    骨架折叠出「请输入搜索关键词」这类完整文案。
     *
     * 参数键统一去前缀 `N`（Vue 生成的 `{N0}` 占位匹配数字索引 `0`）。
     */
    private fun extractInterpolationParams(call: JSCallExpression, messages: Map<String, String>): Map<String, String> {
        val secondArg = call.arguments.getOrNull(1) ?: return emptyMap()
        val text = secondArg.text
        if (text.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        // 字面量： "key": 'value' / "key": "value" / "key": 数字
        val re = Regex("""["']?(\w+)["']?\s*:\s*("[^"]*"|'[^']*'|-?\d+)""")
        re.findAll(text).forEach { match ->
            val key = match.groupValues[1].removePrefix("N")
            val rawValue = match.groupValues[2]
            val value = if (rawValue.startsWith("\"") || rawValue.startsWith("'"))
                rawValue.substring(1, rawValue.length - 1)
            else rawValue
            result[key] = value
        }
        // 内层翻译调用：N0: $t('innerKey') / N0: xxx.t('innerKey')
        val tRe = Regex("""["']?(\w+)["']?\s*:\s*(?:[\w.]+\s*\.\s*\$?t|\$?t)\s*\(\s*['"]([^'"]+)['"]\s*\)""")
        tRe.findAll(text).forEach { match ->
            val key = match.groupValues[1].removePrefix("N")
            val innerKey = match.groupValues[2]
            // 用内层 key 的译文替换，使骨架折叠显示完整文案
            result[key] = messages[innerKey] ?: innerKey
        }
        return result
    }
}

// 注：占位符插值逻辑已迁移至 I18nFramework 策略
// （VueI18nStrategy / ReactI18nextStrategy / GenericStrategy.interpolatePlaceholders），
// 原 interpolatePlaceholders(value, params, isVue) 方法已删除。