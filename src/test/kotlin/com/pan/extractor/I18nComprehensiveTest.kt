package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * 综合场景测试矩阵（BUG_ANALYSIS P0.5 / P0.6 / P1.4 / P2.4）。
 *
 * P0.5 — Import Injection / PSI Rewrite / WriteBack 组合验证
 * P0.6 — multiline / nested expression / TSX / JSX 场景
 * P1.4 — import / export / alias 路径推断
 * P2.4 — Unicode / emoji / CRLF 边界
 */
class I18nComprehensiveTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        // Vue 项目 package.json
        myFixture.addFileToProject(
            "package.json",
            """
            {
              "name": "comprehensive-test",
              "dependencies": {
                "vue": "^3.0.0",
                "vue-i18n": "^9.0.0"
              }
            }
            """.trimIndent()
        )
        // i18n 实例文件（命名导出）—— 检查文件是否已存在，存在则直接复用，避免重复创建
        val existing = myFixture.findFileInTempDir("src/locales/index.ts")
        if (existing == null) {
            myFixture.addFileToProject(
                "src/locales/index.ts",
                """
                import { createI18n } from 'vue-i18n';
                export const i18n = createI18n({
                  legacy: false,
                  locale: 'zh',
                  messages: { zh: {} },
                });
                """.trimIndent()
            )
        }
    }

    private fun configureFile(fileName: String, text: String) = myFixture.let {
        val psi = it.addFileToProject(fileName, text)
        it.configureFromExistingVirtualFile(psi.virtualFile)
        psi
    }

    // ── P0.6: multiline $t 调用 ──────────────────────────────────

    fun testMultilineTCallInScript() {
        val file = configureFile(
            "src/multiline.ts",
            """
            const msg = ${'$'}t(
              'hello'
            );
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        // 跨行 $t() 调用应被 collectTKeysFromRawText 的 multiline 正则命中
        assertTrue(
            "跨行 ${'$'}t() 调用的 key 应被提取",
            processor.existingStrings.containsKey("hello") ||
                processor.existingStrings.any { it.value == "hello" }
        )
    }

    fun testMultilineTCallWithTrailingComma() {
        val file = configureFile(
            "src/multiline2.ts",
            """
            const msg = ${'$'}t(
              'world',
              { name: 'test' }
            );
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "跨行带参数 ${'$'}t() 的 key 应被提取",
            processor.existingStrings.any { it.value == "world" }
        )
    }

    // ── P0.6: nested expression ───────────────────────────────────

    fun testNestedExpressionInTernary() {
        val file = configureFile(
            "src/nested.ts",
            """
            const a = true ? '你好' : '世界';
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("三元表达式中的两个中文都应被提取", 2, processor.extractedStrings.size)
    }

    fun testNestedExpressionInFunctionCall() {
        val file = configureFile(
            "src/nested2.ts",
            """
            console.log('提示信息', format('错误信息'));
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("函数调用参数中的两个中文都应被提取", 2, processor.extractedStrings.size)
    }

    fun testNestedExpressionInArray() {
        val file = configureFile(
            "src/nested3.ts",
            """
            const arr = ['第一项', '第二项', '第三项'];
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("数组字面量中的三个中文都应被提取", 3, processor.extractedStrings.size)
    }

    /* P0.6 nested：箭头函数体表达式（BUG_ANALYSIS 4.2 点名 `items.map(item => "你好")`）。 */
    fun testArrowBodyStringInMap() {
        val file = configureFile(
            "src/nested5.ts",
            """
            const labels = items.map(item => '项目' + item.name);
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "箭头函数体中含中文的字符串应被提取",
            processor.extractedStrings.any { it.value.contains("项目") }
        )
    }

    /**
     * P0.6 高危险性正确性：同一函数调用的多个字符串参数必须各自独立包裹，
     * 绝不能错误合并成 `$t('第一项', '第二项')`（该形态在 vue-i18n/regex 下会被
     * 当成缺省命名的复数 key，语义完全不同）。对应 BUG_ANALYSIS 4.3。
     */
    fun testMultiStringArgsNotMergedIntoSingleCall() {
        val file = configureFile(
            "src/nested4.ts",
            """
            foo('第一项', '第二项');
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("两个参数中文都应被提取", 2, processor.extractedStrings.size)

        processor.runWithUndo()
        val rewritten = file.text
        assertFalse(
            "不应把多参合并成单个 \${'$'}t('第一项','第二项')，实际:\n$rewritten",
            Regex("""\${'$'}t\(\s*['"]第一项['"]\s*,\s*['"]第二项['"]\s*\)""").containsMatchIn(rewritten)
        )
        assertTrue(
            "两个参数应各自独立包裹为 \${'$'}t(...)，实际:\n$rewritten",
            Regex("""\${'$'}t\(\s*['"]第一项['"]\s*\)""").containsMatchIn(rewritten) &&
                Regex("""\${'$'}t\(\s*['"]第二项['"]\s*\)""").containsMatchIn(rewritten)
        )
    }

    // ── P2: 批量 / 大文件提取 ────────────────────────────────────

    /**
     * P2 大文件场景（BUG_ANALYSIS section 5）：批量 1000 个中文字符串应全部提取，
     * 且不得丢项、不得出现重复（extractedStrings 为 key→value 映射，key 唯一且数量精确）。
     */
    fun testLargeBatchExtraction() {
        val count = 1000
        val sb = StringBuilder()
        for (i in 0 until count) {
            sb.append("const s$i = '批量字符串$i';\n")
        }
        val file = configureFile("src/large_batch.ts", sb.toString())
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("批量 $count 个中文字符串应全部提取", count, processor.extractedStrings.size)
        assertTrue("应含首个字符串", processor.extractedStrings.containsValue("批量字符串0"))
        assertTrue("应含最后字符串", processor.extractedStrings.containsValue("批量字符串${count - 1}"))
    }

    // ── P1-3: Runtime Fixture 语义等价 ────────────────────────────

    /**
     * P1-3（BUG_ANALYSIS section 7）：真实运行时形态的语义等价。
     * React 项目按约定使用 `t`（ReactI18nextStrategy.tFunctionName = "t"），
     * 常用形态是 `const t = getI18n().t;` 后 `t('中文')`。
     * 参数应被识别为“已翻译 key”进 existingStrings，而**不得**作为硬编码文案进 extractedStrings。
     */
    fun testRuntimeReactGetI18nTArgNotExtracted() {
        myFixture.addFileToProject(
            "packages/react-app/package.json",
            """{ "react": "^18", "react-i18next": "^13" }"""
        )
        val file = configureFile(
            "packages/react-app/useI18nCall.ts",
            """
            import { getI18n } from 'react-i18next';
            const t = getI18n().t;
            export const msg = t('已经国际化的中文');
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "getI18n().t 的参数应被视为已翻译（进 existingStrings）",
            processor.existingStrings.containsValue("已经国际化的中文")
        )
        assertFalse(
            "getI18n().t 的参数不得再被当作待提取硬编码文案",
            processor.extractedStrings.containsValue("已经国际化的中文")
        )
    }

    /**
     * P1-3（BUG_ANALYSIS section 7）：Vue 中 `const $t = i18n.global.t; $t('中文')`
     * 别名绑定后的调用，参数同为已翻译 key，不得再被提取，且不得被二次改写。
     */
    fun testRuntimeVueGlobalAliasArgNotExtracted() {
        val file = configureFile(
            "src/globalAlias.vue",
            """
            <template>
              <div>{{ ${'$'}t('别名中文') }}</div>
            </template>
            <script setup lang="ts">
            import { i18n } from './locales/index';
            const ${'$'}t = i18n.global.t;
            const label = ${'$'}t('脚本别名中文');
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue(
            "别名 \${'$'}t('中文') 的中文应进 existingStrings",
            processor.existingStrings.containsValue("别名中文") &&
                processor.existingStrings.containsValue("脚本别名中文")
        )
        assertFalse(
            "别名 \${'$'}t 调用内中文不得被当作待提取硬编码",
            processor.extractedStrings.containsValue("别名中文") ||
                processor.extractedStrings.containsValue("脚本别名中文")
        )
    }

    // ── P0.6: TSX / JSX 场景 ─────────────────────────────────────

    fun testTSXAttributeChinese() {
        // React 项目依赖
        myFixture.addFileToProject(
            "packages/react-app/package.json",
            """{ "react": "^18" }"""
        )
        val file = configureFile(
            "packages/react-app/Button.tsx",
            """
            export function Button() {
              return <button title="点击确认">提交</button>;
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("TSX 属性中的中文应被提取", processor.extractedStrings.isNotEmpty())
    }

    fun testJSXTextContent() {
        myFixture.addFileToProject(
            "packages/react-jsx/package.json",
            """{ "react": "^18" }"""
        )
        val file = configureFile(
            "packages/react-jsx/Label.jsx",
            """
            export function Label() {
              return <span>标签内容</span>;
            }
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("JSX 文本内容应被提取", processor.extractedStrings.isNotEmpty())
    }

    // ── P0.5: Import Injection / PSI Rewrite 组合 ────────────────

    fun testImportInjectionForVueI18n() {
        val file = configureFile(
            "src/ImportTest.vue",
            """
            <template>
                <div>测试文本</div>
            </template>
            <script setup lang="ts">
            const a = 1;
            </script>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("Vue SFC 中文应被提取", 1, processor.extractedStrings.size)

        fun injectImport() {
            // 与 production 调用链对齐（CommandProcessor + WriteCommandAction）。
            // 直接 WriteCommandAction 在 EAP 上会抛 IncorrectOperationException，
            // runWithUndo() 内部已封装两层；这里手动复刻以验证 injector 单元。
            com.intellij.openapi.command.CommandProcessor.getInstance().executeCommand(
                project,
                {
                    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                        processor.injector.ensureI18nInstanceImported(file, isVue = true)
                    }
                },
                "ImportInjection",
                null
            )
        }

        // 第一次注入：应成功写入 i18n 实例 import
        injectImport()
        val textAfterFirst = file.text
        val importOccurrences = Regex("""from\s+['"]\.\.(/locales|/locales/index)['"]|from\s+['"]@/locales['"]""")
        assertTrue(
            "应注入指向 vue-i18n 实例的 import，实际文件:\n$textAfterFirst",
            importOccurrences.containsMatchIn(textAfterFirst)
        )

        // 第二次注入：幂等，不应重复追加 import（仍只有一处）
        injectImport()
        val textAfterSecond = file.text
        val count = importOccurrences.findAll(textAfterSecond).count()
        assertEquals("重复注入不应追加第二条 import，实际文件:\n$textAfterSecond", 1, count)
    }

    fun testWriteBackDoesNotDuplicate() {
        val file = configureFile(
            "src/WriteBackTest.vue",
            """
            <template>
                <div>第一次</div>
            </template>
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        val initialCount = processor.extractedStrings.size
        assertTrue("应提取到中文", initialCount > 0)

        // 执行重写：必须用 processor.runWithUndo()（内部包 CommandProcessor + WriteCommandAction）
        // 直接 processor.run() 会报 PSI 写操作线程越权。
        processor.runWithUndo()
        // 重新 collect，已替换的中文不应再被提取
        val processor2 = I18nProcessor(project, file)
        processor2.collect()
        assertEquals("WriteBack 后二次 collect 应无重复提取", 0, processor2.extractedStrings.size)
    }

    // ── P1.4: import / export / alias 路径推断 ──────────────────

    fun testVueImportPathAlias() {
        val file = configureFile("src/views/Home.vue", "<template><div>首页</div></template>")
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("alias 路径项目应正常提取", processor.extractedStrings.isNotEmpty())
    }

    fun testVueImportPathRelative() {
        // 测试从子目录文件中提取中文（setUp 已创建 src/locales/index.ts）
        val file = configureFile("src/sub/Header.vue", "<template><div>头部</div></template>")
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertTrue("相对路径项目应正常提取", processor.extractedStrings.isNotEmpty())
    }

    // ── P2.4: Unicode / emoji / CRLF 边界 ───────────────────────

    fun testUnicodeChineseCharacters() {
        val file = configureFile(
            "src/unicode.ts",
            """
            const a = '你好世界';
            const b = '简体中文';
            const c = '繁體中文';
            """.trimIndent()
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("Unicode 中文字符（简繁）应全部提取", 3, processor.extractedStrings.size)
    }

    fun testEmojiInString() {
        val file = configureFile(
            "src/emoji.ts",
            """const a = '提交🚀成功';"""
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("含 emoji 的中文字符串应被提取", 1, processor.extractedStrings.size)
        assertTrue("提取的文本应保留 emoji", processor.extractedStrings.values.first().contains("🚀"))
    }

    fun testCRLFLineEndings() {
        // 模拟 Windows CRLF 换行
        val file = configureFile(
            "src/crlf.ts",
            "const a = '第一行';\r\nconst b = '第二行';\r\n"
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        assertEquals("CRLF 换行的两个中文应被提取", 2, processor.extractedStrings.size)
    }

    fun testEmptyStringIgnored() {
        val file = configureFile(
            "src/empty.ts",
            """const a = ''; const b = '   '; const c = '有效文本';"""
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        // 空字符串和纯空白应被忽略，只有"有效文本"被提取
        assertEquals("空字符串和纯空白不应被提取", 1, processor.extractedStrings.size)
    }

    fun testMixedChineseAndEnglish() {
        val file = configureFile(
            "src/mixed.ts",
            """const a = 'Hello 世界'; const b = '你好';"""
        )
        val processor = I18nProcessor(project, file)
        processor.collect()
        // 含中文的字符串应被提取
        assertEquals("两个含中文的字符串都应被提取", 2, processor.extractedStrings.size)
    }
}
