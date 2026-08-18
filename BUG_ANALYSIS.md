# i18n-extractor Bug & Test Coverage Analysis

> 分析时间：2026-08-18  
> 分析对象：`robertpanvip/i18n-extractor`  
> 目的：记录当前设计风险、潜在 Bug、测试缺口，以及下一阶段建议的 TODO。

## 1. 总体结论

当前项目已经拥有比较扎实的核心测试体系，尤其是 Vue / React 的 i18n 提取、Folding、Merge、WriteBack 以及历史 Bug Regression Test。

**综合评价：约 7/10。**

当前主要问题不是“测试数量太少”，而是：

- 核心 Processor 测试较多，但架构边界测试不足；
- Framework Detection / Registry 需要持续验证扩展性和优先级；
- Monorepo / 多 package 场景仍需要加强；
- i18n Instance Locator 部分逻辑存在文本搜索误判风险；
- Regex 与 PSI 混用，复杂 JS/TS 语法容易出现 false positive / false negative；
- Import Injection / PSI Rewrite / WriteBack 属于高风险区域，组合测试不足；
- IntelliJ Editor / PSI / VFS / Action / Undo-Redo 生命周期测试不足；
- 性能、大文件、多文件项目测试不足。

因此下一阶段不建议单纯继续堆功能，而应该优先收敛核心架构并主动寻找未知 Bug。

---

## 2. 当前测试体系

当前已经存在较多测试，包括：

- `I18nProcessorTest`
- `VueI18nProcessorTest`
- `ReactI18nProcessorTest`
- `ReactI18nTCallScenarioTest`
- `I18nFoldingBuilderTest`
- `MergeApplierTest`
- `MergeApplierPureTest`
- `CommonPrefixSuffixFactorizerTest`
- `EntryFileLocatorCoreFunctionTest`
- `TsFileEditorCoreFunctionTest`
- `I18nPsiToolsCoreFunctionTest`
- `I18nBootstrapSupportTest`
- `BugRegressionTest`
- `BugReproTest`
- `BugReproVueAttributeTest`
- `InlineScanBugRegressionTest`
- `UtilSpreadRoutingTest`
- `UtilWriteBackTest`
- `UtilWriteBackEntryFileTest`

### 当前评分

| 模块 | 评分 |
|---|---:|
| 核心算法测试 | 8/10 |
| Regression Test | 9/10 |
| 架构测试 | 6/10 |
| IDE Integration | 4/10 |
| Runtime Correctness | 2/10 |
| Performance Test | 3/10 |
| **综合** | **7/10** |

---

# 3. 高风险设计 / 潜在 Bug

## 3.1 Processor 状态可能污染后续执行

重点检查 `I18nProcessor` 中的 mutable state，例如：

- `extractedStrings`
- `collectedSites`
- `pendingChanges`
- `blockedSiteIds`
- `siteCounter`
- framework / fallback 状态

如果同一个 Processor 实例执行：

```text
collect()
collect()
run()
```

必须保证不会重复累积结果或重复应用 Change。

**建议：**尽量让分析阶段接近 stateless，并让一次执行产生独立的 `ExtractionResult` / `ChangePlan`。

---

## 3.2 Regex 不应作为 JS/TS 语义分析的主要手段

类似 `$t(...)`、`i18n.t(...)`、`i18n.global.t(...)` 的识别，如果主要依赖 Regex，容易受到以下语法影响：

```ts
$t(
  'hello'
)

$i18n
  .global
  .t('hello')

$t(`hello ${name}`)
```

也容易误判：

```ts
const x = "$t('hello')"
// $t('hello')
const fn = $t
```

**建议：**优先使用 PSI CallExpression / Reference / Argument 分析，Regex 只保留为 fallback。

---

## 3.3 i18n Instance Locator 存在文本误判风险

重点检查类似：

```text
createI18n(
initReactI18next
i18n.init(
```

的 `contains` / Regex 检测。

以下内容不应该被当成真实调用：

```ts
const text = "createI18n()"
// createI18n()
const fn = createI18n
console.log("initReactI18next")
```

**建议：**逐步升级为 PSI → Import Resolution → CallExpression → Reference Resolution。

---

## 3.4 Import Injection / PSI Rewrite / WriteBack 是最高风险区域

完整链路：

```text
Extraction
  ↓
PSI Rewrite
  ↓
Import Injection
  ↓
Merge
  ↓
WriteBack
```

重点风险：

