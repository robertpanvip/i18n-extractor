# i18n-extractor 项目架构与稳定性评审

> 评审时间：2026-08-20  
> 评审对象：`main` 当前实现  
> 评审方式：逐项对照核心源码、Plan/Analyzer/Rewriter/Resource/Strategy 以及现有生命周期测试  
> 综合评分：**8.2 / 10**

> **重要说明**：本文只把已经在代码中存在的能力标记为“已完成”。建议项与现状严格分开。特别是 Translation Call 已经存在“三态语义判定”，这里不会再把它错误地列成“需要新增三级可信度模型”。

---

# 1. 最终结论

这个项目已经完成了一轮比较明显的架构收敛：

```text
I18nProcessor
      ↓
I18nFileOrchestrator
      ↓
I18nAnalyzer / JsStringCollector / ImportManager
      ↓
CollectedPlan / CollectedResult
      ↓
RewritePlan
      ↓
RewriteInterpreter / Rewriter
      ↓
Import / Resource
```

当前最重要的判断不是“还需要继续拆多少类”，而是：

> **架构重构已经基本进入收尾阶段，下一阶段应该从“重构驱动开发”切换到“正确性驱动开发”。**

当前真正需要继续投入的地方是：

1. Translation Call 三态判定的语义覆盖与边界验证；
2. 自动 Rewrite 的安全性；
3. Apply 前的完整验证边界；
4. Vue Injected PSI 生命周期；
5. Import / Hook 注入的精确性；
6. TS Resource Merger 的语义安全和测试；
7. 多文件修改与 Resource 写回之间的一致性；
8. 测试从“功能覆盖”继续向 Golden / Negative / Lifecycle 发展。

**不建议现在继续进行大规模架构拆分。**

---

# 2. 当前实现与上一次评审最大的变化

## 2.1 `I18nProcessor` 已经明显变薄

当前 `I18nProcessor` 的公开主流程已经基本收敛为：

```kotlin
extract()
collect()
apply()
run()
runWithUndo()
```

实际工作由 `I18nFileOrchestrator` 编排，Processor 主要负责依赖、能力接口和状态容器。

因此旧结论：

> “I18nProcessor 是当前主要 God Object，需要继续大拆。”

**已经不再成立。**

现在正确结论是：

> `I18nProcessor` 已经进入“薄 Orchestrator / Host”状态，继续拆分的收益已经明显下降。

---

# 3. 当前架构真实状态

当前代码已经具备这些真实存在的层：

```text
core
 └── I18nProcessor

orchestrator
 └── I18nFileOrchestrator

analyzer
 ├── I18nAnalyzer
 ├── SymbolAnalyzer
 └── TranslationAnalyzer

model
 └── ExtractionSite / TranslationCall / ExtractionContext

planner
 ├── ExtractionPlan
 ├── RewritePlan
 ├── ImportPlan
 ├── ResourcePlan
 ├── HookInjectPlan
 └── CollectedPlan / CollectedResult

rewriter
 ├── RewriteInterpreter
 ├── VueRewriter
 ├── JsRewriter
 └── ImportRewriter

resource
 ├── TsObjectMerger
 ├── TsResourceWriter
 └── JsonWriter

strategy
 ├── VueI18nStrategy
 ├── ReactI18nextStrategy
 ├── ReactIntlStrategy
 └── SolidI18nStrategy
```

这个架构已经足够继续产品化。

## 3.1 一个必须明确的架构事实

当前已经有 `ImportPlan` / `ResourcePlan` 类型，但 `ExtractionPlan` 本身目前主要承载：

```text
selectedAffix
selectedDigit
blockedSiteIds
rewrites
```

也就是说：

> **目前还不能把“Code Rewrite + Import + Resource”描述为已经完全统一到一个 `ExtractionPlan` 中。**

这是上一版评审中容易说过头的地方。

当前实际运行流程仍然是：

