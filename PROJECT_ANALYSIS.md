# i18n-extractor 全面架构、正确性与工程稳定性评估

> 评审时间：2026-08-20  
> 评审对象：`robertpanvip/i18n-extractor` 当前 `main`  版本  
> 评审结论：**8.3 / 10，已经进入“正确性收敛”阶段**

---

## 1. Executive Summary

当前项目已经完成了相当大一部分早期架构债务清理：Framework Strategy、Scanner、Analyzer、Planner、Import、Resource Writer、Validation、Undo/Redo 和 Vue PSI 生命周期测试都已经形成比较明确的边界。

项目当前已经不是“架构混乱、需要继续大规模重构”的状态。下一阶段最重要的工作应该从：

```text
架构重构驱动
        ↓
正确性驱动
        ↓
安全 Rewrite
        ↓
黑盒回归测试
        ↓
性能与兼容性
```

换句话说：**现在不建议继续为了拆类而拆类。**

目前真正影响项目成熟度的核心问题是：

1. Translation Call 的语义确认仍需要进一步强化；
2. 自动 Rewrite 仍需要更严格的 Plan Validation 和语法验证；
3. Hook / Import 注入需要进一步从文本匹配转向 PSI / symbol 语义；
4. Vue Injected PSI 仍然是生命周期风险最高的区域；
5. `TsObjectMerger` 已成为新的高复杂度模块，需要大量 Golden Test；
6. Code + Import + Resource 的跨文件一致性需要继续保证；
7. 测试重点应从“数量”转向“黑盒、Negative、Idempotency、Lifecycle”；
8. CI 和性能需要分层，而不是继续单纯扩大单个 IntelliJ Platform Test Task。

---

# 2. 当前评分

| 模块 | 评分 | 结论 |
|---|---:|---|
| 总体架构 | **8.5** | 已经进入可维护阶段 |
| Framework Detection | **8.5** | 覆盖面较完整 |
| Vue 支持 | **8.5** | 目前最成熟的框架路径之一 |
| React / react-i18next | **8.0** | 主流程成熟，但 Hook / semantic resolve 仍需强化 |
| React Intl | **6.5** | 应独立建模，不应简单套 `t()` |
| Translation Detection | **8.0** | 已明显优于 Regex，但仍是核心风险 |
| Import Rewrite | **7.5** | 功能完整，semantic matching 仍可加强 |
| Resource Writer | **7.5** | JSON 较稳，TS merger 风险较高 |
| TS Resource Merge | **7.0** | `TsObjectMerger` 已成为重点测试区域 |
| PSI 使用 | **8.0** | 基础设计正确，Injected PSI 仍有风险 |
| 自动修改安全性 | **7.5** | 已有 Validation，但需要更完整的二次验证 |
| 测试体系 | **8.0** | 数量已经不错，应转向黑盒与生命周期 |
| 生命周期测试 | **7.5** | Vue 已有基础，仍需扩展删除/reparse/multi-file |
| 架构解耦 | **8.0** | 相比之前明显提升，不建议继续大拆 |
| 性能 | **6.5** | 尚未系统 profiling |
| CI | **7.5** | 可用，但 retry / test 分层仍可优化 |

### 综合：**8.3 / 10**

项目已经可以继续产品化。当前阶段的主要目标不是“让架构更漂亮”，而是：

> **确保插件在真实项目中宁可少做，也不要误改。**

---

# 3. 当前推荐架构

目前建议维持并逐步收敛为：

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
                      ProjectChangePlan
                              │
                           Validate
                              │
                 ┌────────────┴────────────┐
                 ▼                         ▼
             Rewriter                ImportManager
                 │                         │
                 └────────────┬────────────┘
                              ▼
                        ResourceWriter
                              │
                              ▼
                       Single WriteCommand
                              │
                              ▼
                          Undo / Redo
