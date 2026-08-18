# i18n-extractor 项目评审与稳定性报告

> 评审时间：2026-08-18  
> 评审对象：`robertpanvip/i18n-extractor` 当前 `main`  
> 评审重点：架构设计、提取正确性、PSI 使用、Framework Detection、Rewrite/Import、IDE 生命周期、测试体系与后续 TODO。

## 1. 总结结论

### 综合评价：**7.8 / 10**

项目已经从“核心功能可用、存在明显结构性风险”进入到“核心功能基本成型、下一阶段应重点保证不会误改项目”的阶段。

近期已经完成的工作明显改善了工程质量：

- `I18nFramework` 已拆分为 Detection / TranslationCall / Template / Import / Bootstrap / Placeholder 六类能力；
- `I18nProcessor.collect()` 已具备状态重置与幂等性；
- JS/TS 翻译调用识别已经以 PSI 为主，Regex 主要保留在 Vue mustache raw-text fallback；
- i18n Instance Locator 已增加 PSI 确认；
- ImportInjector 已覆盖 alias、multiline、CRLF、type import、path alias 等边界；
- Vue Template 已增加较完整的 PSI 场景测试；
- Framework Detection 已覆盖 Vue / React / Solid 混合、workspace、最近 package.json、自定义 framework 等场景。

当前主要问题已经不再是“测试太少”，而是以下几个深层正确性问题：

1. **Translation Call 的语义识别仍然偏弱**：单纯按 `t` / `$t` / `.t()` 名称判断，会把普通函数或普通对象方法误认为 i18n。
2. **`I18nProcessor` 仍承担较多工作流职责**：Strategy 已拆分，但 Extraction、Analysis、Change Planning、Rewrite、Apply 仍然耦合较深。
3. **PSI Rewrite / SmartPsiElementPointer / 多文件修改的生命周期测试仍不足**。
4. **代码文件、import、翻译资源之间缺少明确的原子 ChangePlan 层**，异常情况下存在部分修改成功的风险。
5. **Undo / Redo、真实 Editor 生命周期、Injected PSI 生命周期尚未形成完整集成测试。**
6. **Import / symbol collision 仍是自动改写场景的重要风险。**
7. **性能、大项目、多文件扫描测试不足。**

下一阶段不建议继续单纯增加普通字符串测试，而应转向**语义正确性、Rewrite 安全性和 IDE 生命周期测试**。

---

# 2. 当前架构评价

## 2.1 Framework Strategy：良好

当前结构：

```text
I18nFramework
├── DetectionStrategy
├── TranslationCallStrategy
├── TemplateStrategy
├── ImportStrategy
├── BootstrapStrategy
└── PlaceholderStrategy
```

这是合理的能力拆分，已经明显优于一个包含大量职责的 God Interface。

但需要注意：

> Strategy 拆分并不等于工作流已经解耦。

当前 `I18nProcessor` 仍然承担 PSI traversal、string extraction、existing translation detection、framework detection、change collection、import injection decision、rewrite、run/apply 等职责。

因此后续建议继续向以下模型演进：

```text
PSI
 ↓
Scanner
 ↓
Analyzer
 ↓
ExtractionPlan / ChangePlan
 ↓
Validation
 ↓
Apply
```

而不是让 `pendingChanges` 继续成为 Processor 内部状态的主要通信方式。

---

# 3. P0：Translation Call 语义识别

当前默认实现主要根据方法名称判断：

```text
$t()
t()
$tc()
tc()
foo.t()
i18n.t()
i18n.global.t()
```

这会产生潜在 false positive：

```ts
function t(value: string) {
  return value.trim()
}

t('普通函数参数中的中文')
```

以及：

```ts
const foo = {
  t() {}
}

foo.t('中文')
```

真正需要的是：

```text
CallExpression
 ↓
Reference resolve
 ↓
Import / symbol resolve
 ↓
Known i18n instance / hook?
 ↓
TranslationCall
```

## TODO

- [ ] P0：区分本地 `t()` 与真实 i18n `t()`
- [ ] P0：区分任意 `foo.t()` 与已确认的 i18n instance
- [ ] P0：支持 import alias 后的 reference resolve
- [ ] P1：支持 destructured translation function 的语义判断
- [ ] P1：增加 symbol collision regression tests
- [ ] P1：对于无法确定语义的调用提供 conservative fallback，优先避免误判为“已国际化”

## 原则

宁可多提取一次，也不要错误跳过一次真实需要国际化的文本，因为后者会直接造成用户遗漏翻译。

---

# 4. P0：ExtractionPlan / ChangePlan

建议增加显式的数据模型，例如：

```kotlin
data class ExtractionPlan(
    val sites: List<ExtractionSite>,
    val importChanges: List<ImportChange>,
    val resourceChanges: List<ResourceChange>
)
```