```text
CollectedResult
    ↓
RewriteInterpreter
    ↓
代码 PSI Rewrite
    ↓
ImportManager / ImportPlanner
    ↓
Import Rewrite
    ↓
Resource Writer / MergeApplier 等资源处理
```

因此“统一 ProjectChangePlan”仍然是后续架构优化，而不是已完成事项。

---

# 4. 当前评分

| 模块 | 评分 | 结论 |
|---|---:|---|
| Framework Detection | **8.5** | 已有较完整策略/注册体系 |
| Translation Detection | **8.5** | 已经有 Symbol + 三态语义判定 |
| Vue 支持 | **8.5** | Injected PSI 生命周期已有专门测试 |
| React / react-i18next | **8.2** | 基本能力成熟，Hook 注入仍偏粗 |
| React Intl | **7.5** | 已有独立 Strategy，但仍需扩大真实语义覆盖 |
| Import Rewrite | **8.0** | 已有较多边界测试，仍有文本匹配残留 |
| Source Rewrite | **8.0** | 已经 Plan 化，但 Validation 仍不完整 |
| TS Resource | **7.2** | `TsObjectMerger` 已独立，但复杂度较高 |
| PSI 生命周期 | **8.0** | Vue 已有完整回归样例，仍有边界待补 |
| 测试体系 | **8.2** | 数量和类型都不错，下一步应强化 Golden/Negative |
| 架构解耦 | **8.2** | 相比之前明显改善，不应再大拆 |
| 自动修改安全性 | **7.6** | 是当前最值得继续投入的方向 |
| 性能 | **6.8** | 尚未形成系统 profiling / benchmark |
| CI | **7.5** | 可用，但测试分层和 retry 策略仍可改善 |

### 综合：**8.2 / 10**

---

# 5. P0：Translation Call 三态语义判定 —— 已存在，不要重新设计

这是本次必须纠正的结论。

当前代码已经明确存在：

```kotlin
enum class TranslationCallStatus {
    TRANSLATION,
    NON_TRANSLATION,
    UNKNOWN,
}
```

而且 `TranslationAnalyzer` 已经明确规定：

```text
TRANSLATION
    = 已经有来源证据证明是翻译调用

NON_TRANSLATION
    = 已经有来源证据证明不是翻译调用

UNKNOWN
    = 无法证明任何一边
```

并不是简单的：

```text
t() = translation
```

当前调用链已经是：

```text
CallExpression
      ↓
SymbolAnalyzer.analyze()
      ↓
Reference Resolution
      ↓
SymbolOrigin
      ↓
TranslationCallStatus
```

`SymbolOrigin` 已经包含：

```text
I18N_FRAMEWORK_IMPORT
I18N_HOOK_OR_FACTORY
PLUGIN_DOLLAR_T
LOCAL_SHADOW
NON_I18N
UNKNOWN
```

因此：

> **不要再增加一个新的 `TranslationCallConfidence` 枚举。**

当前三态模型已经是正确的领域模型。

## 5.1 当前真正需要做的事情

不是重新设计三态，而是验证三态在更多语义场景下是否正确。

重点继续覆盖：

```text
裸 t()
tc()
$t()
$tc()

import alias
namespace import
barrel / re-export
local shadow
普通对象 foo.t()
i18n.t()
i18n.global.t()
useTranslation()
useI18n()
createI18n()
getI18n()
跨文件 instance
```

## 5.2 当前代码的一个重要安全选择

对于翻译候选名：

```text
t
 tc
$t
$tc
```

如果无法证明来源，当前实现会进入 `UNKNOWN`，并通过 `StringContext.INSIDE_UNKNOWN_CALL` **保守跳过自动修改**。

这意味着当前设计优先级是：

```text
避免误改 > 避免漏提
```

这个选择适合自动 Rewrite 插件，但必须通过测试把边界固定下来。

## 5.3 后续 TODO

