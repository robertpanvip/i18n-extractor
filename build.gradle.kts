plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.5.0"
}

group = "com.pan"
version = "1.3.2"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
      webstorm("2025.3") {
       useInstaller = false
      }

       testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
       testFramework(TestFrameworkType.Plugin.XML)
       testFramework(TestFrameworkType.Plugin.JavaScript)
        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    
        bundledPlugins(
            "JavaScript",
            "org.jetbrains.plugins.vue", 
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
             1.2.0 fix: optimize comment extraction logic
    """.trimIndent()
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
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}
