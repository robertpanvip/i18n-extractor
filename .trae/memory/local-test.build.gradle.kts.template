// 完整可直接 cp 覆盖 /workspace/build.gradle.kts 的模板（本地测试临时用，测完 git checkout 回滚）
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
    maven {
        name = "JetbrainsSnapshots"
        url = uri("https://www.jetbrains.com/intellij-repository/snapshots")
        content {
            includeGroupByRegex("""com\.jetbrains\.intellij.*""")
            includeGroupByRegex("""com\.jetbrains.*""")
            includeGroupByRegex("""org\.jetbrains\.intellij.*""")
            includeGroupByRegex("""bundled.*""")
        }
    }
    maven {
        name = "JetbrainsReleases"
        url = uri("https://www.jetbrains.com/intellij-repository/releases")
        content {
            includeGroupByRegex("""com\.jetbrains\.intellij.*""")
            includeGroupByRegex("""com\.jetbrains.*""")
            includeGroupByRegex("""org\.jetbrains\.intellij.*""")
            includeGroupByRegex("""bundled.*""")
        }
    }
    maven {
        name = "TencentMavenPublic"
        url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        content {
            excludeGroupByRegex("""com\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""com\.jetbrains.*""")
            excludeGroupByRegex("""org\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""bundled.*""")
        }
    }
    mavenCentral {
        content {
            excludeGroupByRegex("""com\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""com\.jetbrains.*""")
            excludeGroupByRegex("""org\.jetbrains\.intellij.*""")
            excludeGroupByRegex("""bundled.*""")
        }
    }
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
        ideaVersion { sinceBuild = "251" }
        changeNotes = "1.2.0 fix: optimize comment extraction logic"
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
