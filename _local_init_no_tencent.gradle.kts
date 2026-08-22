// 简化版 init script：只做 Java 21 探测，不添加腾讯镜像
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
            val noProxy = (System.getenv("NO_PROXY") ?: System.getenv("no_proxy")).orEmpty().trim()
            if (noProxy.isNotBlank()) {
                val nonProxyHosts = noProxy
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && '/' !in it }
                    .joinToString("|") { entry ->
                        if (entry.startsWith('.')) "*${entry}" else entry
                    }
                if (nonProxyHosts.isNotBlank()) {
                    System.setProperty("${scheme}.nonProxyHosts", nonProxyHosts)
                }
            }
        } catch (_: Throwable) { }
    }
    applyEnvProxy("HTTPS_PROXY", "https")
    applyEnvProxy("HTTP_PROXY", "http")
    for (s in listOf("http", "https")) {
        val cur = System.getProperty("${s}.nonProxyHosts").orEmpty()
        val items = cur.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        items += "localhost"
        items += "127.0.0.1"
        items += "::1"
        System.setProperty("${s}.nonProxyHosts", items.joinToString("|"))
    }
}

// Java 21 探测
run {
    val userHome = System.getProperty("user.home") ?: System.getenv("HOME") ?: ""
    val expectedMajor = 21
    val candidates: MutableList<java.io.File> = mutableListOf<java.io.File>().apply {
        val miseRoot = (System.getenv("MISE_INSTALLS_PATH") ?: System.getenv("MISE_DATA_DIR")?.let { "$it/installs" } ?: "$userHome/.local/share/mise/installs").let { java.io.File(it, "java") }
        if (miseRoot.isDirectory) miseRoot.listFiles()?.let { addAll(it) }
        val sdkCandidates = java.io.File(System.getenv("SDKMAN_CANDIDATES_DIR") ?: "$userHome/.sdkman/candidates/java")
        if (sdkCandidates.isDirectory) sdkCandidates.listFiles()?.let { addAll(it) }
        val asdfInstalls = java.io.File(System.getenv("ASDF_DATA_DIR") ?: "$userHome/.asdf/installs/java")
        if (asdfInstalls.isDirectory) asdfInstalls.listFiles()?.let { addAll(it) }
        val libJvm = java.io.File("/usr/lib/jvm")
        if (libJvm.isDirectory) libJvm.listFiles()?.let { addAll(it) }
        val jvmsDir = java.io.File("/Library/Java/JavaVirtualMachines")
        if (jvmsDir.isDirectory) jvmsDir.listFiles()?.forEach { f -> add(java.io.File(f, "Contents/Home")) }
        System.getenv("JAVA_HOME")?.also { add(java.io.File(it)) }
    }.distinct().toMutableList()

    fun javaMajorOf(home: java.io.File): Int? {
        val binJava = java.io.File(home, "bin/java")
        if (!binJava.isFile || !binJava.canExecute()) return null
        val release = java.io.File(home, "release")
        if (release.isFile) {
            try {
                val implVersionLine: String? = release.useLines { lines ->
                    lines.firstOrNull { line -> line.startsWith("JAVA_VERSION=") || line.startsWith("IMPLEMENTOR_VERSION=") }
                }
                if (implVersionLine != null) {
                    val v = implVersionLine.substringAfter('=', missingDelimiterValue = "")
                        .trim('"', '\'', ' ')
                        .split('-', '.').firstNotNullOfOrNull { tok -> tok.toIntOrNull() }
                    if (v != null) return v
                }
            } catch (_: Throwable) { }
        }
        return try {
            val pb = ProcessBuilder(binJava.absolutePath, "-version").redirectErrorStream(true)
            val proc = pb.start()
            val firstLine = proc.inputStream.bufferedReader().use { it.readLine() }.orEmpty()
            proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
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
    try {
        gradle.extra.set("DETECTED_JAVA21", detectedJava21)
    } catch (_: Throwable) {
        if (detectedJava21 != null) {
            System.setProperty("init.detectedJava21", detectedJava21.absolutePath)
        }
    }

    if (detectedJava21 != null) {
        val curPaths = System.getProperty("org.gradle.java.installations.paths").orEmpty().trim()
        val all = (curPaths.split(',').map { it.trim() }.filter { it.isNotEmpty() } + found).distinct()
        System.setProperty("org.gradle.java.installations.paths", all.joinToString(","))
        System.setProperty("org.gradle.java.installations.auto-download", "false")
        println("  [init:java21] 已探测到 Java $expectedMajor：${detectedJava21.path.takeLast(48)}")
        println("  [init:java21] 将在每个 project 中用 task-level javaHome/jdkHome 强制使用该 JDK")
    } else {
        System.err.println("  [init:java21] ⚠ 未在本地检测到 Java $expectedMajor 安装。")
    }
}

// Java 21 toolchain 强制覆写
allprojects {
    val detectedJava21: java.io.File? =
        (try { gradle.extra["DETECTED_JAVA21"] as? java.io.File } catch (_: Throwable) { null })
            ?: (System.getProperty("init.detectedJava21")?.let { java.io.File(it) }?.takeIf { it.isDirectory })

    if (detectedJava21 != null) {
        val runningMajor = System.getProperty("java.specification.version")?.toIntOrNull() ?: 21

        pluginManager.withPlugin("java-base") {
            afterEvaluate {
                configure<org.gradle.api.plugins.JavaPluginExtension> {
                    toolchain {
                        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(runningMajor))
                    }
                    sourceCompatibility = org.gradle.api.JavaVersion.VERSION_21
                    targetCompatibility = org.gradle.api.JavaVersion.VERSION_21
                }
                tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
                    options.release.set(21)
                    options.isFork = true
                    options.forkOptions.javaHome = detectedJava21
                }
            }
        }

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
                                    try { kc.getMethod("setJdkHome", String::class.java).invoke(kOpts, detectedJava21.absolutePath) } catch (_: Throwable) { }
                                    try { kc.getMethod("setJvmTarget", String::class.java).invoke(kOpts, "21") } catch (_: Throwable) { }
                                }
                            } catch (_: Throwable) { }
                        }
                    }
                    tasks.configureEach(action)
                }
            }
        }
    }
}