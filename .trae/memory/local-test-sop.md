# i18n-extractor 项目长期记忆 / 本地测试 SOP
> 建立：2026-08-12，Trae 会话用户显式要求"添加到本地长期记忆"，跨会话复用。
> 路径匹配：`/workspace/.trae/memory/local-test-sop.md`

---

## 🎯 核心 SOP：「本地跑测试」的正确三步法

**项目特点**：IntelliJ Platform Plugin（Gradle + Kotlin 2.x + JDK 21），使用 `org.jetbrains.intellij.platform:2.18.1`。
本地沙箱环境常见问题：
- `ideaIU:LATEST-EAP-SNAPSHOT` 浮动版本元数据经常 404/被限流（Maven Central 429）
- mise 安装的 JDK 21 不被 Gradle Toolchain 识别（"Cannot find a Java installation"）
- 沙箱网络只允许国内镜像访问，JetBrains 相关依赖必须走官方仓库（`intellij-repository/releases|snapshots`）+ 内容过滤

### Step 1：临时改 2 个配置文件（只本地测试用，测完必回滚）

#### 1a) `settings.gradle.kts`（pluginManagement 换成腾讯镜像优先）
```kotlin
pluginManagement {
    repositories {
        maven {
            name = "TencentGradlePlugins"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        }
        maven {
            name = "TencentMavenPublic"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        gradlePluginPortal()
    }
}
rootProject.name = "i18n-extractor"
```

#### 1b) `build.gradle.kts`（镜像+钉死版本+禁用 Toolchain+内存限制）
关键片段（完整内容另存 `./local-test.build.gradle.kts.template`，可直接 cp 覆盖）：
```kotlin
repositories {
    // 顺序敏感：JetBrains 相关只走官方，避免 Maven Central 429
    maven { name = "JetbrainsSnapshots"; url = uri("https://www.jetbrains.com/intellij-repository/snapshots"); content { includeGroupByRegex("com\\.jetbrains.*"); includeGroupByRegex("org\\.jetbrains\\.intellij.*") } }
    maven { name = "JetbrainsReleases";  url = uri("https://www.jetbrains.com/intellij-repository/releases");  content { includeGroupByRegex("com\\.jetbrains.*"); includeGroupByRegex("org\\.jetbrains\\.intellij.*") } }
    maven { name = "TencentMavenPublic"; url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/"); content { excludeGroupByRegex("com\\.jetbrains.*") } }
    mavenCentral { content { excludeGroupByRegex("com\\.jetbrains.*") } }
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        jetbrainsRuntime()
        // 🔴 关键：不要用 LATEST-EAP-SNAPSHOT，本地必须钉死缓存里有的版本号
        intellijIdeaUltimate("2025.2.6.3") { useInstaller = false }
        testFramework(TestFrameworkType.Plugin.XML)
        testFramework(TestFrameworkType.Plugin.JavaScript)
        testFramework(TestFrameworkType.Platform)
        bundledPlugins("JavaScript","org.jetbrains.plugins.vue","org.intellij.plugins.postcss","com.intellij.css","org.jetbrains.plugins.sass","org.jetbrains.plugins.less","HtmlTools","com.intellij.copyright","intellij.webpack","JSIntentionPowerPack","JavaScriptDebugger")
        platformDependency(Coordinates("com.jetbrains.intellij.platform","poly-symbols-test-framework"))
        platformDependency(Coordinates("com.jetbrains.intellij.platform","lsp-test-framework"))
    }
    testImplementation("junit:junit:4.13.2")
}

// 🔴 关键：禁用 Toolchain，不然沙箱的 JDK 探测不到
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

tasks {
    test {
        useJUnit()
        maxHeapSize = "2g"
        jvmArgs("-Xmx2g", "-XX:MaxMetaspaceSize=512m")
        testLogging { showStandardStreams = true; events("passed","failed","skipped","standardOut","standardError") }
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
    }
}
```

### Step 2：跑测试（指定 JDK，不用 daemon，不用 config cache）
```bash
export JAVA_HOME=/root/.local/share/mise/installs/java/21.0.2
export GRADLE_OPTS="-Xmx1g"
cd /workspace

# 只跑关键回归（推荐，1-2 分钟）：
./gradlew --no-daemon --no-configuration-cache test \
  --tests "com.pan.extractor.ReactI18nProcessorTest.testReactTsNoComponentNoHookShouldNotInjectUseTranslation" \
  --tests "com.pan.extractor.ReactI18nProcessorTest.testReactTsWithCustomHookShouldStillInjectUseTranslation" \
  --tests "com.pan.extractor.ReactI18nProcessorTest.testTranslationResourceByLocaleNameShouldSkip" \
  --tests "com.pan.extractor.ReactI18nProcessorTest.testTranslationResourceByDirectoryShouldSkip" \
  --tests "com.pan.extractor.ReactI18nProcessorTest.testReactI18nTInjectImportWhenMissing" \
  --tests "com.pan.extractor.VueI18nProcessorTest.testVueI18nGlobalTInjectImportWhenMissing" \
  --tests "com.pan.extractor.VueI18nProcessorTest.testVueCustomHookInTsFileInjectsUseI18n" \
  --tests "com.pan.extractor.VueI18nProcessorTest.testVueCustomHookArrowFunctionInTsFileInjectsUseI18n"

# 全量跑：
# ./gradlew --no-daemon --no-configuration-cache test
```

