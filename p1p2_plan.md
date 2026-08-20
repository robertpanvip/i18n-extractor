# P1 / P2 落地计划

> 依据《PROJECT_ANALYSIS.md》§26 P1/P2 清单，从已完成的 P0（A/B/C 组）自然延续推进。
> 执行原则：**测试先行、风险从低到高、每批可验证**。
> 本文件随实施进度勾选，作为分批对照的单一事实来源。

## 本批聚焦（用户已确认的四个方向）

| 方向 | 对应 §26 | 类别 |
|---|---|---|
| 翻译语义强化（延续 B 组） | P1-翻译·re-export | 测试扩充 |
| React Intl 扩展 | P1-React Intl | 测试扩充（黑盒） |
| Hook 与生命周期测试 | P1-Hook·生命周期 | 测试扩充 |
| 类型化重构（P2） | P2 | 轻量重构 |

## 批次 A：翻译语义强化（纯 Postive/Negative 矩阵扩充）

- [x] A1. 跨文件**多级** barrel 递归 re-export（A→B→vue-i18n）判定为 TRANSLATION
- [x] A2. namespace 再导出 + 别名（`import * as ns from '@/i18n'` 且 i18n re-export 自框架）→ TRANSLATION
- [x] A3. `useI18n()` 解构出 `t` 但同函数另有**本地同名函数**遮蔽 → 不误判 TRANSLATION（Negative）
- [x] A4. 普通模块 `import { t } from '@/utils'` 且 utils 非 re-export 框架 → NON_TRANSLATION（Negative）
- 可验证出口：`SymbolSemanticMatrixTest` 新增用例全部通过，`UNKNOWN` 仍为保守、非高风险改写

## 批次 B：React Intl 黑盒（P1：FormattedMessage / defineMessages）

- [x] B1. `defineMessages` / `defineIntlConfig` 包裹的对象字面量能被解析为资源（已有解析基础，补黑盒断言）
- [x] B2. `formatMessage({ id: '已翻译' })` 的**已有翻译**识别（已被 formatMessage 包住的 key 不再重复提取）
- [x] B3. `FormattedMessage`（JSX 组件形态，P1）识别为翻译（不重复提取）
- 可验证出口：黑盒 extract / apply 一致，`ReactI18nProcessorTest` / 新增用例通过

## 批次 C：Hook + 生命周期测试（P1）

- [x] C1. Vue directive(`v-t`) + interpolation(`{{ }}`) 混合生命周期，改写后可 reparse 无错且不重复提取
  - `VueLifecycleTest.testVueDirectiveAndInterpolationMixedLifecycle`：同一模板 v-t 指令值 + mustache 插值，第一遍各自改写、第二遍已翻译不再提取、二次 apply 幂等。
- [x] C2. React Hook hook 注入后 `useTranslation()` 解构函数行为的生命周期（改写 → 重新 collect → 幂等）
  - `ReactI18nProcessorTest.testReactUseTranslationHookLifecycleIdempotent`：注入 import/const → JSX 文案改写为 `{ t(\`...\`) }` → 第二遍 extractedStrings 为空、源码与第一遍逐字一致。
- 可验证出口：`VueLifecycleTest`（4 用例）/ `ReactI18nProcessorTest`（46 用例）全部通过，0 失败。

## 批次 D：P2 类型化重构（轻量）

- [x] D1. `RewritePlan` / `ExtractionSite` 明显 boolean 模型收紧（若存在可安全收敛的字段）
  - `RewritePlan`：曾作为三个**互斥布尔**的 `isJSX`/`isDirective`/`isAngular` 收紧为单一 `AttributeRenderForm` 枚举（VUE_BINDING / JSX / DIRECTIVE / ANGULAR），从类型上杜绝「JSX ∧ directive」非法组合；`recordPlan`/`recordRewrite`/`RewriteInterpreter`/`SourceRewriter` 全链路同步，行为 1:1。
  - `CollectedPlan`：用来编码可选结果的 `reactFallbackChecked` + `reactFallbackResult` 双布尔收紧为单个可空 `reactFallback: Boolean?`（null = 未计算）。
- [x] D2. `Framework capability API`：把 `paramKeyNeedsQuote` / `placeholderFor` 等能力从字符串检查集中为能力查询（若改动面可控，否则仅记录）
  - 经核查，该能力**已在目标架构中落位**为 `PlaceholderStrategy` 接口（`paramKeyNeedsQuote` / `placeholderFor` / `paramKey` / `interpolatePlaceholders`），生产调用方 `JsStringCollector` 已走 `fw.paramKeyNeedsQuote` 能力查询而非字符串/框架名硬编码；各策略均已实现，本轮无需新增改动，仅记录确认。
- 可验证出口：编译通过；`VueI18nProcessorTest` / `VueLifecycleTest` / `AngularI18nProcessorTest` / `ReactI18nProcessorTest` / `I18nVueTemplatePsi{,2}Test` / `MergeApplierTest` / `ProjectChangePlannerTest` / `ProjectPreflightValidatorTest` 全部通过（`--no-build-cache`，规避 Gradle 构建缓存陈旧类）。

## 进度

- 批次 A（翻译语义强化）：A1-A4 全部完成，`SymbolSemanticMatrixTest` 23 用例通过。
- 批次 B（React Intl 黑盒）：B1-B3 全部完成，`ReactI18nProcessorTest` 通过（含新增 3 条结构化识别用例）。
- 批次 C（Hook + 生命周期）：C1（Vue 指令+插值混合）/ C2（React useTranslation 幂等）完成。
- 批次 D（P2 类型化）：D1（`RewritePlan` → `AttributeRenderForm` 枚举、`CollectedPlan` → 可空 `reactFallback`）/ D2（`PlaceholderStrategy` 能力 API 已落位）完成。
- 2026-08-20 全量验证（`--no-build-cache`）：SymbolSemanticMatrixTest / ReactI18nProcessorTest / VueLifecycleTest / VueI18nProcessorTest / AngularI18nProcessorTest / I18nVueTemplatePsi{,2}Test / MergeApplierTest / ProjectChangePlannerTest / ProjectPreflightValidatorTest 全部通过，BUILD SUCCESSFUL。