- offset 变化；
- PSI element invalidation；
- `SmartPsiElementPointer` 生命周期；
- 多次 rewrite 相互影响；
- duplicate import；
- import alias 冲突；
- TSX / JSX 语法破坏；
- Vue injected language；
- CRLF/LF；
- multiline rewrite。

---

## 3.5 Framework Detection 的项目级误判

混合项目可能同时存在 Vue / React / Solid：

```text
root/
├── package.json
└── packages/
    ├── react-app/
    ├── vue-app/
    └── solid-app/
```

必须保证当前文件使用最近的 package/module 语义，而不是简单依赖 root package.json。

推荐优先级：

```text
当前文件语义
    > 当前 package
    > 父 package
    > root package
    > Generic
```

---

# 4. P0 TODO — 必须优先完成

## 4.1 修复 Processor 状态污染

- [ ] 确认 `collect()` 可以安全重复执行
- [ ] 每次执行正确 reset extraction state
- [ ] reset `pendingChanges`
- [ ] reset `collectedSites`
- [ ] reset `blockedSiteIds`
- [ ] reset `siteCounter`
- [ ] reset framework / fallback state
- [ ] 防止同一个 Change 被应用两次
- [ ] 增加 `collect()` 幂等性测试
- [ ] 增加 `run()` 重复执行测试

目标：

```text
collect()
collect()
```

第二次结果必须与第一次一致。

---

## 4.2 Regex → PSI

- [ ] `$t()` 使用 PSI 分析
- [ ] `i18n.t()` 使用 PSI 分析
- [ ] `i18n.global.t()` 使用 PSI 分析
- [ ] multiline call 使用 PSI
- [ ] chained call 使用 PSI
- [ ] alias / reference resolve 使用 PSI
- [ ] Regex 仅作为无法解析时的 fallback

---

## 4.3 增加 Negative Extraction Test

以下内容都应该 **0 extraction**：

```ts
const text = "$t('hello')"

// $t('hello')

/* $t('hello') */

const fn = $t

const x = `text $t('hello')`
```

至少增加 20～30 个 negative cases，覆盖：

- 字符串
- 注释
- block comment
- template literal
- function reference
- 普通变量
- JSX string
- Vue attribute 普通文本
- 非 translation function 的 `.t()`

---

## 4.4 Harden ImportInjector

- [ ] 已存在 import 不重复添加
- [ ] named import
- [ ] default import
- [ ] namespace import
- [ ] import alias
- [ ] multiline import
- [ ] type import
- [ ] side-effect import
- [ ] import path alias
- [ ] 相对路径
- [ ] Windows path
- [ ] CRLF
- [ ] 保持原有 alias 不变
- [ ] 不产生 duplicate import

例如：

```ts
import { t as translate } from './i18n'
```

不能被错误改写成：

```ts
import { t } from './i18n'
```

导致 `translate(...)` 失效。

---

# 5. P1 TODO — 架构与稳定性

## 5.1 重构 `I18nFramework`

当前 Framework Strategy 容易逐渐变成 God Interface。

建议拆成 capability：

```text
Framework
├── DetectionStrategy
├── TranslationCallStrategy
├── TemplateStrategy
├── ImportStrategy
├── BootstrapStrategy
└── PlaceholderStrategy
```

TODO：

- [ ] 拆分 Framework Detection
- [ ] 拆分 Translation Call
- [ ] 拆分 Template
- [ ] 拆分 Import
- [ ] 拆分 Bootstrap
- [ ] 拆分 Placeholder
- [ ] 减少 `I18nFramework` 方法数量

---

## 5.2 Framework Detection Matrix

- [ ] Vue detection
- [ ] React detection
- [ ] Solid detection
- [ ] Generic fallback
- [ ] Framework priority
- [ ] Custom framework registration
- [ ] React + Vue
- [ ] React + Solid
- [ ] Vue + Solid
- [ ] React + Vue + Solid
- [ ] Monorepo root/package 冲突
- [ ] pnpm workspace
- [ ] yarn workspace
- [ ] npm workspace

---

## 5.3 i18n Instance Locator

- [ ] 从文本搜索逐步迁移到 PSI
- [ ] import resolve
- [ ] default import
- [ ] named import
- [ ] namespace import
- [ ] alias import
- [ ] re-export
- [ ] 多实例
- [ ] 跨文件实例
- [ ] 注释 / 字符串 negative test

---

## 5.4 Vue Template PSI

- [ ] interpolation
- [ ] directive
- [ ] attribute
- [ ] component prop
- [ ] slot
- [ ] script setup
- [ ] template comment
- [ ] multiline expression
- [ ] nested expression
- [ ] template literal
- [ ] escaped interpolation

