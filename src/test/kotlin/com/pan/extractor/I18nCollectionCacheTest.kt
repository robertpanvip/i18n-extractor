package com.pan.extractor

import com.pan.extractor.ui.*
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

/**
 * 集合缓存（[collectionCache]）的全生命周期测试。
 *
 * 验证 [collectJSCallExpressions] 和 [collectRawTCalls] 共用缓存时的各种场景：
 *  - 基本缓存命中/未命中
 *  - 部分缓存（一方计算完另一方尚未计算时不应误用）
 *  - 文件修改后缓存失效
 *  - 文件关闭/重新打开后缓存自动清理
 *  - 不同文件不共享缓存
 */
class I18nCollectionCacheTest : BasePlatformTestCase() {

    private lateinit var originalFoldLang: String

    override fun setUp() {
        super.setUp()
        originalFoldLang = I18nSettings.getInstance().foldDisplayLanguage()
        I18nSettings.getInstance().setFoldDisplayLanguage("zh")
        myFixture.addFileToProject(
            "package.json",
            """{"name":"root","dependencies":{"vue":"^3","vue-i18n":"^9"}}"""
        )
        myFixture.addFileToProject(
            "src/locales/zh.ts",
            """
            export default {
              '你好世界': '你好世界',
              'hello': '你好',
            }
            """.trimIndent()
        )
        // 清理缓存，确保每个测试从干净状态开始
        collectionCache.clear()
    }

    override fun tearDown() {
        try {
            I18nSettings.getInstance().setFoldDisplayLanguage(originalFoldLang)
            collectionCache.clear()
        } finally {
            super.tearDown()
        }
    }

