import org.jetbrains.intellij.platform.gradle.models.Coordinates
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.pan"
version = "1.3.7"

repositories {
    // 注意：仓库优先级按声明顺序从高到低，所以先放 JetBrains 官方仓库
    // JetBrains Snapshots（EAP 版本）
    maven {
        name = "JetbrainsSnapshots"
        url = uri("https://www.jetbrains.com/intellij-repository/snapshots")
        // 只接受 JetBrains 相关 group，避免被意外用于其他包
        content {
            includeGroupByRegex("""com\.jetbrains\.intellij.*""")
            includeGroupByRegex("""com\.jetbrains.*""")
            includeGroupByRegex("""org\.jetbrains\.intellij.*""")
        }
    }
    // JetBrains Releases（稳定版本）
    maven {
        name = "JetbrainsReleases"
        url = uri("https://www.jetbrains.com/intellij-repository/releases")
        content {
            includeGroupByRegex("""com\.jetbrains\.intellij.*""")
            includeGroupByRegex("""com\.jetbrains.*""")
            includeGroupByRegex("""org\.jetbrains\.intellij.*""")
        }
    }

    // 腾讯镜像（公共 + JetBrains）
    maven {
        name = "TencentJetbrains"
        url = uri("https://mirrors.cloud.tencent.com/jetbrains/")
        content {
            excludeGroupByRegex("""com\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""com\.jetbrains.*""")
            excludeGroupByRegex("""org\.jetbrains\.intellij.*""")
        }
    }
    maven {
        name = "TencentMavenPublic"
        url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        // 关键：在 TencentMavenPublic 上排除所有 JetBrains 相关的 group，
        // 防止先走这个仓库遇到 429 限流把整个仓库禁用。
        content {
            excludeGroupByRegex("""com\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""com\.jetbrains.*""")
            excludeGroupByRegex("""org\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""bundled.*""")
        }
    }

    // Maven Central，同样排除 JetBrains 相关的 group
    mavenCentral {
        content {
            excludeGroupByRegex("""com\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""com\.jetbrains.*""")
            excludeGroupByRegex("""org\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""bundled.*""")
        }
    }

    // intellijPlatform defaultRepositories() 会添加：
    // - cache-redirector.jetbrains.com / intellij-repository / releases.jetbrains.com 等
    // - bundledPlugin 以及各种 deps 依赖都在这些特殊仓库里能找到
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        jetbrainsRuntime()

        intellijIdeaUltimate("2025.2.6.3") {
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
            "HtmlTools",
            "com.intellij.copyright",
            "intellij.webpack",
            "JSIntentionPowerPack",
            "JavaScriptDebugger",
        )

        platformDependency(Coordinates("com.jetbrains.intellij.platform", "poly-symbols-test-framework"))
        platformDependency(Coordinates("com.jetbrains.intellij.platform", "lsp-test-framework"))
    }

    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
             1.2.0 fix: optimize comment extraction logic
    """.trimIndent()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks {
    test {
        useJUnit()
        maxHeapSize = "2g"
        jvmArgs("-Xmx2g", "-XX:MaxMetaspaceSize=512m")
        testLogging {
            showStandardStreams = true
            events("passed", "failed", "skipped", "standardOut", "standardError")
        }
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}
