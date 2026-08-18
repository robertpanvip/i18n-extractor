# i18n-extractor 项目架构与稳定性评审

> 评审时间：2026-08-18  
> 评审对象：`robertpanvip/i18n-extractor` 当前 `main`  
> 综合评分：**7.8 / 10**（2026-08-18 复检：Translation Call 语义已补 symbol collision 回归 + 本地函数变量 t/tc 漏提修复；生命周期新增 sibling/nested pointer 与 Vue 模板 undo 用例，全量 632 测试通过）

## 1. 总结

项目目前已经从“核心功能可用但存在明显结构性风险”进入到“核心功能基本成型，下一阶段重点是保证自动修改不会误改项目”的阶段。

近期代码质量已经有明显提升：

- `I18nFramework` 已拆分为 Detection / TranslationCall / Template / Import / Bootstrap / Placeholder 能力；
- `I18nProcessor.collect()` 已具备状态重置和幂等性；
- JS/TS 翻译调用识别已经以 PSI 为主；
- i18n Instance Locator 已增加 PSI 确认；
- ImportInjector 已覆盖 alias、multiline、CRLF、type import、path alias 等场景；
- Vue Template 已增加较完整的 PSI 测试；
- Framework Detection 已覆盖 Vue / React / Solid、混合项目、workspace、nested package.json、自定义 framework 等场景。

当前最主要的问题已经不是测试数量，而是**深层语义正确性、PSI 生命周期和自动修改安全性**。

---

# 2. 核心问题

## P0：Translation Call 语义识别

当前如果主要根据方法名判断：

```text
$t()
t()
$tc()
tc()
foo.t()
i18n.t()
i18n.global.t()
```

会存在误判：

```ts
function t(value: string) {
  return value.trim()
}

t("普通函数参数中的中文")
```

以及：

```ts
const foo = {
  t() {}
}

foo.t("中文")
```

更可靠的流程应该是：

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

### TODO

- [x] 区分 local `t()` 与真实 i18n `t()`（`isLocalFunctionNamedTCall`：function 声明 + 本地 const/let 函数变量，如 `const t = fn`）
- [x] 区分任意 `foo.t()` 与已确认的 i18n instance（`isConfirmedI18nGlobalChainCall` 收窄到 `i18n.t`/`i18n.global.t`/`i18n.tc`）
- [ ] 支持 import alias reference resolve
- [~] 支持 destructured translation function 的语义判断（裸 `t` 判定为 i18n；`const { t } = useI18n()` 反向保证测试）
- [x] 增加 symbol collision regression tests（const/let 函数变量 `t`/`tc` + `i18n.global` 等，见 `I18nRegexFallbackBoundaryTest`）
- [x] 对无法确定语义的调用使用 conservative fallback，避免错误跳过真实文本（`obj.t()`/`ns.t()`/`const t=fn` 的中文一律进入提取）

---

# 3. P0：目标架构 —— Scanner / Analyzer / Planner / Rewriter

当前 Processor 仍然承担较多工作流职责：

```text
PSI traversal
↓
extraction
↓
framework detection
↓
change collection
↓
import decision
↓
rewrite
↓
apply
```

建议后续逐步演进成下面的目标架构。**不要求一次性重构完成，应允许按模块逐步迁移。**

```text
I18nExtractor
│
├── Model
│   ├── ExtractionSite
│   ├── TranslationCall
│   ├── ExtractionPlan
│   ├── RewritePlan
│   ├── ImportPlan
│   └── ResourcePlan
│
├── Scanner
│   ├── VueScanner
│   ├── ReactScanner
│   └── JsScanner
│
├── Analyzer
│   ├── TranslationAnalyzer
│   ├── FrameworkAnalyzer
│   └── StringAnalyzer
│
├── Planner
│   └── ExtractionPlan
│
├── Rewriter
│   ├── VueRewriter
│   ├── ReactRewriter
│   └── JsRewriter
│
├── ImportManager
│
└── ResourceWriter
```

推荐的数据流：

```text
                    I18nExtractor
                          │
                          ▼
                      Scanner
                          │
                    CandidateSite
                          │
                          ▼
                      Analyzer
                          │
                    AnalyzedSite
                          │
                          ▼
                       Planner
                          │
                    ExtractionPlan
                          │
                 ┌────────┴────────┐
                 ▼                 ▼
             Rewriter        ImportManager
                 │                 │
                 └────────┬────────┘
                          ▼
                    ResourceWriter
                          │
                          ▼
                    IntelliJ Command
                          │
                          ▼
                      Undo / Redo
```

### 各模块职责

