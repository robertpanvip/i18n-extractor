package com.pan.extractor

import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixtureTestCase


class I18nProcessorTest :
    LightPlatformCodeInsightFixtureTestCase() {


    /**
     * 测试 Vue template 普通文本
     *
     * 输入:
     * <div>你好</div>
     *
     * 期望:
     * {{ $t('你好') }}
     */
    fun testVueTemplateTextExtract() {

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


        val processor =
            I18nProcessor(
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


        processor.execute()


        assertTrue(
            file.text.contains("\$t")
        )
    }


    /**
     * 已经存在 $t 不应该重复处理
     */
    fun testExistingTShouldSkip() {

        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>
                    {{ $t('你好') }}
                </div>
            </template>
            """.trimIndent()
        )


        val processor =
            I18nProcessor(
                project,
                file
            )


        processor.collect()


        assertTrue(
            processor.extractedStrings.isEmpty()
        )
    }



    /**
     * 测试 JS 字符串
     */
    fun testJavaScriptStringExtract() {


        val file =
            myFixture.configureByText(
                "test.ts",
                """
                const message = "你好"
                """.trimIndent()
            )


        val processor =
            I18nProcessor(
                project,
                file
            )


        processor.collect()


        assertEquals(
            "你好",
            processor.extractedStrings.values.first()
        )


        processor.execute()


        assertTrue(
            file.text.contains("\$t")
        )
    }



    /**
     * 测试 JSX
     */
    fun testReactJsxExtract() {


        val file =
            myFixture.configureByText(
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


        val processor =
            I18nProcessor(
                project,
                file
            )


        processor.collect()


        assertEquals(
            1,
            processor.extractedStrings.size
        )


        processor.execute()


        assertTrue(
            file.text.contains("\$t")
        )
    }




    /**
     * Vue 属性测试
     *
     * <div title="你好">
     *
     */
    fun testVueAttributeExtract() {


        val file =
            myFixture.configureByText(
                "Test.vue",
                """
                <template>
                    <div title="你好">
                    </div>
                </template>
                """.trimIndent()
            )


        val processor =
            I18nProcessor(
                project,
                file
            )


        processor.collect()


        assertEquals(
            "你好",
            processor.extractedStrings.values.first()
        )


        processor.execute()


        assertTrue(
            file.text.contains("\$t")
        )
    }




    /**
     * 测试模板变量
     *
     * `你好${name}`
     */
    fun testTemplateLiteralExtract(){


        val file =
            myFixture.configureByText(
                "test.ts",
                """
                const text = `你好${'$'}{name}`
                """.trimIndent()
            )


        val processor =
            I18nProcessor(
                project,
                file
            )


        processor.collect()


        assertEquals(
            1,
            processor.extractedStrings.size
        )


        processor.execute()


        assertTrue(
            file.text.contains("\$t")
        )
    }



    /**
     * 测试中文判断
     */
    fun testHasChinese(){

        val processor =
            I18nProcessor(
                project,
                myFixture.configureByText(
                    "test.ts",
                    ""
                )
            )


        assertTrue(
            processor.hasChinese("你好")
        )


        assertFalse(
            processor.hasChinese("hello")
        )
    }



    /**
     * 测试 key 生成
     */
    fun testBuildTExpression(){


        val processor =
            I18nProcessor(
                project,
                myFixture.configureByText(
                    "test.ts",
                    ""
                )
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
