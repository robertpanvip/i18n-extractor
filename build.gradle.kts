import org.jetbrains.intellij.platform.gradle.models.Coordinates

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.pan"
version = "1.3.2"

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
        
        intellijIdeaUltimate("2025.3") {
            useInstaller = false
        }

       testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    
        bundledPlugins(
            "JavaScript",
            "org.jetbrains.plugins.vue"
        )
     platformDependency(Coordinates("com.jetbrains.intellij.platform", "poly-symbols-test-framework"))
     platformDependency(Coordinates("com.jetbrains.intellij.platform", "lsp-test-framework"))
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
             1.2.0 fix: optimize comment extraction logic
    """.trimIndent()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
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
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}
