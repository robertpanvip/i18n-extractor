rootProject.name = "i18n-extractor"

pluginManagement {
    val isCi = System.getenv("CI") == "true"
    repositories {
        if (!isCi) {
            maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
            maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        }
        gradlePluginPortal()
    }
}
