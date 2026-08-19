// _local_test_mem.gradle.kts —— 限制 test task 的 fork JVM 内存，避免 OOM

allprojects {
    afterEvaluate {
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            maxHeapSize = "700m"
            jvmArgs("-Xms256m", "-Xmx700m", "-XX:MaxMetaspaceSize=256m", "-XX:+UseSerialGC")
            forkEvery = 0
        }
    }
}
