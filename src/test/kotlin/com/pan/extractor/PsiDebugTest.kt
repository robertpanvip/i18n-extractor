package com.pan.extractor

import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PsiDebugTest : BasePlatformTestCase() {
    fun testDebugVueAttrTemplate() {
        val vue = """
        <template>
          <div>
            <a-tooltip :title="`${'$'}{isZn ? '文档贡献者：' : 'Contributor: '}${'$'}{item.login}`">
              <a :href="item.url">{{ item.login }}</a>
            </a-tooltip>
          </div>
        </template>
        <script setup lang="ts">
          const isZn = true
          const item = { login: 'test', url: '#' }
        </script>
        """.trimIndent()
        val d = '$'
        val fixed = vue.replace("\${'$'}", d.toString())
        val file = myFixture.addFileToProject("Test.vue", fixed)

        println("\n=== File root children ===")
        file.children.forEachIndexed { i, c ->
            println("  child[$i]: ${c.javaClass.simpleName} -> ${c.text.take(80).replace("\n", "\\n")}")
            c.children.forEachIndexed { j, cc ->
                println("    child[$i][$j]: ${cc.javaClass.simpleName}")
            }
        }
        println("\n=== file.text length: ${file.text.length} ===")

        // 检查哪些元素是候选
        println("\n=== All element types ===")
        val types = mutableSetOf<String>()
        file.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
            override fun visitElement(element: PsiElement) {
                val name = element.javaClass.simpleName
                types.add(name)
                if (element.text.length > 5 && element.text.length < 100) {
                    println("  $name: ${element.text.replace("\n", "\\n")}")
                }
                super.visitElement(element)
            }
        })
        println("\n=== Unique types: ${types.sorted().joinToString(", ")}")
    }
}
