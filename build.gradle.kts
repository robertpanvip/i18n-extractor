import org.jetbrains.intellij.platform.gradle.models.Coordinates
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.pan"
version = "1.13.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        jetbrainsRuntime()

        intellijIdeaUltimate("LATEST-EAP-SNAPSHOT") {
            useInstaller = false
        }

        testFramework(TestFrameworkType.Plugin.XML)
        testFramework(TestFrameworkType.Plugin.JavaScript)
        testFramework(TestFrameworkType.Platform)

        bundledPlugins(
            "JavaScript",
            "org.jetbrains.plugins.vue",
            "org.intellij.plugins.postcss",
            "com.intellij.css",
            "org.jetbrains.plugins.sass",
            "org.jetbrains.plugins.less",
        )
    }

    // kotlin-stdlib 由 IDE 提供，标记为 compileOnly 避免打包进发行 ZIP
    compileOnly(kotlin("stdlib"))

    testImplementation(
        "junit:junit:4.13.2"
    )
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
            <h4>1.13.0</h4>
            <ul>
                <li>Fix: toggling the folding switch (or the display language) in Settings now immediately refreshes all open editors — <code>\${'$'}t()</code> folds and toggle affordances disappear/rebuild on the spot, no longer waiting for the file to be closed and reopened</li>
            </ul>
            <h4>1.12.0</h4>
            <ul>
                <li>Fix: stable performance — <code>SymbolAnalyzer</code> no longer triggers the IDE TypeScript service when deciding bare vs. chained <code>t</code> calls (purely syntactic <code>qualifier == null</code> check), eliminating 20s <code>ideGetResolvedSignature</code> timeouts during extraction</li>
                <li>Fix: folding no longer throws <code>PsiInvalidElementAccessException</code> when the PSI tree is rewritten between collection and folding (defensive <code>isValid</code> guards on stale elements)</li>
                <li>Fix: Vue template <code>\${'$'}t()</code> folds no longer steal the native <code>&lt;div&gt;</code> / tag / function bracket folds — the injected JS path no longer creates raw folds (cross-file descriptors were silently dropped by <code>CompositeFoldingBuilder.assertSameFile</code>), while <code>VueHostFoldingBuilder</code> keeps native structure folding intact</li>
            </ul>
            <h4>1.11.1</h4>
            <ul>
                <li>Fix: mixed files with an existing module-level <code>t('...')</code> call (outside any hook scope) now correctly classify that call by scope and inject a global <code>t</code> alias — <code>const t = getI18n().t</code> (no locale) or <code>const t = i18n.t</code> (locale present) — even when the top-level <code>t</code> cannot be proven as a translation call, while avoiding duplicate <code>useTranslation</code> injection and leaving chained <code>i18n.t</code> calls untouched</li>
            </ul>
            <h4>1.11.0</h4>
            <ul>
                <li>Fix: mixed files (module-level Chinese + component-level Chinese) now always inject a global <code>t</code> alias at the top — <code>const t = getI18n().t</code> fallback when no locale instance exists — while keeping the component-level <code>const { t } = useTranslation()</code> hook</li>
            </ul>
            <h4>1.10.0</h4>
            <ul>
                <li>Performance: significantly faster extraction on large files and long-running sessions</li>
                <li>Cached analysis for injected PSI (Vue mustache) via host-file key fallback — no more repeated full scans</li>
                <li>Precompiled regex constants for local-shadow declaration detection (no per-call recompilation)</li>
                <li>Binary-search scope classification replaces per-function ancestor traversal (O(n) → O(log n))</li>
                <li>Shared file-level cache for React/Vue component and custom-hook detection across detect/collect/rewrite phases</li>
            </ul>
            <h4>1.6.9</h4>
            <ul>
                <li>Multi-framework support with auto-detection: Vue (vue-i18n), React (react-i18next &amp; react-intl), Angular (ngx-translate), Solid (solid-i18n), Svelte (svelte-i18n)</li>
                <li>Multi-language extraction for 10 languages (zh / ja / ko / en / fr / ru / de / es / it / pt), target languages configurable in Settings</li>
                <li>Merge suggestions: public prefix / suffix grouping and digit-placeholder grouping to consolidate similar strings</li>
                <li>Auto-injection of global \${'$'}t and import / hook planning per framework</li>
                <li>More stable extraction, improved idempotency and expanded regression coverage</li>
            </ul>
            <h4>1.2.0</h4>
            <ul>
                <li>fix: optimize comment extraction logic</li>
            </ul>
    """.trimIndent()
    }

    // 启用主代码 instrumentation，恢复平台 @NotNull 契约检查（插件代码传 null / 违背
    // 契约时在测试期即被断言暴露），避免掩盖传 null 问题。
    instrumentCode = true
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks {
    test {
        useJUnit()

        // 仅本地生效（scripts/run-tests.sh 传 -PforkTest=true）：
        // 本地沙箱 cgroup 只有 4GiB，堆 + metaspace + native 直接内存容易超限，
        // 需限制并行 fork 数、堆大小、按类独立 fork 并限定 metaspace。
        // 线上 CI（约 7GB 多核）内存充足，保持默认参数跑得更快。
        if (project.findProperty("forkTest") == "true") {
            maxParallelForks = 1
            maxHeapSize = "768m"
            forkEvery = 1
            jvmArgs(
                "-XX:+UseParallelGC",
                "-XX:MinHeapFreeRatio=5",
                "-XX:MaxHeapFreeRatio=25",
                "-XX:MaxMetaspaceSize=512m"
            )
        }

        testLogging {
            showStandardStreams = true
            events(
                "passed",
                "failed",
                "skipped",
                "standardOut",
                "standardError"
            )
        }
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}