- [ ] P0：继续完善 `t / tc / $t / $tc` 的语义矩阵
- [ ] P0：补充 `useTranslation()` / `useI18n()` 各种解构形式
- [ ] P1：补充跨文件 i18n instance resolve
- [ ] P1：补充复杂 alias / re-export / barrel 场景
- [ ] P1：验证 `UNKNOWN` 是否在所有调用路径都保持“禁止高风险 Rewrite”的语义

---

# 6. P0：Rewrite Validation —— 已有，但验证范围还不够完整

当前已经有 `ChangeValidator`，并且会在部分合并应用前检查：

```text
processor index
site id
replaceRootPointer
PSI element validity
```

失效时会在写入前抛出异常，避免已经验证的合并操作继续写入。

这部分是已经完成的基础设施。

但是不能写成：

> “所有 Change 已经在 Apply 前完整 validation。”

当前验证器主要围绕 MergePlan / SiteRef 做完整性检查，而普通 `RewritePlan`、ImportPlan、Resource 写回并没有全部经过一个统一的 preflight validator。

## 正确目标

```text
CollectedResult / ProjectChangePlan
             ↓
        Preflight Validate
             ↓
 ┌───────────┼───────────┐
 ↓           ↓           ↓
PSI       Import      Resource
pointer   collision    target
 ↓           ↓           ↓
 └───────────┼───────────┘
             ↓
            Apply
```

## TODO

- [x] P0：已有站点 pointer 基础校验
- [x] P0：已有 MergePlan 失效 site 零写入保护
- [ ] P0：将普通 `RewritePlan` 也纳入统一 preflight validation
- [ ] P0：Import collision / import target validation 纳入 preflight
- [ ] P0：Resource target / resource parse validation 纳入 preflight
- [ ] P1：把验证结果结构化为 `ValidationResult`，避免单纯异常驱动

---

# 7. P0：Rewrite Interpreter —— 已完成主要迁移

当前已经存在：

```text
RewritePlan
      ↓
RewriteInterpreter
      ↓
VueRewriter / JsRewriter / Skeleton rewrite
```

并且 `pendingChanges / CollectedChange` 闭包式写入路径已经被大量迁移成数据化 `RewritePlan`。

这是当前架构最重要的进步之一。

## 当前真实状态

已经实现：

- [x] `RewriteKind`
- [x] `RewritePlan`
- [x] `RewriteInterpreter`
- [x] 普通 JS/TS rewrite 数据化
- [x] Vue XML text / attribute rewrite
- [x] skeleton rewrite 数据化
- [x] `CollectedResult` 作为 run 阶段快照

## 仍然需要注意

`RewritePlan` 仍然保存 `SmartPsiElementPointer`，因此它不是完全脱离 PSI 的纯文本计划。

正确表述应该是：

> **RewritePlan 是“不可执行的数据化改写计划”，但其目标仍通过 SmartPsiElementPointer 定位 PSI。**

这对 IntelliJ 插件是合理的，不需要为了“纯数据”强行去掉 pointer。

---

# 8. P0：PSI 生命周期 —— Vue 是当前最高风险区域之一

当前已经存在 `VueLifecycleTest`，而且已经覆盖真实生命周期：

```text
collect
 ↓
runWithUndo
 ↓
源码修改
 ↓
再次 collect
 ↓
确认已翻译
 ↓
再次 apply
```

已有测试包括：

```text
Vue ternary
multiple sibling sites
nested mustache expression
rewrite → reparse → rewrite
```

这部分已经不是“完全没有测试”。

## 仍然需要补

- [ ] P0：删除节点后的 SmartPsiElementPointer 行为
- [ ] P1：外部修改文件后 pointer 失效行为
- [ ] P1：多文件同时 rewrite 后重新获取 PSI
- [ ] P1：directive + interpolation 混合
- [ ] P1：template literal + interpolation 混合
- [ ] P1：slot / component prop 混合

## 最重要的长期测试