### Step 3：任何结果都**必须回滚 Gradle 文件**（不然提交到 CI 会污染）
```bash
cd /workspace
git checkout -- build.gradle.kts settings.gradle.kts
git status -sb            # 确认只有业务文件有 diff
```

> ⚠️ 永远不要提交上面 Step 1 的配置到仓库——CI 有它自己干净的网络和 Toolchain。

---

## 🐛 Bug 修复要点（2026-08-12 提交 d6ed214，PR #12）

### Bug 1：React 项目中，纯工具 TS/TSX 文件（没有组件、没有 Hook）不应该注入 `useTranslation`
- **文件**：[I18nProcessor.kt `ensureReactI18nImported()`](file:///workspace/src/main/kotlin/com/pan/extractor/I18nProcessor.kt#L430-L483)
- **错误模式**：先注入 `import { useTranslation } from 'react-i18next'`，再去查组件是否存在 return → 纯工具文件顶部就塞了个非法 Hook import
- **修复思路**：**顺序翻转**
  1. 先收集 `findReactComponentFunctions(file)` ∪ `findHookFunctions(file)`（注意 React 自定义 Hook 也是合法调用者），去重存 `allTargets`
  2. `allTargets.isEmpty()` → 直接 return，import 和 hook 注入都不做
  3. 非空 → 正常注入 import；再用 allTargets 一起跑 hook 注入循环，这样 `useAuth.ts` 这种只有自定义 Hook 的文件也能正确获得 `const { t } = useTranslation()`

### Bug 2：翻译资源文件（如 `en-US.ts`、`zh-CN.ts`、`src/locales/index.ts` 等）不应该被提取和注入
- **工具函数**：[Util.kt `isTranslationResourceFile(...)`](file:///workspace/src/main/kotlin/com/pan/extractor/Util.kt#L103-L236)
  判定规则（命中任一即视为翻译文件）：
  1. **路径**命中目录段：`locales/` `i18n/` `locale/` `lang/` `languages/` `translations/`（大小写不敏感，分隔符统一）
  2. **基名本身就是 locale code**：ISO 639-1 两字母 + 可选 `-`/`_` + region/script（`en`、`en-US`、`zh_CN`、`zhs`、`zht`、`zhHans`、`zhHant`、`enUS`、`jaJP`、`ptBR` 白名单）
  3. **翻译前缀 + locale 码**组合：基名用 `.` 分割，前缀是 `messages` / `i18n` / `strings` / `locales` / `translations`，且后缀像 locale 码
- **过滤位置（4 层防御，都要同时保持同步）**：
  1. `I18nProcessor.collect()` 开头 return（effects 置空）
  2. `I18nProcessor.run()` 开头 return（双保险）
  3. `I18nExtractorAction.update()` 禁用菜单 + `processSingleFile()` return + `collectSupportedFiles()` 递归时 filterNot
  4. `AllI18nExtractorAction.update()` 禁用菜单 + `transform()` include 结果 filterNot

### 新增单元测试清单（永远不要删/弱化成不校验）
`ReactI18nProcessorTest`（对应 `src/test/kotlin/.../ReactI18nProcessorTest.kt`）：
- `testReactTsNoComponentNoHookShouldNotInjectUseTranslation`
- `testReactTsWithCustomHookShouldStillInjectUseTranslation`
- `testTranslationResourceByLocaleNameShouldSkip`
- `testTranslationResourceByDirectoryShouldSkip`

---

## 🚦 诊断捷径（测试失败先看这些）
1. Kotlin `$t` 编译报错（Unresolved reference 't'）：**一定是 Kotlin 字符串模板转义**，普通字符串用 `\$t`，`"""..."""` 原始字符串用 `${'$'}t`
2. `Cannot find a Java installation @ JDK 21`：Toolchain 开了 → 按本 SOP Step 1b 关掉，直接 `java.sourceCompatibility` + `JAVA_HOME`
3. `Could not resolve ideaIU:LATEST-EAP-SNAPSHOT`：浮动版本元数据 fetch 失败 → 改成 `intellijIdeaUltimate("2025.2.6.3") { useInstaller=false }`
4. 测试中 `findVueI18nInstanceFile` 找不到文件：**不要用 `java.io.File` 遍历**，IntelliJ 测试框架是内存 VirtualFile，必须用 `ProjectRootManager.getInstance(project).contentSourceRoots` + `VfsUtilCore.processFilesRecursively` 或 `FilenameIndex.search()`
5. 测试中别名推断错：测试文件**必须放到 `src/` 子目录下**（如 `src/utils/foo.ts`），放项目根会导致 `relativePath.startsWith("src/")` 条件不命中

## 📌 提交推送习惯
- `git diff --name-only HEAD` 只允许：`**/I18nProcessor.kt`、`**/Util.kt`、`**/*Action.kt`、`**/*Test.kt` 这几类
- `build.gradle.kts` / `settings.gradle.kts` / `.gradle/` / `.trae/memory/`：**永远不要提交**（.gitignore 前 3 个；最后一个是本地记忆目录，按需决定）
- push 完立刻：`gh run list --branch $(git branch --show-current) -L 1` 看 CI 排队/运行状态
