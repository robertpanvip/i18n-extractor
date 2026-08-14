rootProject.name = "i18n-extractor"

// 插件仓库：官仓优先（代理已在 gradle.properties systemProp + env 配置），腾讯兜底
pluginManagement {
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
    }
}
