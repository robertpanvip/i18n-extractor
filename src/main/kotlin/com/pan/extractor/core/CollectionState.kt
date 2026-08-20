package com.pan.extractor.core

/**
 * 「收集期结果/状态」窄契约 —— 供 `JsStringCollector` / `ImportManager` 读写分析器身上
 * 的结果数据，而无需依赖整个 [com.pan.extractor.analyzer.I18nAnalyzer]。由分析器实现。
 *
 * §21 目标：能力面（模板/字符串原语、项目对象）走 [I18nProcessorContract]；结果/状态
 * （本次提取的 [extractedStrings] 与探测到的 [tFunctionName]）走本面。二者分离后，
 * 收集/注入器解耦于「处理器 / 分析器」的具体实现，只依赖最小能力面 —— 与第 3 步接口隔离一致。
 */
interface CollectionState {
    /** 本次收集到的 key → 中文 资源映射（跨多次 collect 累积但每次 start 重置）。 */
    val extractedStrings: MutableMap<String, String>

    /** 探测到的翻译函数名（`t` / `$t` / `i18n.t` …），写入替换文本时使用。 */
    var tFunctionName: String

    /**
     * 全局 `$t` 别名注入标记（P1 收敛三岔）：由策略 [onGlobalDollarTNeeded] 回调在纯工具
     * 文件命中时写入；[ImportManager.run]/[InjectionDecision] 据此决定是否注入全局别名。
     */
    var needInjectGlobalDollarT: Boolean
}