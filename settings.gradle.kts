rootProject.name = "i18n-extractor"

// ---- pluginManagement: 官仓优先（腾讯镜像在某些 IPv6-only 环境不可达），腾讯在后 fallback ----
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "TencentGradlePlugins"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        }
        maven {
            name = "TencentMavenPublic"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        gradlePluginPortal()
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
            name = "JetbrainsSnapshots"
            url = uri("https://www.jetbrains.com/intellij-repository/snapshots")
            content {
                includeGroupByRegex("""com\.jetbrains\.intellij.*""")
                includeGroupByRegex("""com\.jetbrains.*""")
                includeGroupByRegex("""org\.jetbrains\.intellij.*""")
                includeGroupByRegex("""bundled.*""")
            }
        }
        // 腾讯镜像在后兜底（若官仓 429 限速时可命中腾讯缓存）
        maven {
            name = "TencentMavenPublic"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        maven {
            name = "TencentGradlePlugins"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        }
    }
}
