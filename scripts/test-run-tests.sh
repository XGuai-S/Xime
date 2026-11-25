#!/bin/bash
# 用替身 Gradle 验证入口行为，不执行 Android 构建或 clean。
set -euo pipefail
PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/xime-run-tests.XXXXXX")"
trap 'rm -rf -- "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/project with spaces" "$TEST_DIR/outside"
cp "$PROJECT_DIR/run_tests.sh" "$TEST_DIR/project with spaces/run_tests.sh"
cat > "$TEST_DIR/project with spaces/gradlew" <<'GRADLE'
#!/bin/bash
set -euo pipefail
printf '%s\n' "$*" >> "$CALL_LOG"
if [[ -n "${FAIL_TASK:-}" && "$*" == *"$FAIL_TASK"* ]]; then
    exit 23
fi
GRADLE
chmod +x "$TEST_DIR/project with spaces/gradlew"
export CALL_LOG="$TEST_DIR/calls"
run_case() {
    local mode="$1" fail_task="$2" expected_status="$3" expected_calls="$4" status=0 calls
    : > "$CALL_LOG"
    (cd "$TEST_DIR/outside" && FAIL_TASK="$fail_task" bash "$TEST_DIR/project with spaces/run_tests.sh" "$mode") > "$TEST_DIR/output" 2>&1 || status=$?
    [[ "$status" == "$expected_status" ]] || { cat "$TEST_DIR/output"; echo "错误退出码：$mode $status" >&2; exit 1; }
    calls="$(< "$CALL_LOG")"
    [[ "$calls" == "$expected_calls" ]] || { echo "调用序列错误：$mode [$calls]" >&2; exit 1; }
    if [[ "$status" != 0 && "$(< "$TEST_DIR/output")" == *"测试完成！"* ]]; then
        echo "失败被误报为成功：$mode" >&2
        exit 1
    fi
    printf 'PASS %s %s\n' "${mode:-no-argument}" "${fail_task:-success}"
}
UNIT=':app:testDebugUnitTest :plugin-core:testDebugUnitTest'
DEVICE=':app:connectedDebugAndroidTest'
COVERAGE=':app:createDebugCoverageReport'
run_case unit '' 0 "$UNIT"
run_case unit ':app:testDebugUnitTest' 23 "$UNIT"
run_case unit ':plugin-core:testDebugUnitTest' 23 "$UNIT"
run_case instrumented '' 0 "$DEVICE"
run_case instrumented "$DEVICE" 23 "$DEVICE"
run_case all '' 0 "$UNIT"$'\n'"$DEVICE"
run_case all ':plugin-core:testDebugUnitTest' 23 "$UNIT"
run_case all "$DEVICE" 23 "$UNIT"$'\n'"$DEVICE"
run_case coverage '' 0 "$COVERAGE"
run_case coverage "$COVERAGE" 23 "$COVERAGE"
run_case unknown '' 1 ''
run_case '' '' 1 ''
# 真正省略参数，与传空串分别验证。
status=0
(cd "$TEST_DIR/outside" && bash "$TEST_DIR/project with spaces/run_tests.sh") > "$TEST_DIR/output" 2>&1 || status=$?
[[ "$status" == 1 && "$(< "$TEST_DIR/output")" == *"使用方法:"* ]]
echo 'PASS omitted-argument'