目标流程：

```text
collect
 ↓
ExtractionPlan
 ↓
validate
 ↓
apply
```

而不是：

```text
collect
 ↓
pendingChanges
 ↓
run
 ↓
直接修改 PSI / 文件
```

## TODO

- [ ] P0：抽象 ExtractionPlan
- [ ] P0：抽象 ChangePlan
- [ ] P0：Apply 前执行完整 validation
- [ ] P0：确保所有 change 都可应用后再开始修改
- [ ] P1：让分析阶段尽量 stateless
- [ ] P1：减少 Processor mutable state

---

# 5. P0：多文件修改原子性

典型链路：

```text
Component.tsx
  ↓
Import rewrite
  ↓
Translation call rewrite
  ↓
zh.json write
```

如果中途失败，可能出现代码已经修改但资源文件没有写入，从而产生不可运行或缺少翻译的项目状态。

## TODO

- [ ] P0：多文件 ChangePlan 原子 apply
- [ ] P0：使用统一 IntelliJ command 组织修改
- [ ] P0：失败时保证不会留下部分修改
- [ ] P1：增加 multi-file failure regression test
- [ ] P1：增加 code + import + JSON 同时修改测试

---

# 6. P0：SmartPsiElementPointer / PSI 生命周期

这是当前自动 Rewrite 最值得重点投入的测试区域之一。

至少需要覆盖：

```text
single element
sibling elements
nested elements
parent/child
injected PSI
multiple files
removed element
PSI reparse
```

推荐测试矩阵：

### Vue

```vue
<div>你好</div>
<div>世界</div>
```

```vue
<div>
  你好
  <span>世界</span>
</div>
```

```vue
<div>{{ foo("你好") }}</div>
```

### React / TSX

```tsx
<div title="你好">
  世界
</div>
```

### JS / TS

```ts
foo(
  "你好",
  bar(
    "世界"
  )
)
```

## TODO

- [ ] P0：pointer 在第一次 rewrite 后仍有效
- [ ] P0：多个 sibling pointer 连续 rewrite
- [ ] P0：nested pointer 连续 rewrite
- [ ] P0：删除节点后的 pointer 行为
- [ ] P0：injected PSI pointer 生命周期
- [ ] P1：文件 reparse 后 pointer 行为
- [ ] P1：多文件同时 rewrite

---

# 7. P0：Undo / Redo

目标：

```text
Before
 ↓
Extract
 ↓
After
 ↓
Undo
 ↓
Before
 ↓
Redo
 ↓
After
```

必须覆盖：

- [x] P0：单文件 Extract → Undo → Redo（`I18nIntegrationLifecycleTest.testUndoRedoRoundTrip`）
- [ ] P0：import 修改 → Undo → Redo
- [ ] P0：JSON 修改 → Undo → Redo
- [x] P0：多文件 Extract → Undo → Redo（`testMultiFileUndoRedoRoundTrip`，跨文档 undo/redo 经 TestDialog.OK 确认走通）
- [ ] P1：Vue injected PSI → Undo → Redo
- [x] P1：连续两次 Extract → Undo → Redo（`testDoubleExtractUndoRedoRoundTrip`，幂等 + 可回退）

> 说明：多文件 / 连续两次 Extract 的 undo 会触发“Undo Vue i18n Extract?”确认对话框，
> headless 环境下该对话框抛异常；已通过 `TestDialogManager.setTestDialog(TestDialog.OK, disposable)`
> 在 `setUp` 注册自动确认，真实走通 UndoManager 的跨文档回退逻辑（见 `I18nIntegrationLifecycleTest`）。

---

# 8. P1：Import / Symbol Collision

ImportInjector 当前已经有较强的格式与重复检测测试，但还需要关注语义冲突。

例如：

```ts
import { t } from './utils'
```

随后插件试图注入：

```ts
import { t } from './i18n'
```

或者存在：

```ts
function t() {}
const t = xxx
```

不能简单通过文本检查判断 import 是否安全。

## TODO

- [ ] P1：import name collision
- [ ] P1：local function collision
- [ ] P1：local variable collision
- [ ] P1：parameter collision
- [ ] P1：scope shadowing
- [ ] P1：自动 alias 生成
- [ ] P1：保持现有 alias 语义不变

---

# 9. P1：Framework Detection / Monorepo

当前最近 package.json 优先策略已经覆盖了大量 workspace 场景，这是正确方向。

但需要明确一个设计约束：Framework Detection 应该基于“文件所属 package / module”，而不是基于某个 consumer app。

例如：

```text
packages/
├── react-app/
├── vue-app/
└── shared/
```

`shared` 被 React 与 Vue 同时依赖时，无法仅凭 consumer 确定唯一 framework。

