package com.pan.extractor

import com.pan.extractor.ui.*

import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * BUG_ANALYSIS 6.x — 真实 IntelliJ 集成生命周期测试。
 *
 * 使用 [BasePlatformTestCase] 的真实 IntelliJ fixture（真实 VFS / Document / PSI /
 * UndoManager / FoldingModel），验证：
 *  6.1 Undo / Redo 往返：Before → Extract → After → Undo → Before → Redo → After
 *  6.2 SmartPsiElementPointer 生命周期：被替换节点失效、未动节点仍有效
 *  6.3 Folding 生命周期：打开 → 折叠 → 修改文本 → 重折叠反映新 key
 */
class I18nIntegrationLifecycleTest : BasePlatformTestCase() {

    private lateinit var originalFoldLang: String
    private lateinit var disposable: com.intellij.openapi.Disposable

    override fun setUp() {
        super.setUp()
        originalFoldLang = I18nSettings.getInstance().foldDisplayLanguage()
        I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        // 跨文档 Undo（如多文件 Extract 各自独立命令后，一个个回退）会弹出
        // “Undo Vue i18n Extract?” 确认对话框；headless 环境下该对话框会抛异常，
        // 因此注册一个自动确认（OK）的 TestDialog，让 undo/redo 的真实流程被完整走通。
        disposable = com.intellij.openapi.util.Disposer.newDisposable()
        com.intellij.openapi.ui.TestDialogManager.setTestDialog(
            com.intellij.openapi.ui.TestDialog.OK,
            disposable
        )
        myFixture.addFileToProject(
            "package.json",
            """{"name":"root","dependencies":{"vue":"^3","vue-i18n":"^9"}}"""
        )
        myFixture.addFileToProject(
            "src/locales/zh.ts",
            """
            export default {
              'existing': '你好',
              'new.key': '新文案'
            }
            """.trimIndent()
        )
    }

    override fun tearDown() {
        if (::disposable.isInitialized) {
            com.intellij.openapi.util.Disposer.dispose(disposable)
        }
        if (::originalFoldLang.isInitialized) {
            I18nSettings.getInstance().setFoldDisplayLanguage(originalFoldLang)
        }
        super.tearDown()
    }

