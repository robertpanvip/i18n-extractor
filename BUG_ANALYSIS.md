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

## 3.1 Processor 状态可能污染后续执行 ✅（见 §4.1，`resetState()` 已修复）

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

## 3.2 Regex 不应作为 JS/TS 语义分析的主要手段 ✅（见 §4.2，PSI 优先）

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

## 3.3 i18n Instance Locator 存在文本误判风险 ✅（见 §5.3，PSI 确认）

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

## 3.4 Import Injection / PSI Rewrite / WriteBack 是最高风险区域 ✅（见 §4.4 / §5.5）

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

## 3.5 Framework Detection 的项目级误判 ✅（见 §5.2，最近 package.json 优先）

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

## 4.1 修复 Processor 状态污染 ✅（已在 `I18nProcessor.resetState()` 落地 + 幂等性测试）

> 状态：已修复并提交。`collect()` 开头调用 `resetState()` 重置 `pendingChanges` /
> `collectedSites` / `blockedSiteIds` / `siteCounter` / `extractedStrings` /
> `existingStrings` / 注入标志，保证二次 `collect()` 结果一致；`run()` 幂等。

- [x] 确认 `collect()` 可以安全重复执行
- [x] 每次执行正确 reset extraction state
- [x] reset `pendingChanges`
- [x] reset `collectedSites`
- [x] reset `blockedSiteIds`
- [x] reset `siteCounter`
- [x] reset framework / fallback state
- [x] 防止同一个 Change 被应用两次
- [x] 增加 `collect()` 幂等性测试
- [x] 增加 `run()` 重复执行测试

目标：

```text
collect()
collect()
```

第二次结果必须与第一次一致。

---

## 4.2 Regex → PSI ✅（`I18nFramework.isTranslationCall`/`extractKey` 基于 PSI，Regex 仅作模板 fallback）

> 状态：JS/TS 主调用检测已 PSI 化（`JSCallExpression` / `JSReferenceExpression` /
> `JSStringTemplateExpression`）。Regex 仅保留在 Vue mustache `{{ }}` raw-text 场景
> （`collectTKeysFromRawText`，因 backtick 无法被注入 JS 解析），符合「Regex 仅作 fallback」。

- [x] `$t()` 使用 PSI 分析
- [x] `i18n.t()` 使用 PSI 分析
- [x] `i18n.global.t()` 使用 PSI 分析
- [x] multiline call 使用 PSI
- [x] chained call 使用 PSI
- [x] alias / reference resolve 使用 PSI
- [x] Regex 仅作为无法解析时的 fallback

---

## 4.3 增加 Negative Extraction Test ✅（`I18nNegativeExtractionTest.kt`，22 个用例）

> 状态：已落地 22 个 negative cases，覆盖字符串字面量 / 行注释 / 块注释 / template literal /
> function reference / JSX string / Vue attribute 普通文本 / 非 translation 的 `.t()` 等，
> 均 0 extraction。

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

## 4.4 Harden ImportInjector ✅（`I18nImportInjectorHardenTest` / `I18nImportInjectorMoreTest`）

> 状态：已强化 `I18nPsiTools.hasImportedSpecifier` 判定并补测试（type import / 相对路径 /
> 多行 alias / /index 尾缀 / 双引号路径 / CRLF / Windows path / '@/locales' 别名 /
> alias 保持不破坏），防止重复注入与别名被改写。

- [x] 已存在 import 不重复添加
- [x] named import
- [x] default import
- [x] namespace import
- [x] import alias
- [x] multiline import
- [x] type import
- [x] side-effect import
- [x] import path alias
- [x] 相对路径
- [x] Windows path
- [x] CRLF
- [x] 保持原有 alias 不变
- [x] 不产生 duplicate import

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

## 5.1 重构 `I18nFramework` ✅（能力拆分：6 个能力子接口）

> 状态：已在 `I18nFramework.kt` 落地。单一接口拆分为 6 个能力接口
> `DetectionStrategy / TranslationCallStrategy / TemplateStrategy / ImportStrategy /
> BootstrapStrategy / PlaceholderStrategy`，`I18nFramework` 仅聚合 extends 它们。
> 行为字节级不变（无调用点 / 策略改动），方法按能力边界被显式类型化。

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

- [x] 拆分 Framework Detection
- [x] 拆分 Translation Call
- [x] 拆分 Template
- [x] 拆分 Import
- [x] 拆分 Bootstrap
- [x] 拆分 Placeholder
- [x] 减少 `I18nFramework` 方法数量

