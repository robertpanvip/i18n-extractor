// 完整可直接 cp 覆盖 /workspace/settings.gradle.kts 的模板（本地测试临时用，测完 git checkout 回滚）
pluginManagement {
    repositories {
        maven {
            name = "TencentGradlePlugins"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
        }
        maven {
            name = "TencentMavenPublic"
            url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
        }
        gradlePluginPortal()
    }
}
rootProject.name = "i18n-extractor"