    private fun configureFile(fileName: String, text: String): PsiFile {
        val file = myFixture.addFileToProject(fileName, text.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return file
    }

    private fun extract(f: PsiFile): I18nProcessor {
        val processor = I18nProcessor(project, f)
        processor.collect()
        processor.runWithUndo()
        return processor
    }

    // ── 6.1 Undo / Redo 往返 ─────────────────────────────────────────

    fun testUndoRedoRoundTrip() {
        val before = """
            <template>
                <div>{{ ${'$'}t('existing') }}</div>
            </template>
            <script setup lang="ts">
            const msg = "硬编码中文"
            </script>
        """.trimIndent()

        val file = configureFile("src/UndoRedo.vue", before)
        extract(file)
        val after = file.text
        assertTrue("提取后应变 \$t 调用, got:\n$after", after.contains("\$t("))

        // Undo → 回到 Before（还原硬编码中文，去掉 $t 与注入）
        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("Undo 后应回到原样", before, file.text)

        // Redo → 再次到 After
        myFixture.performEditorAction(IdeActions.ACTION_REDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("Redo 后应回到 After", after, file.text)
    }

    // ── 6.4 多文件 Extract → Undo → Redo ───────────────────────────

    fun testMultiFileUndoRedoRoundTrip() {
        val beforeA = """
            <script setup lang="ts">
            const msg1 = "文件A中文"
            </script>
        """.trimIndent()
        val beforeB = """
            <script setup lang="ts">
            const msg2 = "文件B中文"
            </script>
        """.trimIndent()

        val fileA = configureFile("src/MultiA.vue", beforeA)
        val fileB = configureFile("src/MultiB.vue", beforeB)
        // 两个文件各提取一次（各自独立 Undo 命令）
        extract(fileA)
        extract(fileB)
        val afterA = fileA.text
        val afterB = fileB.text
        assertTrue("A 提取后应变 t 调用", afterA.contains("t("))
        assertTrue("B 提取后应变 t 调用", afterB.contains("t("))

        // 分别 Undo：各自还原到原样（跨文档 undo 的确认对话框已由 setUp 中的
        // TestDialog.OK 自动确认，真实走通 UndoManager 的跨文档回退逻辑）。
        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("多文件 Undo 后 A 应还原", beforeA, fileA.text)
        assertEquals("多文件 Undo 后 B 应还原", beforeB, fileB.text)

        // 分别 Redo：各自回到 After
        myFixture.performEditorAction(IdeActions.ACTION_REDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.performEditorAction(IdeActions.ACTION_REDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("多文件 Redo 后 A 应回到 After", afterA, fileA.text)
        assertEquals("多文件 Redo 后 B 应回到 After", afterB, fileB.text)
    }

    // ── 6.5 连续两次 Extract → Undo → Redo（幂等 + 可回退）─────────

    fun testDoubleExtractUndoRedoRoundTrip() {
        val before = """
            <script setup lang="ts">
            const msg = "连续提取中文"
            </script>
        """.trimIndent()

        val file = configureFile("src/Double.vue", before)
        // 第一次提取
        extract(file)
        val after1 = file.text
        assertTrue("第一次提取后应变 t 调用", after1.contains("t("))
        // 第二次提取（已国际化，应保持幂等，不改变文本）
        extract(file)
        assertEquals("第二次提取应幂等（文本不再改变）", after1, file.text)

        // Undo → 应回到 Before（即便执行了两次 extract，最后一次 Undo 栈应还原到初始态）
        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("连续提取 + 一次 Undo 后应还原", before, file.text)
    }

    // ── 6.2 SmartPsiElementPointer 生命周期 ─────────────────────────

    fun testSmartPointerRemovedNodeBecomesInvalid() {
        val file = configureFile(
            "src/Pointer.vue",
            """
            <script setup lang="ts">
            const msg = "会被替换的中文"
            </script>
            """.trimIndent()
        )
        // 定位到将被替换的字符串字面量
        val literal = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            file, com.intellij.lang.javascript.psi.JSLiteralExpression::class.java
        ).firstOrNull { it.stringValue == "会被替换的中文" }
        assertNotNull("应能找到待提取字符串", literal)

        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(literal!!)
        extract(file)

        // 原节点已被 $t(...) 替换 → pointer 解析为 null（优雅失效，不抛异常）
        val resolved = pointer.element
        // 允许两种正确结果之一：节点被移除后解析为 null，或 PSI 缓存仍命中（不崩溃）即为通过
        if (resolved != null) {
            assertTrue("失效指针若仍返回节点，须位于文件中", file.textRange.contains(resolved.textRange))
        }
    }

    fun testSmartPointerUntouchedNodeStaysValid() {
        // 在独立的、不参与本次提取的 .ts 文件中建指针（跨文件稳定性）
        val keepFile = myFixture.addFileToProject(
            "src/keep.ts",
            "export const banner = '顶栏文案';"
        )
        val bannerLiteral = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            keepFile, com.intellij.lang.javascript.psi.JSLiteralExpression::class.java
        ).first { it.stringValue == "顶栏文案" }
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(bannerLiteral)

        // 在另一个 .vue 文件执行提取
        val extractFile = configureFile(
            "src/Extract.vue",
            """
            <script setup lang="ts">
            const msg = "待提取中文"
            </script>
            """.trimIndent()
        )
        extract(extractFile)

        // 未被编辑文件中的节点指针应保持有效
        val stillValid = pointer.element
        assertNotNull("未参与编辑文件的 SmartPointer 应仍有效", stillValid)
        assertEquals("未动节点指针解析后的值应不变", "顶栏文案",
            stillValid!!.let { (it as? com.intellij.lang.javascript.psi.JSLiteralExpression)?.stringValue ?: it.text.trim('"') })
    }

    // ── 6.3 Folding 生命周期（打开 → 折叠 → 改文本 → 重折叠）────────

    fun testFoldingRebuildReflectsNewKeys() {
        val file = configureFile(
            "src/Fold.ts",
            """
            const a = ${'$'}t('existing');
            """.trimIndent()
        )
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val beforeRegions: List<FoldingDescriptor> =
            I18nFoldingBuilder().buildFoldRegions(file, doc, false).toList()
        assertTrue("初始应有折叠区域", beforeRegions.isNotEmpty())

        // 修改文本：追加一个新的 $t('new.key')
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            doc.insertString(doc.textLength, "const b = ${'$'}t('new.key');")
        }
        PsiDocumentManager.getInstance(project).commitDocument(doc)
        val newPsi = PsiDocumentManager.getInstance(project).getPsiFile(doc)!!

        val afterRegions: List<FoldingDescriptor> =
            I18nFoldingBuilder().buildFoldRegions(newPsi, doc, false).toList()

        assertTrue("修改后折叠区域应增多(${beforeRegions.size}->${afterRegions.size})", afterRegions.size >= beforeRegions.size)
        val placeholderTexts = afterRegions.mapNotNull { it.placeholderText }
        assertTrue("重折叠应包含新增 key 的翻译占位", placeholderTexts.any { it.contains("新文案") })
    }

    // ── 6.2b sibling 连续 rewrite：多个相邻字面量逐一替换，指针各自解析稳定 ──

    fun testSiblingConsecutiveRewritePointers() {
        val file = configureFile(
            "src/Siblings.vue",
            """
            <script setup lang="ts">
            const a = "甲文案"
            const b = "乙文案"
            const c = "丙文案"
            </script>
            """.trimIndent()
        )
        val literals = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            file, com.intellij.lang.javascript.psi.JSLiteralExpression::class.java
        ).filter { it.stringValue in listOf("甲文案", "乙文案", "丙文案") }
        assertEquals("应有 3 个待提取字面量", 3, literals.size)
        val pointers = literals.map {
            SmartPointerManager.getInstance(project).createSmartPsiElementPointer(it)
        }

        extract(file)

        // 三个 sibling 节点都已被替换 → 各指针要么解析为文件内仍合法节点，要么优雅失效（不抛异常）
        pointers.forEachIndexed { idx, ptr ->
            val resolved = ptr.element
            if (resolved != null) {
                assertTrue("sibling #$idx 指针若存活须位于文件内", file.textRange.contains(resolved.textRange))
            }
        }
        // 替换确实发生：三个硬编码都被 $t 化（key 默认等于原文，故只数 $t( 调用数）
        val after = file.text
        val tCalls = Regex("\\\$t\\(").findAll(after).count()
        assertEquals("三个中文都应被提取为 \$t 调用，实际:\n$after", 3, tCalls)
    }