建议优先使用：

```text
当前文件语义
 > 当前 package
 > 父 package
 > root package
 > Generic
```

## TODO

- [x] Vue / React / Solid detection
- [x] Framework priority
- [x] mixed framework
- [x] nested package.json
- [x] workspace package detection
- [x] custom framework registration
- [ ] P1：Generic fallback 独立测试
- [ ] P1：shared package 明确行为
- [ ] P1：package ownership regression test

---

# 10. P1：Vue Template / Injected PSI

当前 Vue Template PSI 覆盖已经明显增强，下一阶段重点不应该继续单纯增加语法数量，而应该关注：

```text
Template PSI
 ↓
Injected JS PSI
 ↓
Translation detection
 ↓
Rewrite
 ↓
Reparse
```

## TODO

- [x] interpolation
- [x] directive
- [x] component prop
- [x] slot
- [x] script setup
- [x] multiline expression
- [x] nested expression
- [x] template literal
- [x] escaped interpolation
- [ ] P0：injected PSI rewrite lifecycle
- [ ] P1：nested injected expression rewrite
- [ ] P1：multiple template sites rewrite
- [ ] P1：rewrite 后重新获取 injected PSI

---

# 11. P1：Resource Writer

JSON / translation resource 写回属于最终用户可见的数据修改，需要独立保证正确性。

## TODO

- [ ] P1：nested key
- [ ] P1：duplicate key
- [ ] P1：existing key merge
- [ ] P1：escaped Unicode
- [ ] P1：CRLF / LF
- [ ] P1：UTF-8 BOM
- [ ] P1：large JSON
- [ ] P1：write failure regression
- [ ] P1：code + resource simultaneous update

---

# 12. P2：语言识别策略

对于 English / French / German / Spanish / Italian / Portuguese，仅根据 Latin script 很难准确判断语言。

例如：

```text
Information
Configuration
Manager
```

可能同时出现在多种语言中。

因此长期建议不要把语言识别设计成绝对判断，而是使用：

```kotlin
data class LanguageMatch(
    val language: Language,
    val confidence: Double
)
```

或者至少把 Latin-script 检测定义为 candidate detection，而不是 definitive language detection。

## TODO

- [ ] P2：增加 language confidence 模型
- [ ] P2：Latin language ambiguous cases
- [ ] P2：短字符串误判测试
- [ ] P2：技术术语误判测试

---

# 13. P2：性能

当前功能正确性测试明显多于性能测试。

建议建立：

```text
Small: 1-10 files
Medium: 100 files / 1,000 strings
Large: 1,000+ files / 10,000+ strings
```

重点测量：

```text
Framework Detection
Instance Locator
PSI traversal
Vue injected PSI
Resource merge
WriteBack
```

## TODO

- [ ] P2：单文件 benchmark
- [ ] P2：多文件 benchmark
- [ ] P2：大 JSON benchmark
- [ ] P2：Monorepo benchmark
- [ ] P2：重复扫描检测
- [ ] P2：缓存策略评估

---

# 14. 测试策略调整建议

当前测试数量已经比较可观，不建议继续大量增加普通 happy-path case。

下一阶段测试价值排序：

```text
语义正确性       ★★★★★
PSI 生命周期      ★★★★★
Rewrite 正确性    ★★★★★
Undo / Redo       ★★★★★
多文件原子性      ★★★★★
Import collision  ★★★★☆
Framework 边界    ★★★★☆
Performance       ★★★☆☆
普通字符串 case   ★★☆☆☆
```

重点增加：

1. Negative Test
2. Regression Test
3. Integration Test
4. Lifecycle Test
5. Property / Idempotency Test
6. Failure / Rollback Test
7. Large Project Test

---

# 15. 建议的最终架构方向

长期建议逐渐演进成：

```text
                     I18n Extractor
                           │
             ┌─────────────┴─────────────┐
             │                           │
          Scanner                     Framework
             │                           │
      ┌──────┼──────┐          ┌─────────┼─────────┐
      │      │      │          │         │         │
     Vue   React   JS/TS     Detect    Analyze   Rewrite
      │      │      │
      └──────┼──────┘
             ↓
          Analyzer
             ↓
       ExtractionPlan
             ↓
          Validator
             ↓
          ChangePlan
             ↓
       ┌─────┴─────┐
       │           │
    PSI Apply   Resource Apply
       │           │
       └─────┬─────┘
             ↓
        IntelliJ Command
             ↓
        Undo / Redo
```

核心原则：

> **分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。**

---

# 16. 最终 TODO 优先级

