import org.jetbrains.intellij.platform.gradle.models.Coordinates
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.pan"
version = "1.16.8"

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
            <h4>1.16.8</h4>
            <ul>
                <li>Fix: re-translating an already translated file no longer loses keys such as <code>第{versionNum}版</code>. The symbol-analysis cache generation now includes the PSI Document stamp in addition to the VFS stamp; plugin rewrites only bump the Document stamp, so the previous VFS-only key reused a stale, offset-keyed call analysis within the same IDE session and classified the rewritten <code>${'$'}t(...)</code> as non-translation. Restarting the IDE cleared the static cache, which is why a restart appeared to fix it</li>
                <li>Fix: string-concatenation/template rewrites now generate a complete <code>JSCallExpression</code> PSI instead of a bare text leaf, so an injected <code>${'$'}t('第{versionNum}版', {...})</code> is recognized on the next collection pass and its key can be written back</li>
            </ul>
            <h4>1.16.7</h4>
            <ul>
                <li>Fix: pre-existing translation keys in the resource file are no longer silently deleted during merge. Sentences whose diff contains sentence/label punctuation (e.g. <code>发布的版本：</code>) are no longer over-merged with unrelated short-prefix siblings (e.g. <code>发布成功</code>); previously both full sentences were marked as consumed and <code>dropExistingKeys</code> removed the user's existing <code>发布成功:'发布成功'</code> entry</li>
            </ul>
            <h4>1.16.6</h4>
            <ul>
                <li>Fix: Vue directive values that are string concatenations (e.g. <code>:label="'你好，' + name"</code>) no longer produce junk translation keys containing raw code; they are now extracted as semantic placeholder messages (<code>你好，{name}</code>) just like concatenations in script code</li>
                <li>Fix: merge output is now consistent between identical template-literal and string-concat texts - the same text no longer yields a nested skeleton in one place and a plain key in another; duplicate literal operands in a concatenation are no longer extracted twice; and punctuation-only diffs no longer split fixed phrases into meaningless skeletons (e.g. <code>你好{N0}</code>)</li>
                <li>Fix: the <code>useI18n()</code> const is now injected after the import in Vue <code>&lt;script setup&gt;</code> instead of above it; newly injected imports are recognized via a PSI anchor so the hook always lands below the import</li>
            </ul>
            <h4>1.16.5</h4>
            <ul>
                <li>Feature: interpolations whose value is a dotted property chain now use the chain's tail segment as the placeholder key (e.g. <code>\${'$'}{item.versionNum}</code> extracts to <code>{versionNum}</code>) while the full expression stays as the param value; when different expressions collide on the same tail key within one message they fall back to numbered <code>{N0}</code>/<code>{N1}</code></li>
                <li>Fix: merge skeleton rewriting is now site-isolated - a broken expression (null or containing syntax errors, e.g. backslash-terminated skeletons) is no longer written into source nor registered as a translation key, and a single failing site no longer aborts the whole merge leaving half-applied state</li>
                <li>Fix: merge suggestions no longer silently shrink - placeholder-diff groups are rejected before their sites are consumed, so remaining sites can still form valid groups</li>
            </ul>
            <h4>1.16.4</h4>
            <ul>
                <li>Fix: interpolations whose value is a dotted property chain (e.g. <code>\${'$'}{item.versionNum}</code>) no longer produce an illegal unquoted object key <code>{ item.versionNum: ... }</code> (invalid JS that vue-i18n cannot resolve either); Vue-style frameworks fall back to numbered <code>{N0}</code> placeholders while the full variable expression is kept as the param value</li>
                <li>Fix: merge suggestions no longer cut i18n placeholders across prefix/suffix boundaries (previously produced broken skeletons like <code>第{{N0}.versionNum}版</code> with <code>{N0: 'item'}</code> quoting a variable into a string literal); whole-placeholder diffs are no longer suggested for merging</li>
            </ul>
            <h4>1.16.3</h4>
            <ul>
                <li>Fix: translatable string literals inside template-interpolation fallback params (e.g. <code>\${'$'}{cond ? '中文' : 'Other'}</code>) are now extracted into <code>\${'$'}t()</code> calls and registered in the resource dictionary, instead of being left raw inside the named-param object. Guards prevent double-wrapping existing <code>\${'$'}t()</code> arguments, object-key literals, and literals whose key sanitization would be lossy (trailing dot / @ / escapes)</li>
                <li>Test: the env-dependent Top100 complex-file scan now skips when its manually-prepared path list is absent, restoring a meaningful CI test signal</li>
            </ul>
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
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks {
    test {
        useJUnit()

        // 批量扫描 ExternalProjectE2ETest.testBatchScanAllFixtures 需要加载 17 个
        // 外部项目 × 200 文件，耗内存（512m+ metaspace）且慢（30s+），
        // 不放在默认 `gradle test` 里跑。改用 `gradle batchE2ETest` 手动触发。
        exclude("**/ExternalProjectE2ETest.testBatchScanAllFixtures*")

        // 仅本地生效（scripts/run-tests.sh 传 -PforkTest=true）：
        // 本地沙箱 cgroup 只有 4GiB，堆 + metaspace + native 直接内存容易超限，
        // 需限制并行 fork 数、堆大小、按类独立 fork 并限定 metaspace。
        // 线上 CI（约 7GB 多核）内存充足，保持默认参数跑得更快。
        if (project.findProperty("forkTest") == "true") {
            maxParallelForks = 1
            maxHeapSize = "512m"
            forkEvery = 1
            jvmArgs(
                "-XX:+UseParallelGC",
                "-XX:MinHeapFreeRatio=5",
                "-XX:MaxHeapFreeRatio=15",
                "-XX:MaxMetaspaceSize=512m",
                "-XX:ReservedCodeCacheSize=64m",
                "-Didea.jcef.enabled=false",
                "-Djava.util.concurrent.ForkJoinPool.common.parallelism=2"
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

    // 手动触发批量扫描：gradle batchE2ETest
    // 包含两种模式:
    //   1. testBatchScanAllFixtures     — 之前的受控扫描 (17项目 x 200文件)
    //   2. testBruteForceScanAll        — 暴力全量扫描 (17项目 x 所有中文文件, 5300+)
    register<Test>("batchE2ETest") {
        group = "verification"
        description = "Batch scan over 17 real open-source projects (200+ files, ~30s)"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnit()
        include("**/ExternalProjectE2ETest.testBatchScanAllFixtures*")
        // 暴力扫描需要更大内存 + 更长时间，单独用 gradle bruteForceE2ETest 触发
        // 批量扫描需要更大内存
        maxHeapSize = "1024m"
        jvmArgs(
            "-XX:+UseParallelGC",
            "-XX:MaxMetaspaceSize=512m",
            "-XX:ReservedCodeCacheSize=128m",
            "-Didea.jcef.enabled=false"
        )
    }

    // 暴力全量扫描：5300+ 文件，可能跑几分钟
    register<Test>("bruteForceE2ETest") {
        group = "verification"
        description = "BRUTE-FORCE scan: all 5300+ Chinese files from 17 projects, ~5-10min"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnit()
        include("**/ExternalProjectE2ETest.testBruteForceScanAll*")
        maxHeapSize = "1536m"
        jvmArgs(
            "-XX:+UseParallelGC",
            "-XX:MaxMetaspaceSize=768m",
            "-XX:ReservedCodeCacheSize=128m",
            "-Didea.jcef.enabled=false"
        )
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