```text
collect
 ↓
apply
 ↓
reparse
 ↓
collect
 ↓
apply
 ↓
reparse
 ↓
collect
```

最终必须保持：

```text
no new extraction
no duplicate $t
no syntax error
no source drift
```

---

# 9. P0：Hook 注入精确性 —— 当前仍然存在真实风险

当前 `HookInjectPlan` 已经数据化：

```kotlin
data class HookInjectPlan(
    val target: HookTarget,
    val statement: String,
)
```

这是好的。

但是它当前只描述：

```text
REACT
SOLID
VUE_COMPONENT
VUE_HOOK
VUE_SFC_SCRIPT
```

并没有描述具体的：

```text
target function / target pointer
```

实际 `ImportRewriter` 对 React/Solid 会继续扫描：

```text
React component functions
+
hook functions
```

然后批量注入。

因此之前提出的“HookInjectPlan 精确到 owning function”仍然是有效建议。

## 风险

例如一个文件：

```tsx
function Component() {
    return <div>你好</div>
}

function helper() {
    const value = buildSomething()
}
```

理想情况应该只给真正需要翻译能力的函数注入：

```tsx
function Component() {
    const { t } = useTranslation()
    ...
}
```

而不是因为文件中存在提取文本，就把所有候选函数都注入。

## TODO

- [ ] P0：HookInjectPlan 增加具体 function / pointer 定位
- [ ] P0：从“文件存在文本 → 批量扫描函数”改成“ExtractionSite → owning function → 注入”
- [ ] P1：验证 nested function / helper / callback / custom hook 场景
- [ ] P1：验证同一文件多个组件时只注入真正需要的组件

---

# 10. P0：Import Rewrite —— 语义 PSI 化仍值得继续

当前 ImportManager / ImportPlanner / ImportRewriter 已经形成比较完整的流程：

```text
InjectionDecision
      ↓
ImportPlanner
      ↓
ImportPlan
      ↓
ImportRewriter
```

并且已有 alias、namespace、重复 import 等边界测试。

但是代码里仍存在一部分：

```text
text.contains(...)
Regex
```

用于判断某些 import / hook 是否已经存在。

这在简单场景有效，但不是最终最可靠的 IntelliJ PSI 方案。

## 推荐逐步迁移

从：

```text
文本是否包含 useTranslation
```

变成：

```text
ES6ImportDeclaration
 ↓
imported name
 ↓
local alias
 ↓
resolve
```

## TODO

- [ ] P0：核心 import existence 判断逐步改为 PSI
- [ ] P1：alias resolve 与 imported symbol resolve 完整统一
- [ ] P1：避免字符串误命中注释、对象属性、普通字符串
- [ ] P1：建立 import semantic matrix

---

# 11. P0：React Intl —— 已经有独立 Strategy，不应再把它当成“尚未接入”

当前代码已经存在：

```text
ReactIntlStrategy
```

而且不是简单复制 React-i18next：

```text
id = react-intl
hookImport = useIntl
formatMessage
buildCallExpression()
placeholder = {0}
```

并且它已经处理：

```tsx
formatMessage({ id: 'key' })
```

这种与 `t('key')` 不同的调用形态。

因此：

> **react-intl 已经接入 Strategy 层。**

但当前实现仍明显属于“已接入、需要继续提高覆盖率”的状态。

## 需要继续验证

```text
useIntl()
formatMessage()
intl.formatMessage()
props.intl.formatMessage()
FormattedMessage
MessageDescriptor
defineMessages
defaultMessage
id
description
values
```

尤其 `FormattedMessage`、`defineMessages`、MessageDescriptor 相关语义仍值得做专门的集成测试。

## TODO

- [ ] P0：建立 React Intl 黑盒 fixture
- [ ] P0：验证 `formatMessage({id})` 的已有翻译识别
- [ ] P0：验证 values / ICU 参数
- [ ] P1：`FormattedMessage`
- [ ] P1：`defineMessages`
- [ ] P1：`defaultMessage`
- [ ] P1：`MessageDescriptor`

