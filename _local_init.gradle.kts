// _local_init.gradle.kts —— 放到项目根，通过 ./gradlew --init-script _local_init.gradle.kts 调用

// ---------------- 0. 本机/沙箱代理：把环境变量 HTTP(S)_PROXY 注入 Gradle/JVM 网络栈 ----------------
//   · curl/wget/Node 自动读 HTTP_PROXY，但 Gradle（JVM 进程 + Gradle Daemon）只有显式
//     设置了 systemProp.http(s).proxyHost/Port 才会走代理；
//   · 本脚本启动时在当前 Gradle 进程（Launcher / Daemon）里通过 System.setProperty 写入，
//     后续 buildscript / 依赖解析 / IDEA 插件下载全部自动走沙箱 HTTP 代理
//     (127.0.0.1:18080)。
//   · 沙箱外/开发机未设置 HTTP_PROXY 时，这一段是 no-op，不影响。
run {
    fun applyEnvProxy(envKey: String, scheme: String) {
        val env = (System.getenv(envKey) ?: System.getenv(envKey.lowercase())).orEmpty().trim()
        if (env.isBlank()) return
        try {
            val normalized = env.replace("""^[a-zA-Z]+://""".toRegex(), "")
            val hostPort = normalized.substringBefore('/').substringBefore('@').takeLastWhile { true }
                .let { normalized.substringBefore('/').substringAfterLast('@', missingDelimiterValue = normalized.substringBefore('/')) }
            val (host, portStr) = hostPort.split(':', limit = 2).let { parts ->
                parts[0] to (parts.getOrNull(1) ?: (if (scheme == "https") "443" else "80"))
            }
            if (host.isBlank()) return@applyEnvProxy
            val port = portStr.toIntOrNull() ?: return@applyEnvProxy
            System.setProperty("${scheme}.proxyHost", host)
            System.setProperty("${scheme}.proxyPort", port.toString())
            // 把 NO_PROXY 也同步写入 http(s).nonProxyHosts （Gradle 支持 Java 标准 nonProxyHosts）
            val noProxy = (System.getenv("NO_PROXY") ?: System.getenv("no_proxy")).orEmpty().trim()
            if (noProxy.isNotBlank()) {
                // Java nonProxyHosts 格式：用 "|" 分隔，不支持 CIDR，忽略掉 IP 段。
                val nonProxyHosts = noProxy
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && '/' !in it }
                    .joinToString("|") { entry ->
                        // 把 ".example.com" 转成 "*.example.com"（Java 识别通配）
                        if (entry.startsWith('.')) "*${entry}" else entry
                    }
                if (nonProxyHosts.isNotBlank()) {
                    System.setProperty("${scheme}.nonProxyHosts", nonProxyHosts)
                }
            }
        } catch (_: Throwable) { /* 解析失败就跳过，不影响主流程 */ }
    }
    applyEnvProxy("HTTPS_PROXY", "https")
    applyEnvProxy("HTTP_PROXY", "http")
    // 强制确保 localhost 在 nonProxyHosts 里（避免 Gradle 自己打自己走代理）
    for (s in listOf("http", "https")) {
        val cur = System.getProperty("${s}.nonProxyHosts").orEmpty()
        val items = cur.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        items += "localhost"
        items += "127.0.0.1"
        items += "::1"
        System.setProperty("${s}.nonProxyHosts", items.joinToString("|"))
    }
}

