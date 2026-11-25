#!/bin/bash
# 打包包装器：构建结束后只回收【本项目】的构建进程
# 用法：./build_clean.sh [任务...]    默认 :app:assembleDebug
#
# 背景：本机内存有限（15GB 总，可用常低于 6GB），Gradle Daemon 与 Kotlin 编译
# 守护会常驻 500MB~1GB，构建完成后需要立即回收。
#
# 关键约定（M13）：绝不按进程名全局杀。同一台机器上可能还有别的工作区在构建，
# `pkill -f GradleDaemon` 这类写法会把它们一起打断。这里只做两件事：
#   1) `./gradlew --stop`：只停本项目的守护进程；
#   2) 按【父子关系】记录本次构建的后代 PID（构建 JVM → Gradle 守护 → Kotlin 编译守护），
#      事后按这些 PID 精确回收，全程不匹配进程名。

set -o pipefail
cd "$(dirname "$0")" || exit 1

export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}
TASKS="${*:-:app:assembleDebug}"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xime-build-clean.XXXXXX")"
LOG="$WORK_DIR/build.log"
PIDFILE="$WORK_DIR/pids"

# 记录某 PID 的子进程与孙进程（只按父子关系，不看进程名）
record_descendants() {
    local parent="$1" child grandchild
    for child in $(pgrep -P "$parent" 2>/dev/null || true); do
        printf '%s\n' "$child" >> "$PIDFILE"
        for grandchild in $(pgrep -P "$child" 2>/dev/null || true); do
            printf '%s\n' "$grandchild" >> "$PIDFILE"
        done
    done
}

# 构建期间持续采样：守护进程可能是构建中途才起来的
sample_build_tree() {
    local root="$1"
    while kill -0 "$root" 2>/dev/null; do
        record_descendants "$root"
        sleep 0.2
    done
}

cleanup() {
    local status=$? pids pid alive=0
    echo ""
    echo "[cleanup] 回收本项目构建进程…"
    # 收尾前再扫一遍已记录 PID 的子进程：Kotlin 编译守护是 Gradle 守护的子进程
    pids="$(sort -u "$PIDFILE" 2>/dev/null || true)"
    for pid in $pids; do
        record_descendants "$pid"
    done
    # 只停本项目守护进程（同版本守护按项目复用，不碰别的目录）
    ./gradlew --stop >/dev/null 2>&1
    # 本项目构建留下的后代按 PID 精确回收（典型为 Kotlin 编译守护）
    for pid in $(sort -u "$PIDFILE" 2>/dev/null || true); do
        kill -0 "$pid" 2>/dev/null && kill -TERM "$pid" 2>/dev/null
    done
    sleep 1
    for pid in $(sort -u "$PIDFILE" 2>/dev/null || true); do
        if kill -0 "$pid" 2>/dev/null; then
            alive=$((alive + 1))
            kill -KILL "$pid" 2>/dev/null
        fi
    done
    echo "[cleanup] 本次构建遗留进程: ${alive}"
    rm -rf -- "$WORK_DIR"
    return "$status"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

echo "[build] 任务: $TASKS"
: > "$PIDFILE"
: > "$LOG"

# 后台跑构建以拿到本次构建的 PID 根，同时采样后代用于收尾
./gradlew $TASKS --console=plain > "$LOG" 2>&1 &
BUILD_PID=$!
sample_build_tree "$BUILD_PID" &
SAMPLER_PID=$!

status=0
wait "$BUILD_PID" || status=$?
# 根进程已退出 → 采样循环自然结束；等它收尾，避免与回收并发
wait "$SAMPLER_PID" 2>/dev/null || true

grep -E "^e:|error:|FAILED|BUILD|tests completed" "$LOG" | tail -20

if [ "$status" -eq 0 ]; then
    echo "[build] 成功"
else
    echo "[build] 失败 (exit $status)"
fi
exit "$status"