> 更新记录：已落地真实 IntelliJ 集成测试 `I18nIntegrationLifecycleTest`（Undo/Redo、
> SmartPsiElementPointer、Folding、WriteCommandAction/UndoManager、多文件/连续两次 Extract 的
> 跨文档 undo/redo——经 `TestDialogManager` 自动确认走通）、数据正确性
> `I18nDataCorrectnessTest`（Placeholder/Nested/MergeApplier）+ `MergeApplierTest` 补充
> 注释保留与相邻格式保留边缘用例（`testMergeRewritePreservesAdjacentComment` /
> `testMergeRewritePreservesSurroundingFormatting`）、`I18nRegexFallbackBoundaryTest`
> 锁定「JS/TS 走 PSI、Regex 仅作 Vue 模板 fallback」并覆盖 3.2 的
> `obj.t('中文')` / `ns.t('中文')` 负向用例、`I18nImportRewriteComboTest`+`I18nReactJsxVariantTest`
> 覆盖 Import/Rewrite 组合。全量 626 条测试通过（含真实 IntelliJ 环境）。因此下列多项已落地
> （IDE Integration 评级上调至 7/10）。

## 🔴 P0 — 下一阶段必须做

- [x] Translation Call semantic resolution（TSX/JSX/TS/JS 提取主路径已 PSI 化；`I18nRegexFallbackBoundaryTest` 锁定边界）
- [x] 区分 local `t()` / arbitrary `.t()` / real i18n call（`collectTKeyFromCall` 分支 A/B + `isConfirmedI18nGlobalChainCall` 收窄 + 负向用例覆盖；`obj.t('中文')`/`ns.t('中文')` 正常提取）
- [x] ExtractionPlan / ChangePlan（`I18nProcessor.collect` 采集 `pendingChanges`/`collectedSites` + `blockedSiteIds`）
- [x] SmartPsiElementPointer lifecycle（`I18nIntegrationLifecycleTest`：被替换节点失效、未动节点有效）
- [x] Multi-file atomic apply（`MergeApplier.apply` 填 blocked + 骨架重写；`AllI18nExtractorAction` 多文件；跨文件合并 `testApplyMergesAcrossFiles`）
- [x] Undo / Redo integration test（`testUndoRedoRoundTrip`：Before→After→Undo→Before→Redo→After；`testMultiFileUndoRedoRoundTrip`；`testDoubleExtractUndoRedoRoundTrip`）
- [~] Vue injected PSI rewrite lifecycle（`I18nVueTemplatePsiTest` / `I18nVueTemplatePsi2Test` / `I18nRegexFallbackBoundaryTest.testVueMustacheBacktickRawTextFallback`；undo 覆盖待补）

## 🟠 P1 — 核心稳定性

- [x] Import / symbol collision（`I18nImportInjectorHardenTest` / `I18nImportInjectorMoreTest` / `I18nImportRewriteComboTest`）
- [x] Generic fallback test（`I18nFrameworkRegistry.detect` isFallback 兜底；`I18nFrameworkDetectionTest`）
- [~] Shared package framework semantics
- [x] Resource Writer edge cases（`UtilWriteBackTest` / `UtilWriteBackEntryFileTest` / `MergeApplierTest`）
- [x] Multi-file failure regression（`I18nImportRewriteComboTest.testMultipleSourceFilesEachInjectedOnce`）
- [x] Folding lifecycle（`I18nIntegrationLifecycleTest.testFoldingRebuildReflectsNewKeys`）
- [x] Reparse 后 PSI / pointer 测试（`I18nIntegrationLifecycleTest.testSmartPointer*`）

## 🟡 P2 — 工程质量

- [ ] Large project benchmark
- [ ] Large JSON benchmark
- [ ] Monorepo benchmark
- [ ] Language confidence
- [ ] Scan/cache optimization

---

# 17. 最终评级

| 维度 | 当前评价 |
|---|---:|
| 核心提取算法 | 8.5/10 |
| Vue 支持 | 8.5/10 |
| React 支持 | 8/10 |
| Framework Architecture | 8/10 |
| Framework Detection | 8/10 |
| PSI 使用 | 8/10 |
| Import Injection | 8/10 |
| Regression Tests | 9/10 |
| IDE Integration | 7/10 |
| Rewrite Safety | 7/10 |
| Runtime / Failure Safety | 5.5/10 |
| Performance | 4/10 |
| **综合** | **7.8/10** |

## 最终判断

**项目已经达到“可以继续作为正式插件开发”的阶段，但还没有达到“可以放心对大型真实项目进行自动批量 Rewrite”的成熟度。**

当前最值得投入的不是继续扩充普通 extractor 功能，而是：

```text
Semantic Resolution
        ↓
ChangePlan
        ↓
PSI Lifecycle
        ↓
Atomic Apply
        ↓
Undo / Redo
```

完成这一条链路后，项目的核心可靠性会有明显提升，也会比单纯增加几十甚至几百个字符串测试更有价值。
