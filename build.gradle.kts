import org.jetbrains.intellij.platform.gradle.models.Coordinates
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.pan"
version = "1.7.8"

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

        // 限制并行 fork 数（3 核足够），CI 和本地统一使用。
        maxParallelForks = 1
        // 以下内存限制仅本地生效（scripts/run-tests.sh 传 -PforkTest=true），
        // 因为本地沙箱 cgroup 只有 4GiB，堆 + metaspace + native 直接内存容易超限。
        // 线上 CI（约 7GB）内存充足，无需此限制，且去掉后跑得更快。
        if (project.findProperty("forkTest") == "true") {
            maxHeapSize = "768m"
            // 每个测试类独立 fork JVM，避免 IDEA 平台原生/直接内存跨类累积导致 OOM。
            forkEvery = 1
            jvmArgs(
                "-XX:MaxMetaspaceSize=512m"
            )
        }
        // 测试 JVM GC 优化（CI 和本地均适用）：ParallelGC 高吞吐 + 主动释放空闲堆。
        jvmArgs(
            "-XX:+UseParallelGC",
            "-XX:MinHeapFreeRatio=5",
            "-XX:MaxHeapFreeRatio=25"
        )

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
