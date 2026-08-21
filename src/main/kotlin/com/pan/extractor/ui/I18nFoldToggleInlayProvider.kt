package com.pan.extractor.ui

import com.pan.extractor.strategy.I18nFrameworkRegistry
import com.pan.extractor.messages.LocaleMessages
import com.pan.extractor.*
import com.pan.extractor.analyzer.*

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
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 在 t()/`$t()` 调用前添加可点击的 ↩ inlay 提示，用于快速折叠/展开切换。
 * 注册为 [EditorFactoryListener]，在编辑器打开时自动添加。
 *
 * 性能策略：
 * 1. 索引未完成（dumb mode）时延迟处理，避免冷启动阻塞项目打开；
 * 2. 全局串行队列，同一时间只处理一个文件，避免多文件并发 PSI resolve 争抢线程池；
 * 3. 处理前再次检查 editor 是否已释放 / 是否进入 dumb mode，跳过无效任务。
 */
class I18nFoldToggleInlayProvider : EditorFactoryListener {

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(I18nFoldToggleInlayProvider::class.java)
        /** 全局串行化：同一时间只处理一个文件的 inlay */
        private val inlayBusy = AtomicBoolean(false)
        /** 待处理队列 */
        private val pendingInlays = ConcurrentLinkedQueue<InlayTask>()

        private data class InlayTask(val editor: Editor, val file: PsiFile, val messages: Map<String, String>)
    }

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return

        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@invokeLater
            if (!isI18nFile(file.name)) return@invokeLater

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

            // 翻译调用收集 + inlay 添加：入队后串行处理
            enqueueInlay(editor, project, file, messages)
        }
    }

    private fun isI18nFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".ts") || lower.endsWith(".tsx") ||
            lower.endsWith(".js") || lower.endsWith(".jsx") ||
            lower.endsWith(".vue")
    }

    /**
     * 将编辑器加入 inlay 处理队列。
     * 若项目仍在索引（dumb mode），延迟到 smart mode 后再入队。
     */
    private fun enqueueInlay(editor: Editor, project: Project, file: PsiFile, messages: Map<String, String>) {
        if (editor.isDisposed || project.isDisposed) return

        // 索引未完成时延迟，避免冷启动阻塞项目打开
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
            PsiTreeUtil.collectElementsOfType(file, JSCallExpression::class.java).forEach { call ->
                if (!fw.isTranslationCall(call)) return@forEach
                translationCallCount++
                val key = fw.extractKey(call) ?: return@forEach
                if (key !in messages) return@forEach
                list.add(InlayTarget(call.textRange.endOffset))
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
