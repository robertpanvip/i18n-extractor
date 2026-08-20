pluginManagement {
    repositories {
        // 腾讯云 Gradle 插件镜像
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        gradlePluginPortal()
    }
}

rootProject.name = "i18n-extractor"
