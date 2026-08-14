// _local_init.gradle.kts —— 放到项目根，通过 ./gradlew --init-script _local_init.gradle.kts 调用

// ---------------- pluginManagement: 腾讯优先，官方兜底 ----------------
//   若腾讯镜像不可达（某些容器环境只有 IPv6 公网、腾讯 IPv6 不通），Gradle 会继续尝试下一
//   个仓库；所以保证 gradlePluginPortal() / mavenCentral() 也同时存在，避免"只有腾讯、
//   腾讯不通 → 全局拉不下来插件/依赖"。
settingsEvaluated {
    pluginManagement {
        repositories {
            // 把官方原有的 repositories 先保留；腾讯放最前面，有网就走腾讯，没网自动 fallback。
            val existing = repositories.toList()
            repositories.clear()
            maven {
                name = "TencentGradlePluginsInit"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
            maven {
                name = "TencentMavenPublicInit"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            }
            existing.forEach { repositories.add(it) }
            // 最后官仓兜底
            if (!existing.any { it.name.equals("Gradle Central Plugin Repository", ignoreCase = true) }) {
                gradlePluginPortal()
            }
            if (!existing.any { it.name.equals("MavenRepo", ignoreCase = true) || it.name.equals("mavenCentral", ignoreCase = true) }) {
                mavenCentral()
            }
        }
    }
}

// ---------------- 所有项目级 repo: 腾讯镜像在前，官仓在后（双栈） ----------------
//   说明：
//     · 用户要求"使用腾讯镜像" → 腾讯在前优先命中；
//     · 某些容器内 IPv6-only 到 mirrors.cloud.tencent.com 实际不可达 → 必须保留官
//       方的 mavenCentral()/gradlePluginPortal()/JetBrains 仓在后面，Gradle 在腾讯 408/
//       ConnectException 后会自动 fallthrough 到下一个 repo，保证构建能跑起来。
allprojects {
    buildscript {
        repositories {
            // 先加腾讯前置（如后面对象为同一个 URL 不会被重复 add）
            maven {
                name = "TencentMavenPublicBuildScript"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
            }
            maven {
                name = "TencentGradlePluginsBuildScript"
                url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
            }
        }
    }

    afterEvaluate {
        // + 专门加 JetBrains snapshots/releases 仓，只服务 com.jetbrains.* / org.jetbrains.intellij.*
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

        // 腾讯镜像"预插"在每个项目 repositories 列表最前面（用名字做去重），
        // 不替换/删除原有 mavenCentral/GPP。fallthrough 由 Gradle 自动执行。
        repositories {
            val names = this.map { it.name }.toSet()
            if ("TencentMavenPublic" !in names) {
                maven {
                    name = "TencentMavenPublic"
                    url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
                }
            }
            if ("TencentGradlePlugins" !in names) {
                maven {
                    name = "TencentGradlePlugins"
                    url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/")
                }
            }
        }

        // 最后再把顺序调成：腾讯仓前置 → 原有仓 → JetBrains 专用仓
        val tencentList = repositories.filter { it.name.startsWith("Tencent") }
        val jbList = repositories.filter { it.name.startsWith("Jetbrains") }
        val others = repositories.filter { it !in tencentList && it !in jbList }
        repositories.clear()
        tencentList.forEach { repositories.add(it) }
        others.forEach { repositories.add(it) }
        jbList.forEach { repositories.add(it) }

        // ---------- 本地环境覆盖（只作用于本地 init 脚本执行时，不会写入线上 build.gradle.kts） ----------
        //   · main 上 java.toolchain.languageVersion = Java 21，但本地容器只安装了 Java 17，
        //     否则 Gradle 会尝试自动下载 Java 21 toolchain（外网不通或慢）。
        //   · Kotlin 编译 jvmTarget 也同步降到 17，保持字节码与本地 JDK 一致。
        //   · 检测到本地 JAVA_HOME 指向 Java 21+ 时不强制降级，避免误伤开发机。
        try {
            val localJvmVer = System.getProperty("java.specification.version")?.toIntOrNull()
            val project = this@afterEvaluate
            if (localJvmVer != null && localJvmVer < 21) {
                project.plugins.withType(org.gradle.api.plugins.JavaPlugin::class.java) {
                    project.extensions.configure(org.gradle.api.plugins.JavaPluginExtension::class.java) {
                        toolchain {
                            it.languageVersion.set(JavaLanguageVersion.of(Math.max(localJvmVer, 17)))
                        }
                    }
                }
                try {
                    val kotlinJvmClass = Class.forName("org.jetbrains.kotlin.gradle.dsl.JvmTarget")
                    val target = if (localJvmVer >= 17) {
                        kotlinJvmClass.getField("JVM_17").get(null)
                    } else {
                        kotlinJvmClass.getField("JVM_${localJvmVer * 10}").takeIf { it != null }?.get(null)
                            ?: kotlinJvmClass.getField("JVM_17").get(null)
                    }
                    project.tasks.withType(Class.forName("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")) { task ->
                        try {
                            val coClass = Class.forName("org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions")
                            val opts = (task as org.gradle.api.tasks.compile.AbstractCompile).extensions
                                .findByName("compilerOptions")
                            if (opts != null && coClass.isInstance(opts)) {
                                val m = coClass.getMethod("setJvmTarget", Object::class.java)
                                m.invoke(opts, target)
                            }
                        } catch (_: Throwable) { /* 任务没配置 compilerOptions 就跳过 */ }
                    }
                } catch (_: Throwable) { /* 没应用 Kotlin 插件也正常，忽略 */ }
            }
        } catch (_: Throwable) { /* 保护：任何本地覆盖失败都不影响主构建 */ }
    }
}
