// _local_init.gradle.kts —— 放到项目根，通过 ./gradlew --init-script _local_init.gradle.kts 调用

// ---------------- pluginManagement 走腾讯 ----------------
settingsEvaluated {
    pluginManagement {
        repositories.clear()
        repositories {
            maven {
                name = "TencentGradlePluginsInit"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
            maven {
                name = "TencentMavenPublicInit"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            }
            // 最后官仓兜底
            gradlePluginPortal()
            mavenCentral()
        }
    }
}

// ---------------- 所有项目级 repo 替换成腾讯 + JetBrains 专用仓 ----------------
allprojects {
    buildscript {
        repositories.withType<MavenArtifactRepository>().configureEach repo@{
            val u = this.url.toString()
            when {
                u.startsWith("https://repo.maven.apache.org/maven2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
                u.startsWith("https://plugins.gradle.org/m2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
        }
    }

    afterEvaluate {
        // + 专门加一个 JetBrains snapshots 仓，只服务 com.jetbrains.intellij.* / bundled.* 组
        //   否则 ideaIU:LATEST-EAP-SNAPSHOT 会被错误导去 MavenCentral 然后被 429 限速
        repositories {
            maven {
                name = "JetbrainsSnapshotsInit"
                url = uri("https://www.jetbrains.com/intellij-repository/snapshots")
                content {
                    includeGroupByRegex("""com\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""com\.jetbrains.*""")
                    includeGroupByRegex("""org\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""bundled.*""")
                }
            }
            maven {
                name = "JetbrainsReleasesInit"
                url = uri("https://www.jetbrains.com/intellij-repository/releases")
                content {
                    includeGroupByRegex("""com\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""com\.jetbrains.*""")
                    includeGroupByRegex("""org\.jetbrains\.intellij.*""")
                    includeGroupByRegex("""bundled.*""")
                }
            }
        }
        // 最后再把项目级 mavenCentral / GPP 的 URL 替换一次
        repositories.withType<MavenArtifactRepository>().configureEach repo@{
            val u = this.url.toString()
            when {
                u.startsWith("https://repo.maven.apache.org/maven2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
                u.startsWith("https://plugins.gradle.org/m2") ->
                    this.setUrl("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
        }
    }
}