重点验证：

```vue
{{ $t('hello') }}
:title="$t('hello')"
<MyComponent :title="$t('hello')" />
<!-- {{ $t('hello') }} -->
```

---

## 5.5 Import / Rewrite 组合测试

至少覆盖：

- [ ] import 已存在
- [ ] import alias
- [ ] export default i18n
- [ ] export const i18n
- [ ] export { i18n }
- [ ] index 文件
- [ ] nested path
- [ ] TS
- [ ] TSX
- [ ] JS
- [ ] JSX
- [ ] Vue
- [ ] 多文件同时修改

---

# 6. P1 TODO — IDE 生命周期

## 6.1 Undo / Redo

- [ ] Extract → Undo
- [ ] Extract → Redo
- [ ] 多文件 Extract → Undo
- [ ] 多文件 Extract → Redo
- [ ] import 修改 → Undo
- [ ] JSON 修改 → Undo
- [ ] TS 修改 → Undo

目标：

```text
Before → Extract → After → Undo → Before → Redo → After
```

---

## 6.2 SmartPsiElementPointer 生命周期

- [ ] PSI 修改后 pointer 是否有效
- [ ] 删除节点后的 pointer
- [ ] 文件重写后的 pointer
- [ ] 多次修改后的 pointer
- [ ] injected PSI 修改后的 pointer

---

## 6.3 Folding 生命周期

- [ ] 打开文件
- [ ] 建立 Folding
- [ ] 修改文本
- [ ] 重新 PSI
- [ ] Folding 更新
- [ ] 删除 translation
- [ ] 添加 translation
- [ ] 关闭 / 重新打开文件

---

## 6.4 IDE Integration Test

建立真实 IntelliJ 集成测试：

```text
IntelliJ
 ↓
Editor
 ↓
PSI
 ↓
Injected PSI
 ↓
FoldingModel
 ↓
Action
 ↓
WriteCommandAction
 ↓
VFS / Document
```

- [ ] Action visibility
- [ ] Action update
- [ ] Editor Folding
- [ ] Vue injected language
- [ ] Document → PSI 一致性
- [ ] WriteCommandAction
- [ ] Undo/Redo
- [ ] 保存后内容一致

---

# 7. P2 TODO — 数据正确性

## 7.1 Placeholder

- [ ] 单 placeholder
- [ ] 多 placeholder
- [ ] 重复 placeholder
- [ ] placeholder 顺序
- [ ] placeholder alias
- [ ] placeholder quote
- [ ] placeholder escape

例如：

```ts
$t('hello {name}')
$t('hello {name} {age}')
```

---

## 7.2 Nested Expression

覆盖：

```ts
foo({
  title: "你好",
  description: "世界"
})
```

```ts
foo(condition ? "你好" : "世界")
```

```ts
items.map(item => "你好")
```

确保每个字符串单独处理，而不是错误地包裹整个表达式。

---

## 7.3 MergeApplier

- [ ] 空 merge
- [ ] duplicate key
- [ ] nested key
- [ ] conflict key
- [ ] existing object
- [ ] existing array
- [ ] null
- [ ] primitive/object conflict
- [ ] comment preservation
- [ ] formatting preservation

---

# 8. P2 TODO — 性能

建立 benchmark：

```text
100 strings
1,000 strings
10,000 strings
100,000 strings
```

文件规模：

```text
10 KB
100 KB
1 MB
10 MB
```

记录：

- [ ] framework detection 时间
- [ ] PSI scan 时间
- [ ] extraction 时间
- [ ] existing-key scan 时间
- [ ] import injection 时间
- [ ] writeBack 时间
- [ ] Folding 时间
- [ ] 内存使用

同时检查：

- [ ] 同一 PSI 不重复遍历
- [ ] 同一文件不重复解析
- [ ] package.json 不重复读取
- [ ] framework 不重复 detection
- [ ] 重型计算不在 EDT 执行

---

# 9. P2 TODO — Runtime / Framework 扩展

## Runtime Correctness

静态 PSI 测试无法完全验证真实运行时语义。

后续可增加最小 runtime fixture：

```ts
const $t = i18n.global.t;
$t("hello");
```

```ts
const $t = getI18n().t;
$t("hello");
```

- [ ] Vue I18n runtime fixture
- [ ] React i18next runtime fixture
- [ ] alias function runtime fixture
- [ ] fallback behavior

## Framework 扩展

架构稳定后再增加：

- [ ] i18next
- [ ] react-intl / FormatJS
- [ ] next-intl
- [ ] Lingui
- [ ] 更多 Vue / Solid i18n API