---

# 12. P1：Framework Strategy 解耦

当前 Framework Strategy 已经比较成熟，尤其：

```text
VueI18nStrategy
ReactI18nextStrategy
ReactIntlStrategy
SolidI18nStrategy
```

但是仍需要检查 Analyzer 中是否存在：

```kotlin
framework is ReactI18nextStrategy
```

这种具体类型判断。

如果确实需要某种能力，长期应该优先变成 capability：

```kotlin
framework.supportsGlobalI18nFallback()
framework.supportsHookInjection()
framework.translationCallStyle()
```

而不是让 Analyzer 了解每个具体 Strategy 类型。

## 原则

```text
Analyzer → Framework capability

而不是

Analyzer → Framework concrete class
```

这项属于 P1，不需要现在大改。

---

# 13. P1：`ExtractionSite` 仍有 boolean model 演化空间

当前 `ExtractionSite` 包含：

```kotlin
isVue
isReact
form
```

而 `RewritePlan` 还包含：

```kotlin
isJSX
isDirective
isAngular
```

这说明当前模型仍在使用一部分 boolean 来表达语法形态。

长期更好的模型是：

```kotlin
enum class SiteKind {
    VUE_TEMPLATE_TEXT,
    VUE_DIRECTIVE,
    JSX_TEXT,
    JSX_ATTRIBUTE,
    JS_LITERAL,
    JS_TEMPLATE,
    ANGULAR_ATTRIBUTE,
}
```

或者：

```kotlin
RewriteContext(
    framework = ...,
    siteKind = ...,
    language = ...,
)
```

## 注意

这不是当前 P0。

当前代码已经能工作，因此不要为了“模型漂亮”马上重构。等新增 Framework / Syntax 时再处理最合适。

---

# 14. P0：TS Resource / `TsObjectMerger`

最新代码已经把大量 TS Resource 合并逻辑独立到：

```text
resource/TsObjectMerger.kt
```

这次拆分本身是正确方向：

```text
TsFileEditor
    ↓
TsObjectMerger
```

当前 `TsObjectMerger` 已经处理：

```text
flat key → nested object
深度合并
冲突处理
drop old keys
静态 property
静态 block
非静态行保留
spread routing
range replacement
```

这是一个复杂度比较高的模块。

## 最大风险

不要把：

```text
TS object literal
```

当成：

```text
JSON object
```

例如：

```ts
const messages = {
    hello: getMessage(),
    ...common,
    world: '世界'
}
```

正确策略应该是：

```text
静态部分 → 可以安全 merge
动态部分 → 尽量原样保留
```

当前代码已经明确朝这个方向实现，这是正确的。

## P0 Golden Test

目前搜索不到以 `TsObjectMergerTest` 命名的独立测试类，因此这一块不能评价为“已经有完整 Golden Test 保护”。已有 `TsFileEditorCoreFunctionTest` / Bug regression 等相关测试，但最新独立 merger 的复杂行为应该进一步直接覆盖。

建议建立：

```text
resource/ts/
├── basic
├── nested
├── dotted-key
├── dotted-key-with-ellipsis
├── spread
├── dynamic-value
├── comments
├── arrays
├── duplicate
├── conflict
└── mixed-static-dynamic
```

每个 fixture：

```text
input.ts
expected.ts
```

然后：

```text
input
 ↓
merge
 ↓
expected
 ↓
reparse
 ↓
再次 merge
 ↓
结果不漂移
```

## 特别需要验证 dotted key

当前实现会把满足条件的：

```text
common.confirm
```

尝试展开为：

```text
common: {
    confirm: ...
}
```

而像：

```text
加载中...
```

因为包含空分段，不应该被错误展开。

这部分已经有明确防护逻辑，但应该通过 Golden Test 长期锁死。

---

# 15. P0：Resource Schema 语义风险

需要特别注意：

