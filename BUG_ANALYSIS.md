# i18n-extractor Bug & Test Coverage Analysis

> 分析时间：2026-08-18
>
> 分析对象：`robertpanvip/i18n-extractor`
>
> 本文基于当前仓库源码、测试结构以及近期 Bug 修复提交进行分析。重点关注潜在 Bug、测试覆盖率与后续测试建设方向。

## 1. 总体结论

当前项目已经拥有比较完整的核心测试体系，尤其是 Vue / React 的 i18n 提取、Folding、Merge、写回以及历史 Bug Regression Test。

**综合评价：约 7/10。**

问题不在于“测试太少”，而在于：

- 测试大量集中在已经发现的 Bug 上；
- 核心 Processor 测试很多，但架构层测试不足；
- Framework Detection / Registry 缺少系统性测试；
- Monorepo 场景覆盖不足；
- i18n Instance Locator 仍存在文本搜索误判风险；
- Import Injection / PSI Rewrite / WriteBack 是高风险区域，但组合测试不足；
- 真正的 IntelliJ IDE 生命周期、Action、Editor、VFS 级集成测试不足；
- Runtime correctness 基本没有覆盖；
- 性能和大项目场景缺少系统测试。

因此，当前项目更像是：

```text
核心算法测试：较强
Regression Test：较强
架构测试：一般
IDE Integration Test：偏弱
Runtime Test：明显不足
Performance Test：不足
```

---

## 2. 当前测试体系评价

目前已经存在较多测试类，包括但不限于：

- `I18nProcessorTest`
- `VueI18nProcessorTest`
- `ReactI18nProcessorTest`
- `ReactI18nTCallScenarioTest`
- `I18nFoldingBuilderTest`
- `MergeApplierTest`
- `MergeApplierPureTest`
- `CommonPrefixSuffixFactorizerTest`
- `LanguageExtractorSettingsTest`
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

这说明项目已经不是缺少测试，而是测试策略需要进一步升级。

### 核心算法测试

**8/10**

Vue / React Processor 测试较充分，核心 extraction 逻辑已经有较好的回归保护。

### Regression Test

**9/10**

最近连续增加 `BugRepro`、`BugRegression` 等测试，这是项目目前比较强的一部分。

### 架构测试

**5/10**

Framework Registry、Framework Detection、Monorepo 等架构层场景还没有形成完整测试矩阵。

### IDE Integration Test

**4/10**

真实的 IntelliJ Editor / PSI / VFS / Action / Folding / WriteCommand 组合场景覆盖不足。

### Runtime Correctness

**2/10**

静态 PSI 测试无法验证真实 Vue I18n / React i18next API 的运行时语义，目前这方面测试明显不足。

---

## 3. P0：发现的高风险问题

## 3.1 `I18nFrameworkRegistry` 的注册机制与检测机制没有真正连接

当前 Registry 类似：

```kotlin
object I18nFrameworkRegistry {
    private val strategies = mutableListOf<I18nFramework>()

    init {
        register(VueI18nStrategy)
        register(SolidI18nStrategy)
        register(ReactI18nextStrategy)
        register(GenericStrategy)
    }

    fun register(strategy: I18nFramework) {
        strategies.add(strategy)
    }

    fun detect(element: PsiElement): I18nFramework {
        if (Util.isVue(element)) return VueI18nStrategy
        if (Util.isSolid(element)) return SolidI18nStrategy
        if (Util.isReact(element)) return ReactI18nextStrategy
        return GenericStrategy
    }
}
```

问题是：`strategies` 被注册了，但 `detect()` 并没有遍历 `strategies`。

这意味着：

```kotlin
register(MyFramework)
```

实际上不能让 `MyFramework` 自动参与检测。

### 建议

改成类似：

```kotlin
fun detect(element: PsiElement): I18nFramework {
    return strategies.firstOrNull { it.matches(element) }
        ?: GenericStrategy
}
```

同时必须建立：

- Vue Detection Test
- React Detection Test
- Solid Detection Test
- Generic Fallback Test
- Custom Framework Registration Test
- Framework Priority Test

这是当前最明显的架构级问题之一。

---

## 3.2 Framework Detection 在 Monorepo 中存在较高风险

当前框架判断依赖：

```text
Util.isVue()
Util.isReact()
Util.isSolid()
```

如果项目结构类似：

```text
root/
├── package.json
└── packages/
    ├── react-app/
    │   └── package.json
    └── vue-app/
        └── package.json
```

就必须保证当前文件使用的是最近的 package/module，而不是错误地使用 root package.json 的依赖信息。

必须增加：