---

# 10. CI 稳定性

如果 CI 使用 retry：

```text
第一次失败
 ↓
retry 成功
 ↓
CI Green
```

不要让 retry 隐藏 flaky test。

- [ ] 保留 retry
- [ ] 第一次失败记录 warning
- [ ] 保存第一次失败日志
- [ ] 统计 flaky test
- [ ] 定期清理 flaky test
- [ ] 本地 / CI Gradle 版本统一
- [ ] IntelliJ Platform / EAP 版本变更时验证测试兼容性

---

# 11. 推荐执行顺序

<<<<<<< HEAD
不要继续无脑增加 framework 功能，建议按照下面顺序推进：
=======
### P0

1. 修复 `I18nFrameworkRegistry` 注册与检测脱节问题。 ✅（`I18nFramework.matches()` + `detect()` 遍历）
2. 增加 Framework Detection 测试矩阵。 ✅（`I18nFrameworkDetectionTest`，15 用例）
3. 增加 Monorepo 测试。 ✅（root+package、混合、workspace）
4. 降低 i18n Instance Locator 对文本搜索的依赖。 ✅（注释剥离 + PSI 调用检测，`I18nInstanceLocatorCoreTest`）
5. 加强 Import Injection / PSI Rewrite / WriteBack 的组合测试。 ✅（`I18nComprehensiveTest`）
6. 增加 multiline / nested expression / TSX / JSX 场景。 ✅（`I18nComprehensiveTest`）

### P1

1. 增加真实 IDE Integration Test。 ⏳ 未覆盖（需真实 IntelliJ 集成环境）
2. 增加 Vue injected language 测试。 ⏳ 部分（`I18nFoldingBuilderTest` 有 injected 断言，未全覆盖）
3. 增加 Runtime Fixture。 ✅（React `t = getI18n().t` / Vue `$t = i18n.global.t` 语义等价，`I18nComprehensiveTest.testRuntime*`）
4. 完善 import / export / alias 测试。 ✅（relative + `@/locales` alias，`I18nComprehensiveTest`）
5. 增加 Plugin Descriptor / startup 验证。 ⏳ 未覆盖

### P2

1. 性能测试。 ⏳ 仅批量提取正确性（`testLargeBatchExtraction`），无耗时/内存基准
2. 大文件测试。 ✅（1000 字符串批量提取）
3. 多文件 Merge 测试。 ✅（`MergeApplierTest.testApplyMergesAcrossFiles`）
4. Unicode / emoji / CRLF 测试。 ✅（`I18nComprehensiveTest`）
5. Flaky Test 监控。 ✅（CI retry warning + Gradle 版本统一）

---

### 对齐总结（2026-08-18）

| 分组 | 已完成 | 未覆盖 |
|---|---|---|
| P0 | 6/6 | - |
| P1 | 2/5 | IDE Integration、Vue injected language 全量、Plugin Descriptor 验证 |
| P2 | 4/5 | 性能基准（耗时/内存） |

全量测试：**29 个套件、全部通过、0 失败**（本地 `intellijIdeaUltimate` 平台）。

## 11. 最终评价

当前项目的优势是：

- Vue / React 核心逻辑测试比较扎实；
- Regression Test 建设得很好；
- 最近的 Bug 修复能够及时转化为测试；
- PSI / Merge / WriteBack 已经有较多基础测试。

当前项目的主要风险是：
>>>>>>> fa4eb40 (feat: 评估项目架构)

```text
① Processor state / idempotency
        ↓
② Regex → PSI
        ↓
③ Negative extraction tests
        ↓
④ ImportInjector hardening
        ↓
⑤ InstanceLocator
        ↓
⑥ Framework Detection / Monorepo
        ↓
⑦ Framework capability refactor
        ↓
⑧ Vue / React / Solid Integration
        ↓
⑨ Undo / Redo / Folding lifecycle
        ↓
⑩ Performance
        ↓
⑪ New frameworks
```

## 最重要的 5 个 TODO

如果只能优先做五项：

1. **[P0] Make `I18nProcessor.collect()` idempotent and reduce mutable state.**
2. **[P0] Replace regex-based i18n call detection with PSI analysis.**
3. **[P0] Add comprehensive negative extraction tests.**
4. **[P0] Harden `I18nImportInjector` against alias / multiline / duplicate imports.**
5. **[P1] Refactor `I18nFramework` into smaller capabilities.**

完成这五项后，再扩展新的 i18n framework，整体稳定性会明显高于继续增加单个 framework 的功能。
