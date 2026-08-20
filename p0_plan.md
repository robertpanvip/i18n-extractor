# P0 落地计划

> 依据《PROJECT_ANALYSIS.md》§26 的 P0 清单，按依赖与收益分批落地。
> 执行顺序：**C → B → A → D → E → F**。
> 本文件随实施进度勾选，作为分批对照的单一事实来源。

## 存量核对

| 项 | 状态 |
|---|---|
| `ChangeValidator` | 已有，仅覆盖 MergePlan/SiteRef 的 site 指针校验 |
| `I18nNegativeExtractionTest` | 已有，可扩充 |
| `SymbolSemanticMatrixTest` | 已有，可扩充 |
| `I18nImportRewriteComboTest` | 已有 |
| 统一 preflight | 缺失（A 组） |
| `assertNoPsiErrors` helper | 缺失（C 组） |
| 独立 `TsObjectMergerTest` | 缺失（E 组） |
| React Intl 黑盒 | 缺失（F 组） |

## P0 分组

### A. preflight 安全（§6 / §16）—— 实现类
- [ ] A1. 普通 `RewritePlan` 纳入统一 Apply 前 validation
- [ ] A2. Import collision 纳入 Apply 前 validation
- [ ] A3. Resource target 纳入 Apply 前 validation
- [ ] A4. 建立 Code + Import + Resource 的统一 preflight（`ProjectChangePlanner` preflight），写入前完成全部检查，失败零写入
- 依赖：先读透 `ChangeValidator`（已读，目前仅服务合并计划）
- 可验证出口：构造失效 pointer / import 冲突 / resource 解析失败 → 断言**零写入**

### B. 翻译三态（§5 / §18）—— 测试扩充
- [ ] B1. 扩充 `t / tc / $t / $tc` 语义矩阵（`SymbolSemanticMatrixTest`）
- [ ] B2. `useTranslation()` / `useI18n()` 解构与来源证明
- [ ] B3. Negative Tests：未知 / 影子 / 普通对象 `foo.t()` / shadowing（`I18nNegativeExtractionTest`）
- 依赖：现有 `TranslationAnalyzer` / `SymbolAnalyzer`
- 可验证出口：`UNKNOWN` 不触发高风险 rewrite（负例断言）

### C. 语法完整性（§19）—— helper + 测试
- [ ] C1. 建 `assertNoPsiErrors()` helper
- [ ] C2. 关键 Rewrite fixture 加入 reparse 语法完整性校验
- [ ] C3. TS resource 写回后 PSI parse 校验
- 依赖：C 自身（helper 先行）
- 可验证出口：Vue / JSX / template / Angular / TS resource 改写后 `PsiErrorElement == 0`

### D. Hook 注入精确性（§9）—— 实现类
- [ ] D1. `HookInjectPlan` 增加 owning function / pointer
- [ ] D2. 从"文件存在文本 → 批量扫描函数"改为"ExtractionSite → owning function → 注入"
- 依赖：读 `ImportRewriter` 注入路径
- 可验证出口：同文件多组件**只注入真正需要的**函数

### E. TsObjectMerger Golden（§14）—— 测试
- [ ] E1. 独立 `TsObjectMergerTest`（fixture: basic / nested / dotted-key / dotted-key-with-ellipsis / spread / dynamic-value / conflict）
- [ ] E2. dotted key 防护用 Golden 锁死（`加载中...` 不误展开）
- 依赖：无（merger 已收敛完毕）
- 可验证出口：`merge → reparse → 再 merge` 不漂移

### F. React Intl 黑盒（§11）—— 测试
- [ ] F1. `formatMessage({ id })` 的已有翻译识别
- [ ] F2. values / ICU 参数
- 依赖：无
- 可验证出口：黑盒 extract / apply

## 进度

（开始执行后按批次勾选）

- [x] C 组：C1 建 `assertNoPsiErrors()` helper（`testutil/TestPsiAssertions`，8 测试通过）
- [x] C 组：C2 关键 Rewrite 产物（JSX attribute / JS template）reparse 无错
- [x] C 组：C3 TS 资源写回（nested/dotted/spread/arrays/省略号防护）reparse 无错
- [x] B 组：B1 扩充 `t / tc / $t / $tc` 语义矩阵
- [x] B 组：B2 `useTranslation()` / `useI18n()` 解构与来源证明
- [x] B 组：B3 Negative Tests：未知 / 影子 / 普通对象 `foo.t()` / shadowing
- [x] A 组：A1 普通 `RewritePlan` 纳入统一 Apply 前 validation
- [x] A 组：A2 Import collision 纳入 Apply 前 validation
- [x] A 组：A3 Resource target 纳入 Apply 前 validation
- [x] A 组：A4 建立 Code + Import + Resource 的统一 preflight，失败零写入