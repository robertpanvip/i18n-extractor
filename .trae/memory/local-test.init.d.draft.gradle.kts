// 放在 /root/.gradle/init.d/local-test.gradle.kts 或直接复制内容到 settings.gradle.kts / build.gradle.kts
// 作用：强制本地 Gradle 不解析 LATEST-EAP-SNAPSHOT；在沙箱里钉死 2025.2.6.3 并替换仓库顺序
// 使用方法：复制 -> 粘贴到 /workspace/build.gradle.kts 对应段落（不建议 init.d 因为 init.d 改不了 intellijPlatform DSL 的 resolutionStrategy 深度配置）
