# I18n-Extractor

> 一款 IntelliJ IDEA 插件，一键提取 Vue / React 项目中硬编码的文案（中文、日文、韩文、英文等），并自动替换为 i18n 国际化调用。

English | [简体中文](README.zh-CN.md)

![License](https://img.shields.io/badge/license-MIT-green)
![GitHub release](https://img.shields.io/github/v/release/robertpanvip/i18n-extractor?color=blue)
![Tests](https://github.com/robertpanvip/i18n-extractor/actions/workflows/test.yml/badge.svg)

## 功能特性

- **多框架支持**：自动识别项目框架，并使用匹配的 i18n 语法
- **多语言提取**：支持提取中文、日文、韩文、英文、法文、俄文、德文、西班牙文、意大利文、葡萄牙文，目标语言可在设置中配置
- **智能提取**：扫描模板文本、属性值、TS 字符串字面量、字符串拼接、模板字面量
- **上下文感知**：根据字符串在语法树中的角色（文本节点、属性、JS 字符串等）判断是否为用户可见文案，而非仅靠字符匹配
- **自动注入**：自动添加 `import` 语句和 hook 声明（Vue 的 `useI18n` / React 的 `useTranslation`）
- **幂等安全**：跳过已国际化的文本，不会重复提取
- **批量处理**：支持单文件提取和项目级批量提取
- **JSON 导出**：提取结果自动格式化为 JSON，一键复制到剪贴板
- **撤销支持**：所有修改包裹在 `WriteCommandAction` 中，完整支持 Ctrl+Z 撤销
- **模板插值**：支持 `` `${变量}` `` 模板字符串插值，自动转换为 i18n 插值格式
- **合并建议**：自动识别公共前后缀与数字占位，智能建议合并相似文案

> **框架支持状态**：**Vue**（`vue-i18n`）和 **React**（`react-i18next`）是主要、且已完整测试的目标。其他框架（如 Angular / `ngx-translate`、Svelte、独立 i18next 等）属于**实验性支持**——相关代码路径已存在，但**未纳入测试套件、不保证生成结果正确、且可能随时调整**。如果生产项目依赖非 Vue/React 框架，请在应用前自行校验生成的代码。

## 支持的文件类型

| 类型 | 扩展名 |
|------|--------|
| Vue 单文件组件 | `.vue` |
| React 组件 | `.tsx` |
| TypeScript | `.ts` |

## 支持的语言

| 语言 | 代码 | 说明 |
|------|------|------|
| 中文 | `zh` | CJK 表意文字，默认启用 |
| 日文 | `ja` | 平假名 + 片假名 |
| 韩文 | `ko` | 谚文音节 |
| 英文 | `en` | 句子/短语启发式（Approach A） |
| 法文 | `fr` | 法语专属重音符 |
| 俄文 | `ru` | 西里尔字母 |
| 德文 | `de` | 变音符号 + `ß` |
| 西班牙文 | `es` | `ñ` + 重音元音 |
| 意大利文 | `it` | 重音元音 |
| 葡萄牙文 | `pt` | 波浪元音（ã/õ 等） |

> 说明：拉丁字母系语言（英/法/德/西/意/葡）在纯 ASCII 文本上会互相重叠。每种语言通过其专属字符做确定性判定；纯 ASCII 文本只会被英文启发式命中。这是已知且有意的取舍。

## 安装

### 从 JetBrains Marketplace 安装

在 IDE 中打开 `Settings → Plugins → Marketplace`，搜索 **I18n-Extractor** 安装。

### 手动安装

1. 前往 [Releases](https://github.com/robertpanvip/i18n-extractor/releases) 下载最新的 `.zip` 包
2. 在 IDE 中打开 `Settings → Plugins → ⚙ → Install Plugin from Disk...`
3. 选择下载的 zip 文件，重启 IDE

## 使用方法

### 单文件提取

1. 打开 `.vue` / `.tsx` / `.ts` 文件
2. 在编辑器中右键 → **国际化提取**
3. 预览提取结果 JSON，确认后点击 OK
4. 代码自动替换为 `$t()` 调用，JSON 发送到配置的输出目的地（剪贴板 / 文件 / 每次询问）

### 项目级批量提取

1. 打开 `Refactor` 菜单 → **项目国际化提取**
2. 插件会根据 `tsconfig.json` 的 `include` 配置扫描匹配的文件
3. 预览所有提取结果，确认后批量替换

## 设置面板

打开 `Settings → Tools → I18n Extractor`：

| 选项 | 说明 | 默认值 |
|------|------|--------|
| 目标语言 | 要提取的语言（多选） | 仅中文 |
| 输出去向 | 剪贴板 / 写入文件 / 每次询问 | 每次询问 |
| 最小提取长度 | 少于该字符数的文案不提取 | 1 |
| 合并建议阈值 | 公共前后缀合计至少达到该字符数才生成合并建议 | 2 |
| 排除目录 | 扫描时跳过的目录名（逗号分隔） | `node_modules, .git, dist, ...` |
| 自定义翻译目录 | 额外的翻译资源目录名（逗号分隔） | *(空)* |
| Vue 占位符前缀 | Vue 命名占位符前缀（如 `N` → `{N0}`；不能为空） | `N` |

输出去向控制提取后的行为：选择 **剪贴板** 或 **写入文件** 时不再弹窗询问，直接按此方式输出；选择 **每次询问** 时在弹窗中展示选项（向后兼容）。

## 转换示例

### Vue 模板

```vue
<!-- 转换前 -->
<template>
  <div>你好，世界</div>
  <button type="primary" @click="handleSubmit">确定</button>
</template>

<!-- 转换后 -->
<template>
  <div>{{ $t('你好，世界') }}</div>
  <button type="primary" @click="handleSubmit">{{ $t('确定') }}</button>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
const { t: $t } = useI18n();
</script>
```

### React 组件

```tsx
// 转换前
export default function App() {
  return <div title="提示信息">你好</div>
}

// 转换后
import { useTranslation } from 'react-i18next';

export default function App() {
  const { t: $t } = useTranslation();
  return <div title={$t('提示信息')}>{ $t(`你好`) }</div>
}
```

### 模板字符串插值

```ts
// 转换前
const msg = `欢迎，${userName}！`

// 转换后（Vue）
const msg = $t('欢迎，{0}！', { "0": userName })

// 转换后（React）
const msg = $t('欢迎，{{0}}！', { "0": userName })
```

## 提取规则

### 会被提取的内容

- Vue 模板中的纯文本节点：`<div>中文</div>`
- Vue 属性值（含指令动态绑定内的字符串）
- React JSX 文本节点和属性值
- JS/TS 中的字符串字面量：`'中文'`、`"中文"`、`` `中文` ``
- 字符串拼接：`"你好" + name`
- 模板字面量：`` `欢迎，${name}` ``
- 指令中的显式字符串字面量，如 `:title="'中文'"`

### 不会被提取的内容

- 已国际化的文本：`$t('xxx')`、`t('xxx')`
- 注释中的文本
- `<style>` 标签内的文本
- TypeScript 枚举成员值（运行时不支持 `$t()`，会报 TS18033）
- 纯英文/符号的 `$t` 调用或标识符
- `Enum['中文']` 索引访问
- 长度小于配置的最小提取长度的字符串

## 项目结构

```
src/
├── main/kotlin/com/pan/extractor/
│   ├── AllI18nExtractorAction.kt       # 项目级批量提取 Action
│   ├── I18nExtractorAction.kt          # 单文件提取 Action
│   ├── I18nProcessor.kt                # 核心提取与替换逻辑
│   ├── LanguageExtractor.kt            # 可插拔的多语言提取器（zh/ja/ko/en/fr/ru/de/es/it/pt）
│   ├── I18nSettings.kt                 # 全局设置与持久化
│   ├── I18nSettingsConfigurable.kt     # 设置面板（Settings → Tools → I18n Extractor）
│   ├── CommonPrefixSuffixFactorizer.kt # 合并建议因子化器（前后缀 / 数字分组）
│   ├── MergeApplier.kt                 # 应用合并建议到代码与资源文件
│   ├── ExtractedStringsDialog.kt       # 结果预览对话框
│   └── Util.kt                         # 工具函数（框架检测、写回等）
├── main/resources/META-INF/
│   └── plugin.xml                      # 插件配置
└── test/kotlin/com/pan/extractor/
    ├── I18nProcessorTest.kt            # JS/TS 通用逻辑测试
    ├── VueI18nProcessorTest.kt         # Vue 专属测试
    ├── ReactI18nProcessorTest.kt       # React 专属测试
    ├── LanguageExtractorSettingsTest.kt# 多语言与设置测试
    └── ...                             # 合并、写回、因子化器与回归测试
```

## 开发

### 环境要求

- JDK 21+
- Gradle 9.0+

### 构建

```bash
./gradlew build
```

### 运行测试

```bash
./gradlew test
```

### 本地调试

```bash
./gradlew runIde
```

## 贡献

欢迎提交 Issue 和 Pull Request！

## License

MIT