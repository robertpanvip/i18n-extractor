package com.pan.extractor

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.ide.plugins.PluginManager


class I18nProcessorTest : BasePlatformTestCase() {


    /**
     * 测试 Vue template 普通文本
     */
    fun testVueTemplateTextExtract() {
 PluginManagerCore
            .getLoadedPlugins()
            .forEach {
                println(it.pluginId.id)
            }


        val vue =
            PluginManagerCore.isPluginInstalled(
                PluginId.getId("org.jetbrains.plugins.vue")
            )


        println("vue installed = $vue")
        
        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>
                    你好
                </div>
            </template>
            """.trimIndent()
        )
        
println(file.language)
println(file.node.elementType)
println(file.text)
println(file.fileType)
println(file.language)
        val processor = I18nProcessor(
            project,
            file
        )


        processor.collect()


        assertEquals(
            1,
            processor.extractedStrings.size
        )


        assertEquals(
            "你好",
            processor.extractedStrings.values.first()
        )
    }


    /**
     * 测试 JS 字符串
     */
    fun testJavaScriptStringExtract() {

        val file = myFixture.configureByText(
            "test.ts",
            """
            const message = "你好"
            """.trimIndent()
        )


        val processor = I18nProcessor(
            project,
            file
        )


        processor.collect()


        assertEquals(
            1,
            processor.extractedStrings.size
        )


        assertEquals(
            "你好",
            processor.extractedStrings.values.first()
        )
    }


    /**
     * 测试 JSX
     */
    fun testReactJsxExtract() {

        val file = myFixture.configureByText(
            "App.tsx",
            """
            export default function App(){

                return (
                    <div>
                        你好
                    </div>
                )

            }
            """.trimIndent()
        )


        val processor = I18nProcessor(
            project,
            file
        )


        processor.collect()


        assertEquals(
            1,
            processor.extractedStrings.size
        )
    }


    /**
     * 测试已经存在 t 函数跳过
     */
    fun testExistingTShouldSkip() {

        val file = myFixture.configureByText(
            "test.ts",
            """
           const msg = ${'$'}t("你好")
            """.trimIndent()
        )


        val processor = I18nProcessor(
            project,
            file
        )


        processor.collect()


        assertTrue(
            processor.extractedStrings.isEmpty()
        )
    }


    /**
     * 测试中文判断
     */
    fun testHasChinese() {

        val file = myFixture.configureByText(
            "test.ts",
            ""
        )


        val processor = I18nProcessor(
            project,
            file
        )


        assertTrue(
            processor.hasChinese("你好")
        )


        assertFalse(
            processor.hasChinese("hello")
        )
    }


    /**
     * 测试生成表达式
     */
    fun testBuildTExpression() {

        val file = myFixture.configureByText(
            "test.ts",
            ""
        )


        val processor = I18nProcessor(
            project,
            file
        )


        assertEquals(
            "\$t('你好')",
            processor.buildTFunctionExpr(
                "你好",
                "{}"
            )
        )
    }
}