```ts
{
  "user.name": "姓名"
}
```

和：

```ts
{
  user: {
    name: "姓名"
  }
}
```

不是同一个原生对象结构。

因此插件是否把 dotted key 自动展开，是一个**产品语义选择**，而不是纯技术实现。

建议明确策略：

```text
默认保持用户原有 key schema
```

或者：

```text
提供 dotted-key nesting 配置
```

不要让 merge 行为在用户不知情的情况下改变 resource schema。

如果当前产品明确规定 dotted key 必须展开，则至少应在文档和测试中固定这个约定。

---

# 16. P0：多文件修改原子性 —— 当前已经有基础，但不能宣称完全事务化

当前已经有：

```text
CommandProcessor
+
WriteCommandAction
+
Undo / Redo
```

也已经有针对 code + import + resource 的生命周期测试基础。

但是需要区分两个概念：

### IntelliJ Command 原子性

可以做到：

```text
一次 Command
 ↓
多个 PSI 修改
 ↓
Undo
```

### 真正业务事务

需要：

```text
所有 PSI
+
所有 import
+
所有 resource
```

在修改之前全部完成 validation。

当前项目还没有一个统一的：

```text
ProjectChangePlan
```

把所有 Code / Import / Resource change 作为一个完整 preflight 单元。

## TODO

- [ ] P0：统一 Code / Import / Resource ChangePlan
- [ ] P0：所有资源目标在 Apply 前检查
- [ ] P0：所有 import collision 在 Apply 前检查
- [ ] P0：所有 rewrite pointer 在 Apply 前检查
- [ ] P1：失败恢复策略
- [ ] P1：跨文件 resource 写入失败 regression test

---

# 17. P1：测试体系 —— 数量不是主要问题，结构才是

当前测试已经覆盖很多实际问题，包括：

```text
Symbol semantic
Import rewrite
Vue processor
Vue lifecycle
Bug reproduction
TsFileEditor core
```

因此不建议继续无脑增加：

```text
assertTrue(...)
assertEquals(...)
```

真正需要强化的是四层测试。

## Level 1：Pure Unit

```text
key generation
language detection
merge algorithm
symbol classification
```

## Level 2：PSI Integration

```text
JS PSI
TS PSI
JSX PSI
Vue Injected PSI
Import PSI
```

## Level 3：Black-box Extraction

```text
input source
 ↓
extract/apply
 ↓
output source
+
resource
```

## Level 4：Lifecycle

```text
extract
 ↓
apply
 ↓
reparse
 ↓
extract
 ↓
apply
 ↓
undo
 ↓
redo
```

---

# 18. P0：Negative Tests

这是目前非常值得继续强化的一类测试。

不仅要测试：

```text
应该提取
```

还必须测试：

```text
绝对不能误提取
绝对不能误改写
绝对不能误注入
```

至少包括：

```ts
function t(x) {
    return x
}

t('你好')
```

```ts
const foo = {
    t(x) {
        return x
    }
}
foo.t('你好')
```

```ts
const t = somethingElse

t('你好')
```

```ts
foo('你好')
```

```ts
console.log('你好')
```

```ts
// 你好
```

以及：

```text
普通字符串
URL
CSS
测试代码
属性名
对象 key
```

当前 TranslationAnalyzer 已经明确选择 UNKNOWN 保守策略，因此这些测试应该重点验证：

> **UNKNOWN 不应该导致高风险 Rewrite。**

---

# 19. P0：Rewrite 后语法完整性

自动修改插件最重要的指标之一不是：

```text
字符串结果正确
```

而是：

```text
修改之后代码仍然是合法 PSI
```

建议所有关键 Golden Test 增加：

```text
Rewrite
 ↓
reparse
 ↓
PsiErrorElement == 0
```

尤其覆盖：

```text
Vue injected PSI
JSX attribute
JS template literal
ternary
nested expression
Angular interpolation
TS object resource
```

