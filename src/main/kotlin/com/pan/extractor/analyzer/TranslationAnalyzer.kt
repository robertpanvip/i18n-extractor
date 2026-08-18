package com.pan.extractor.analyzer

import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.psi.PsiElement

/**
 * 翻译调用三态判定 —— 取代旧的「名字像 t 就当已翻译」模型。
 *
 * [TranslationCallStatus] 三态：
 *  - [TranslationCallStatus.TRANSLATION]：**已证明**是翻译调用（来源 =
 *    i18n 框架 import / hook 或工厂产物 / 插件 \$t）→ 调用方应跳过提取（existingStrings）；
 *  - [TranslationCallStatus.NON_TRANSLATION]：**已证明**不是翻译调用（本地对象 / 本地函数 /
 *    非 i18n 模块 import）→ 调用方应正常提取参数中的文案；
 *  - [TranslationCallStatus.UNKNOWN]：无法证明任何一边 → 调用方**既不提取也不改写**
 *    （零误改，宁可漏掉一次提取机会，也不破坏用户代码）。
 *
 * 判定顺序（来自 [SymbolAnalyzer] 的来源证明，而非名字）：
 * ```
 * CallExpression → callee → Reference Resolution → symbol → 来源分类 → 三态
 * ```
 * 「t / tc / \$t」只是进入来源证明的**弱特征**，不是语义证明。
 */
enum class TranslationCallStatus {
    TRANSLATION,
    NON_TRANSLATION,
    UNKNOWN,
}

/** 字符串字面量相对于外层翻译调用上下文的位置。 */
enum class StringContext {
    /** 完全不在任何翻译 / 未知调用里 → 正常提取替换。 */
    NONE,

    /** 直接就是一个 TRANSLATION 调用的单字符串参数（`\$t('中文')`）→ 完全跳过。 */
    DIRECT_TRANSLATION_ARG,

    /** 位于 TRANSLATION 调用的参数表达式内部（`\$t(cond ? 'a' : 'b')`）→ 只换 key 不包 \$t。 */
    INSIDE_TRANSLATION_EXPRESSION,

    /** 位于 UNKNOWN 调用（无法证明来源）的参数内部 → 保守跳过，零误改。 */
    INSIDE_UNKNOWN_CALL,
}

/**
 * 翻译调用分析器（无状态，线程安全）。
 * 上层（I18nPsiTools / JsStringCollector / I18nProcessor）通过它做一切
 * 「是不是翻译调用」的判定，替代旧的 isI18nTranslationCall 名字兜底。
 */
object TranslationAnalyzer {

    /** 判定一个调用的三态。 */
    fun statusOf(call: JSCallExpression): TranslationCallStatus {
        val analysis = SymbolAnalyzer.analyze(call)
        return when (analysis.origin) {
            SymbolOrigin.I18N_FRAMEWORK_IMPORT,
            SymbolOrigin.I18N_HOOK_OR_FACTORY,
            SymbolOrigin.PLUGIN_DOLLAR_T,
            -> TranslationCallStatus.TRANSLATION

            SymbolOrigin.LOCAL_SHADOW,
            SymbolOrigin.NON_I18N,
            -> TranslationCallStatus.NON_TRANSLATION

            SymbolOrigin.UNKNOWN -> TranslationCallStatus.UNKNOWN
        }
    }

    /** 布尔兼容视图：只有「已证明是翻译」才返回 true（供旧调用点 / 折叠等使用）。 */
    fun isTranslationCall(call: JSCallExpression): Boolean =
        statusOf(call) == TranslationCallStatus.TRANSLATION

    /**
     * 名字是否为翻译函数候选（t / tc / \$t / \$tc）。
     * 「候选名 + 无法证明」才进入三态 UNKNOWN 保守跳过；非候选名（普通方法调用）即使
     * 无法证明来源也按普通调用处理，其参数中的中文正常提取。
     */
    fun isTranslationCandidateName(call: JSCallExpression): Boolean {
        val method = call.methodExpression
        val name = when {
            method is com.intellij.lang.javascript.psi.JSReferenceExpression -> method.referenceName
            method != null -> method.text.substringAfterLast('.')
            else -> null
        }
        return name == "t" || name == "tc" || name == "\$t" || name == "\$tc"
    }

    /**
     * 计算字符串字面量在其外层调用上下文中的位置（用于提取 / 替换策略）。
     *
     * 判定规则：
     *  - **UNKNOWN 只看直接调用**（字面量的最近 JSCallExpression）：直接参数是未知调用
     *    （如 `t('中文')` 且 t 无法证明）→ [StringContext.INSIDE_UNKNOWN_CALL] 保守跳过；
     *    更深层祖先的 UNKNOWN（如 `console.log(i18n.t('中文'))` 中的 console.log）**不阻断**
     *    ——否则所有包在未知调用里的真实硬编码都会漏提。
     *  - **TRANSLATION 走祖先链**：直接参数命中（`$t('中文')`）→ [StringContext.DIRECT_TRANSLATION_ARG]；
     *    参数表达式内部命中（`$t(cond ? 'a' : 'b')`）→ [StringContext.INSIDE_TRANSLATION_EXPRESSION]。
     */
    fun contextOf(stringExpr: JSLiteralExpression): StringContext {
        val parent = stringExpr.parent
        val directCall = when {
            parent is JSCallExpression -> parent
            parent.parent is JSCallExpression -> parent.parent as JSCallExpression
            else -> null
        }

        // 直接参数是 UNKNOWN 调用且**名字是翻译候选**（t/tc/\$t/\$tc）→ 三态核心：无法证明来源，
        // 保守跳过（零误改）。非候选名（console.log / alert / foo 等普通方法调用）即使无法证明
        // 也按普通调用处理 → 参数中的中文正常进入提取（宁多提不漏提）。
        if (directCall != null && statusOf(directCall) == TranslationCallStatus.UNKNOWN &&
            isTranslationCandidateName(directCall)
        ) {
            return StringContext.INSIDE_UNKNOWN_CALL
        }
        // 直接参数是已证明的翻译调用 → 完全跳过（已有完整 $t('x')）
        if (directCall != null && statusOf(directCall) == TranslationCallStatus.TRANSLATION) {
            return StringContext.DIRECT_TRANSLATION_ARG
        }

        // 祖先链：字面量嵌套在某个已证明翻译调用的参数表达式里（如 $t(isPinned ? 'a' : 'b')）
        var cursor: PsiElement? = stringExpr.parent
        while (cursor != null) {
            if (cursor is JSCallExpression && cursor !== directCall &&
                statusOf(cursor) == TranslationCallStatus.TRANSLATION
            ) {
                return StringContext.INSIDE_TRANSLATION_EXPRESSION
            }
            cursor = cursor.parent
        }
        return StringContext.NONE
    }
}