```

推荐模块职责：

| 模块 | 职责 |
|---|---|
| Model | 领域模型，不保存不必要的可变 PSI 状态 |
| Scanner | 发现候选节点，不负责最终语义判断，不修改 PSI |
| Analyzer | Framework、Translation Call、字符串有效性、Site 语义分析 |
| Planner | 把分析结果转换成完整 Change Plan |
| Validator | Apply 前检查 pointer、import、resource、collision、目标合法性 |
| Rewriter | 只负责按照 Plan 修改源码 |
| ImportManager | import / alias / hook / collision 处理 |
| ResourceWriter | JSON / TS 等 resource merge 与写回 |
| Orchestrator | 编排流程，不承载具体 Framework 语义 |

## 重要结论

**不要再大规模拆 `I18nProcessor`。**

目前架构已经足够清晰。继续为了抽象而增加空 Strategy / Rewriter，只会增加理解成本。只有出现真实行为差异时才继续拆分。

---

# 4. P0：Translation Call 语义识别

这是当前最重要的正确性问题之一。

不能只根据调用名称判断：

```ts
function t(value) {
  return value.trim()
}

t('你好')
```

这里的 `t()` 不应该被默认视为 i18n。

同样：

```ts
const foo = {
  t(value) { return value }
}

foo.t('你好')
```

也不能直接认定为 translation call。

## 推荐模型

增加明确的置信度：

```kotlin
enum class TranslationCallConfidence {
    CONFIRMED,
    PROBABLE,
    UNKNOWN,
    NON_TRANSLATION
}
```

然后：

```kotlin
data class TranslationCall(
    val call: JSCallExpression,
    val key: String,
    val functionName: String,
    val confidence: TranslationCallConfidence,
)
```

## 识别策略

```text
CallExpression
      ↓
Reference resolve
      ↓
Import / local symbol resolve
      ↓
Known i18n instance / known hook?
      ↓
TranslationCallConfidence
```

### 处理原则

```text
CONFIRMED
    ↓
可以认为已经国际化

PROBABLE
    ↓
可以展示为候选，但不要阻止提取

UNKNOWN
    ↓
继续提取

NON_TRANSLATION
    ↓
普通代码
```

### 最重要的安全原则

> **无法确认是不是 i18n 时，宁可继续提取，也不要错误地认为它已经国际化。**

## TODO

- [ ] P0：建立完整 `t / $t / tc / $tc / i18n.t / i18n.global.t` 语义矩阵
- [ ] P0：继续覆盖 local shadowing
- [ ] P0：覆盖 `useTranslation()` 多种 destructuring
- [ ] P0：覆盖 `useI18n()` 多种 destructuring
- [ ] P1：跨文件 i18n instance resolve
- [ ] P1：复杂 re-export / barrel chain
- [ ] P1：namespace import 与 alias 的完整语义验证

---

# 5. P0：Rewrite Validation

现在已经存在 Plan / Validation，这个方向正确，但还应该把 Validation 明确提升为自动修改的安全门。

目标：

```text
Collect
  ↓
Plan
  ↓
Validate ALL
  ↓
Apply
```

而不是：

```text
Apply A
 ↓
Apply B
 ↓
Apply C
 ↓
C 失败
```

## Validation 至少应该检查

```text
✓ target PSI pointer still valid
✓ target file still valid
✓ target belongs to expected file
✓ rewrite target is writable
✓ import collision is resolved
✓ hook target still exists
✓ resource file exists / can be created
✓ resource syntax is supported
✓ key does not create illegal merge
✓ framework assumptions still hold
```

## TODO

- [x] 已有 Apply 前 Validation
- [ ] P0：扩大 Validation 覆盖范围到 Resource / Import / Hook
- [ ] P0：所有高风险 rewrite 都必须经过 validator
- [ ] P1：为 validator 增加结构化 `ValidationError`
- [ ] P1：把“无法安全修改”与“程序异常”分开处理

---

# 6. P0：Rewrite 后 PSI / Syntax Validation

字符串替换成功不代表源码正确。

每次重要 Rewrite 后建议至少验证：

```text
Rewrite
 ↓