## TODO

- [ ] P0：建立统一 `assertNoPsiErrors()` helper
- [ ] P0：关键 Rewrite fixture 全部加入语法完整性验证
- [ ] P1：Resource TS 写回后也做 PSI parse 验证

---

# 20. P1：Idempotency

当前已经存在 Vue 生命周期幂等测试：

```text
第一次 rewrite
 ↓
reparse
 ↓
第二次 collect
 ↓
不再重复提取
```

这很好。

但建议把“幂等”提升成项目级 invariant：

```text
extract(apply(source))
```

应该满足：

```text
no new extraction
no duplicate translation call
no source drift
no resource drift
```

尤其 TS Resource：

```text
merge
 ↓
write
 ↓
parse
 ↓
merge again
```

应该保证输出稳定。

---

# 21. P1：Performance

目前没有足够证据说明项目已经存在严重性能问题，但当前扫描链路包含：

```text
Framework detection
PSI traversal
Symbol resolve
Instance locator
Import detection
Resource analysis
```

在大型项目中可能产生重复 PSI traversal。

因此下一步不要直接上复杂缓存。

推荐先做 profiling：

```text
file
framework detection
symbol analysis
scanner
rewrite
import
resource
```

记录：

```text
10 files
100 files
1000 files
10000 strings
```

确认真正热点之后，再考虑：

```text
CachedValue
PsiModificationTracker
project-level index
```

---

# 22. P1：CI / Test 执行策略

当前项目已经具备 GitHub Actions 测试工作流，也针对之前遇到的 IntelliJ Platform / Gradle 测试不稳定问题做了 retry 等处理。

长期不建议把“第一次测试失败、第二次成功”作为正常测试语义。

更好的模型是：

```text
确定性测试失败
    ↓
立即失败

基础设施 / OOM / flaky
    ↓
单独 retry / 标记
```

同时建议逐步把测试分层：

```text
Unit
PSI
Integration
Lifecycle
```

这样可以降低 PR 测试反馈时间。

---

# 23. P1：WebStorm / IntelliJ Compatibility

这个项目高度依赖：

```text
JavaScript PSI
TypeScript PSI
JSX PSI
Vue Injected PSI
ES6 Import PSI
```

因此 IDE 版本兼容性比普通 Kotlin 插件更加重要。

建议最终形成：

```text
WebStorm
IntelliJ IDEA
```

至少覆盖项目实际支持的主要版本线。

重点测试：

```text
JS/TS PSI
JSX
Vue injected
import resolve
```

---

# 24. 不建议现在做的事情

## ❌ 1. 不要继续大拆 `I18nProcessor`

目前已经足够薄。

## ❌ 2. 不要重新设计 TranslationCall 三态

当前已经有：

```text
TRANSLATION
NON_TRANSLATION
UNKNOWN
```

继续扩展边界测试即可。

## ❌ 3. 不要为了“纯数据”强行删除 SmartPsiElementPointer

IDE 插件 Rewrite 必须可靠定位 PSI，pointer 是合理的数据引用。

## ❌ 4. 不要继续增加空的 Rewriter

当前 `ReactRewriter` / `SolidRewriter` 仍然主要是占位类型。

只有真正出现不同 Rewrite 行为时再把它们做成独立实现。

## ❌ 5. 不要现在急着做复杂缓存

先 profiling。

## ❌ 6. 不要单纯为了测试数量继续增加大量普通测试

优先 Golden / Negative / Lifecycle。

---

# 25. 推荐下一阶段执行顺序

