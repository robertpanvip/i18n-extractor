import org.jetbrains.intellij.platform.gradle.models.Coordinates
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "com.pan"
version = "1.5.0"

repositories {
    mavenCentral()
    gradlePluginPortal()
    maven("https://www.jetbrains.com/intellij-repository/releases") {
        name = "JetbrainsReleases"
        content {
            includeGroupByRegex("""com\.jetbrains\.intellij.*""")
            includeGroupByRegex("""com\.jetbrains.*""")
            includeGroupByRegex("""org\.jetbrains\.intellij.*""")
            includeGroupByRegex("""bundled.*""")
        }
    }
    maven("https://www.jetbrains.com/intellij-repository/snapshots") {
        name = "JetbrainsSnapshots"
        content {
            includeGroupByRegex("""com\.jetbrains\.intellij.*""")
            includeGroupByRegex("""com\.jetbrains.*""")
            includeGroupByRegex("""org\.jetbrains\.intellij.*""")
            includeGroupByRegex("""bundled.*""")
        }
    }
    maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") { name = "TencentMavenPublic" }
    maven("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") { name = "TencentGradlePlugins" }
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure Gradle IntelliJ Plugin
dependencies {
    intellijPlatform {
        jetbrainsRuntime()

        intellijIdeaUltimate("2025.1", useInstaller = false)

        testFramework(TestFrameworkType.Bundled)
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
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks {
    test {
        useJUnit()

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
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}