- root React + package Vue
- root Vue + package React
- React/Vue 混合 Monorepo
- pnpm workspace
- yarn workspace
- npm workspace

测试。

---

## 3.3 i18n Instance Locator 仍存在文本搜索误判风险

目前部分逻辑仍然类似：

```kotlin
text.contains("createI18n(")
text.contains("initReactI18next")
Regex("(?:i18n|i18next)\\s*\\.\\s*init\\s*\\(")
```

这会产生误判，例如：

```ts
const text = "createI18n()"
```

```ts
// createI18n()
```

```ts
const fn = createI18n
```

```ts
console.log("initReactI18next")
```

都可能被文本搜索命中。

### 建议

逐步使用 PSI：

```text
PSI
 ↓
Import Resolution
 ↓
CallExpression
 ↓
Reference Resolution
 ↓
判断 createI18n / i18n.init
```

Regex 可以保留为 fallback，但不应该作为主要语义判断方式。

---

## 3.4 `T_CALL_PATTERN` 对 JS/TS 语法覆盖有限

目前类似：

```kotlin
Regex("(?:\\$(?:t|tc)|i18n\\.global\\.(?:t|tc)|i18n\\.(?:t|tc))\\(\\s*([`\"'])([^`\"'\\n]+)\\1\\s*[,)]")
```

对于以下情况存在风险：

```js
$t(
  'hello'
)
```

```js
i18n
  .global
  .t('hello')
```

```js
t(
  `hello`
)
```

```js
$t(`hello ${name}`)
```

建议主要通过 PSI 的 CallExpression / arguments 判断，Regex 只作为兼容 fallback。

---

## 3.5 模板字符串的静态判断过于依赖文本

类似：

```kotlin
if (value.contains("\${")) return null
```

这种判断对于简单情况可用，但不是真正的 JS/TS 语法分析。

建议使用 PSI：

```text
JSStringTemplateExpression
 ↓
检查是否存在 interpolation
 ↓
无 interpolation → 静态 key
有 interpolation → 动态 key，不提取
```

同时增加 escaped interpolation 等边界测试。

---

## 3.6 Import Injection / PSI Rewrite / WriteBack 是最高风险区域

项目当前已经涉及：

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

这里容易出现：

- offset 变化
- PSI element invalidation
- SmartPsiElementPointer 失效
- 多次 rewrite 相互影响
- import 重复
- import alias 冲突
- TSX / JSX 语法破坏
- Vue injected language 问题
- CRLF/LF 问题
- multiline rewrite 问题

目前虽然已有 `MergeApplierTest`、`UtilWriteBackTest`、`TsFileEditorCoreFunctionTest` 等测试，但组合场景仍不足。

---

## 4. P1：需要重点补充的场景

### 4.1 Framework Matrix

建议建立：

| 场景 | Vue | React | Solid | Generic |
|---|---:|---:|---:|---:|
| 单引号 | ✅ | ✅ | ✅ | ✅ |
| 双引号 | ✅ | ✅ | ✅ | ✅ |
| 模板字符串 | ✅ | ✅ | ✅ | ✅ |
| multiline | 待补 | 待补 | 待补 | 待补 |
| nested call | 待补 | 待补 | 待补 | 待补 |
| ternary | 待补 | 待补 | 待补 | 待补 |
| object property | 待补 | 待补 | 待补 | 待补 |
| array element | 待补 | 待补 | 待补 | 待补 |
| JSX attribute | - | 待补 | - | 待补 |
| Vue attribute | 待补 | - | - | - |
| comment | 待补 | 待补 | 待补 | 待补 |

---

### 4.2 Nested Expression

需要覆盖：

```ts
foo({
  title: "你好",
  description: "世界"
})
```

应转换为：

```ts
foo({
  title: $t("你好"),
  description: $t("世界")
})
```

还需要：

```ts
foo(condition ? "你好" : "世界")
```

```ts
const x = condition ? "你好" : `${name}你好`;
```

```ts
items.map(item => "你好")
```

---

### 4.3 连续字符串与同表达式多个字符串

例如：

```ts
const a = "你好";
const b = "世界";
```

以及：

```ts
foo("你好", "世界")
```

必须保证第二种不会错误变成：

```ts
$t("你好", "世界")
```

而应该是：

```ts
foo($t("你好"), $t("世界"))
```

---

### 4.4 Import 场景

至少需要覆盖：

- import 已存在
- named import
- default import
- import alias
- export default i18n
- export const i18n
- export { i18n }
- import 路径别名
- 相对路径
- index 文件路径折叠
- Windows 路径
- CRLF

---

### 4.5 Vue / React / TSX / JSX

需要分别验证：

- `.ts`
- `.tsx`
- `.js`
- `.jsx`
- `.vue`
- Vue template injected JS
- Vue attribute
- JSX attribute
- JSX expression
- slot / directive

---

## 5. P2：边界和性能测试

### 编码

- UTF-8
- emoji
- 中文 + 英文
- Unicode 特殊字符

### 换行

- LF
- CRLF

### 大文件

建议至少：

- 1,000 个字符串
- 10,000 个字符串
- 1 MB TS 文件
- 多文件项目

同时记录：

- extraction 时间
- PSI 操作时间
- writeBack 时间
- Folding 构建时间
- 内存使用情况

---

## 6. IDE Integration Test 缺口

当前测试体系主要集中于 Processor / PSI / Core Function。

还应该增加真实 IntelliJ 环境下的集成测试：

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

重点测试：

- 右键 Action 是否正确显示
- Action Update 是否正确
- Editor 中 Folding 是否正确
- Vue injected language 是否正确
- 修改 Document 后 PSI 是否保持有效
- WriteCommandAction 后 Undo/Redo 是否正确
- 文件保存后内容是否正确

---

## 7. Runtime Correctness 缺口

静态分析无法完全验证某些 API 的真实运行时行为。

例如：

```ts
const $t = i18n.global.t;
$t("hello");
```

以及：

```ts
const $t = getI18n().t;
$t("hello");
```

需要通过真实 Vue I18n / React i18next fixture 或最小运行时测试确认语义是否等价。

---

## 8. Plugin Descriptor / Dependency

当前插件已经支持：

- Vue
- React
- Solid
- Generic

但 `plugin.xml` 如果硬依赖 Vue Plugin，会导致纯 React 用户也必须安装 Vue Plugin。

建议评估：

```xml
<depends>JavaScript</depends>
```

并将 Vue / React / Solid 能力尽量设计为可选能力或运行时检测。

同时建议增加 Plugin Descriptor Validation / IDE Startup Test。

---

## 9. CI 测试稳定性

当前 CI 存在失败后 retry 的机制。

这种方式可以缓解 IntelliJ Test 偶发失败，但会隐藏 flaky test：

```text
第一次失败
 ↓
