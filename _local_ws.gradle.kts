// _local_ws.gradle.kts —— 配合 --offline 使用：本地 webstorm 源
// 远程（腾讯镜像/官方 CDN）下载 1.1GB 的 webstorm-2026.1.zip 曾在沙箱中大文件截断，
// 且 modules-2 缓存中无该 zip。此脚本把本地 maven 仓库（已用 curl 断点续传完整下载）
// 作为首个仓库；配合 --offline 时，gradle 对 webstorm 坐标只会落到该本地仓库解析。

allprojects {
    repositories {
        maven {
            name = "LocalWebstorm"
            url = uri("file:///tmp/wslocal")
        }
    }
}