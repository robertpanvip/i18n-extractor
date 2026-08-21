package com.pan.extractor.ui

import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.messages.LocaleMessages
import com.pan.extractor.*
import com.pan.extractor.analyzer.*

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 在 t()/`$t()` 调用前添加可点击的 ↩ inlay 提示，用于快速折叠/展开切换。
 * 注册为 [EditorFactoryListener]（编辑器打开）+ [FileEditorManagerListener]（tab 选中）。
 *
 * 性能策略：
 * 1. **只处理当前 active 的编辑器**：同一时间用户只看/操作一个 tab。非 active 的编辑器先进入
 *    waiting 表，待用户切换到该 tab（selectionChanged）时才入队——避免项目恢复时一次性处理
 *    几十个历史 tab。全局串行队列保证同一时间只做一份 PSI 遍历，不争抢线程池。
 * 2. 索引未完成（dumb mode）时延迟到 smart mode，避免冷启动阻塞项目打开。
 * 3. 处理前再次检查 editor 是否已释放 / 是否进入 dumb mode，跳过无效任务。
 * 4. 文件类型由策略的 [com.pan.extractor.strategy.DetectionStrategy.supportedFileSuffixes] 决定
 *    （Vue→.vue、Svelte→.svelte、Angular→.html），不再硬编码扩展名。
 */
class I18nFoldToggleInlayProvider : EditorFactoryListener, FileEditorManagerListener {

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(I18nFoldToggleInlayProvider::class.java)
        /** 全局串行化：同一时间只处理一个文件的 inlay */
        private val inlayBusy = AtomicBoolean(false)
        /** 记录 inlayBusy 被占用的时刻，用于看门狗检测"忙标志被异常遗留导致的永久死锁" */
        private val busySinceMillis = AtomicLong(0L)
        /** 忙标志允许的最大持有时间；超过则视为异常遗留，强制复位避免 inlay 从此不再显示 */
        private const val BUSY_WATCHDOG_MS = 64_000L
        /** 待处理队列（当前 active 的编辑器） */
        private val pendingInlays = ConcurrentLinkedQueue<InlayTask>()
        /** 已处理过 inlay 的编辑器实例：同一编辑器只处理一次，避免 editorCreated /
         *  selectionChanged 重复扫描。按编辑器（而非文件 url）去重，且 editorReleased 时移除：
         *  关闭再重开是全新编辑器 → 会重新处理；编辑器关闭时条目即时回收避免泄漏 */
        private val processedEditors = ConcurrentHashMap.newKeySet<Editor>()

        /** 编辑器文档变化后防抖重新调度 inlay 的定时任务。键为编辑器实例，
         *  编辑器关闭时自动取消。防抖间隔 [DEBOUNCE_MS] 内连续修改只触发一次。 */
        private val debounceFutures = ConcurrentHashMap<Editor, ScheduledFuture<*>>()
        /** 防抖间隔（毫秒）：用户停止输入后等待此时间再重新计算 inlay。 */
        private const val DEBOUNCE_MS = 500L

        private data class InlayTask(val editor: Editor, val file: PsiFile, val messages: Map<String, String>)
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return

        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@invokeLater

            // 文件类型交给策略判断：当前文件命中哪套框架，就用它声明的受支持后缀做快速 gate。
            val fw = I18nFrameworkRegistry.detect(file)
            val lower = file.name.lowercase()
            if (fw.supportedFileSuffixes.none { lower.endsWith(it) }) return@invokeLater

            val messages = LocaleMessages.loadCached(project, file)
            if (messages.isEmpty()) return@invokeLater