Commit Document
 ↓
Reparse PSI
 ↓
PsiErrorElement == 0
```

对于 TypeScript / TSX / Vue / JS：

```kotlin
PsiTreeUtil.findChildrenOfType(
    file,
    PsiErrorElement::class.java
)
```

必须确保 Rewrite 没有新增语法错误。

## 推荐测试

```text
Before parse
      ↓
Collect
      ↓
Apply
      ↓
Reparse
      ↓
Syntax validation
      ↓
Collect again
```

### 核心目标

> **Rewrite 后不仅源码字符串正确，而且 PSI 必须仍然合法。**

---

# 7. P0：Idempotency / 幂等性

真正的幂等性不能只测试内部 state reset。

必须测试：

```text
extract
 ↓
apply
 ↓
reparse
 ↓
extract again
```

第二次应该：

```text
0 new translation sites
```

建议大量采用 Golden Test：

```kotlin
val first = extractAndApply(input)
val second = extractAndApply(first)
assertEquals(first, second)
```

重点覆盖：

- React JSX
- React attributes
- Vue template
- Vue directives
- Vue injected JS
- existing translation calls
- resource files

---

# 8. P0：Vue Injected PSI 生命周期

Vue 仍然是整个项目生命周期风险最高的路径之一：

```text
Vue PSI
 ↓
Injected JS PSI
 ↓
SmartPsiElementPointer
 ↓
Rewrite
 ↓
Document change
 ↓
Reparse
 ↓
Injected PSI rebuild
```

需要特别测试：

```text
single element
sibling elements
nested expressions
parent / child
removed element
reparse
multiple files
multiple rewrite
```

## TODO

- [x] sibling pointer
- [x] nested pointer
- [x] 基础 Undo / Redo
- [x] rewrite → reparse → second collect
- [x] multiple sibling rewrite
- [x] nested expression rewrite
- [ ] P0：删除节点后的 pointer 行为
- [ ] P1：复杂 directive + interpolation
- [ ] P1：template literal + interpolation
- [ ] P1：slot / component prop
- [ ] P1：多文件 injected PSI 同时修改

---

# 9. P0：Hook 注入必须精确到 Function / Site

当前 React Hook 注入逻辑存在一个长期风险：

```text
文件中存在中文
    ↓
文件中存在 React component
    ↓
注入 useTranslation()
```

理想模型应该是：

```text
ExtractionSite
      ↓
owning function
      ↓
该 function 是否真的需要 t
      ↓
只注入该 function
```

建议：

```kotlin
data class HookInjectPlan(
    val targetFunction: SmartPsiElementPointer<JSFunction>,
    val hookName: String,
    val bindingName: String,
)
```

而不是只描述：

```text
React file needs hook
```

## 必测场景

```tsx
function A() {
  return <div>你好</div>
}

function B() {
  return <div>世界</div>
}
```

应只给真正需要的 function 注入。

同时覆盖：

- nested function
- arrow function
- component function
- custom hook
- class component
- function already containing `useTranslation`
- hook alias
- destructured alias

---

# 10. P0：Import 从 Text Matching 向 PSI / Symbol Matching 迁移

当前 ImportManager 已经覆盖大量边界，但仍有一些地方依赖：

```text
text.contains(...)
Regex
compact text matching
```

这在简单项目里足够，但在大型 TS 项目里容易误判。

例如：

```ts
const foo = {
  text: 'useTranslation'
}
```

不应该被当作 import / hook 存在。

推荐：

```text
ES6ImportDeclaration
 ↓
imported name
 ↓
local alias
 ↓
PsiReference
 ↓