```text
Phase 1 —— 正确性基础
────────────────────────
① 完善 Translation Call 三态测试矩阵
② Negative Tests
③ Rewrite 后 PSI syntax validation
④ 完整 preflight validation

Phase 2 —— Rewrite 安全
────────────────────────
⑤ HookInjectPlan 精确到 owning function
⑥ Import semantic resolve
⑦ UNKNOWN / low-confidence 路径安全边界

Phase 3 —— Resource
────────────────────────
⑧ TsObjectMerger Golden Tests
⑨ TS resource parse validation
⑩ dotted-key schema 行为固定

Phase 4 —— 事务与生命周期
────────────────────────
⑪ ProjectChangePlan
⑫ Code + Import + Resource preflight
⑬ 多文件失败回归
⑭ Undo / Redo / Reparse

Phase 5 —— 工程质量
────────────────────────
⑮ Performance profiling
⑯ CI 测试分层
⑰ WebStorm / IntelliJ compatibility matrix
```

---

# 26. 最终 P0 / P1 / P2 TODO

## P0

- [ ] 完善 Translation Call 三态语义矩阵
- [ ] 完善 `useTranslation / useI18n` 解构与来源证明
- [ ] 普通 RewritePlan 纳入统一 Apply 前 validation
- [ ] Import collision 纳入 Apply 前 validation
- [ ] Resource target 纳入 Apply 前 validation
- [ ] Rewrite 后 PSI syntax validation
- [ ] HookInjectPlan 精确到 owning function
- [ ] Negative Tests 覆盖未知/普通 `t()` / `foo.t()` / shadowing
- [ ] `TsObjectMerger` Golden Tests
- [ ] TS Resource 写回后的 PSI parse validation
- [ ] React Intl 黑盒测试：formatMessage / values / ICU
- [ ] 建立 Code + Import + Resource 的统一 preflight ChangePlan

## P1

- [ ] Translation Call 跨文件 instance resolve
- [ ] re-export / barrel / namespace 更多组合测试
- [ ] Import 判断逐步 PSI 化
- [ ] React Intl：FormattedMessage / defineMessages / MessageDescriptor
- [ ] Hook nested function / helper / callback 测试
- [ ] Vue directive + interpolation 混合生命周期
- [ ] slot / component prop 生命周期
- [ ] dotted-key schema 明确产品语义
- [ ] Idempotency 项目级测试
- [ ] CI 测试分层
- [ ] WebStorm / IntelliJ compatibility matrix
- [ ] Performance profiling

## P2

- [ ] `ExtractionSite` / `RewritePlan` boolean 模型进一步类型化
- [ ] Framework capability API，减少具体 Strategy 类型判断
- [ ] CachedValue / PsiModificationTracker 等缓存优化
- [ ] 更细粒度的 Resource formatting preservation

---

# 27. 最终判断

当前项目已经不是“需要继续架构重构”的阶段。

更准确的状态是：

```text
架构
████████████████░░  8.2

功能
████████████████░░  8.0+

测试
████████████████░░  8.2

自动修改安全性
███████████████░░░  7.6

性能
█████████████░░░░░  6.8
```

最关键的结论只有三个：

### ① Translation Call 三态已经存在，而且方向正确

不要再重做可信度模型。

应该继续提高：

```text
Symbol evidence
+
Scope resolution
+
Framework source
+
Cross-file resolution
```

的覆盖率。

### ② 架构重构已经基本够了

`I18nProcessor`、`I18nFileOrchestrator`、`CollectedPlan`、`RewritePlan`、`RewriteInterpreter` 已经形成了比较清晰的流水线。

下一阶段继续拆类的收益低于继续补正确性。

### ③ 当前最重要的风险是“自动修改”

真正决定这个插件能不能让用户放心使用的，不是：

```text
支持多少 Framework
```

而是：

```text
它判断错的时候
会不会修改用户代码？

它修改以后
代码会不会失效？

它修改多文件时
会不会留下半完成状态？
```

因此后续开发优先级应该坚定地围绕：

> **Conservative semantic analysis → Preflight validation → Safe Rewrite → Reparse validation → Idempotency**

展开。

如果这条链路稳定下来，这个项目就会从“功能比较完整的 i18n extractor”真正进入“可以放心在真实大型项目中使用的 IDE 自动重构工具”阶段。
