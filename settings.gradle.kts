// ═══════════════════════════════════════════════════
// 本地测试临时配置（通过后自动回滚）
// 思路：PREFER_SETTINGS，统一在 dependencyResolutionManagement 里注册镜像，
// intellijPlatform.defaultRepositories() 在 build.gradle.kts 里保留（由它解析
// ideaIU / bundledPlugins / jetbrainsRuntime，这些都走 cache-redirector.jetbrains.com）。
// Maven Central 换腾讯镜像，避免 ideaIU 以外的包被限流 429。
// ═══════════════════════════════════════════════════
pluginManagement {
    repositories {
        maven {
            name = "TencentGradlePlugins"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        }
        gradlePluginPortal()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        // 1) Tencent Maven 镜像代替 Maven Central
        mavenCentral {
            name = "TencentMirror"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            content {
                // IntelliJ Platform / IDE / bundledPlugin / org.jetbrains 工件
                // 一概不准打腾讯镜像（这些腾讯不一定有，还会错误触发"找不到 → fallback → 429"路径）
                excludeGroupByRegex("""com\.jetbrains\..*""")
                excludeGroupByRegex("""org\.jetbrains\..*""")
                excludeGroupByRegex("""io\.jetbrains\..*""")
                excludeGroup("bundledPlugin")
            }
        }
        google()
    }
}

rootProject.name = "i18n-extractor"