resolve
```

## TODO

- [ ] P0：新增 semantic import detector
- [ ] P1：减少 `text.contains` 的关键路径使用
- [ ] P1：alias / namespace / type import 全部走统一模型
- [ ] P1：collision detection 统一走 symbol model

---

# 11. P1：Boolean Explosion / Site Model

当前如果继续增加：

```text
isVue
isReact
isJSX
isDirective
isAngular
isInjected
```

很容易产生非法组合。

建议逐步收敛成：

```kotlin
enum class ExtractionKind {
    TEMPLATE_TEXT,
    ATTRIBUTE,
    DIRECTIVE,
    JSX_TEXT,
    JSX_ATTRIBUTE,
    STRING_LITERAL,
    TEMPLATE_LITERAL,
    CONCATENATION,
    EXISTING_TRANSLATION,
}
```

再配合：

```kotlin
data class RewriteContext(
    val framework: FrameworkId,
    val siteKind: ExtractionKind,
    val language: SourceLanguage,
)
```

目标是减少 boolean combination。

---

# 12. P1：Framework Strategy 解耦

Strategy 体系目前已经比较成熟，但 Analyzer / Processor 不应该继续直接判断：

```kotlin
framework is ReactI18nextStrategy
```

更推荐能力接口：

```kotlin
framework.supportsGlobalI18nFallback()
framework.supportsHookInjection()
framework.supportsPluralCall()
framework.supportsGlobalInstance()
```

这样未来加入：

```text
React Intl
Vue I18n
Angular
Solid
Svelte
```

不会继续污染 Analyzer。

## 原则

> **Framework-specific knowledge 应该尽量停留在 Framework Strategy 内。**

---

# 13. P1：React Intl 必须独立建模

React Intl 与 react-i18next 的模型不是简单的 `t()` 替换。

至少需要独立覆盖：

```tsx
<FormattedMessage id="hello" />
```

```tsx
intl.formatMessage({ id: 'hello' })
```

```tsx
const { formatMessage } = useIntl()
```

```tsx
formatMessage({
  defaultMessage: '你好'
})
```

以及：

```text
defineMessages
MessageDescriptor
id
defaultMessage
description
```

建议：

```text
ReactI18nextStrategy
        ≠
ReactIntlStrategy
```

不要让 React Intl 继续依赖 `t()` 的抽象假设。

---

# 14. P0：TsObjectMerger 是当前新的高风险模块

近期 `TsFileEditor` 大量逻辑已经抽到 `TsObjectMerger`。这是正确的职责拆分，但同时意味着 `TsObjectMerger` 已经成为新的复杂核心。

它需要处理：

```text
flat key
nested object
dotted key
spread
comment
static value
expression
array
nested object
format preservation
```

因此下一阶段不要继续向里面塞功能，应该优先增加测试。

---

# 15. P0：TS Resource Golden Test 矩阵

必须覆盖：

### Basic

```ts
const messages = {
  hello: '你好'
}
```

### Dotted key

```ts
const messages = {
  'hello.world': '你好'
}
```

### Nested

```ts
const messages = {
  hello: {
    world: '你好'
  }
}
```

### Spread

```ts
const messages = {
  ...common,
  hello: '你好'
}
```

### Dynamic expression

```ts
const messages = {
  hello: getMessage()
}
```

### Comment

```ts
const messages = {
  hello: '你好', // comment
}
```

### Array

```ts
const messages = {
  hello: ['你好']
}
```

### Dotted key 与真实 nested object 混合

必须验证不会把：

```ts
{
  'user.name': '姓名'
}
```

无条件改造成：

```ts
{
  user: {
    name: '姓名'
  }
}
```

除非这是明确的产品配置行为。

## 建议

默认应该尽量保持用户已有 resource schema，不要因为 merge 方便而改变 key 的语义结构。

---

# 16. P0：Resource Writer 不应把 TS 当 JSON

TS Resource 必须区分：

```text
Static AST
Dynamic expression
```

正确策略：

```text
静态部分
  ↓
可以安全 merge

动态部分
  ↓