第二次成功
 ↓
CI Green
```

建议保留 retry，但将“第一次失败、retry 成功”记录为 warning，并保存相关日志/报告。

另外，本地和 CI 的 Gradle / IntelliJ EAP 版本最好统一，避免：

```text
本地 Gradle ≠ CI Gradle
IDEA EAP 每次变化
```

导致测试结果不一致。

---

## 10. 建议的 P0/P1/P2 优先级

### P0

1. 修复 `I18nFrameworkRegistry` 注册与检测脱节问题。
2. 增加 Framework Detection 测试矩阵。
3. 增加 Monorepo 测试。
4. 降低 i18n Instance Locator 对文本搜索的依赖。
5. 加强 Import Injection / PSI Rewrite / WriteBack 的组合测试。
6. 增加 multiline / nested expression / TSX / JSX 场景。

### P1

1. 增加真实 IDE Integration Test。
2. 增加 Vue injected language 测试。
3. 增加 Runtime Fixture。
4. 完善 import / export / alias 测试。
5. 增加 Plugin Descriptor / startup 验证。

### P2

1. 性能测试。
2. 大文件测试。
3. 多文件 Merge 测试。
4. Unicode / emoji / CRLF 测试。
5. Flaky Test 监控。

---

## 11. 最终评价

当前项目的优势是：

- Vue / React 核心逻辑测试比较扎实；
- Regression Test 建设得很好；
- 最近的 Bug 修复能够及时转化为测试；
- PSI / Merge / WriteBack 已经有较多基础测试。

当前项目的主要风险是：

```text
                 ┌─ Framework Detection
                 │
                 ├─ Monorepo
                 │
                 ├─ Instance Locator
                 │
                 ├─ Import Injection
                 │
Input → PSI → Extract → Rewrite → Merge → Write
                                      ↑
                                  高风险区域
```

因此，下一阶段不建议继续单纯堆积 Regression Test，而应该建立：

> **Framework × Syntax × File Type × Project Structure × Rewrite Operation 的测试矩阵。**

这样才能从“修复已经发现的 Bug”逐渐升级到“主动发现未知 Bug”。

### 综合评分

| 模块 | 评分 |
|---|---:|
| 核心算法测试 | 8/10 |
| Regression Test | 9/10 |
| 架构测试 | 5/10 |
| IDE Integration | 4/10 |
| Runtime Correctness | 2/10 |
| Performance Test | 3/10 |
| **综合** | **7/10** |
