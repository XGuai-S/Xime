#!/bin/bash
# build_clean.sh 回归：用替身 gradlew / pgrep / pkill 断言「只回收本项目构建进程」。
#
# 缺陷背景（M13）：旧实现 cleanup 里 `pkill -f "GradleDaemon"` / `pkill -f
# "KotlinCompileDaemon"` / `pkill -f "kotlin-daemon"` 按进程名全局杀进程，会打断同机
# 其它工作区正在跑的构建。本脚本断言：
#   1) 只调用 `./gradlew --stop` 收尾，且构建任务参数原样传递；
#   2) 构建失败退出码原样返回，且收尾仍然执行；
#   3) 全程不出现任何 pkill 调用（替身 pkill 一旦被调用即失败）；
#   4) 源码可执行行里不出现按名字全局杀的写法；
#   5) 行为验证：本次构建拉起的编译守护被按 PID 回收，别的工作区同名进程不受影响。
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xime-build-clean.XXXXXX")"
trap 'rm -rf -- "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/project" "$TEST_DIR/bin" "$TEST_DIR/outside"

cp "$PROJECT_DIR/build_clean.sh" "$TEST_DIR/project/build_clean.sh"

# 替身 gradlew：记录每次调用的参数；--stop 直接成功；FAIL_BUILD 非空时构建失败退出 23
cat > "$TEST_DIR/project/gradlew" <<'GRADLE'
#!/bin/bash
printf 'gradlew %s\n' "$*" >> "$CALL_LOG"
if [[ "$*" == *"--stop"* ]]; then exit 0; fi
if [[ -n "${FAIL_BUILD:-}" ]]; then exit 23; fi
exit 0
GRADLE
chmod +x "$TEST_DIR/project/gradlew"

# 替身 pgrep：记录参数，行为等价「没有子进程」
cat > "$TEST_DIR/bin/pgrep" <<'PGREP'
#!/bin/bash
printf 'pgrep %s\n' "$*" >> "$CALL_LOG"
exit 1
PGREP
# 替身 pkill：一旦被调用就留证据（脚本必须从不调用它）
cat > "$TEST_DIR/bin/pkill" <<'PKILL'
#!/bin/bash
printf 'pkill %s\n' "$*" >> "$CALL_LOG"
printf 'called\n' >> "$PKILL_LOG"
exit 1
PKILL
chmod +x "$TEST_DIR/bin/pgrep" "$TEST_DIR/bin/pkill"
# 替身 kill：按 PID 精确回收只允许出现在 cleanup 收尾（不代表全局杀）
cat > "$TEST_DIR/bin/kill" <<'KILL'
#!/bin/bash
printf 'kill %s\n' "$*" >> "$CALL_LOG"
exit 0
KILL
chmod +x "$TEST_DIR/bin/kill"

export CALL_LOG="$TEST_DIR/calls"
export PKILL_LOG="$TEST_DIR/pkill-called"
: > "$PKILL_LOG"
export PATH="$TEST_DIR/bin:$PATH"

fail() { echo "FAIL $*" >&2; exit 1; }

run_case() {
    local label="$1" expected_status="$2" fail_build="$3" extra_arg="$4" expected_first="$5"
    : > "$CALL_LOG"
    local status=0
    (
        cd "$TEST_DIR/outside"
        if [[ -n "$extra_arg" ]]; then
            FAIL_BUILD="$fail_build" bash "$TEST_DIR/project/build_clean.sh" "$extra_arg"
        else
            FAIL_BUILD="$fail_build" bash "$TEST_DIR/project/build_clean.sh"
        fi
    ) > "$TEST_DIR/output" 2>&1 || status=$?
    [[ "$status" == "$expected_status" ]] || { cat "$TEST_DIR/output"; fail "$label 退出码=$status 期望=$expected_status"; }
    # 只看 gradlew 调用序列（pgrep/kill 是并发的采样与回收，不参与顺序断言）：
    # 第一条是构建任务（参数原样），最后一条是 --stop
    local first last
    first="$(grep '^gradlew ' "$CALL_LOG" | head -1)"
    last="$(grep '^gradlew ' "$CALL_LOG" | tail -1)"
    [[ "$first" == "$expected_first" ]] || fail "$label 首个 gradlew 调用=[$first] 期望=[$expected_first]"
    [[ "$last" == "gradlew --stop" ]] || fail "$label 最后 gradlew 调用=[$last] 期望=[gradlew --stop]"
    printf 'PASS %s\n' "$label"
}