            // 点击 inlay 时切换折叠状态；点击折叠文本时跳转到翻译文件
            editor.addEditorMouseListener(object : EditorMouseListener {
                override fun mouseClicked(e: EditorMouseEvent) {
                    if (e.mouseEvent.button != MouseEvent.BUTTON1) return
                    val clickOffset = editor.logicalPositionToOffset(
                        editor.xyToLogicalPosition(e.mouseEvent.point)
                    )
                    val clickedInlay = editor.inlayModel.getInlineElementsInRange(
                        clickOffset, clickOffset
                    ).firstOrNull { it.renderer is I18nFoldToggleRenderer }

                    if (clickedInlay != null) {
                        // 点击 inlay（↩ 图标）→ 切换折叠/展开
                        val offset = clickedInlay.offset
                        val fm = editor.foldingModel
                        fm.runBatchFoldingOperation {
                            val fold = fm.allFoldRegions
                                .filter { it.startOffset <= offset && offset <= it.endOffset }
                                .minByOrNull { it.endOffset - it.startOffset }
                            if (fold != null) {
                                fold.isExpanded = !fold.isExpanded
                            }
                        }
                    } else {
                        // 点击折叠文本（翻译译文）→ 跳转到翻译文件
                        val fm = editor.foldingModel
                        val collapsedRegion = fm.allFoldRegions.firstOrNull { fold ->
                            !fold.isExpanded && fold.startOffset <= clickOffset && clickOffset <= fold.endOffset
                        }
                        if (collapsedRegion != null && !project.isDisposed) {
                            // 查找该折叠区域对应的翻译 key
                            val key = findKeyForFoldRegion(project, editor, collapsedRegion)
                            if (key != null) {
                                e.mouseEvent.consume()
                                navigateToKey(project, file, key)
                            }
                        }
                    }
                }
            })

