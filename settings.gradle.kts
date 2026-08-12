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