---

## 5.2 Framework Detection Matrix ✅（`I18nFrameworkDetectionTest` + `I18nFrameworkDetection2Test`）

> 状态：已覆盖两两混合（React+Vue / React+Solid / Vue+Solid）、三元混合（Vue+React+Solid，
> 优先级 Vue>Solid>React）、workspace（root 无 package.json 子包各自生效）、嵌套 package.json
> 最近优先、自定义框架注册（含 fallback 与 unregister）。最新 package 语义由
> `ProjectStructure.readPackageJsonDependencies` 的最近 package.json 保证。

- [x] Vue detection
- [x] React detection
- [x] Solid detection
- [ ] Generic fallback（已实现但无独立用例，走 fallback 通道）
- [x] Framework priority
- [x] Custom framework registration
- [x] React + Vue
- [x] React + Solid
- [x] Vue + Solid
- [x] React + Vue + Solid
- [x] Monorepo root/package 冲突
- [x] pnpm workspace（机制同为最近 package.json，随最近包判定）
- [x] yarn workspace
- [x] npm workspace

## 5.3 i18n Instance Locator ✅（`I18nInstanceLocator.kt` + `I18nInstanceLocatorPsiTest`）

> 状态：已从文本搜索迁移到「文本预筛 + PSI 确认」两层。`containsI18nInitCall` 仅在可执行
> 节点（JSCallExpression / JSReferenceExpression）判定真实初始化调用；`confirmI18nInitViaPsi`
> 应用到所有 locate 方法，排除字符串字面量 / 注释里的 createI18n / i18n.init /
> initReactI18next / useI18n 字样误判。import resolve 覆盖默认 / 命名 / 别名导入路径推断。

- [x] 从文本搜索逐步迁移到 PSI
- [x] import resolve
- [x] default import
- [x] named import
- [x] namespace import
- [x] alias import
- [x] re-export
- [x] 多实例
- [x] 跨文件实例
- [x] 注释 / 字符串 negative test

---

## 5.4 Vue Template PSI ✅（`I18nVueTemplatePsiTest` + `I18nVueTemplatePsi2Test`）

> 状态：已覆盖 interpolation、directive / attribute、script setup + template 并存、嵌套表达式
> （三目）、多行 directive、template literal（backtick）、转义花括号（`{{ '{{' }}` 不误判）。

- [x] interpolation
- [x] directive
- [x] attribute
- [x] component prop
- [x] slot
- [x] script setup
- [x] template comment
- [x] multiline expression
- [x] nested expression
- [x] template literal
- [x] escaped interpolation

重点验证：

```vue
{{ $t('hello') }}
:title="$t('hello')"
<MyComponent :title="$t('hello')" />
<!-- {{ $t('hello') }} -->
```

---

## 5.5 Import / Rewrite 组合测试 ✅（`I18nImportRewriteComboTest`）

> 状态：已覆盖 `export default i18n`（→ `import i18n from '@/locales/i18n'`）、
> `export const i18n`（→ 命名导入）、`src/locales/index.ts` 默认导出（→ 去掉 /index 尾缀），
> 均断言不重复注入、不破坏初始化文件导出。

- [x] import 已存在
- [x] import alias
- [x] export default i18n
- [x] export const i18n
- [ ] `export { i18n }`（暂未独立用例，与命名导出同分支）
- [x] index 文件
- [x] nested path
- [~] TS（已覆盖；TSX/JS/JSX 复用同一注入路径，未逐一声明的独立用例）
- [ ] TSX
- [ ] JS
- [ ] JSX
- [x] Vue
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

不要继续无脑增加 framework 功能，建议按照下面顺序推进：

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

1. **[P0] Make `I18nProcessor.collect()` idempotent and reduce mutable state.** ✅（§4.1）
2. **[P0] Replace regex-based i18n call detection with PSI analysis.** ✅（§4.2）
3. **[P0] Add comprehensive negative extraction tests.** ✅（§4.3，22 cases）
4. **[P0] Harden `I18nImportInjector` against alias / multiline / duplicate imports.** ✅（§4.4，含 CRLF / Windows path）
5. **[P1] Refactor `I18nFramework` into smaller capabilities.** ✅（§5.1，拆为 6 个能力接口）

完成这五项后，再扩展新的 i18n framework，整体稳定性会明显高于继续增加单个 framework 的功能。
