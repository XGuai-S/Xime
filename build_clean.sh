#!/bin/bash
# 打包包装器：构建结束后自动清理 Gradle / Kotlin 后台进程
# 用法：./build.sh [任务...]    默认 :app:assembleDebug
#
# 说明：本机内存有限（15GB 总，可用常低于 6GB），Gradle Daemon 与
# Kotlin 编译守护会常驻 500MB~1GB。此脚本在构建完成后立即回收，
# 保证"结束后台就结束这些"。

set -o pipefail
cd "$(dirname "$0")" || exit 1

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}
TASKS="${*:-:app:assembleDebug}"

cleanup() {
    echo ""
    echo "[cleanup] 回收构建进程…"
    ./gradlew --stop >/dev/null 2>&1
    pkill -f "KotlinCompileDaemon" 2>/dev/null
    pkill -f "GradleDaemon" 2>/dev/null
    pkill -f "kotlin-daemon" 2>/dev/null
    sleep 1
    left=$(pgrep -fc "GradleDaemon|KotlinCompileDaemon" 2>/dev/null || echo 0)
    echo "[cleanup] 剩余构建进程: ${left}"
}
trap cleanup EXIT INT TERM

echo "[build] 任务: $TASKS"
./gradlew $TASKS --console=plain 2>&1 | grep -E "^e:|error:|FAILED|BUILD|tests completed" | tail -20
status=${PIPESTATUS[0]}

if [ "$status" -eq 0 ]; then
    echo "[build] 成功"
else
    echo "[build] 失败 (exit $status)"
fi
exit "$status"