原样保留
```

不要为了生成 JSON 而执行或猜测：

```ts
foo()
bar()
getMessage()
```

否则插件可能改变用户程序语义。

---

# 17. P0：Negative Tests

当前测试不应该只证明“应该提取”。还必须大量证明“绝对不能误提取”。

至少增加：

### 普通函数

```ts
function t(x) {
  return x
}
t('你好')
```

### 普通对象

```ts
foo.t('你好')
```

### 注释

```ts
// 你好
```

### URL

```ts
'https://你好.com'
```

### CSS content

```css
.content {
  content: '你好';
}
```

### 测试断言

```ts
expect('你好')
```

### Object key

```ts
const x = {
  '你好': 'world'
}
```

目标：

```text
No false positive
No accidental import
No accidental hook
No accidental rewrite
```

---

# 18. P0：Golden Test 体系

这个项目特别适合 Golden / Fixture Test。

建议：

```text
tests/fixtures/
├── vue/
│   ├── basic/
│   ├── directive/
│   ├── injected/
│   └── lifecycle/
│
├── react/
│   ├── react-i18next/
│   ├── react-intl/
│   ├── hook/
│   └── collision/
│
├── js/
│   ├── plain/
│   └── existing-i18n/
│
└── resource/
    ├── json/
    └── typescript/
```

每个 fixture：

```text
input
 ↓
extract + apply
 ↓
expected source
 ↓
expected resource
 ↓
reparse
 ↓
second extract
 ↓
0 changes
```

这会比继续增加大量内部 `assertTrue()` 更有价值。

---

# 19. P1：测试分层

建议建立四层：

```text
Level 1 — Pure Unit
    ↓
Level 2 — PSI Integration
    ↓
Level 3 — Full Extraction / Golden
    ↓
Level 4 — Lifecycle / Undo / Redo
```

## Level 1

测试：

```text
key generation
placeholder
language detection
resource merge algorithm
framework capability
```

## Level 2

测试：

```text
PSI resolve
import
JSX
Vue injected PSI
```

## Level 3

测试真实输入输出：

```text
source → source + resource
```

## Level 4

测试：

```text
apply
undo
redo
reparse
apply again
```

---

# 20. P1：CI / Gradle 测试策略

当前 CI 已经具备失败 retry，但长期不建议把所有测试失败都视为“再跑一次可能成功”。

建议：

```text
确定性测试失败
    ↓
立即失败

基础设施 / flaky / OOM
    ↓
单独 retry
```

避免形成：

> “第一次 test 失败没关系，再跑一次就好了。”

## Gradle

目前 `forkEvery = 1` 这类保守配置有助于隔离 IntelliJ Platform 测试，但会显著增加启动成本。

不要立即提高并行度，而应该先把测试拆层：

```text
Unit
PSI
Integration
Lifecycle
```

然后再决定哪些测试可以并行。

---

# 21. P1：Undo / Redo

Undo / Redo 已经有基础测试，但需要确保：

```text
source
+
import
+
resource
```

属于同一个用户操作的完整变更。

测试目标：

```text
Initial
 ↓
Apply
 ↓
Undo
 ↓
Initial
 ↓
Redo
 ↓
Applied
```

重点覆盖：

- multi-file
- Vue injected PSI
- TS resource
- JSON resource
- import injection
- hook injection

---

# 22. P1：性能 Profiling

现在已经有：

```text
project-level scan
PSI traversal
framework detection
instance locator
resource merge
```

下一阶段建议先 profiling，不要马上加复杂 cache。

至少统计：

```text
file
framework detection
instance locator
scanner
analyzer
planner
rewrite
resource writer
```

例如：

```text
Foo.vue
  detection: 2ms
  locator: 8ms
  scanner: 12ms
  analyzer: 4ms
  planner: 2ms
  rewrite: 6ms
  resource: 3ms
```

确认瓶颈以后，再考虑：

```text
CachedValue
PsiModificationTracker
project-level cache
```

不要在 profiling 前盲目引入缓存，否则容易增加 PSI invalidation 的复杂度。

---

# 23. P1：Framework Detection

Framework Detection 当前已经比较成熟，不建议大改。

推荐优先级：

```text
当前文件明确语义
        >
