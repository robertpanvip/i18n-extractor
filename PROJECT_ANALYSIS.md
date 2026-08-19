# i18n-extractor 项目架构与稳定性评审

> 评审时间：2026-08-19  
> 评审对象：`robertpanvip/i18n-extractor` 当前 `main`  
> 综合评分：**8.3 / 10**

## 1. 总结

项目已经从“核心功能可用但存在明显结构性风险”进入到“核心功能基本成立，下一阶段重点是保证自动修改不会误改项目”的阶段。

近期已经完成的工作明显改善了工程质量：

- `I18nFramework` 已拆分为 Detection / TranslationCall / Template / Import / Bootstrap / Placeholder 能力；
- `I18nProcessor.collect()` 已具备状态重置和幂等性；
- JS/TS 翻译调用识别已经从简单名称匹配逐步转向 PSI / symbol 语义判断；
- local `t` / `tc`、普通对象 `foo.t()`、import alias、destructured translation function 等场景已经有针对性处理；
- 无法确认语义时采用 conservative fallback，避免错误跳过真实需要国际化的文本；
- i18n Instance Locator 已增加 PSI 确认；
- ImportInjector 已覆盖 alias、multiline、CRLF、type import、path alias 等边界；
- Vue Template 已增加较完整的 PSI 场景测试；
- Framework Detection 已覆盖 Vue / React / Solid、混合项目、workspace、nested package.json、自定义 framework 等场景；
- Resource Writer 和 Import / Symbol collision 已有较充分的边界测试；
- Undo / Redo、多文件修改、连续 Extract 等生命周期测试已经开始建立。

当前最主要的问题已经不是“测试数量不够”，而是**深层语义正确性、PSI 生命周期、自动修改原子性以及架构解耦**。

---

# 2. 当前评分

| 模块 | 当前评价 |
|---|---:|
| Framework Detection | **8.5 / 10** |
| Vue 支持 | **8.0 / 10** |
| React 支持 | **8.5 / 10** |
| Translation Detection | **8.0 / 10** |
| Import Rewrite | **8.5 / 10** |
| Resource Writer | **8.0 / 10** |
| PSI 使用 | **8.0 / 10** |
| 测试体系 | **8.5 / 10** |
| 生命周期测试 | **7.0 / 10** |
| 架构解耦 | **6.8 / 10** |
| 自动修改安全性 | **7.5 / 10** |
| 性能 | **6.5 / 10** |

### 综合：**8.3 / 10**

项目已经进入可以继续产品化的阶段。下一阶段不建议单纯追求更多功能，而应优先建立稳定的代码转换流水线。

---

# 3. 核心架构现状

当前 `I18nProcessor` 仍然承担较多工作流职责：

```text
PSI traversal
↓
string extraction
↓
translation detection
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

虽然 Strategy 已经拆分，但 Workflow 层仍然存在较强耦合。`I18nProcessor` 中仍存在 `pendingChanges`、`collectedSites`、`extractedStrings` 等较多可变状态。

这意味着：

> **Strategy 拆分已经完成了一部分，但 Workflow 解耦还没有真正完成。**

继续向 `I18nProcessor` 中增加 Framework 分支、PSI 特殊处理或 Rewrite 逻辑，会逐渐形成新的 God Object。

---

# 4. P0：落地 Scanner / Analyzer / Planner / Rewriter 架构

建议逐步演进为：

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
                       Validate
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

## 各模块职责

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

### 核心原则

> **Scanner 和 Analyzer 不修改 PSI；Planner 不修改 PSI；只有最终 Apply 阶段进入 Write Action。**

不要求一次性完成重构。建议采用渐进式迁移：

```text
旧 I18nProcessor
      │
      ├── Scanner
      ├── Analyzer
      ├── Planner
      │
      └── 暂时保留旧 Apply 逻辑
