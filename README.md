# I18n-Extractor

> IntelliJ IDEA 插件，一键提取 Vue / React 项目中的中文字符串，自动替换为 i18n 国际化调用。

![License](https://img.shields.io/badge/license-MIT-green)
![Version](https://img.shields.io/badge/version-1.3.2-blue)
![Tests](https://github.com/robertpanvip/i18n-extractor/actions/workflows/test.yml/badge.svg)

## 功能特性

- **多框架支持**：自动识别 Vue / React 项目，分别使用 vue-i18n 和 react-i18next 语法
- **智能提取**：扫描模板文本、属性值、TS 字符串字面量、字符串拼接、模板字面量
- **自动注入**：自动添加 import 语句和 hook 声明（Vue 的 `useI18n` / React 的 `useTranslation`）
- **幂等安全**：跳过已国际化的文本，不会重复提取
- **批量处理**：支持单文件提取和项目级批量提取
- **JSON 导出**：提取结果自动格式化为 JSON，一键复制到剪贴板
- **撤销支持**：所有修改包裹在 WriteCommandAction 中，完整支持 Ctrl+Z 撤销
- **模板插值**：支持 `${变量}` 模板字符串插值，自动转换为 i18n 插值格式

## 支持的文件类型

| 类型 | 扩展名 |
|------|--------|
| Vue 单文件组件 | `.vue` |
| React 组件 |  `.tsx` |
| TypeScript | `.ts` |

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
2. 在编辑器中右键 → **中文国际化提取**
3. 预览提取结果 JSON，确认后点击 OK
4. 代码自动替换为 `$t()` 调用，JSON 自动复制到剪贴板

### 项目级批量提取

1. 打开 `Refactor` 菜单 → **项目中文国际提取**
2. 插件会根据 `tsconfig.json` 的 `include` 配置扫描匹配的文件
3. 预览所有提取结果，确认后批量替换

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

### 不会被提取的内容

- 已国际化的文本：`$t('xxx')`、`t('xxx')`
- 注释中的文本
- `<style>` 标签内的文本
- TypeScript 枚举成员值（运行时不支持 `$t()`，会报 TS18033）
- 纯英文/数字/符号字符串
- `Enum['中文']` 索引访问

## 项目结构

```
src/
├── main/kotlin/com/pan/extractor/
│   ├── AllI18nExtractorAction.kt    # 项目级批量提取 Action
│   ├── I18nExtractorAction.kt       # 单文件提取 Action
│   ├── I18nProcessor.kt             # 核心提取与替换逻辑
│   ├── ExtractedStringsDialog.kt    # 结果预览对话框
│   └── Util.kt                      # 工具函数（React/Vue 检测等）
├── main/resources/META-INF/
│   └── plugin.xml                   # 插件配置
└── test/kotlin/com/pan/extractor/
    ├── I18nProcessorTest.kt         # JS/TS 通用逻辑测试（34 例）
    ├── VueI18nProcessorTest.kt      # Vue 专属测试（22 例）
    └── ReactI18nProcessorTest.kt    # React 专属测试（12 例）
```

## 开发

### 环境要求

- JDK 25+
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
