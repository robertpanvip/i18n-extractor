package com.pan.extractor.core

/**
 * 跨类共享的固定正则（P2：收敛重复内联 Regex）。
 *
 * 只收录「不依赖运行期变量、一次编译可复用」的模式；涉及动态变量（如改名别名 `$name`、文件路径）的
 * 正则无法共享，仍留在调用处内联。调用方引用本目录取代重复字面量，降低每次调用重新编译的开销。
 */
object RegexCatalog {

    /** TS/JS 标识符：`[A-Za-z_$][\w$]*`。 */
    val IDENTIFIER = Regex("""[A-Za-z_$][\w$]*""")

    /** `from 'pkg'` 导入源路径捕获。 */
    val FROM = Regex("""from\s*['"]([^'"]+)['"]""")

    /** `createI18n(` 调用。 */
    val CREATE_I18N = Regex("""createI18n\s*\(""")

    /** `lng:` / `locale:` 语言代码捕获。 */
    val LANGUAGE_CODE = Regex("""(?:lng|locale)\s*:\s*['"]([^'"]+)['"]""")

    /** `export default <*i18n*>` 星号导出。 */
    val EXPORT_DEFAULT_I18N_STAR = Regex("""export\s+default\s+\w*[Ii]18n\w*""")

    /** `export default createI18n(` 。 */
    val EXPORT_DEFAULT_CREATE_I18N = Regex("""export\s+default\s+createI18n\s*\(""")

    /** 中文字符。 */
    val HAN = Regex("""[\u4e00-\u9fff]""")

    /** 数字（可含小数）。 */
    val NUMBER = Regex("""-?\d+(?:\.\d+)?""")

    /** 模板插值 `${...}`（捕获内容）。 */
    val TEMPLATE_INTERPOLATION = Regex("""\$\{([^}]*)\}""")

    /** 仅整行行首空白（用于求缩进）。 */
    val LEADING_WHITESPACE = Regex("""^(\s*)""")
}