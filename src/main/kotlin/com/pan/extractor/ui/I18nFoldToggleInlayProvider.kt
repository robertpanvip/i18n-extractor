package com.pan.extractor.ui

import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.messages.LocaleMessages
import com.pan.extractor.*
import com.pan.extractor.analyzer.*

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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
        /** 待处理队列（当前 active 的编辑器） */
        private val pendingInlays = ConcurrentLinkedQueue<InlayTask>()
        /** 尚未被选中的编辑器任务：project -> editor(身份) -> task，切 tab 时取用 */
        private val waitingInlays =
            ConcurrentHashMap<Project, ConcurrentHashMap<Editor, InlayTask>>()

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

            // 点击 inlay 时切换折叠状态（鼠标监听器轻量，保留在 EDT）
            editor.addEditorMouseListener(object : EditorMouseListener {
                override fun mouseClicked(e: EditorMouseEvent) {
                    if (e.mouseEvent.button != MouseEvent.BUTTON1) return
                    val clickedInlay = editor.inlayModel.getInlineElementsInRange(
                        editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.mouseEvent.point)),
                        editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.mouseEvent.point))
                    ).firstOrNull { it.renderer is I18nFoldToggleRenderer }
                    if (clickedInlay != null) {
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
                    }
                }
            })

            // 只在当前 active 编辑器上立即处理；非 active 的等切到它时再处理。
            val isSelected = FileEditorManager.getInstance(project).selectedTextEditor?.let { it === editor } == true
            if (isSelected) {
                enqueueInlay(editor, project, file, messages)
            } else {
                waitingInlays.computeIfAbsent(project) { ConcurrentHashMap() }[editor] =
                    InlayTask(editor, file, messages)
            }
        }
    }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val project = event.manager.project
        val w = waitingInlays[project] ?: return
        val editor = event.manager.selectedTextEditor ?: return
        val task = w.remove(editor) ?: return
        if (editor.isDisposed || project.isDisposed) return
        enqueueInlay(editor, project, task.file, task.messages)
    }

    /**
     * 将编辑器加入 inlay 处理队列。
     * 若项目仍在索引（dumb mode），延迟到 smart mode 后再入队。
     */
    private fun enqueueInlay(editor: Editor, project: Project, file: PsiFile, messages: Map<String, String>) {
        if (editor.isDisposed || project.isDisposed) return

        // 索引未完成时延迟，避免冷启动阻塞项目打开；顺带清理本项目已释放的 waiting 项。
        if (DumbService.isDumb(project)) {
            DumbService.getInstance(project).runWhenSmart {
                if (!editor.isDisposed && !project.isDisposed) {
                    enqueueInlay(editor, project, file, messages)
                }
            }
            return
        }

        pendingInlays.add(InlayTask(editor, file, messages))
        drainInlayQueue(project)
    }

    /**
     * 串行排空队列：同一时间只有一个文件在处理。
     * 通过 [inlayBusy] CAS 保证无并发，处理完一个后自动取下一个。
     */
    private fun drainInlayQueue(project: Project) {
        if (!inlayBusy.compareAndSet(false, true)) return

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

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (!task.editor.isDisposed && !project.isDisposed && !DumbService.isDumb(project)) {
                    addFoldToggleInlaysAsync(task.editor, task.file, task.messages)
                }
            } catch (t: Throwable) {
                LOG.warn("Inlay 处理异常: ${task.file.name}", t)
            } finally {
                inlayBusy.set(false)
                if (pendingInlays.isNotEmpty()) {
                    drainInlayQueue(project)
                }
            }
        }
    }

    /**
     * 后台线程：遍历 PSI 收集翻译调用，完成后 EDT 写入 inlay。
     * 将耗时的 PSI 遍历 + Symbol 解析从 UI 线程剥离，避免打开大文件时卡顿。
     * PSI 访问必须在 read action 内执行。
     */
    private fun addFoldToggleInlaysAsync(editor: Editor, file: PsiFile, messages: Map<String, String>) {
        // 后台池线程默认没有 Job / ProgressIndicator 线程上下文；TS resolve 引擎里的
        // runBlockingCancellable 会因缺少进度上下文抛 IllegalStateException。这里用
        // EmptyProgressIndicator 注入进度上下文，保证整个 PSI 遍历 + resolve 过程可取消。
        ProgressManager.getInstance().runProcess({
            addFoldToggleInlaysUnderProgress(editor, file, messages)
        }, EmptyProgressIndicator())
    }

    private fun addFoldToggleInlaysUnderProgress(editor: Editor, file: PsiFile, messages: Map<String, String>) {
        val t0 = System.nanoTime()
        val fileSize = ApplicationManager.getApplication().runReadAction<Int> { file.textLength }
        val fw = I18nFrameworkRegistry.detect(file)

        data class InlayTarget(val offset: Int)

        val targets = ApplicationManager.getApplication().runReadAction<MutableList<InlayTarget>> {
            val list = mutableListOf<InlayTarget>()
            var translationCallCount = 0
            val callStarts = mutableSetOf<Int>()
            // Vue({{ }} / :绑定 注入) / Svelte / Angular 的模板表达式是注入语言，
            // 仅在宿主树 collectElementsOfType 扫不到，需把注入片段也纳入。
            collectJSCallExpressionsInjected(file).forEach { call ->
                callStarts.add(call.textRange.startOffset)
                // 反引号 key 调用（{{ $t(`..`) }}）坐标不可靠，inlay 交由下方
                // collectRawBacktickTCalls 兜底，避免与兜底 inlay 重复叠加。
                if (isBacktickKeyCall(call)) return@forEach
                if (!fw.isTranslationCall(call)) return@forEach
                translationCallCount++
                val key = fw.extractKey(call) ?: return@forEach
                if (key !in messages) return@forEach
                list.add(InlayTarget(call.textRange.endOffset))
            }
            // 反引号 mustache（{{ $t(`..`) }}）注入 PSI 无 JSCallExpression，按原始文本兜底加 inlay，
            // 使折叠切换标识也能落到这类调用上。与 JSCall 按起始偏移去重，避免重复叠加。
            collectRawBacktickTCalls(file).forEach { raw ->
                if (raw.range.startOffset in callStarts) return@forEach
                if (raw.key !in messages) return@forEach
                list.add(InlayTarget(raw.range.endOffset))
            }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val msg = "I18nFoldToggleInlay[打开] file=${file.name} size=${fileSize}B translationCalls=$translationCallCount elapsed=${elapsedMs}ms"
            if (elapsedMs >= 100) LOG.info(msg) else LOG.debug(msg)
            list
        }

        if (targets.isEmpty()) return

        // EDT：写入 inlay（仅在 UI 线程安全）
        ApplicationManager.getApplication().invokeLater({
            if (editor.isDisposed) return@invokeLater
            val inlayModel = editor.inlayModel
            for (t in targets) {
                inlayModel.addInlineElement(t.offset, true, I18nFoldToggleRenderer(editor))
            }
        })
    }

}