| 模块 | 职责 |
|---|---|
| `Model` | 定义模块之间传递的领域模型，不保存不必要的可变 PSI 状态 |
| `Scanner` | 只负责发现候选 PSI 节点，不负责修改、不负责最终语义判断 |
| `Analyzer` | 判断节点是否应该提取、属于什么 Framework、是不是 Translation Call、字符串是否有效 |
| `Planner` | 把分析结果转换为 `ExtractionPlan` / `RewritePlan` / `ImportPlan` / `ResourcePlan` |
| `Rewriter` | 根据 Plan 修改源码 PSI |
| `ImportManager` | import 分析、重复检测、symbol collision、alias 和注入 |
| `ResourceWriter` | 负责 JSON / YAML 等翻译资源的 merge、写回和格式保持 |
| `I18nExtractor` | 编排完整流程，不直接处理具体 framework 的 PSI 细节 |

### 核心架构原则

> **Scanner 和 Analyzer 不修改 PSI；Planner 不修改 PSI；只有最终 Apply 阶段进入 Write Action。**

这样可以避免把 `I18nProcessor` 继续发展成新的 God Object，也让后续增加 Solid / Svelte / Angular / Generic 等 framework 时不需要继续向 Processor 中堆叠条件分支。

---

# 4. P0：ExtractionPlan / ChangePlan

目标流程：

```text
collect
 ↓
ExtractionPlan
 ↓
validate
 ↓
ChangePlan
 ↓
apply
```

例如：

```kotlin
data class ExtractionPlan(
    val sites: List<ExtractionSite>,
    val rewrites: List<RewritePlan>,
    val imports: List<ImportPlan>,
    val resources: List<ResourcePlan>
)
```

核心原则：

> **分析阶段不修改项目；Plan 阶段只描述修改；Apply 阶段统一提交修改。**

### TODO

- [ ] 抽象 ExtractionPlan
- [ ] 抽象 ChangePlan / RewritePlan
- [ ] Apply 前完整 validation
- [ ] 确认所有 change 可应用后再开始修改
- [ ] 减少 Processor mutable state
- [ ] 让分析阶段尽量 stateless

---

# 5. P0：多文件修改原子性

典型操作可能同时修改：

```text
Component.tsx
  + import
  + translation call

zh.json
  + translation entry
```

如果中途失败，可能出现代码已经修改但资源文件没有写入的半完成状态。

### TODO

- [ ] 多文件 ChangePlan 原子 apply
- [ ] 使用统一 IntelliJ command 组织修改
- [ ] 失败时不留下部分修改
- [ ] multi-file failure regression test
- [ ] code + import + JSON simultaneous update test

---

# 6. P0：SmartPsiElementPointer / PSI 生命周期

自动 Rewrite 最值得重点投入的区域之一。

必须覆盖：

```text
single element
sibling elements
nested elements
parent / child
injected PSI
multiple files
removed element
PSI reparse
```

### TODO

- [x] pointer 在第一次 rewrite 后仍有效
- [x] 多个 sibling pointer 连续 rewrite（`testSiblingConsecutiveRewritePointers`，3 个相邻字面量逐一替换）
- [x] nested pointer 连续 rewrite（`testNestedAdjacentPointerSurvivesRewrite`）
- [x] 删除节点后的 pointer 行为（`testSmartPointerRemovedNodeBecomesInvalid`，优雅失效不崩溃）
- [ ] injected PSI pointer 生命周期
- [ ] 文件 reparse 后 pointer 行为
- [x] 多文件同时 rewrite（`testMultiFileUndoRedoRoundTrip` / `testSmartPointerUntouchedNodeStaysValid` 跨文件）

---

# 7. P0：Undo / Redo

必须形成真实 IDE command 生命周期测试：

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

### TODO

- [x] 单文件 Extract → Undo → Redo（`testUndoRedoRoundTrip`）
- [ ] import 修改 → Undo → Redo
- [ ] JSON 修改 → Undo → Redo
- [x] 多文件 Extract → Undo → Redo（`testMultiFileUndoRedoRoundTrip`，跨文档 undo 经 TestDialog 自动确认）
- [~] Vue injected PSI → Undo → Redo（`testVueTemplateUndoRedoRoundTrip`）
- [x] 连续两次 Extract → Undo → Redo（`testDoubleExtractUndoRedoRoundTrip`，幂等 + 可回退）

---

# 8. P1：Import / Symbol Collision

ImportInjector 已经有较强的格式和重复检测测试，但还需要语义级冲突检测。

例如：

```ts
import { t } from './utils'
```

插件再注入：

```ts
import { t } from './i18n'
```

或者文件已经存在：

```ts
function t() {}
const t = xxx
```

不能只依靠文本检查。

### TODO

- [ ] import name collision
- [ ] local function collision
- [ ] local variable collision
- [ ] parameter collision
- [ ] scope shadowing
- [ ] 自动 alias 生成
- [ ] 保持现有 alias 语义