当前 package.json
        >
workspace package
        >
root package
        >
Generic
```

尤其 shared package：

```text
packages/
├── react-app
├── vue-app
└── shared
```

shared 中的代码不应该简单根据 consumer app 推断成 React 或 Vue。

应尽量保持：

```text
file evidence
package evidence
workspace evidence
```

分层判断。

---

# 24. P1：SourceRewriter 不要过度抽象

如果某个 Rewriter 当前没有真正的 framework-specific 行为，不建议仅为了架构完整而创建空实现。

例如：

```text
ReactRewriter
SolidRewriter
```

如果内部没有差异，可以先由通用 JS/TS Rewriter 承担。

原则：

> **真实行为差异出现时再抽象，而不是为了“看起来有架构”而提前创建空 Strategy。**

---

# 25. P1：README / 产品能力声明

当前项目已经覆盖较多 framework 和场景，但建议将成熟度区分为：

### Stable

```text
Vue
React / react-i18next
```

### Experimental

```text
React Intl
Angular
Solid
Svelte
```

不是说这些功能不能使用，而是避免 README 的“支持”让用户理解成“所有语法都已经达到稳定插件级别”。

---

# 26. P1：WebStorm / IntelliJ Compatibility

项目高度依赖：

```text
JavaScript PSI
TypeScript PSI
JSX PSI
Vue PSI
Injected PSI
ES6 import PSI
```

因此 JetBrains 平台版本升级时必须重点回归。

建议 CI 至少考虑：

```text
WebStorm 2025.x
WebStorm 2026.x
IntelliJ IDEA 2026.x
```

特别是 Vue / JavaScript plugin 的 API 行为。

---

# 27. P2：进一步性能优化

在 profiling 之后，如果确认存在重复扫描，再考虑：

```text
CachedValue
PsiModificationTracker
ProjectStructure cache
Framework detection cache
Instance locator cache
```

不要提前做大规模缓存。

---

# 28. 最重要的架构原则

整个项目后续开发应该遵守以下规则：

### 原则 1：Analyze 与 Apply 分离

```text
Analyze
  ↓
Plan
  ↓
Validate
  ↓
Apply
```

### 原则 2：Write Action 是最后一步

Scanner / Analyzer / Planner 都不应该修改 PSI。

### 原则 3：高风险推断必须 conservative

```text
不知道 → 不要擅自修改
```

### 原则 4：优先保持用户代码语义

国际化插件最重要的不是“提取最多”，而是：

> **不破坏用户已有代码。**

### 原则 5：Rewrite 后必须可重新解析

```text
rewrite
 ↓
reparse
 ↓
valid PSI
```

### 原则 6：一次用户操作应该是一个 Undo 单元

Code + Import + Resource 应尽量属于同一个 command。

### 原则 7：不要把 TS 当 JSON

动态表达式必须保留。

---

# 29. 当前阶段不建议做的事情

## ❌ 1. 不要继续大规模拆 `I18nProcessor`

现在已经足够薄。继续拆的收益已经明显下降。

## ❌ 2. 不要为了架构漂亮增加空 Rewriter

只有真正存在不同的 rewrite 规则才抽象。

## ❌ 3. 不要继续无脑增加普通单元测试

现在测试数量已经不是最大问题。

## ❌ 4. 不要急着做复杂缓存

先 profiling。

## ❌ 5. 不要为了降低 false positive 而激进识别 i18n

插件宁可多提取一次，也不能漏掉用户真正需要国际化的内容，更不能误修改业务代码。

---

# 30. 推荐实施路线

## Phase 1 — Correctness Gate

**最高优先级**

```text
TranslationCall semantic resolve
        ↓
Negative tests
        ↓
Rewrite Validation
        ↓
PSI syntax validation
        ↓
Idempotency
```

目标：

> 自动修改安全。

---

## Phase 2 — Injection Safety

```text
HookInjectPlan → exact owning function
        ↓
