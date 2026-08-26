#!/usr/bin/env bash
#
# 本地运行本插件的测试 —— 把环境里踩过的坑固化成一个脚本。
#
# 关键经验（都是实际遇到并解决的）：
#   1. 必须用 JDK 21。默认 JAVA_HOME 可能是 JDK 25，Gradle/IntelliJ 平台构建会失败。
#   2. 用 Gradle 9.4.1（mise 安装）。wrapper 下载很容易超时，直接走本地 gradle 更稳。
#   3. 本沙箱通过代理访问外网，必须把代理参数传给 Gradle JVM，否则依赖下载/校验会失败。
#   4. 用 --no-daemon 避免 daemon 复用导致 java.home 不一致。
#   5. XDG_RUNTIME_DIR 必须设为有效目录，否则 IntelliJ Platform 测试框架会反复打印
#      "error: XDG_RUNTIME_DIR is invalid or not set in the environment" 并可能触发 OOM。
#
# 用法：
#   scripts/run-tests.sh                          # 跑全部测试
#   scripts/run-tests.sh --tests com.pan.extractor.InlineScanBugRegressionTest
#   scripts/run-tests.sh --tests "com.pan.extractor.*"
#
set -euo pipefail

# 1) 定位 JDK 21 -----------------------------------------------------------
MISE_INSTALLS="${MISE_INSTALLS:-$HOME/.local/share/mise/installs}"
JDK21_CANDIDATES=(
  "$MISE_INSTALLS/java/temurin-21"
  "$MISE_INSTALLS/java/21.0.2"
  "/usr/lib/jvm/java-21-openjdk-amd64"
  "/opt/java/jdk-21"
)
JDK21=""
for d in "${JDK21_CANDIDATES[@]}"; do
  if [ -d "$d" ]; then JDK21="$d"; break; fi
done

if [ -z "$JDK21" ]; then
  echo "[run-tests] 未找到 JDK 21，请先安装：mise install java@temurin-21" >&2
  exit 1
fi
export JAVA_HOME="$JDK21"
export PATH="$JAVA_HOME/bin:$PATH"
echo "[run-tests] JAVA_HOME=$JAVA_HOME"

# 2) 定位 Gradle ----------------------------------------------------------
GRADLE_BIN=""
GRADLE_CANDIDATES=(
  "$MISE_INSTALLS/gradle/9.4.1/bin/gradle"
  "$(command -v gradle || true)"
)
for g in "${GRADLE_CANDIDATES[@]}"; do
  if [ -n "$g" ] && [ -x "$g" ]; then GRADLE_BIN="$g"; break; fi
done
if [ -z "$GRADLE_BIN" ]; then GRADLE_BIN="./gradlew"; fi
echo "[run-tests] GRADLE=$GRADLE_BIN"

# 3) 从环境变量推导代理，传给 Gradle JVM ------------------------------------
# 只能放 kwargs：用 -D 传给 daemon JVM，否则依赖下载/校验会失败。
PROXY_ARGS=()
for var in HTTPS_PROXY HTTP_PROXY https_proxy http_proxy; do
  val="${!var:-}"
  if [ -n "$val" ]; then
    host_port="${val#*://}"          # 去掉 http:// 前缀
    host_port="${host_port%/}"       # 去掉结尾斜杠
    host="${host_port%%:*}"
    port="${host_port##*:}"
    if [ -n "$host" ] && [ -n "$port" ] && [ "$port" != "$host" ]; then
      PROXY_ARGS+=("-Dhttps.proxyHost=$host" "-Dhttps.proxyPort=$port" "-Dhttp.proxyHost=$host" "-Dhttp.proxyPort=$port")
      break
    fi
  fi
done
if [ "${#PROXY_ARGS[@]}" -gt 0 ]; then
  echo "[run-tests] 使用代理参数: ${PROXY_ARGS[*]}"
fi

# 4) 设置 XDG_RUNTIME_DIR（IntelliJ Platform 测试框架要求，否则反复打印错误并可能 OOM）
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp}"

# 5) 用 --no-daemon 强制单次构建，并把 java.home 显式指到 JDK 21 ----------------
#    -PforkTest=true：本地沙箱内存有限（4GiB），按类独立 fork 避免 OOM；
#    线上 CI 不传此标记，不走 forkEvery，套件跑得更快。
echo "[run-tests] 开始执行: gradle test $*"
exec "$GRADLE_BIN" test \
  "$@" \
  -PforkTest=true \
  --no-daemon \
  -Dorg.gradle.java.home="$JDK21" \
  "${PROXY_ARGS[@]}"