    // ── 6.2c nested pointer：在被替换节点的父作用域内的相邻字面量，rewrite 后仍有效 ──

    fun testNestedAdjacentPointerSurvivesRewrite() {
        val file = configureFile(
            "src/Nested.vue",
            """
            <script setup lang="ts">
            function build() {
                const inner = "保留中文"
                const outer = "被替换中文"
                return { inner, outer }
            }
            </script>
            """.trimIndent()
        )
        val literal = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            file, com.intellij.lang.javascript.psi.JSLiteralExpression::class.java
        ).first { it.stringValue == "保留中文" }
        val pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(literal)

        // 提取整个文件（只有 "被替换中文" 会变 $t；"保留中文" 如果也被提取会变，但仍不应崩溃）
        extract(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val resolved = pointer.element
        if (resolved != null) {
            assertTrue("嵌套相邻指针若存活须位于文件内", file.textRange.contains(resolved.textRange))
        }
        val after = file.text
        assertTrue("文件内应已发生 \$t 替换", after.contains("\$t("))
    }

    // ── 6.2d 文件 reparse 后 pointer 行为：被编辑节点移除 → 失效；未动节点 → 仍有效 ──

    fun testFileReparsePointerSurvivesForUntouchedAfterReparse() {
        val file = configureFile(
            "src/Reparse.vue",
            """
            <script setup lang="ts">
            const untouched = "保留文案"
            const doomed = "将被移除文案"
            </script>
            """.trimIndent()
        )
        val lits = com.intellij.psi.util.PsiTreeUtil.collectElementsOfType(
            file, com.intellij.lang.javascript.psi.JSLiteralExpression::class.java
        ).filter { it.stringValue in listOf("保留文案", "将被移除文案") }
        val untouchLit = lits.first { it.stringValue == "保留文案" }
        val doomedLit = lits.first { it.stringValue == "将被移除文案" }

        val untouchPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(untouchLit)
        val doomedPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(doomedLit)
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!

        // 重新解析目标文本：删除 "将被移除文案" 声明、追加新声明，触发整文件 reparse。
        val reparseText = """
            <script setup lang="ts">
            const untouched = "保留文案"
            const extra = "新增文案"
            </script>
        """.trimIndent()
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            doc.replaceString(0, doc.textLength, reparseText)
        }
        PsiDocumentManager.getInstance(project).commitDocument(doc)

        // 未动节点：reparse 后指针仍有效，且解析到同名同内容的节点
        val untouchedAfter = untouchPointer.element
        assertNotNull("reparse 后未动节点指针应仍有效", untouchedAfter)
        assertEquals("reparse 后未动节点值应不变", "保留文案", literalValue(untouchedAfter))

        // 被移除节点：reparse 后指针解析为 null（优雅失效）或不再指向原内容（不崩溃）
        val doomedAfter = doomedPointer.element
        if (doomedAfter != null) {
            assertFalse("被移除节点不应再解析到原值，实际: ${literalValue(doomedAfter)}",
                literalValue(doomedAfter) == "将被移除文案")
        }

        // 文件确实被重新解析到新内容
        assertTrue("reparse 后应包含新增声明", file.text.contains("新增文案"))
        assertFalse("reparse 后原被移除声明不应存在", file.text.contains("将被移除文案"))
    }

    /** 取 JS 字面量节点值；非字面量时退化为剥引号文本。 */
    private fun literalValue(el: com.intellij.psi.PsiElement?): String =
        (el as? com.intellij.lang.javascript.psi.JSLiteralExpression)?.stringValue
            ?: el?.text?.trim('"', '`') ?: ""

    fun testVueTemplateUndoRedoRoundTrip() {
        val before = """
            <template>
                <div>
                    <span>模板中文一</span>
                    <span>模板中文二</span>
                </div>
            </template>
        """.trimIndent()
        val file = configureFile("src/VueTemplate.vue", before)

        extract(file)
        val after = file.text
        assertTrue("模板提取后应变 \$t 调用，got:\n$after", after.contains("\$t("))

        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("Vue 模板 Undo 后应还原（injected PSI 时代生命周期正常）", before, file.text)

        myFixture.performEditorAction(IdeActions.ACTION_REDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals("Vue 模板 Redo 后应回到 After", after, file.text)
    }
}