# 1) 默认任务：构建 → --stop 收尾
run_case default-success 0 '' '' 'gradlew :app:assembleDebug --console=plain'
# 2) 显式任务：参数原样传给 gradlew
run_case explicit-task 0 '' 'test' 'gradlew test --console=plain'
# 3) 构建失败：退出码原样返回，收尾仍然执行
run_case build-failure 23 1 '' 'gradlew :app:assembleDebug --console=plain'

# 4) 全程不得调用 pkill
[[ ! -s "$PKILL_LOG" ]] || fail "cleanup 仍在调用 pkill（会打断其它工作区构建）：$(cat "$PKILL_LOG")"
# 5) pgrep 只允许按父子关系（-P <pid>）定位，不允许按名字/命令行（-f、裸名字）全局扫描
while IFS= read -r line; do
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^pgrep\ -P\ [0-9]+$ ]] || fail "仍按进程名/命令行全局扫描：[$line]"
done < <(grep -E '^pgrep' "$CALL_LOG" || true)
# 6) 源码静态断言（只看可执行行，注释里的举例不算）：不含 pkill、不按守护进程名字杀
CODE_LINES="$(grep -vE '^[[:space:]]*#' "$TEST_DIR/project/build_clean.sh")"
! grep -q 'pkill' <<< "$CODE_LINES" || fail "build_clean.sh 可执行行仍含 pkill"
! grep -qE 'KotlinCompileDaemon|GradleDaemon' <<< "$CODE_LINES" || fail "build_clean.sh 可执行行仍按进程名处理守护进程"
echo 'PASS no-global-kill'

# 7) 行为验证（真实 pgrep/kill）：本次构建起的「Kotlin 编译守护」被回收，
#    同时模拟的「别的工作区 GradleDaemon」必须完好——旧实现按名字杀会把它一起干掉。
BEHAVE_DIR="$TEST_DIR/behave"
mkdir -p "$BEHAVE_DIR"
cp "$PROJECT_DIR/build_clean.sh" "$BEHAVE_DIR/build_clean.sh"
# 行为验证要用真实的 pgrep/kill，撤掉替身目录
export PATH="${PATH#"$TEST_DIR/bin:"}"
cat > "$BEHAVE_DIR/gradlew" <<'GRADLE2'
#!/bin/bash
if [[ "$*" == *"--stop"* ]]; then exit 0; fi
# 模拟 Gradle 构建期间拉起的编译守护（本次构建的子进程）
sleep 300 &
printf '%s\n' "$!" > "$DAEMON_PID_FILE"
sleep 1
exit 0
GRADLE2
chmod +x "$BEHAVE_DIR/gradlew"
export DAEMON_PID_FILE="$TEST_DIR/daemon.pid"
# 另一工作区：命令行里同样含 GradleDaemon（旧实现会被 pkill -f 命中）
bash -c 'sleep 300' GradleDaemon &
OTHER_PID=$!
disown "$OTHER_PID" 2>/dev/null || true
behavior_status=0
(cd "$TEST_DIR/outside" && bash "$BEHAVE_DIR/build_clean.sh") > "$TEST_DIR/behave-output" 2>&1 || behavior_status=$?
[[ "$behavior_status" == 0 ]] || { cat "$TEST_DIR/behave-output"; fail "行为验证退出码=$behavior_status"; }
daemon_pid="$(cat "$DAEMON_PID_FILE" 2>/dev/null || true)"
[[ -n "$daemon_pid" ]] || fail "行为验证：假 gradlew 没记录到编译守护 PID"
sleep 0.5
if kill -0 "$daemon_pid" 2>/dev/null; then
    kill -KILL "$daemon_pid" 2>/dev/null || true
    kill -KILL "$OTHER_PID" 2>/dev/null || true
    fail "本次构建的编译守护（PID $daemon_pid）没有被回收"
fi
if ! kill -0 "$OTHER_PID" 2>/dev/null; then
    fail "别的工作区进程（模拟 GradleDaemon）被误杀"
fi
kill -KILL "$OTHER_PID" 2>/dev/null || true
echo 'PASS reaps-own-daemon-only'

echo 'build_clean.sh 回归全部通过'