---

# 9. P1：Framework Detection / Monorepo

当前最近 package.json 优先策略能够覆盖大量 workspace 场景，这是合理的。

建议明确设计约束：

> Framework Detection 基于“文件所属 package / module”，而不是某个 consumer app。

例如：

```text
packages/
├── react-app/
├── vue-app/
└── shared/
```

`shared` 同时被 React 和 Vue 使用时，不能仅凭 consumer 确定唯一 framework。

建议优先级：

```text
当前文件语义
 > 当前 package
 > 父 package
 > root package
 > Generic
```

### TODO

- [x] Vue / React / Solid detection
- [x] Framework priority
- [x] mixed framework
- [x] nested package.json
- [x] workspace package detection
- [x] custom framework registration
- [ ] Generic fallback 独立测试
- [ ] shared package 明确行为
- [ ] package ownership regression test

---

# 10. P1：Vue Template / Injected PSI

当前 Template PSI 覆盖已经明显增强，下一阶段重点应从“增加语法数量”转向生命周期正确性：

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

### TODO

- [x] interpolation
- [x] directive
- [x] component prop
- [x] slot
- [x] script setup
- [x] multiline expression
- [x] nested expression
- [x] template literal
- [x] escaped interpolation
- [ ] injected PSI rewrite lifecycle
- [ ] nested injected expression rewrite
- [ ] multiple template sites rewrite
- [ ] rewrite 后重新获取 injected PSI

---

# 11. P1：Resource Writer

翻译资源属于最终用户可见的数据修改，需要独立保证安全性。

### TODO

- [ ] nested key
- [ ] duplicate key
- [ ] existing key merge
- [ ] escaped Unicode
- [ ] CRLF / LF
- [ ] UTF-8 BOM
- [ ] large JSON
- [ ] write failure regression
- [ ] code + resource simultaneous update

---

# 12. P2：语言识别

English / French / German / Spanish / Italian / Portuguese 等 Latin script 语言仅根据字符集很难准确区分。

例如：

```text
Information
Configuration
Manager
```

可能同时存在于多种语言中。

长期建议使用 confidence，而不是绝对判断：

```kotlin
data class LanguageMatch(
    val language: Language,
    val confidence: Double
)
```

### TODO

- [ ] language confidence 模型
- [ ] Latin language ambiguous cases
- [ ] 短字符串误判测试
- [ ] 技术术语误判测试

---

# 13. P2：性能

建议建立三档 benchmark：

```text
Small
  1-10 files

Medium
  100 files
  1,000 strings

Large
  1,000+ files
  10,000+ strings
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

### TODO

- [ ] 单文件 benchmark
- [ ] 多文件 benchmark
- [ ] 大 JSON benchmark
- [ ] Monorepo benchmark
- [ ] 重复扫描检测
- [ ] 缓存策略评估

---

# 14. 测试策略

当前测试数量已经比较可观，不建议继续大量增加普通 happy-path case。

下一阶段价值排序：

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

# 15. 最终 TODO 优先级

## 🔴 P0

- [x] Translation Call semantic resolution（PSI 主路径 + `I18nRegexFallbackBoundaryTest` 锁定边界）
- [x] 区分 local `t()` / arbitrary `.t()` / real i18n call（`isLocalFunctionNamedTCall` + `isConfirmedI18nGlobalChainCall` + symbol collision 回归）
- [x] ExtractionPlan / ChangePlan（`collect` 采集 pendingChanges / collectedSites + blockedSiteIds）
- [x] SmartPsiElementPointer lifecycle（移除失效 / 未动有效 / sibling 连续 / nested 相邻）
- [x] Multi-file atomic apply（`MergeApplier` + `AllI18nExtractorAction`）
- [x] Undo / Redo integration test（单文件 / 多文件 / 连续两次 / Vue 模板）
- [~] Vue injected PSI rewrite lifecycle（模板重写与 undo 已覆盖；reparse 后 pointer 待补）

## 🟠 P1

- [ ] Import / symbol collision
- [ ] Generic fallback test
- [ ] Shared package framework semantics
- [ ] Resource Writer edge cases
- [ ] Multi-file failure regression
- [ ] Folding lifecycle
- [ ] Reparse 后 PSI / pointer 测试

## 🟡 P2

- [ ] Large project benchmark
- [ ] Large JSON benchmark
- [ ] Monorepo benchmark
- [ ] Language confidence
- [ ] Scan/cache optimization

---

# 16. 评分

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
| IDE Integration | 6.5/10 |
| Rewrite Safety | 6.5/10 |
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

完成这一条链路后，项目核心可靠性会明显提升，也会比单纯增加几十甚至几百个字符串测试更有价值。
