// _local_test_mem.gradle.kts —— 限制 test task 的 fork JVM 内存，避免 OOM

allprojects {
    afterEvaluate {
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            maxHeapSize = "1500m"
            jvmArgs("-Xms512m", "-Xmx1500m", "-XX:MaxMetaspaceSize=384m", "-XX:+UseSerialGC")
            forkEvery = 0
        }
    }
}