            // 文档变化监听（防抖）：用户修改文本后只重新计算编辑偏移附近的翻译调用
            // 的 inlay，避免扫描整个文件。连续修改只在停止输入 [DEBOUNCE_MS] 后触发一次。
            @Suppress("DEPRECATION")
            editor.document.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    scheduleDebouncedRecompute(editor, project, file, messages, event.offset)
                }
            })

            // 立即入队处理（每个文件只处理一次）。此前"先入 waiting 表、等切 tab 再入队"的
            // 延迟交接在后台恢复 tab 时可能因 selectionChanged 未触发而静默丢失，导致 inlay 永不出现。
            enqueueInlay(editor, project, file, messages)
        }
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val project = event.manager.project
        val editor = event.manager.selectedTextEditor ?: return
        if (editor.isDisposed || project.isDisposed) return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val fw = I18nFrameworkRegistry.detect(file)
        val lower = file.name.lowercase()
        if (fw.supportedFileSuffixes.none { lower.endsWith(it) }) return
        val messages = LocaleMessages.loadCached(project, file)
        if (messages.isEmpty()) return
        enqueueInlay(editor, project, file, messages)
    }

    /**
     * 将编辑器加入 inlay 处理队列。
     * 若项目仍在索引（dumb mode），延迟到 smart mode 后再入队。
     */
    private fun enqueueInlay(editor: Editor, project: Project, file: PsiFile, messages: Map<String, String>) {
        if (editor.isDisposed || project.isDisposed) return

        // 索引未完成时延迟到 smart mode，避免冷启动阻塞项目打开。
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).runWhenSmart {
                if (!editor.isDisposed && !project.isDisposed) {
                    enqueueInlay(editor, project, file, messages)
                }
            }
            return
        }

        // 同一编辑器只处理一次（本例 editorCreated/selectionChanged 都会进入这里）。
        // 关闭再重开 = 新编辑器实例 → 未在集合中 → 会重新处理并重挂 inlay。
        if (!processedEditors.add(editor)) return

        pendingInlays.add(InlayTask(editor, file, messages))
        drainInlayQueue(project)
    }

    /** 编辑器关闭：回收其处理标记与防抖任务，确保该文件重开后能重新挂上 inlay。 */
    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        processedEditors.remove(editor)
        debounceFutures.remove(editor)?.cancel(false)
    }

    /**
     * 文档变化后防抖：只重新计算编辑偏移附近的翻译调用 inlay，不扫描整个文件。
     * 连续修改只在用户停止输入 [DEBOUNCE_MS] 后触发一次。
     */
    private fun scheduleDebouncedRecompute(editor: Editor, project: Project, file: PsiFile, messages: Map<String, String>, changeOffset: Int) {
        debounceFutures.remove(editor)?.cancel(false)
        val future = AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (editor.isDisposed || project.isDisposed) return@schedule
            updateToggleInlayAtOffset(editor, project, file, messages, changeOffset)
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        debounceFutures[editor] = future
    }

    /**
     * 局部更新：在 ReadAction 中查找编辑偏移处的 [JSCallExpression]，若命中且 key 在
     * 翻译文案中则添加/更新 inlay；若不再是有效翻译调用则移除该位置的 inlay。
     *
     * 不扫描整个文件，只检查编辑点附近的单个调用。
     */
    private fun updateToggleInlayAtOffset(editor: Editor, project: Project, file: PsiFile, messages: Map<String, String>, offset: Int) {
        if (editor.isDisposed || project.isDisposed) return

        ReadAction.nonBlocking<Unit> {
            if (editor.isDisposed || project.isDisposed || DumbService.isDumb(project)) return@nonBlocking

            // 查找编辑偏移处的 JSCallExpression（.ts/.tsx/.js/.jsx 宿主树调用）
            val call = PsiTreeUtil.findElementOfClassAtOffset(
                file, offset, JSCallExpression::class.java, false
            )
            if (call != null) {
                val fw = I18nFrameworkRegistry.detect(call)
                val isTranslation = fw.isTranslationCall(call)
                val key = if (isTranslation) fw.extractKey(call) else null
                val callEnd = call.textRange.endOffset

                // EDT：移除旧 inlay，若 key 仍有效则添加新 inlay
                ApplicationManager.getApplication().invokeLater {
                    if (editor.isDisposed) return@invokeLater
                    removeToggleInlaysAtOffset(editor, callEnd)
                    if (key != null && key in messages) {
                        editor.inlayModel.addInlineElement(callEnd, false, I18nFoldToggleRenderer(editor, key, project))
                    }
                }
            }
            // 未找到 JSCallExpression → 不做任何事（旧 inlay 会在编辑器重开时被清理）
        }
            .inSmartMode(project)
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /** 移除编辑器中指定偏移处的 [I18nFoldToggleRenderer] 类型 inlay。 */
    private fun removeToggleInlaysAtOffset(editor: Editor, offset: Int) {
        for (inlay in editor.inlayModel.getInlineElementsInRange(offset, offset)) {
            if (inlay.renderer is I18nFoldToggleRenderer) {
                inlay.dispose()
            }
        }
    }

    /**
     * 串行排空队列：同一时间只有一个文件在处理。
     * 通过 [inlayBusy] CAS 保证无并发，处理完一个后自动取下一个。
     */
    private fun drainInlayQueue(project: Project) {
        // 看门狗：若忙标志被异常遗留（旧版本崩溃曾导致此状态），超过阈值后强制复位，
        // 避免队列从此永久被跳过、inlay 不再显示。
        val since = busySinceMillis.get()
        if (since != 0L && System.currentTimeMillis() - since > BUSY_WATCHDOG_MS) {
            LOG.warn("I18nFoldToggleInlay: inlayBusy 超过 ${BUSY_WATCHDOG_MS}ms 未释放，强制复位")
            inlayBusy.set(false)
            busySinceMillis.set(0L)
        }
        if (!inlayBusy.compareAndSet(false, true)) return
        busySinceMillis.set(System.currentTimeMillis())

        val task = pendingInlays.poll()
        if (task == null) {
            inlayBusy.set(false)
            return
        }

        // 跳过已释放的 editor，继续处理下一个
        if (task.editor.isDisposed) {
            inlayBusy.set(false)
            if (pendingInlays.isNotEmpty()) drainInlayQueue(project)
            return
        }
        if (project.isDisposed) {
            inlayBusy.set(false)
            return
        }

        // ReadAction.nonBlocking 在后台池线程上以「可取消 read action + coroutine Job」语义执行，
        // 提供 TS resolve 引擎（JSGraphBuildExecutor.runBlockingCancellable）所需的 Job/进度上下文。
        // 此前的 executeOnPooledThread + runProcess(EmptyProgressIndicator) 只注入进度指示器、
        // 不安装 coroutine Job；无该 Job 时 runBlockingCancellable 抛
        // "There is no ProgressIndicator or Job in this thread"，导致折叠与 inlay 全部失效。
        ReadAction.nonBlocking<Unit> {
            if (!task.editor.isDisposed && !project.isDisposed && !DumbService.isDumb(project)) {
                addFoldToggleInlaysUnderProgress(task.editor, task.file, task.messages)
            }
        }
            .inSmartMode(project)
            .submit(AppExecutorUtil.getAppExecutorService())
            .onSuccess { finishInlayTask(project) }
            .onError(java.util.function.Consumer<Throwable> { t ->
                LOG.warn("Inlay 处理异常: ${task.file.name}", t)
                finishInlayTask(project)
            })
    }

    /** 释放"正在处理"占用位并继续排空队列。 */
    private fun finishInlayTask(project: Project) {
        inlayBusy.set(false)
        busySinceMillis.set(0L)
        if (pendingInlays.isNotEmpty()) {
            drainInlayQueue(project)
        }
    }

    private fun addFoldToggleInlaysUnderProgress(editor: Editor, file: PsiFile, messages: Map<String, String>) {
        val t0 = System.nanoTime()
        val fileSize = ApplicationManager.getApplication().runReadAction<Int> { file.textLength }
        val fw = I18nFrameworkRegistry.detect(file)
        LOG.info("I18nFoldToggleInlay[运行] file=${file.name}")

        data class InlayTarget(val offset: Int, val key: String)

        val targets = ApplicationManager.getApplication().runReadAction<MutableList<InlayTarget>> {
            val list = mutableListOf<InlayTarget>()
            var translationCallCount = 0
            val callStarts = mutableSetOf<Int>()
            // 宿主树的 JSCallExpression（`<script>` 块 / 属性绑定 / .ts/.tsx）坐标即宿主文档坐标，
            // 直接可用于 inlay。Vue mustache `{{ $t(...) }}` 是注入表达式，注入坐标并不可靠，
            // 统一交由下方 collectRawTCalls 以宿主原始文本兜底（见函数注释）。
            collectJSCallExpressions(file).forEach { call ->
                callStarts.add(call.textRange.startOffset)
                // 不再跳过反引号调用：宿主树里的 `t(\`key\`)`（React 属性/JSX 表达式、Vue
                // <script> 块等）坐标可靠，应直接生成 inlay；Vue mustache 注入式 `$t(\`)`
                // 本就不在宿主树里，由下方 collectRawTCalls 兜底，且按 callStarts 去重不叠加。
                if (!fw.isTranslationCall(call)) return@forEach
                translationCallCount++
                val key = fw.extractKey(call) ?: return@forEach
                if (key !in messages) return@forEach
                list.add(InlayTarget(call.textRange.endOffset, key))
            }
            // 模板表达式（mustache / 属性）里的 $t('x') 调用按宿主原始文本兜底加 inlay，
            // 使折叠切换标识落到这些调用上。与 JSCall 按起始偏移去重，避免重复叠加。
            collectRawTCalls(file).forEach { raw ->
                if (raw.range.startOffset in callStarts) return@forEach
                if (raw.key !in messages) return@forEach
                list.add(InlayTarget(raw.range.endOffset, raw.key))
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            LOG.info("I18nFoldToggleInlay[目标] file=${file.name} targets=${list.size} translationCalls=$translationCallCount size=${fileSize}B elapsed=${elapsedMs}ms")
            list
        }

        if (targets.isEmpty()) {
            LOG.info("I18nFoldToggleInlay[无目标] file=${file.name} —— 未生成任何切换 inlay")
            return
        }

        // EDT：写入 inlay（仅在 UI 线程安全）
        ApplicationManager.getApplication().invokeLater({
            if (editor.isDisposed) return@invokeLater
            val inlayModel = editor.inlayModel
            var added = 0
            for (t in targets) {
                // relatesToPrecedingText=false：让 ↩ inlay 挂在折叠结束边界之后的文本上，
                // 否则折进 [call.start, call.end] 折叠区后随折叠一起隐藏，导致折叠后无法再通过该 inlay 展开。
                val project = editor.project ?: continue
                if (inlayModel.addInlineElement(t.offset, false, I18nFoldToggleRenderer(editor, t.key, project)) != null) added++
            }
            LOG.info("I18nFoldToggleInlay[已加] file=${file.name} added=$added")
        })
    }

    /** Ctrl+点击 inlay 时跳转到翻译文件中该 key 所在的行。 */
    private fun navigateToKey(project: Project, contextPsiFile: PsiFile, key: String) {
        try {
            val entryFile = com.pan.extractor.locate.EntryFileLocator.findChineseLocaleEntryFile(project, contextPsiFile)
                ?: return
            val psiFile = PsiManager.getInstance(project).findFile(entryFile) ?: return
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: return

            // 在翻译文件中定位 key 所在行（支持 'key' / "key" / `key` 三种引号风格）
            val escapedKey = key.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
            val patterns = listOf("'$escapedKey'", "\"$escapedKey\"", "`$escapedKey`")
            val lineIdx = doc.text.lines().indexOfFirst { line ->
                patterns.any { it in line }
            }
            val offset = if (lineIdx >= 0) doc.getLineStartOffset(lineIdx) else 0
            OpenFileDescriptor(project, entryFile, offset).navigate(true)
        } catch (e: Exception) {
            LOG.warn("导航到翻译文件失败: key=$key", e)
        }
    }

    /**
     * 从折叠区域查找关联的翻译 key。
     * 优先通过 [PsiTreeUtil.findElementOfClassAtOffset] 在编辑偏移处查找
     * [JSCallExpression] 并提取 key（宿主树调用）；未命中时回退到
     * [collectRawTCalls] 按起始偏移匹配（Vue 模板注入调用）。
     */
    private fun findKeyForFoldRegion(project: Project, editor: Editor, foldRegion: FoldRegion): String? {
        if (project.isDisposed) return null
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return null
        val fw = I18nFrameworkRegistry.detect(file)

        // 尝试从宿主树 JSCallExpression 提取 key
        val call = ReadAction.compute<JSCallExpression?, Exception> {
            if (project.isDisposed) return@compute null
            PsiTreeUtil.findElementOfClassAtOffset(file, foldRegion.startOffset, JSCallExpression::class.java, false)
        }
        if (call != null && fw.isTranslationCall(call)) {
            return fw.extractKey(call)
        }

        // 回退：从原始文本调用匹配（Vue 模板）
        val rawCalls = collectRawTCalls(file)
        return rawCalls.firstOrNull { it.range.startOffset == foldRegion.startOffset }?.key
    }

}

/**
 * 文件级集合缓存结果：缓存 [collectJSCallExpressions] 和 [collectRawTCalls] 的输出，
 * 供折叠和 inlay 共用，避免重复 PSI 遍历和正则扫描。
 */
internal data class CachedCollectionResult(
    val jsCalls: List<JSCallExpression>,
    val rawTCalls: List<RawTCall>,
    /** jsCalls 是否已计算（无计算时不可让另一方误用空列表） */
    val jsCallsComputed: Boolean = false,
    /** rawTCalls 是否已计算 */
    val rawTCallsComputed: Boolean = false,
    /** 缓存时的文件内容版本戳，用于检测文件修改后缓存失效 */
    val modificationStamp: Long = 0L,
)

/**
 * 文件级集合缓存：键为 [PsiFile] 弱引用，[PsiFile] 被 GC 回收时自动清理。
 * 折叠和 inlay 共用此缓存，任一先计算完后另一方可直接复用，将每个文件的 PSI 遍历
 * 和正则扫描从 2 次降为 1 次，大文件（数千行 TSX/Vue）显著减少重复开销。
 *
 * 线程安全：通过 [Collections.synchronizedMap] 保证 get/put 原子性。
 * 正确性不依赖缓存命中：即使因时序问题双方同时计算，结果也是正确的，仅浪费一次计算。
 */
internal val collectionCache = Collections.synchronizedMap(WeakHashMap<PsiFile, CachedCollectionResult>())

/**
 * 收集 [root] 所在宿主文档树中的 [JSCallExpression]（仅宿主树，不含注入片段）。
 *
 * 注意：Vue 模板表达式 `{{ $t(...) }}` / 属性绑定 `:x="$t(...)"` 是**注入语言**，注入出的
 * [JSCallExpression].textRange 是**注入文件坐标**——若直接当作宿主文档坐标套用会错位折叠、
 * 打断正常文字（历史上正是如此出现「2 个折叠预览、点开才恢复」）。因此模板/属性里的 `$t()`
 * 调用**不**在此收集，统一交给 [collectRawTCalls] 以注入宿主原始文本正则兜底（宿主绝对坐标）。
 * 此函数只收集真正属于宿主文档树的调用（普通 .ts/.tsx/.js/.jsx、Vue `<script>` 块），
 * 其 textRange 即宿主坐标、node 即宿主树节点，也才能作为折叠描述符的锚点。
 */
internal fun collectJSCallExpressions(root: PsiElement): List<JSCallExpression> {
    // 缓存命中：仅当 jsCalls 已标记为计算完成且文件版本戳未变时才复用，
    // 避免文件修改后同一 PsiFile 实例返回过期数据。
    if (root is PsiFile) {
        val cached = collectionCache[root]
        if (cached != null && cached.jsCallsComputed && cached.modificationStamp == root.modificationStamp) return cached.jsCalls
    }
    val result = PsiTreeUtil.collectElementsOfType(root, JSCallExpression::class.java).toList()
    // 写入缓存：PSI 文件级共享，供折叠/inlay 另一方复用
    if (root is PsiFile) {
        val existing = collectionCache[root]
        collectionCache[root] = CachedCollectionResult(
            jsCalls = result,
            rawTCalls = existing?.rawTCalls ?: emptyList(),
            jsCallsComputed = true,
            rawTCallsComputed = existing?.rawTCallsComputed ?: false,
            modificationStamp = root.modificationStamp,
        )
    }
    return result
}

/**
 * 模板表达式中的翻译调用（`$t('x')` / `$t("x")` / `$t(\`x\`)`）在 Vue 里是注入语言，
 * 注入出的 [JSCallExpression] 的 textRange 是**注入文件**坐标，直接套用到宿主文档会产生
 * 错位折叠（打断正常文字）。因此一律按注入宿主原始文本正则兜底，产出**宿主文档绝对坐标**
 * 的折叠 / inlay 锚点。仅命中翻译资源中存在的 key。
 */
internal val RAW_T_CALL_PATTERN = Regex(
    "(?:\\$(?:t|tc)|i18n\\.global\\.(?:t|tc)|i18n\\.(?:t|tc))" +
        "\\(\\s*(?:`([^`]*)`|'([^']*)'|\"([^\"]*)\")\\s*[,)]"
)

/** 反引号/单引号/双引号 `$t()` 原始文本调用：key + 所在宿主元素 + 文档绝对偏移范围 + 完整调用文本。 */
internal class RawTCall(
    val key: String,
    val element: PsiElement,
    val range: TextRange,
    val text: String,
) {
    companion object {
        /** 从正则匹配中提取 key（三个引号风格三选一，均已去引号）。 */
        fun keyOf(m: MatchResult): String? =
            m.groupValues[1].takeIf { it.isNotEmpty() }
                ?: m.groupValues[2].takeIf { it.isNotEmpty() }
                ?: m.groupValues[3].takeIf { it.isNotEmpty() }
    }
}

/** 匹配翻译调用起始：`$t(` / `$tc(` / `i18n.global.t(` / `i18n.t(`（含开始的 `(`）。 */
internal val RAW_T_CALL_OPENER_PATTERN = Regex(
    "(?:\\$(?:t|tc)|i18n\\.global\\.(?:t|tc)|i18n\\.(?:t|tc))\\s*\\("
)

/**
 * 在顶层文件所有注入宿主的原始文本中找 `$t('..')` / `$t("..")` / `$t(\`..\`)` 调用，
 * 读取完整调用（含参数，括号平衡），产出**宿主文档绝对坐标**的折叠 / inlay 锚点（需在 read action 内调用）。
 */
internal fun collectRawTCalls(file: PsiFile): List<RawTCall> {
    // 缓存命中：仅当 rawTCalls 已标记为计算完成且文件版本戳未变时才复用，
    // 避免文件修改后同一 PsiFile 实例返回过期数据。
    val cached = collectionCache[file]
    if (cached != null && cached.rawTCallsComputed && cached.modificationStamp == file.modificationStamp) return cached.rawTCalls
    val result = linkedMapOf<Int, RawTCall>() // startOffset -> call，天然去重
    PsiTreeUtil.collectElementsOfType(file, PsiLanguageInjectionHost::class.java).forEach { host ->
        val hostText = host.text
        val base = host.textRange.startOffset
        RAW_T_CALL_OPENER_PATTERN.findAll(hostText).forEach { om ->
            val parenIdx = om.range.last // 即 `(` 的下标
            val end = matchingParenEnd(hostText, parenIdx) ?: return@forEach
            val callText = hostText.substring(om.range.first, end)
            val key = RawTCall.keyOf(RAW_T_CALL_PATTERN.find(callText) ?: return@forEach) ?: return@forEach
            val start = base + om.range.first
            result[start] = RawTCall(key, host, TextRange(start, base + end), callText)
        }
    }
    val list = result.values.toList()
    // 写入缓存
    val existing = collectionCache[file]
    collectionCache[file] = CachedCollectionResult(
        jsCalls = existing?.jsCalls ?: emptyList(),
        rawTCalls = list,
        jsCallsComputed = existing?.jsCallsComputed ?: false,
        rawTCallsComputed = true,
        modificationStamp = file.modificationStamp,
    )
    return list
}

/** 自 [openIdx]（`(` 所在下标）向后找括号平衡的结束位置（返回开区间 end，即 `)` 后一位）。 */
private fun matchingParenEnd(s: String, openIdx: Int): Int? {
    var depth = 0
    for (i in openIdx until s.length) {
        when (s[i]) {
            '(' -> depth++
            ')' -> {
                depth--
                if (depth == 0) return i + 1
            }
        }
    }
    return null
}

/**
 * 从模板原始调用文本 `$t('key', { ... })` 中提取插值参数（Vue `{N0}` / React `{0}` 的替换值）。
 * 与 [I18nFoldingBuilder] 基于 JSCallExpression 的参数提取逻辑等价，但工作在宿主原始文本上：
 *  - 字面量： `N0: '搜索关键词'` / `"0": 2`
 *  - 内层翻译调用：`N0: $t('搜索关键词')` —— 用内层 key 的译文作为参数值。
 * 参数键统一去前缀 `N`。仅用于 [I18nFoldingBuilder.addRawFolds] 的模板兜底折叠。
 */
internal fun extractParamsFromText(rawText: String, messages: Map<String, String>): Map<String, String> {
    if (!rawText.contains(',')) return emptyMap()
    val result = mutableMapOf<String, String>()
    val re = Regex("""["']?(\w+)["']?\s*:\s*("[^"]*"|'[^']*'|-?\d+)""")
    re.findAll(rawText).forEach { match ->
        val key = match.groupValues[1].removePrefix("N")
        val rawValue = match.groupValues[2]
        val value = if (rawValue.startsWith("\"") || rawValue.startsWith("'"))
            rawValue.substring(1, rawValue.length - 1)
        else rawValue
        result[key] = value
    }
    val tRe = Regex("""["']?(\w+)["']?\s*:\s*(?:[\w.]+\s*\.\s*\$?t|\$?t)\s*\(\s*['"]([^'"]+)['"]\s*\)""")
    tRe.findAll(rawText).forEach { match ->
        val key = match.groupValues[1].removePrefix("N")
        val innerKey = match.groupValues[2]
        result[key] = messages[innerKey] ?: innerKey
    }
    return result
}

/** 在 t() 调用末尾渲染一个灰色 ↩ 符号，点击可折叠/展开。Ctrl+click 跳转到翻译文件。 */
class I18nFoldToggleRenderer(
    private val editor: Editor,
    /** 关联的翻译 key，用于 Ctrl+click 导航到翻译文件。null 表示不支持导航。 */
    val key: String? = null,
    private val project: Project? = null,
) : com.intellij.openapi.editor.EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int = 20

    override fun paint(
        inlay: Inlay<*>,
        g: Graphics,
        targetRegion: Rectangle,
        textAttributes: com.intellij.openapi.editor.markup.TextAttributes,
    ) {
        g.color = JBColor.GRAY
        g.font = g.font.deriveFont(12f)
        val fm = g.getFontMetrics(g.font)
        val charWidth = fm.charWidth('\u21A9')
        val x = targetRegion.x + (targetRegion.width - charWidth) / 2
        g.drawString("\u21A9", x, targetRegion.y + fm.ascent)
    }
}