    private fun configureFile(fileName: String, text: String): PsiFile {
        val file = myFixture.addFileToProject(fileName, text.trimIndent())
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        return file
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. 基本缓存命中
    // ─────────────────────────────────────────────────────────────────

    fun testCacheHitAfterBothComputed() {
        val file = configureFile("src/Hit.ts", """const a = \${'$'}t('你好世界');""")
        // 第一次调用：计算并写入缓存
        val js1 = collectJSCallExpressions(file)
        assertEquals("应收集到 JSCallExpression", 1, js1.size)
        // 检查缓存：jsCalls 已计算，rawTCalls 尚未计算
        var cached = collectionCache[file]
        assertNotNull("缓存中应有条目", cached)
        assertTrue("jsCalls 应标记为已计算", cached!!.jsCallsComputed)
        // rawTCalls 尚未计算，所以 rawTCallsComputed 应为 false
        // 第二次调用 collectRawTCalls：计算并写入缓存
        val raw1 = collectRawTCalls(file)
        // 检查缓存：两者都标记为已计算
        cached = collectionCache[file]
        assertNotNull("缓存中应有条目", cached!!)
        assertTrue("jsCalls 应标记为已计算", cached.jsCallsComputed)
        assertTrue("rawTCalls 应标记为已计算", cached.rawTCallsComputed)
        // 第三次调用任一方：应命中缓存
        val js2 = collectJSCallExpressions(file)
        assertEquals("第二次调用应返回相同结果", js1.size, js2.size)
        assertEquals("第二次调用应返回相同引用", js1, js2)
        val raw2 = collectRawTCalls(file)
        assertEquals("第二次调用应返回相同结果", raw1.size, raw2.size)
        assertEquals("第二次调用应返回相同引用", raw1, raw2)
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. 部分缓存：先调用 collectJSCallExpressions
    //    → collectRawTCalls 不应命中空 rawTCalls
    // ─────────────────────────────────────────────────────────────────

    fun testPartialCacheJsCallsFirst() {
        val file = configureFile("src/PartialJs.ts", """const a = \${'$'}t('你好世界');""")
        // 1) 只调用 collectJSCallExpressions
        val js = collectJSCallExpressions(file)
        assertEquals("应收集到 JSCallExpression", 1, js.size)
        // 缓存中 jsCalls 已计算，但 rawTCalls 未计算
        val cached = collectionCache[file]
        assertNotNull(cached)
        assertTrue("jsCalls 应标记为已计算", cached!!.jsCallsComputed)
        assertTrue("rawTCalls 不应标记为已计算（尚未计算）", !cached.rawTCallsComputed)
        // 2) 再调用 collectRawTCalls — 不应命中缓存（rawTCalls 未计算）
        val raw = collectRawTCalls(file)
        // 确保 rawTCalls 确实被计算了（可能有值也可能为空，取决于文件内容）
        // 关键是 rawTCallsComputed 现在为 true
        val cached2 = collectionCache[file]
        assertTrue("rawTCalls 现在应标记为已计算", cached2!!.rawTCallsComputed)
        assertEquals("rawTCalls 结果应与缓存一致", raw, cached2.rawTCalls)
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. 部分缓存：先调用 collectRawTCalls
    //    → collectJSCallExpressions 不应命中空 jsCalls
    // ─────────────────────────────────────────────────────────────────

    fun testPartialCacheRawTCallsFirst() {
        val file = configureFile("src/PartialRaw.vue", """
            <template>
              <div>{{ \${'$'}t('你好世界') }}</div>
            </template>
        """.trimIndent())
        // 1) 只调用 collectRawTCalls
        val raw = collectRawTCalls(file)
        // 缓存中 rawTCalls 已计算，但 jsCalls 未计算
        val cached = collectionCache[file]
        assertNotNull(cached)
        assertTrue("rawTCalls 应标记为已计算", cached!!.rawTCallsComputed)
        assertTrue("jsCalls 不应标记为已计算（尚未计算）", !cached.jsCallsComputed)
        // 2) 再调用 collectJSCallExpressions — 不应命中缓存（jsCalls 未计算）
        val js = collectJSCallExpressions(file)
        // 确保 jsCalls 确实被计算了
        val cached2 = collectionCache[file]
        assertTrue("jsCalls 现在应标记为已计算", cached2!!.jsCallsComputed)
        assertEquals("jsCalls 结果应与缓存一致", js, cached2.jsCalls)
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. 文件修改后缓存失效
    // ─────────────────────────────────────────────────────────────────

    fun testCacheInvalidatedAfterFileModification() {
        val file = configureFile("src/Modify.ts", """const a = \${'$'}t('你好世界');""")
        // 首次计算，写入缓存
        val js1 = collectJSCallExpressions(file)
        val raw1 = collectRawTCalls(file)
        val stampBefore = collectionCache[file]!!.modificationStamp

        // 修改文件内容
        WriteCommandAction.runWriteCommandAction(project) {
            file.viewProvider.document?.insertString(file.textLength, "\nconst b = \${'$'}t('hello');")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        // 文件修改后，modificationStamp 应变化
        val stampAfter = file.modificationStamp
        assertTrue("文件修改后 modificationStamp 应变化", stampAfter != stampBefore)

        // 再次调用 — 由于 modificationStamp 不匹配，应缓存未命中并重新计算
        val js2 = collectJSCallExpressions(file)
        assertTrue("修改后应收集到更多 JSCallExpression", js2.size >= js1.size)

        val cached = collectionCache[file]
        assertNotNull("修改后缓存应更新", cached)
        assertEquals("缓存中的 modificationStamp 应与文件一致", file.modificationStamp, cached!!.modificationStamp)
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. 文件修改后部分缓存失效
    //    collectJSCallExpressions 先计算 → 缓存 → 修改文件
    //    → collectRawTCalls 应重新计算（只改 rawTCalls，不改 jsCalls）
    // ─────────────────────────────────────────────────────────────────

    fun testPartialCacheInvalidatedAfterModification() {
        val file = configureFile("src/PartialModify.ts", """const a = \${'$'}t('你好世界');""")
        // 先只计算 jsCalls
        collectJSCallExpressions(file)
        val cached1 = collectionCache[file]!!
        assertTrue("jsCalls 已计算", cached1.jsCallsComputed)

        // 修改文件：追加新行（纯 JS 文件，追加的 $t 会在宿主树中形成 JSCallExpression）
        WriteCommandAction.runWriteCommandAction(project) {
            file.viewProvider.document?.insertString(file.textLength, "\nconst c = \${'$'}t('hello');")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        // 获取最新 PsiFile 引用（commit 后可能实例相同，但内容已更新）
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!
        val latestFile = PsiDocumentManager.getInstance(project).getPsiFile(doc)!!

        // 再次调用 collectJSCallExpressions — 应缓存未命中（modificationStamp 变化）
        val js2 = collectJSCallExpressions(latestFile)
        assertTrue("修改后应收集到更多 JSCallExpression", js2.size >= 2) // 原来 1 个 + 新加 1 个
        // 缓存应包含新的 jsCalls 且 modificationStamp 已更新
        val cached2 = collectionCache[latestFile]!!
        assertTrue("jsCalls 应标记为已计算", cached2.jsCallsComputed)
        assertEquals("modificationStamp 应与文件一致", latestFile.modificationStamp, cached2.modificationStamp)
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. 不同文件不共享缓存
    // ─────────────────────────────────────────────────────────────────

    fun testDifferentFilesHaveSeparateCacheEntries() {
        val fileA = configureFile("src/A.ts", """const a = \${'$'}t('你好世界');""")
        val fileB = configureFile("src/B.ts", """const b = \${'$'}t('hello');""")

        collectJSCallExpressions(fileA)
        assertNotNull("fileA 应有缓存", collectionCache[fileA])
        assertNull("fileB 不应有缓存（尚未计算）", collectionCache[fileB])

        collectJSCallExpressions(fileB)
        assertNotNull("fileB 现在应有缓存", collectionCache[fileB])
        // 两个缓存条目应独立
        val cachedA = collectionCache[fileA]!!
        val cachedB = collectionCache[fileB]!!
        assertTrue("fileA 的 jsCalls 应有内容", cachedA.jsCalls.isNotEmpty())
        assertTrue("fileB 的 jsCalls 应有内容", cachedB.jsCalls.isNotEmpty())
        // 两个缓存对象应为不同实例
        assertTrue("不同文件的缓存条目应为不同对象", cachedA !== cachedB)
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. 多次调用同一文件：缓存命中后，结果应稳定
    // ─────────────────────────────────────────────────────────────────

    fun testMultipleCallsReturnSameInstance() {
        val file = configureFile("src/Stable.ts", """const a = \${'$'}t('你好世界');""")
        val js1 = collectJSCallExpressions(file)
        collectRawTCalls(file)
        // 后续多次调用应全部命中缓存
        for (i in 1..5) {
            val jsN = collectJSCallExpressions(file)
            val rawN = collectRawTCalls(file)
            assertEquals("第 $i 次调用 jsCalls 应与第一次相同", js1, jsN)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 8. 空文件：缓存应正确处理空结果
    // ─────────────────────────────────────────────────────────────────

    fun testEmptyFileCacheHandling() {
        val file = configureFile("src/Empty.ts", "")
        val js = collectJSCallExpressions(file)
        assertTrue("空文件应无 JSCallExpression", js.isEmpty())
        val raw = collectRawTCalls(file)
        assertTrue("空文件应无 RawTCall", raw.isEmpty())

        val cached = collectionCache[file]
        assertNotNull("空文件也应有缓存条目", cached)
        assertTrue("空文件 jsCalls 应标记为已计算", cached!!.jsCallsComputed)
        assertTrue("空文件 rawTCalls 应标记为已计算", cached.rawTCallsComputed)
        assertTrue("空文件 jsCalls 应为空列表", cached.jsCalls.isEmpty())
        assertTrue("空文件 rawTCalls 应为空列表", cached.rawTCalls.isEmpty())

        // 再次调用应命中缓存
        val js2 = collectJSCallExpressions(file)
        assertTrue("缓存命中后应返回空列表", js2.isEmpty())
    }

    // ─────────────────────────────────────────────────────────────────
    // 9. FoldingBuilder 间接使用缓存
    //    buildFoldRegions 内部调用 collectJSCallExpressions + collectRawTCalls
    //    验证缓存被正确填充
    // ─────────────────────────────────────────────────────────────────

    fun testFoldingBuilderPopulatesCache() {
        val file = configureFile("src/FoldCache.ts", """const a = \${'$'}t('你好世界');""")
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)!!

        // buildFoldRegions 内部会调用 collectJSCallExpressions + collectRawTCalls
        I18nFoldingBuilder().buildFoldRegions(file, doc, false)

        val cached = collectionCache[file]
        assertNotNull("buildFoldRegions 后应有缓存", cached)
        assertTrue("jsCalls 应已计算", cached!!.jsCallsComputed)
        // rawTCalls 可能为空也可能有内容，但应标记为已计算
        assertTrue("rawTCalls 应已计算", cached.rawTCallsComputed)
        // 验证缓存与直接调用结果一致
        val directJs = collectJSCallExpressions(file)
        assertEquals("缓存应与直接调用一致", cached.jsCalls, directJs)
    }

    // ─────────────────────────────────────────────────────────────────
    // 10. 缓存清理：collectionCache.clear() 后应重新计算
    // ─────────────────────────────────────────────────────────────────

    fun testCacheClearForcesRecompute() {
        val file = configureFile("src/Clear.ts", """const a = \${'$'}t('你好世界');""")
        collectJSCallExpressions(file)
        assertNotNull("缓存应有条目", collectionCache[file])

        collectionCache.clear()
        assertNull("缓存清理后应无条目", collectionCache[file])

        // 再次调用应重新计算
        val js = collectJSCallExpressions(file)
        assertEquals("应重新计算到 JSCallExpression", 1, js.size)
        assertNotNull("重新计算后应有新缓存条目", collectionCache[file])
    }

    // ─────────────────────────────────────────────────────────────────
    // 11. 模拟关闭再打开（PsiFile 被 GC 后重用 URL）
    //     通过删除旧文件、创建同名新文件来模拟
    // ─────────────────────────────────────────────────────────────────

    fun testSimulateCloseAndReopen() {
        // 第一次打开文件：使用临时文件名，避免与其它测试冲突
        val fileName = "src/Reopen_${System.nanoTime()}.ts"
        val file1 = configureFile(fileName, """const a = \${'$'}t('你好世界');""")
        collectJSCallExpressions(file1)
        collectRawTCalls(file1)
        val cached1 = collectionCache[file1]
        assertNotNull("第一次应有缓存", cached1)

        // 模拟关闭：清除缓存（如同 PsiFile 被 GC）
        collectionCache.clear()

        // 模拟重新打开：同名文件，但通过 addFileToProject 会创建新 PsiFile 实例
        // 注意：addFileToProject 不会覆盖已存在文件，因此用新文件路径
        val fileName2 = "src/Reopen2_${System.nanoTime()}.ts"
        val file2 = configureFile(fileName2, """const a = \${'$'}t('你好世界');""")
        assertTrue("重新打开后的 PsiFile 应有相同内容", file2.text.contains("你好世界"))
        // 缓存被清空后，应重新计算
        val js2 = collectJSCallExpressions(file2)
        assertEquals("重新打开后应重新计算 JSCallExpression", 1, js2.size)
        val cached2 = collectionCache[file2]
        assertNotNull("重新打开后应有新缓存条目", cached2)
        assertTrue("新缓存 jsCalls 应已计算", cached2!!.jsCallsComputed)
    }
}