/**
 * 累计 [root] 及其全部注入片段中的 [JSCallExpression]，按起始偏移去重。
 *
 * Vue 的 mustache `{{ $t(...) }}` / 指令绑定 `:x="$t(...)"`、Svelte 的 `{$t(...)}`、
 * Angular 模板插值 `{{ 'k' | translate }}` 都是**注入语言**，在宿主 PSI 树上直接
 * `collectElementsOfType` 扫不到（普通 .ts/.tsx/.js/.jsx 无注入，退化为宿主树扫描）。
 * 折叠 / inlay 都靠它保证模板表达式里的翻译调用能被识别。
 *
 * 需在 read action 内调用。
 */
internal fun collectJSCallExpressionsInjected(root: PsiElement): List<JSCallExpression> {
    val result = linkedMapOf<Int, JSCallExpression>() // startOffset -> call，天然去重
    PsiTreeUtil.collectElementsOfType(root, JSCallExpression::class.java).forEach {
        result[it.textRange.startOffset] = it
    }
    val project = root.project ?: return result.values.toList()
    val inj = InjectedLanguageManager.getInstance(project)
    PsiTreeUtil.collectElementsOfType(root, PsiLanguageInjectionHost::class.java).forEach { host ->
        inj.getInjectedPsiFiles(host)?.forEach { pair ->
            PsiTreeUtil.collectElementsOfType(pair.first, JSCallExpression::class.java).forEach {
                result[it.textRange.startOffset] = it
            }
        }
    }
    return result.values.toList()
}

/**
 * Vue 模板反引号 key 的 mustache（`{{ $t(\`\u6a21\u578b\u81ea\u52a8\u5206\u6bb5\`) }}`）字符串：
 * Vue 对反引号表达式注入出的 PSI 可能**不含** [JSCallExpression]（见
 * [com.pan.extractor.strategy.VueI18nStrategy.collectExistingTKeysFromTemplate] 注释），导致
 * 宿主树 + 注入片段都扫不到该调用。这里按注入宿主原始文本正则兜底，产出
 * 文档绝对坐标的反引号 `$t()` 折叠 / inlay 锚点。仅匹配反引号开头，普通单/双引号
 * 调用已有 [JSCallExpression]（经 [collectJSCallExpressionsInjected]），避免重复叠加。
 */
internal val RAW_T_CALL_PATTERN =
    Regex("(?:\\$(?:t|tc)|i18n\\.global\\.(?:t|tc)|i18n\\.(?:t|tc))\\(\\s*(`)([^`]+)`\\s*[,)]")

/** 反引号 `$t()` 原始文本调用：key + 所在宿主元素 + 文档绝对偏移范围。 */
internal class RawTCall(
    val key: String,
    val element: PsiElement,
    val range: TextRange,
)

/**
 * 首参是否为无插值的反引号模板字符串（`$t(\`key\`)`）。是则命中坐标不可靠的反引号场景，
 * 折叠 / inlay 统一交由 [collectRawBacktickTCalls] 以宿主原始文本兜底，避免与兜底区域
 * 重复叠加。[I18nFoldingBuilder] 与 [I18nFoldToggleInlayProvider] 共用此判断。
 */
internal fun isBacktickKeyCall(call: JSCallExpression): Boolean {
    val first = call.arguments.firstOrNull() ?: return false
    if (first !is com.intellij.lang.javascript.psi.ecma6.JSStringTemplateExpression) return false
    val text = first.text
    if (text.length < 2) return false
    return text.first() == '`' && text.last() == '`' && !text.contains("\${")
}

/** 在顶层文件所有注入宿主的原始文本中找反引号 `$t(`..`)` 调用。需在 read action 内调用。 */
internal fun collectRawBacktickTCalls(file: PsiFile): List<RawTCall> {
    val result = linkedMapOf<Int, RawTCall>() // startOffset -> call，天然去重
    PsiTreeUtil.collectElementsOfType(file, PsiLanguageInjectionHost::class.java).forEach { host ->
        val base = host.textRange.startOffset
        RAW_T_CALL_PATTERN.findAll(host.text).forEach { m ->
            val start = base + m.range.first
            result[start] = RawTCall(m.groupValues[2], host, TextRange(start, base + m.range.last))
        }
    }
    return result.values.toList()
}

/** 在 t() 调用末尾渲染一个灰色 ↩ 符号，点击可折叠/展开。 */
class I18nFoldToggleRenderer(private val editor: Editor) : com.intellij.openapi.editor.EditorCustomElementRenderer {

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