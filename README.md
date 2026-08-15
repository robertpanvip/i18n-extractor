# I18n-Extractor

> An IntelliJ IDEA plugin that extracts hard-coded strings (Chinese, Japanese, Korean, English, and more) from Vue / React projects and replaces them with i18n calls in one click.

[简体中文](README.zh-CN.md) | English

![License](https://img.shields.io/badge/license-MIT-green)
![GitHub release](https://img.shields.io/github/v/release/robertpanvip/i18n-extractor?color=blue)
![Tests](https://github.com/robertpanvip/i18n-extractor/actions/workflows/test.yml/badge.svg)

## Features

- **Multi-framework support**: auto-detects Vue / React projects and uses vue-i18n / react-i18next syntax accordingly
- **Multi-language extraction**: extracts Chinese, Japanese, Korean, English, French, Russian, German, Spanish, Italian and Portuguese — the target languages are configurable in Settings
- **Smart extraction**: scans template text, attribute values, TS string literals, string concatenation and template literals
- **Context-aware**: decides whether a string is user-facing copy based on its role in the syntax tree (text node, attribute, JS string, etc.), not just raw characters
- **Auto-injection**: automatically adds `import` statements and hook declarations (`useI18n` for Vue / `useTranslation` for React)
- **Idempotent & safe**: skips already-internationalized text and never re-extracts
- **Batch processing**: supports both single-file extraction and project-wide batch extraction
- **JSON export**: formats extraction results as JSON and copies them to the clipboard with one click
- **Undo support**: all edits are wrapped in `WriteCommandAction`, so Ctrl+Z works fully
- **Template interpolation**: supports `${variable}` template-literal interpolation and converts it into the i18n placeholder format
- **Merge suggestions**: automatically detects common prefixes/suffixes and digit placeholders to suggest merging similar strings

## Supported File Types

| Type | Extension |
|------|-----------|
| Vue single-file components | `.vue` |
| React components | `.tsx` |
| TypeScript | `.ts` |

## Supported Languages

| Language | Code | Notes |
|----------|------|-------|
| Chinese | `zh` | CJK ideographs, enabled by default |
| Japanese | `ja` | Hiragana + Katakana |
| Korean | `ko` | Hangul syllables |
| English | `en` | Sentence/phrase heuristic (Approach A) |
| French | `fr` | Accented characters |
| Russian | `ru` | Cyrillic |
| German | `de` | Umlauts + `ß` |
| Spanish | `es` | `ñ` + accented vowels |
| Italian | `it` | Accented vowels |
| Portuguese | `pt` | Tilde vowels |

> Note: Latin-script languages (English, French, German, Spanish, Italian, Portuguese) overlap on pure-ASCII text. Each is detected deterministically via its distinctive characters; pure-ASCII text is only picked up by the English heuristic. This is a known and intentional trade-off.

## Installation

### From the JetBrains Marketplace

Open `Settings → Plugins → Marketplace` in your IDE, search **I18n-Extractor** and install it.

### Manual installation

1. Download the latest `.zip` from [Releases](https://github.com/robertpanvip/i18n-extractor/releases)
2. Open `Settings → Plugins → ⚙ → Install Plugin from Disk...` in your IDE
3. Select the downloaded zip and restart the IDE

## Usage

### Single-file extraction

1. Open a `.vue` / `.tsx` / `.ts` file
2. Right-click in the editor → **I18n Extraction**
3. Preview the extracted JSON, confirm with OK
4. Code is automatically replaced with `$t()` calls, and the JSON is sent to the configured output (clipboard / file / ask)

### Project-level batch extraction

1. Open the `Refactor` menu → **Project I18n Extraction**
2. The plugin scans matching files based on the `include` configuration of `tsconfig.json`
3. Preview all extractions, confirm and apply the batch replacement

## Settings

Open `Settings → Tools → I18n Extractor`:

| Option | Description | Default |
|--------|-------------|---------|
| Target languages | Which languages to extract (multi-select) | Chinese only |
| Output destination | Clipboard / Write to file / Ask every time | Ask every time |
| Min string length | Strings shorter than this are not extracted | 1 |
| Merge affix threshold | Combined common prefix/suffix chars required to generate a merge suggestion | 2 |
| Exclude directories | Directory names skipped during scanning (comma-separated) | `node_modules, .git, dist, ...` |
| Custom translation dirs | Extra translation-resource directory names (comma-separated) | *(empty)* |

The output destination controls behaviour after extraction: choosing **Clipboard** or **Write to file** skips the dialog prompt and outputs directly; choosing **Ask every time** shows the options in the dialog (backward compatible).

## Conversion Examples

### Vue template

```vue
<!-- Before -->
<template>
  <div>你好，世界</div>
  <button type="primary" @click="handleSubmit">确定</button>
</template>

<!-- After -->
<template>
  <div>{{ $t('你好，世界') }}</div>
  <button type="primary" @click="handleSubmit">{{ $t('确定') }}</button>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n';
const { t: $t } = useI18n();
</script>
```

### React component

```tsx
// Before
export default function App() {
  return <div title="提示信息">你好</div>
}

// After
import { useTranslation } from 'react-i18next';

export default function App() {
  const { t: $t } = useTranslation();
  return <div title={$t('提示信息')}>{ $t(`你好`) }</div>
}
```

### Template-literal interpolation

```ts
// Before
const msg = `欢迎，${userName}！`

// After (Vue)
const msg = $t('欢迎，{0}！', { "0": userName })

// After (React)
const msg = $t('欢迎，{{0}}！', { "0": userName })
```

## Extraction Rules

### What gets extracted

- Plain text nodes in Vue templates: `<div>中文</div>`
- Vue attribute values (including strings inside dynamic directive bindings)
- React JSX text nodes and attribute values
- JS/TS string literals: `'中文'`, `"中文"`, `` `中文` ``
- String concatenation: `"你好" + name`
- Template literals: `` `欢迎，${name}` ``
- Explicit string literals inside directives such as `:title="'中文'"`

### What does NOT get extracted

- Already-internationalized text: `$t('xxx')`, `t('xxx')`
- Text inside comments
- Text inside `<style>` tags
- TypeScript enum member values (not supported at runtime, would raise TS18033)
- Pure English/symbol/pre-existing-code strings that are `$t` calls or identifiers
- `Enum['中文']` indexed access
- Strings shorter than the configured minimum length

## Project Structure

```
src/
├── main/kotlin/com/pan/extractor/
│   ├── AllI18nExtractorAction.kt       # Project-level batch extraction action
│   ├── I18nExtractorAction.kt          # Single-file extraction action
│   ├── I18nProcessor.kt                # Core extraction & replacement logic
│   ├── LanguageExtractor.kt            # Pluggable multi-language extractors (zh/ja/ko/en/fr/ru/de/es/it/pt)
│   ├── I18nSettings.kt                 # Global settings & persistence
│   ├── I18nSettingsConfigurable.kt     # Settings panel (Settings → Tools → I18n Extractor)
│   ├── CommonPrefixSuffixFactorizer.kt # Merge-suggestion factorizer (prefix/suffix & digit groups)
│   ├── MergeApplier.kt                 # Applies merge suggestions to code & resource files
│   ├── ExtractedStringsDialog.kt       # Result preview dialog
│   └── Util.kt                         # Utilities (framework detection, write-back, etc.)
├── main/resources/META-INF/
│   └── plugin.xml                      # Plugin configuration
└── test/kotlin/com/pan/extractor/
    ├── I18nProcessorTest.kt            # JS/TS general logic tests
    ├── VueI18nProcessorTest.kt         # Vue-specific tests
    ├── ReactI18nProcessorTest.kt       # React-specific tests
    ├── LanguageExtractorSettingsTest.kt# Multi-language & settings tests
    └── ...                             # Merge, write-back, factorizer and regression tests
```

## Development

### Requirements

- JDK 21+
- Gradle 9.0+

### Build

```bash
./gradlew build
```

### Run tests

```bash
./gradlew test
```

### Local debug

```bash
./gradlew runIde
```

## Contributing

Issues and Pull Requests are welcome!

## License

MIT