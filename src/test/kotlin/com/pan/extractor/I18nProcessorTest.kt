class I18nProcessorTest :
    LightPlatformCodeInsightFixtureTestCase() {


    fun testVueTemplateExtract() {

        val file = myFixture.configureByText(
            "Test.vue",
            """
            <template>
                <div>
                    Hello 中文
                </div>
            </template>
            """.trimIndent()
        )


        val processor =
            I18nProcessor(
                project,
                file
            )


        val changes = processor.collect()


        assertEquals(
            1,
            processor.extractedStrings.size
        )


        assertEquals(
            "Hello 中文",
            processor.extractedStrings.values.first()
        )
    }
}
