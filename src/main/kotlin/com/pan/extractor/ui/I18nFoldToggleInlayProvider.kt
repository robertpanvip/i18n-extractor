package com.pan.extractor.ui

import com.pan.extractor.*
import com.pan.extractor.analyzer.*

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.MouseEvent

/**
 * 在 t()/`$t()` 调用前添加可点击的 ↩ inlay 提示，用于快速折叠/展开切换。
 * 注册为 [EditorFactoryListener]，在编辑器打开时自动添加。
 */
class I18nFoldToggleInlayProvider : EditorFactoryListener {

    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        val project = editor.project ?: return

        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@invokeLater
            if (!isI18nFile(file.name)) return@invokeLater

            val messages = LocaleMessages.loadCached(project, file)
            if (messages.isEmpty()) return@invokeLater

            addFoldToggleInlays(editor, file, messages)

            // 点击 inlay 时切换折叠状态
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
                            // 取覆盖 offset 的最小折叠区域，避免误匹配到外层函数折叠
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
        }
    }

    private fun isI18nFile(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".ts") || lower.endsWith(".tsx") ||
            lower.endsWith(".js") || lower.endsWith(".jsx") ||
            lower.endsWith(".vue")
    }

    private fun addFoldToggleInlays(editor: Editor, file: PsiFile, messages: Map<String, String>) {
        val inlayModel = editor.inlayModel
        val fw = I18nFrameworkRegistry.detect(file)

        PsiTreeUtil.collectElementsOfType(file, JSCallExpression::class.java).forEach { call ->
            if (!fw.isTranslationCall(call)) return@forEach

            val key = fw.extractKey(call) ?: return@forEach
            if (key !in messages) return@forEach

            // 在 t() 调用末尾添加可点击的 ↩ inlay
            val offset = call.textRange.endOffset
            inlayModel.addInlineElement(offset, true, I18nFoldToggleRenderer(editor))
        }
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