Import semantic resolve
        ↓
减少 text matching
        ↓
SiteKind / RewriteContext
```

目标：

> 自动 import / hook 注入不误伤。

---

## Phase 3 — Resource Safety

```text
TsObjectMerger
        ↓
Golden tests
        ↓
Dynamic expression preservation
        ↓
Dotted key semantics
        ↓
Reparse
```

目标：

> Resource merge 不改变用户代码语义。

---

## Phase 4 — Lifecycle

```text
Vue Injected PSI
        ↓
Rewrite
        ↓
Reparse
        ↓
Second extraction
        ↓
Undo / Redo
```

目标：

> 长时间使用插件不会因为 PSI 生命周期出现隐蔽错误。

---

## Phase 5 — Performance / Compatibility

```text
Profiling
        ↓
Identify hotspots
        ↓
Cache only where necessary
        ↓
WebStorm / IDEA compatibility matrix
```

目标：

> 大项目可用、IDE 升级可控。

---

# 31. 最终 TODO 优先级

## P0 — 必须完成

- [ ] Translation Call 完整 semantic resolve
- [ ] `t / $t / tc / $tc / i18n.t / i18n.global.t` 完整矩阵
- [ ] `useTranslation / useI18n` destructuring 完整矩阵
- [ ] Rewrite 前完整 Validation
- [ ] Rewrite 后 PSI syntax validation
- [ ] 所有核心路径 Idempotency Test
- [ ] Vue Injected PSI 完整生命周期测试
- [ ] Hook 注入精确到 owning function
- [ ] Import 关键路径逐步 semantic resolve
- [ ] `TsObjectMerger` Golden Test
- [ ] TS Resource dynamic expression preservation test
- [ ] Negative Test 全面覆盖

## P1 — 应该完成

- [ ] Cross-file i18n instance resolve
- [ ] Import / Hook / Collision 统一 Symbol Model
- [ ] `ExtractionKind / RewriteContext` 替代 boolean explosion
- [ ] Framework capability API，减少 `framework is XxxStrategy`
- [ ] React Intl 独立 Strategy
- [ ] Undo / Redo 全链路测试
- [ ] Multi-file failure regression
- [ ] Unit / PSI / Integration / Lifecycle 测试分层
- [ ] CI retry 策略调整
- [ ] WebStorm / IntelliJ compatibility matrix

## P2 — 后续优化

- [ ] 性能 profiling
- [ ] 重复 PSI traversal 优化
- [ ] CachedValue / PsiModificationTracker
- [ ] 大型 workspace benchmark
- [ ] 更完整 framework ecosystem

---

# 32. 最终结论

当前项目已经从：

```text
“功能可以运行，但架构和正确性风险较高”
```

进入：

```text
“架构基本成立，下一阶段应该围绕正确性和安全 Rewrite 收敛”
```

综合评价：

> **8.3 / 10**

如果现在只允许优先做三件事，我建议严格按照下面的顺序：

### 第一：Translation Call 语义解析

解决：

```text
t()
foo.t()
i18n.t()
local t
hook t
alias t
```

到底谁是真正的 i18n。

### 第二：Rewrite Validation + PSI Syntax Validation

保证：

```text
Plan
 ↓
Validate
 ↓
Apply
 ↓
Reparse
 ↓
PSI valid
```

### 第三：TsObjectMerger + Vue Injected PSI Golden / Lifecycle Test

保护当前两个最高风险区域：

```text
Resource Rewrite
Vue PSI Rewrite
```

完成这三项后，项目的成熟度会明显高于继续做大规模架构重构。

---

# 33. 一句话判断

> **现在不要再“重构驱动开发”，应该正式进入“正确性驱动开发”。**
>
> 对一个会自动修改用户源码的 IntelliJ 插件而言，最重要的指标不是提取多少字符串，而是：**能否在复杂真实项目中长期、可预测、可撤销地修改代码，同时保证不改变用户原有语义。**