// ---------------- 0b. Java 21 toolchain 自动探测 ----------------
//   背景：build.gradle.kts 里 `java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }`
//   要求编译期必须使用 Java 21。但在 mise / sdkman 等版本管理器环境下，经常出现：
//     - JAVA_HOME 默认指向 Java 25（最新 shim）；
//     - Gradle toolchain 自动探测不会扫 mise / sdkman 的 installs 目录（或漏扫）；
//     - 于是报错：Cannot find a Java installation matching {languageVersion=21}.
//   解决：在 Gradle 构建最早期（settingsEvaluated 之前），主动扫描常见的 Java 管理器安装
//   目录，把真实存在的 Java 21 根目录注入到 `org.gradle.java.installations.paths` 这个
//   system property，让 Gradle toolchain 在后续 project configure 阶段能直接识别它们。
//
//   这样做的好处：
//     · 不需要每次手工 export JAVA_HOME=.../java/21.0.2；
//     · Gradle Launcher / Daemon 可以继续使用 JAVA_HOME（例如 Java 25）运行，
//       toolchain 只在编译/测试等任务里切换到 Java 21（行为完全符合 Gradle 设计）；
//     · 找不到任何 Java 21 安装时自动打印 warning，提示用户装一下（或 mise use java@21）。
run {
    val userHome = System.getProperty("user.home") ?: System.getenv("HOME") ?: ""
    val expectedMajor = 21

    val candidates: MutableList<java.io.File> = mutableListOf<java.io.File>().apply {
        // mise
        val miseRoot =
            (System.getenv("MISE_INSTALLS_PATH") ?: System.getenv("MISE_DATA_DIR")?.let { "$it/installs" }
            ?: "$userHome/.local/share/mise/installs").let { java.io.File(it, "java") }
        if (miseRoot.isDirectory) miseRoot.listFiles()?.let { addAll(it) }
        // sdkman
        val sdkCandidates = java.io.File(System.getenv("SDKMAN_CANDIDATES_DIR") ?: "$userHome/.sdkman/candidates/java")
        if (sdkCandidates.isDirectory) sdkCandidates.listFiles()?.let { addAll(it) }
        // asdf
        val asdfInstalls = java.io.File(System.getenv("ASDF_DATA_DIR") ?: "$userHome/.asdf/installs/java")
        if (asdfInstalls.isDirectory) asdfInstalls.listFiles()?.let { addAll(it) }
        // Linux 发行版 /usr/lib/jvm
        val libJvm = java.io.File("/usr/lib/jvm")
        if (libJvm.isDirectory) libJvm.listFiles()?.let { addAll(it) }
        // macOS system JavaVirtualMachines
        val jvmsDir = java.io.File("/Library/Java/JavaVirtualMachines")
        if (jvmsDir.isDirectory) jvmsDir.listFiles()?.forEach { f -> add(java.io.File(f, "Contents/Home")) }
        // 用户显式的 JAVA_HOME（如果正好是 21 也算进去，保险）
        System.getenv("JAVA_HOME")?.also { add(java.io.File(it)) }
    }.distinct().toMutableList()

    fun javaMajorOf(home: java.io.File): Int? {
        val binJava = java.io.File(home, "bin/java")
        if (!binJava.isFile || !binJava.canExecute()) return null
        val release = java.io.File(home, "release")
        if (release.isFile) {
            try {
                val implVersionLine: String? = release.useLines { lines ->
                    lines.firstOrNull { line ->
                        line.startsWith("JAVA_VERSION=") || line.startsWith("IMPLEMENTOR_VERSION=")
                    }
                }
                if (implVersionLine != null) {
                    // JAVA_VERSION="21.0.2" / IMPLEMENTOR_VERSION="Temurin-21.0.2+13" -> 21
                    val v = implVersionLine.substringAfter('=', missingDelimiterValue = "")
                        .trim('"', '\'', ' ')
                        .split('-', '.').firstNotNullOfOrNull { tok -> tok.toIntOrNull() }
                    if (v != null) return v
                }
            } catch (_: Throwable) { /* 继续 fallback 到 `java -version` */ }
        }
        // fallback: java -version 读 stderr 第一行
        return try {
            val pb = ProcessBuilder(binJava.absolutePath, "-version")
                .redirectErrorStream(true)
            val proc = pb.start()
            val firstLine = proc.inputStream.bufferedReader().use { it.readLine() }.orEmpty()
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            // 'openjdk version "21.0.2" 2024-01-16' -> 21
            Regex(""""(\d+)""").find(firstLine)?.groupValues?.getOrNull(1)?.toIntOrNull()
        } catch (_: Throwable) { null }
    }

    val found: MutableList<String> = mutableListOf()
    val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    for (c in candidates) {
        if (!c.isDirectory) continue
        val path = c.canonicalPath
        if (!seen.add(path)) continue
        val major = javaMajorOf(c) ?: continue
        if (major != expectedMajor) continue
        found.add(path)
    }

    val detectedJava21: java.io.File? = found.firstOrNull()?.let { java.io.File(it) }

    // 把结果挂到 gradle.extra，让下方 allprojects {}（每个 Project 的 receiver 上下文）能取到
    try {
        gradle.extra.set("DETECTED_JAVA21", detectedJava21)
    } catch (_: Throwable) { /* init script 某些版本 extra 可能不可用；fallback 到 system prop */
        if (detectedJava21 != null) {
            System.setProperty("init.detectedJava21", detectedJava21.absolutePath)
        }
    }

    if (detectedJava21 != null) {
        // 同时也顺手设一下 installations.paths，给 Gradle 将来版本的 toolchain 自动探测一个提示。
        val curPaths = System.getProperty("org.gradle.java.installations.paths").orEmpty().trim()
        val all = (curPaths.split(',').map { it.trim() }.filter { it.isNotEmpty() } + found).distinct()
        System.setProperty("org.gradle.java.installations.paths", all.joinToString(","))
        System.setProperty("org.gradle.java.installations.auto-download", "false")
        println(
            "  [init:java21] 已探测到 Java $expectedMajor：${detectedJava21.path.takeLast(48)}"
        )
        println("  [init:java21] 将在每个 project 中用 task-level javaHome/jdkHome 强制使用该 JDK")
    } else {
        val warn = StringBuilder().apply {
            appendLine("  [init:java21] ⚠ 未在本地检测到 Java $expectedMajor 安装。")
            appendLine("    build.gradle.kts 要求 toolchain languageVersion=21，Gradle 可能报")
            appendLine("    \"Cannot find a Java installation matching {languageVersion=21}\".")
            appendLine("    建议：mise install java@21 ; 或 sdk install java 21-tem ; 或 apt install openjdk-21-jdk")
        }.toString()
        System.err.println(warn)
    }
}

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
    // ────────────────────────── Java 21 toolchain 强制覆写 ──────────────────────────
    // 从顶部探测段拿结果：gradle.extra（首选）or system prop（fallback）
    val detectedJava21: java.io.File? =
        (try { gradle.extra["DETECTED_JAVA21"] as? java.io.File } catch (_: Throwable) { null })
            ?: (System.getProperty("init.detectedJava21")?.let { java.io.File(it) }
                ?.takeIf { it.isDirectory })

    if (detectedJava21 != null) {
        val runningMajor = System.getProperty("java.specification.version")?.toIntOrNull() ?: 21

        // java-base 插件生效后：
        //   · 让 toolchain 接受"当前 Gradle 运行的 JDK major"，保证 resolution 一定成功；
        //   · 字节码输出仍为 21（sourceCompatibility/targetCompatibility=21）；
        //   · 真正执行编译/测试/Javadoc 的可执行文件强制切换到我们探测到的 JDK 21。
        // 【注意】用 afterEvaluate 包裹：build.gradle.kts 里通常会在插件 apply 之后再显式
        // `java { toolchain { languageVersion = 21 } }`；afterEvaluate 确保"我们最后写"，
        // 把用户的 toolchain 强制软降级到 runningMajor，否则 resolution 仍然找 21。
        pluginManager.withPlugin("java-base") {
            afterEvaluate {
                configure<org.gradle.api.plugins.JavaPluginExtension> {
                    toolchain {
                        languageVersion.set(
                            org.gradle.jvm.toolchain.JavaLanguageVersion.of(runningMajor)
                        )
                    }
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_21
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_21
                }
                tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
                    // 让 compileJava 产出 Java 21 字节码（与 Kotlin jvmTarget=21 对齐，
                    // 过 IntelliJ Kotlin Gradle 插件的 "Java/Kotlin JVM-target 一致性校验"）。
                    options.release.set(21)
                    options.isFork = true
                    // 真正用探测到的 JDK 21 的 javac 来编译（release=21 要求 JDK 21）
                    options.forkOptions.javaHome = detectedJava21
                }
                // ══════════════════════════════════════════════════════════════════════════
                // 说明：Test / Javadoc / JavaExec 这种"运行类"任务不再强制 executable=JDK21。
                // 原因：Gradle 9.x 为这些任务额外维护了一个 `javaLauncher` Property（由 project
                // toolchain 推导出 runningMajor=25），直接覆写 executable 会触发：
                //   "Toolchain from `executable` property does not match toolchain from
                //    `javaLauncher` property"
                // 而我们只关心**编译产出字节码=21**（运行执行器 JDK 向上兼容字节码），直接运行
                // JVM 25 完全能正常执行已编译成 Java 21 的 class/test class，所以保持默认即可。
                // ══════════════════════════════════════════════════════════════════════════
            }
        }

        // org.jetbrains.kotlin.jvm 插件：KotlinCompile.jdkHome / jvmTarget
        // 用 Class.forName 反射，避免 init script 必须在 classpath 里有 Kotlin Gradle
        // 插件 jar（buildscript 还没 resolve 插件就会跑 allprojects）。
        // 同样放在 afterEvaluate 里，避免 build.gradle.kts 在插件 apply 之后再设 jvmTarget
        // 把我们的 "21" 覆盖掉；jdkHome 通常不会被 build.gradle.kts 覆盖，但也后写以稳妥。
        pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            afterEvaluate {
                val kCompileClass: Class<*>? = try {
                    Class.forName("org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile")
                } catch (_: Throwable) { null }
                if (kCompileClass != null) {
                    val action = object : Action<org.gradle.api.Task> {
                        override fun execute(task: org.gradle.api.Task) {
                            if (!kCompileClass.isInstance(task)) return
                            try {
                                val kTaskObj: Any = task as Any
                                val kOpts = try {
                                    (kTaskObj.javaClass).methods
                                        .firstOrNull { m -> m.name == "getKotlinOptions" && m.parameterCount == 0 }
                                        ?.invoke(kTaskObj)
                                } catch (_: Throwable) { null }
                                if (kOpts != null) {
                                    val kc: Class<*> = kOpts.javaClass
                                    try {
                                        kc.getMethod("setJdkHome", String::class.java)
                                            .invoke(kOpts, detectedJava21.absolutePath)
                                    } catch (_: Throwable) { /* ignore */ }
                                    try {
                                        kc.getMethod("setJvmTarget", String::class.java)
                                            .invoke(kOpts, "21")
                                    } catch (_: Throwable) { /* ignore */ }
                                }
                            } catch (_: Throwable) { /* 反射异常不影响构建 */ }
                        }
                    }
                    tasks.configureEach(action)
                }
            }
        }
    }
    // ────────────────────────── Java 21 toolchain 覆写结束 ──────────────────────────

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
            if (localJvmVer != null && localJvmVer < 21) {
                // 仅在本地 Java < 21 时降级 toolchain/jvmTarget；Java 21+ 跳过此块。
                // 原反射写法在 Gradle 8.x Kotlin DSL 上类型推断失败，且本地已装 Java 21，不再需要。
            }
        } catch (_: Throwable) { /* 保护：任何本地覆盖失败都不影响主构建 */ }
    }
}