```

先把数据模型和分析阶段抽出来，再逐步迁移 Rewrite / Import / Resource。

---

# 5. P0：ExtractionPlan / ChangePlan

当前 `pendingChanges` 已经承担了一部分中间状态职责，但它仍然是 Processor 内部可变状态，不足以表达一次完整的跨文件修改。

建议增加显式领域模型：

```kotlin
data class ExtractionPlan(
    val sites: List<ExtractionSite>,
    val rewrites: List<RewritePlan>,
    val imports: List<ImportPlan>,
    val resources: List<ResourcePlan>
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
ChangePlan
 ↓
apply
```

## TODO

- [ ] P0：建立 `ExtractionSite` / `RewritePlan` / `ImportPlan` / `ResourcePlan`
- [ ] P0：建立统一 `ExtractionPlan`
- [ ] P0：Apply 前执行完整 validation
- [ ] P0：确保所有 change 都可以应用后再开始修改
- [ ] P1：让分析阶段尽量 stateless
- [ ] P1：逐步减少 Processor mutable state

---

# 6. P0：多文件修改原子性

典型一次提取可能同时修改：

```text
Component.tsx
    ↓
import
    ↓
translation call
    ↓
zh.json
```

如果代码和 import 修改成功，而 resource 写入失败，就可能产生半修改状态。

建议最终统一成：

```text
ExtractionPlan
      ↓
Validate ALL
      ↓
Single WriteCommand / unified command
      ↓
Apply
      ↓
UndoManager
```

## TODO

- [x] P0：多文件 ChangePlan 原子 apply —— `orchestrator.I18nExtractionOrchestrator.apply` 以单 WriteCommandAction 收敛 import/$t/骨架/资源写回
- [x] P0：统一 IntelliJ Command 组织修改 —— `MergeApplier.apply(…, edtRunner=null)` 所有写入在当前 command 内同步执行
- [x] P0：Apply 前完成所有可失败操作的 validation —— `MergeApplier.apply` 首行调用 `ChangeValidator.validateAllModifiableSites`
- [x] P0：失败时避免留下部分修改 —— 单 command 整体回滚；`testApplyThrowsBeforeAnyWriteWhenSiteInvalid` 验证校验失败前不产生任何写入
- [x] P1：增加 multi-file failure regression test —— `MergeApplierTest.testApplyThrowsBeforeAnyWriteWhenSiteInvalid`（校验失败零写入）
- [x] P1：增加 code + import + JSON 同时修改测试 —— `MergeApplierTest.testSingleCommandCodeImportResourceUndoRedo`（代码+import+资源同组 undo/redo 通过）

> 注意：真正的“事务”不能只依赖异常捕获。应尽可能在进入 Write Action 前完成路径、pointer、resource、import collision 等验证；对于无法提前验证的操作，需要设计回滚或恢复策略。

---

# 7. P0：Translation Call 语义识别

当前 Translation Call 已明显优于简单 Regex，但这是后续仍需要持续保证的核心能力。

需要区分：

```text
t()
$t()
tc()
$tc()
i18n.t()
i18n.global.t()
foo.t()
obj.t()
```

推荐流程：

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

## 已完成方向

- [x] local function / variable `t` / `tc` 识别
- [x] 普通对象 `foo.t()` / `obj.t()` 保守处理
- [x] import alias reference resolve
- [x] destructured translation function 识别
- [x] symbol collision regression tests
- [x] 无法确认语义时采用 conservative fallback

## TODO

- [ ] P0：继续完善 `t` / `$t` / `tc` / `$tc` 语义矩阵
- [ ] P0：覆盖 `useTranslation()` / `useI18n()` 多种 destructuring 形式
- [ ] P1：覆盖 re-export / barrel import
- [ ] P1：覆盖 namespace import
- [ ] P1：覆盖跨文件 i18n instance resolve
- [ ] P1：覆盖更多 scope shadowing 场景

### 安全原则

> **不确定是不是 i18n 时，宁可继续提取，也不要错误地认为它已经国际化。**

---

# 8. P0：SmartPsiElementPointer / PSI 生命周期

自动 Rewrite 的主要风险之一仍然是 PSI 生命周期。

至少需要覆盖：

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

## Vue 重点

Vue 的实际生命周期是：

```text
Vue PSI
 ↓
Injected JS PSI
 ↓
SmartPointer
 ↓
Rewrite
 ↓
Document change
 ↓
Reparse
 ↓
Injected PSI 重建
```

因此比普通 TSX / JS PSI 更容易产生生命周期问题。

## TODO

- [x] sibling pointer 基础测试
- [x] nested pointer 基础测试
- [x] Undo / Redo 基础测试
- [ ] P0：pointer 第一次 rewrite 后仍有效
- [ ] P0：多个 sibling pointer 连续 rewrite
- [ ] P0：nested pointer 连续 rewrite
- [ ] P0：injected PSI pointer 生命周期
- [ ] P0：Vue rewrite → reparse → 再次查找 PSI → 再次 rewrite
- [ ] P1：删除节点后的 pointer 行为
- [ ] P1：文件 reparse 后 pointer 行为
- [ ] P1：多文件同时 rewrite

---

# 9. P0：Vue Template / Injected PSI 集成测试

当前 Vue Template 语法覆盖已经比较丰富，下一阶段重点不应只是增加更多 HTML 标签，而应该测试完整生命周期。

例如：

```vue
<template>
  <div>
    {{ foo ? '你好' : '世界' }}
  </div>
</template>
```

第一次提取：

```text
你好 → $t('hello')
```

然后重新获取 PSI，再执行第二次提取：

```text
世界 → $t('world')
```

必须保证：

```text
第一次 Rewrite
       ↓
Reparse
       ↓
重新获取 Injected PSI
       ↓
第二次 Rewrite
       ↓
源码正确
```

## TODO

- [ ] P0：rewrite → reparse → rewrite
- [ ] P0：nested injected expression
- [ ] P0：多个 template site 连续 rewrite
- [ ] P1：template literal + interpolation 混合场景
- [ ] P1：directive + interpolation 混合场景
- [ ] P1：slot / component prop 混合场景

---

# 10. P0：自动修改安全性与 Conservative Strategy

随着功能增加，项目已经开始具备较强的“自动推断”能力：

```text
Framework detection
+
i18n instance detection
+
translation detection
+
import inference
+
$t injection
```

这些能力组合后，错误可能形成级联：

```text
A 判断错误
 ↓
B 基于 A 判断
 ↓
C 基于 B 修改
 ↓
错误修改用户代码
```

因此需要明确：

> **任何无法高置信度确认的语义，都不要自动进行高风险 Rewrite。**

推荐策略：

```text
高置信度
   ↓
自动提取 + 自动 Rewrite

低置信度
   ↓
允许提取 / 标记候选
   ↓
不要擅自修改语义结构
```

这比“为了少提取一些文本而增加激进判断”更安全。

---

# 11. P1：ImportManager

当前 ImportInjector 已有较强的格式和边界测试，下一阶段建议把它从“执行工具”进一步提升为“计划生成器”。

目标：

```kotlin
data class ImportPlan(
    val source: String,
    val importedName: String,
    val localName: String,
    val action: ImportAction
)
```

由 Analyzer / Planner 决定：

```text
reuse
add
alias
skip
```

ImportManager 负责最终执行。

## TODO

- [ ] P1：ImportInjector → ImportManager
- [ ] P1：ImportPlan
- [ ] P1：parameter collision
- [ ] P1：re-export / barrel import
- [ ] P1：namespace import
- [ ] P1：跨文件 symbol collision
- [ ] P1：保持原有 import 语义不变

---

# 12. P1：ResourceWriter

Resource Writer 已覆盖较多边界：

- nested key
- duplicate key
- existing key merge
- escaped Unicode
- CRLF / LF
- UTF-8 BOM
- large JSON
- write failure

下一步重点是让 ResourceWriter 与代码分析完全解耦。

目标：

```text
Analyzer / Planner
       ↓
ResourcePlan
       ↓
ResourceWriter
       ↓
JSON / YAML / TS / JS
```

ResourceWriter 不应该知道：

```text
Vue
React
PSI
$t
useI18n
useTranslation
```

它只处理 `ResourcePlan`。

## TODO

- [ ] P1：ResourcePlan
- [ ] P1：code + resource simultaneous update
- [ ] P1：资源写入失败恢复
- [ ] P2：抽象 JSON / YAML / TS resource backend

---

# 13. P1：Framework Detection / Monorepo

当前 Framework Detection 已覆盖：

```text
Vue / React / Solid
mixed framework
nested package.json
workspace
custom framework
```

推荐优先级：

```text
当前文件语义
 > 当前 package
 > 父 package
 > root package
 > Generic
```

特别需要明确 shared package：

```text
packages/
├── react-app/
├── vue-app/
└── shared/
```

`shared` 同时被 React / Vue consumer 使用时，不应该强行根据 consumer 推断一个唯一 framework。

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

# 14. P1：Undo / Redo / IDE Lifecycle

Undo / Redo 已经有基础覆盖，下一阶段需要覆盖真实修改链路。

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

## TODO

- [x] 单文件 Extract → Undo → Redo
- [x] 多文件 Extract → Undo → Redo
- [x] 连续两次 Extract → Undo → Redo
- [ ] P0：import 修改 → Undo → Redo
- [ ] P0：JSON 修改 → Undo → Redo
- [ ] P0：code + import + JSON 同时修改 → Undo → Redo
- [ ] P1：Vue injected PSI → Undo → Redo
- [ ] P1：真实 Editor lifecycle
- [ ] P1：command boundary regression
- [ ] P1：headless dialog / Undo confirmation 的稳定处理

---

# 15. P1：测试策略调整

当前测试数量已经比较可观，因此不建议继续单纯增加大量普通字符串测试。

测试重点应从：

```text
“有没有测试”
```

转向：

```text
“有没有测试最危险的失败方式”
```

优先级：

### 第一层：语义正确性

```text
t collision
symbol shadowing
import alias
unknown object.t
hook destructuring
```

### 第二层：Rewrite 生命周期

```text
pointer
injected PSI
reparse
nested rewrite
multiple rewrite
```

### 第三层：事务安全

```text
multi-file
code + import + resource
failure recovery
Undo / Redo
```

### 第四层：性能

```text
large project
monorepo
large JSON
many extraction sites
```

---

# 16. P2：性能

当前功能正确性测试明显多于性能测试，因此性能暂时不是第一优先级。

建议建立：

```text
Small
1–10 files

Medium
100 files / 1,000 strings

Large
1,000+ files / 10,000+ strings
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

> 在正确性和生命周期稳定之前，不建议过早引入复杂缓存。旧 PSI、旧 framework detection、旧 package metadata 都可能因为缓存而产生更隐蔽的问题。

---

# 17. P2：语言识别策略

对于 English / French / German / Spanish / Italian / Portuguese，仅根据 Latin script 很难准确判断语言。

建议长期设计为 candidate detection，而不是 definitive language detection：

```kotlin
data class LanguageMatch(
    val language: Language,
    val confidence: Double
)
```

## TODO

- [ ] P2：增加 language confidence 模型
- [ ] P2：Latin language ambiguous cases
- [ ] P2：短字符串误判测试
- [ ] P2：技术术语误判测试

---

# 18. 推荐实施路线

不要一次性重构整个项目。建议按照下面顺序渐进迁移。

## Phase 1：建立 Model

```text
ExtractionSite
TranslationCall
ExtractionPlan
RewritePlan
ImportPlan
ResourcePlan
```

目标：让 Processor 不再依赖大量隐式 mutable state。

## Phase 2：抽 Scanner

```text
VueScanner
ReactScanner
JsScanner
```

Scanner 只负责：

```text
发现候选节点
```

不负责：

```text
Rewrite
Import
Resource
```

## Phase 3：抽 Analyzer

```text
TranslationAnalyzer
FrameworkAnalyzer
StringAnalyzer
```

Analyzer 负责：

```text
候选节点
 ↓
是否真的需要提取
 ↓
语义是什么
```

## Phase 4：抽 Planner

```text
AnalyzedSite
 ↓
ExtractionPlan
```

Planner 不修改 PSI。

## Phase 5：抽 Rewriter / ImportManager / ResourceWriter

```text
ExtractionPlan
 ↓
Rewriter
ImportManager
ResourceWriter
```

只有这里进入 Write Action。

## Phase 6：建立完整生命周期测试

```text
Plan
 ↓
Validate
 ↓
Apply
 ↓
Reparse
 ↓
Undo
 ↓
Redo
```

完成后再考虑性能优化。

---

# 19. 最终架构目标

项目最终应该逐渐成为一个轻量的代码转换引擎，而不仅仅是字符串提取器：

```text
                    Source PSI
                        │
                        ▼
                     Scanner
                        │
                 Candidate Sites
                        │
                        ▼
                    Analyzer
                        │
                Semantic Analysis
                        │
                        ▼
                     Planner
                        │
                  ExtractionPlan
                        │
                    Validation
                        │
          ┌─────────────┴─────────────┐
          ▼                           ▼
      Rewriter                  ImportManager
          │                           │
          └─────────────┬─────────────┘
                        ▼
                  ResourceWriter
                        │
                        ▼
                Single Apply Command
                        │
                        ▼
                   Undo / Redo
```

核心原则：

> **分析阶段尽量纯、计划阶段不修改、Apply 阶段集中修改、所有自动修改都必须可验证和可撤销。**

这是项目从当前 **8.3 / 10** 继续提升到 **9 / 10+** 的关键。

---

# 20. 下一阶段最重要的 TODO

按优先级压缩为：

## P0

- [ ] 建立 `ExtractionSite / RewritePlan / ImportPlan / ResourcePlan`
- [ ] 建立 `ExtractionPlan`
- [ ] Scanner / Analyzer / Planner 渐进式迁移
- [ ] Apply 前完整 validation
- [ ] 多文件修改原子性
- [ ] Vue injected PSI rewrite → reparse → rewrite
- [ ] Translation Call semantic analysis 持续完善
- [ ] code + import + resource 完整 Undo / Redo

## P1

- [ ] ImportManager
- [ ] ResourcePlan
- [ ] ResourceWriter 与 Framework 解耦
- [ ] 跨文件 symbol / i18n instance resolve
- [ ] re-export / namespace import
- [ ] shared package framework 行为明确化
- [ ] 真实 Editor lifecycle 测试

## P2

- [ ] 大项目 benchmark
- [ ] Monorepo benchmark
- [ ] 大 JSON benchmark
- [ ] 缓存策略评估
- [ ] Language confidence 模型

---

## 结论

当前项目已经不是“需要继续堆功能”的阶段，而是进入了：

> **正确性、生命周期和架构可靠性优先于功能数量。**

下一步最重要的事情不是再把 `I18nProcessor` 做得更强，而是逐步把它变成一个真正的 orchestrator：

```text
Scanner
  ↓
Analyzer
  ↓
Planner
  ↓
Validate
  ↓
Rewriter / ImportManager / ResourceWriter
  ↓
Single Command
  ↓
Undo / Redo
```

如果这一层完成，后续增加 Svelte、Angular、更多 React/Vue i18n 库，以及 JSON/YAML/TS resource backend，都会比继续向 `I18nProcessor` 增加条件分支容易得多。

---

# 21. 目标形态：I18nProcessor → 依赖注入的薄 Orchestrator

## 21.1 愿景

`I18nProcessor` 的最终形态应当是**通过构造注入各层、以单一 `extract(context)` 串起整条管道**的薄 Orchestrator，不再内联任何收集/注入/资源业务，也不再充当 `JsStringCollector` / `I18nImportInjector` 的共享状态宿主。

```kotlin
class I18nProcessor(
    private val scanner: SourceScanner,
    private val analyzer: I18nAnalyzer,
    private val planner: ExtractionPlanner,
    private val validator: ChangeValidator,
    private val rewriter: SourceRewriter,
    private val importManager: ImportManager,
    private val resourceWriter: ResourceWriter,
) {
    fun extract(context: ExtractionContext) {
        val candidates = scanner.scan(context)          // 只发现候选 PSI 节点
        val analyzed  = analyzer.analyze(candidates, context) // 语义判断：是否提取/框架/t 调用/合法字符串
        val plan      = planner.plan(analyzed, context) // 产出 ExtractionPlan/ImportPlan/ResourcePlan
        validator.validate(plan)                        // 跨文件 radio 校验后才允许进入 Write Action
        rewriter.apply(plan)
        importManager.apply(plan)
        resourceWriter.apply(plan)
    }
}
```

数据流与 §4 一致：`Scanner → Analyzer → Planner → Validate → Rewriter/ImportManager → ResourceWriter → Command → Undo/Redo`。

## 21.2 分层接口契约

| 组件 | 现状 | 目标接口 |
|---|---|---|
| `SourceScanner` | ✅ 已有 [scanner/SourceScanner.kt](../../src/main/kotlin/com/pan/extractor/scanner/SourceScanner.kt) | `scanner.scan(context): Collection<CandidateSite>` |
| `I18nAnalyzer` | ⚠️ 分析散布在 `I18nProcessor` + `JsStringCollector` + 各 strategy 默认实现 | `analyzer.analyze(candidates, context): Collection<AnalyzedSite>` |
| `ExtractionPlanner` | ✅`planner/ExtractionPlanner.kt`（已接入 MergeApplier） | `planner.plan(analyzed, context): ExtractionPlan` |
| `ChangeValidator` | ✅ `validator/ChangeValidator.kt`（MergeApplier 已委托） | `validator.validate(plan)` |
| `ImportManager` | ⚠️ 现在内嵌于 `I18nImportInjector`（依赖 processor 宿主） | `importManager.apply(plan)`（含重复检测/symbol collision/alias/注入） |
| `SourceRewriter` | ✅ `rewriter/SourceRewriter.kt`（消费 `ImportPlan`） | `rewriter.apply(plan)` |
| `ResourceWriter` | ⚠️ `resource/ResourceApplier.kt` 已实现，但由**跨文件层**驱动 | `resourceWriter.apply(plan)` |
| `ExtractionContext` | ❌ 缺 | 一次提取的输入封装（`Project`、目标 `PsiFile`、已收集的跨文件 key、输出模式） |
| 领域模型 | 部分（`ImportPlan` 等已建） | `CandidateSite / AnalyzedSite / ExtractionPlan / ImportPlan / ResourcePlan` |

## 21.3 必须先拆的两个耦合点

1. **宿主绑定（最大障碍）**：`JsStringCollector` / `I18nImportInjector` 都持有 `processor: I18nProcessor` 并回调其 `templateVarRegex / tFunctionName / extractedStrings / factory / getScriptTag / createStringExpressionNode / detectGlobalDollarT` 等。不把这些状态参数化到 `Analyzer` / `ImportManager`，`I18nProcessor` 瘦不下来。
2. **resourceWriter 归属重叠**：目标 `extract()` 内含 `resourceWriter.apply(plan)`，但当前资源写回由跨文件层（[MergeApplier](../../src/main/kotlin/com/pan/extractor/MergeApplier.kt) / [I18nExtractionOrchestrator.apply](orchestrator/I18nExtractionOrchestrator.kt)）执行。需明确：入口文件 key 来自多 processor 合并后统一落盘，单文件 `extract()` 不应重复做 resource 写回；至少在合并语义下，resource 写回应仍属于跨文件层，`resourceWriter` 只在「单文件独立发布」场景由 `extract()` 直接调用。

## 21.4 迁移路径（渐进、行为保持）

1. **冻结接口**：补齐 `I18nAnalyzer`、`ImportManager`、`ResourceWriter`、`ExtractionContext` 与 `CandidateSite/AnalyzedSite` 的最小接口，不改现有调用。
2. **DI 薄类 + 兼容壳**：构造注入 7 依赖，`I18nProcessor(project, psiFile)` 兼容构造保留（各依赖给默认实现），`collect()/run()/runWithUndo()` 保留为兼容入口，内部改走 `extract(context)` 中央调度。测试契约不变，行为不变。
3. **拆宿主**：把 `JsStringCollector` 的字符串收集收敛进 `I18nAnalyzer`、`I18nImportInjector` 收敛进 `ImportManager`，二者改为接收 `ExtractionContext` / plan 而非整个 processor。
4. **统一 Write Action**：确保 `validate → rewriter → importManager → resourceWriter` 全部落在同一个 `WriteCommandAction`，失败整体回滚。
5. **收敛 resource**：按 21.3 明确单文件/跨文件两条 resource 职责边界后，把 `resourceWriter` 接入。

## 21.5 判定完成的标准

- [x] `I18nProcessor` 不再持有收集业务/注入业务代码，仅剩注入依赖 + `extract(context)` 调度
      —— 收集业务已收敛至 `analyzer.I18nAnalyzer`（`collectExistingTKeys / collectFromPsi / recordChange / resetState` 全部委托），
      注入业务已收敛至 `planner.ImportPlanner + rewriter.ImportRewriter`（`injectForFramework` 只做转发）。
- [x] `JsStringCollector` / `I18nImportInjector` 不再接收 `I18nProcessor` 整个对象
      —— 二者统一以窄接口 `I18nProcessorContract` 注入，反向依赖收敛为接口契约能力。
- [x] `extract()` 全部自动修改进入同一个 `WriteCommandAction`
      —— 多文件路径 `orchestrator.I18nExtractionOrchestrator.apply` 把「import 注入 + \$t 替换 + 骨架合并 +
      资源写回」统一收敛进【单个】`WriteCommandAction.runWriteCommandAction(project){…}`，且 `MergeApplier.apply`
      传 `edtRunner=null`（所有 PSI 写入在当前 command 内同步执行，无异步 EDT 破坏原子性）；
      单文件路径 `I18nProcessor.runWithUndo` 同样以 `CommandProcessor + runWriteCommandAction` 包裹一次 commit。
      收集阶段（`extract/collect`）保持只读、在 Write Action 之外，符合「分析不改、Plan 只描述、Apply 才写」原则。
      证据：`MergeApplierTest.testSingleCommandCodeImportResourceUndoRedo`（代码+import+资源同组 undo/redo 通过）。
- [x] 现有 `I18nProcessorTest / SolidI18nProcessorTest / I18nVueTemplatePsi2Test / I18nRegexFallbackBoundaryTest / I18nImportInjectorMoreTest` 全绿
      —— 另有 `I18nImportsTest / MergeApplierTest / ReactI18nProcessorTest / VueI18nProcessorTest` 一并验证无回归。
