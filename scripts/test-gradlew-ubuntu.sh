#!/usr/bin/env bash
set -euo pipefail

# Override XIME_TEST_PROJECT_ROOT if the checkout is not at its default path.
script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
project_source=${XIME_TEST_PROJECT_ROOT:-$script_dir/..}
wrapper_source=${XIME_GRADLEW_WRAPPER:-$project_source/scripts/gradlew-ubuntu.sh}
if [[ ! -r $wrapper_source ]]; then
    printf '找不到待测 wrapper：%s\n设置 XIME_GRADLEW_WRAPPER 或 XIME_TEST_PROJECT_ROOT。\n' "$wrapper_source" >&2
    exit 1
fi

test_root=$(mktemp -d /tmp/xime-gradle-wrapper-test.XXXXXX)
case "$test_root" in
    /tmp/xime-gradle-wrapper-test.*) ;;
    *) printf '拒绝使用意外临时目录：%s\n' "$test_root" >&2; exit 1 ;;
esac
if [[ $test_root != /* || ! -d $test_root || -L $test_root ]]; then
    printf '临时目录校验失败：%s\n' "$test_root" >&2
    exit 1
fi
cleanup() {
    case "$test_root" in
        /tmp/xime-gradle-wrapper-test.*)
            if [[ $test_root == /* && -d $test_root && ! -L $test_root ]]; then
                rm -rf -- "${test_root:?}"
            else
                printf '拒绝清理未通过校验的临时目录：%s\n' "$test_root" >&2
                return 1
            fi ;;
        *) printf '拒绝清理意外路径：%s\n' "$test_root" >&2; return 1 ;;
    esac
}
trap cleanup EXIT

fixture="$test_root/fixture project with spaces"
outside="$test_root/external working directory with spaces"
fake_jdk="$test_root/fake JDK 21"
wrapper="$fixture/scripts/gradlew-ubuntu.sh"
argv_file="$test_root/gradle-argv.nul"
run_output="$test_root/wrapper-output.txt"
mkdir -p -- "$fixture/scripts" "$fixture/app/src/main/jniLibs/arm64-v8a" \
    "$outside" "$fake_jdk/bin"
cp -- "$wrapper_source" "$wrapper"
printf 'rootProject.name = "fixture"\n' > "$fixture/settings.gradle.kts"
printf 'xime.skipNative=true\nxime.abi=arm64-v8a\n' > "$fixture/local.properties"

cat > "$fake_jdk/bin/java" <<'JAVA'
#!/usr/bin/env bash
if [[ ${1:-} == -version ]]; then
    printf 'openjdk version "21.0.1" 2025-01-21\n' >&2
    exit 0
fi
exit 0
JAVA
cat > "$fake_jdk/bin/javac" <<'JAVAC'
#!/usr/bin/env bash
exit 0
JAVAC
cat > "$fixture/gradlew" <<'GRADLE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\0' "$@" > "${FAKE_GRADLE_ARGV:?}"
exit "${FAKE_GRADLE_EXIT_CODE:-0}"
GRADLE
chmod +x "$fake_jdk/bin/java" "$fake_jdk/bin/javac" "$fixture/gradlew"

native_dir="$fixture/app/src/main/jniLibs/arm64-v8a"
for lib in libonnxruntime libandroidx.graphics.path libasr_jni libhandwriting_jni \
    libonnx_env libonnx_jni libquickjs librime_jni; do
    printf 'fake-so\n' > "$native_dir/$lib.so"
done
: > "$argv_file"
run_status=0
fake_gradle_exit=0
run_wrapper() {
    : > "$argv_file"
    run_status=0
    if (cd -- "$outside" && env \
        JAVA_HOME="$fake_jdk" \
        ANDROID_HOME="$test_root/fake android sdk" \
        ANDROID_SDK_ROOT="$test_root/fake android sdk" \
        XIME_BUILD_ENV="$test_root/no-env-file" \
        XIPM="$test_root/no-xipm" \
        FAKE_GRADLE_ARGV="$argv_file" \
        FAKE_GRADLE_EXIT_CODE="$fake_gradle_exit" \
        bash "$wrapper" "$@") > "$run_output" 2>&1; then
        run_status=0
    else
        run_status=$?
    fi
}
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }
assert_rejected() {
    local label=$1 expected=${2:-2}
    shift 2
    run_wrapper "$@"
    [[ $run_status -eq $expected ]] || fail "$label：预期退出 $expected，实际 $run_status；$(<"$run_output")"
    [[ ! -s $argv_file ]] || fail "$label：错误调用了假 Gradle"
    pass "$label"
}
read_gradle_args() {
    recorded_args=()
    mapfile -d '' -t recorded_args < "$argv_file"
    (( ${#recorded_args[@]} > 0 )) || fail '假 Gradle 未记录参数'
}
assert_exact_args() {
    local label=$1
    shift
    local -a expected=("$@")
    read_gradle_args
    [[ ${#recorded_args[@]} -eq ${#expected[@]} ]] || \
        fail "$label：参数数量不符（${#recorded_args[@]} != ${#expected[@]}）"
    local i
    for (( i=0; i<${#expected[@]}; i++ )); do
        [[ ${recorded_args[i]} == "${expected[i]}" ]] || \
            fail "$label：参数 $i 不符（${recorded_args[i]} != ${expected[i]}）"
    done
    pass "$label（从含空格的外部目录调用）"
}

# 1–9: 安全门和 --tests 输入验证。
assert_rejected '无参拒绝' 2
assert_rejected 'clean 拒绝' 2 clean
assert_rejected '危险任务缩写拒绝' 2 :app:clea
assert_rejected 'install 任务拒绝' 2 installDebug
assert_rejected 'uninstall 任务拒绝' 2 :app:uninstallDebug
assert_rejected 'publish 任务拒绝' 2 publishToMavenLocal
assert_rejected '未知参数拒绝' 2 :app:testDebugUnitTest --unknown-option
assert_rejected '缺失 --tests 参数拒绝' 2 :app:testDebugUnitTest --tests
assert_rejected '不能把选项当 --tests 过滤器' 2 :app:testDebugUnitTest --tests --offline

# 10: 安全任务、测试过滤器及 wrapper 固定选项逐项原样转发。
pattern='com.example.Foo$Inner*'
run_wrapper :app:testDebugUnitTest --tests "$pattern" --max-workers=2 --no-configuration-cache --console=plain
[[ $run_status -eq 0 ]] || fail "安全参数转发失败：$(<"$run_output")"
assert_exact_args '安全参数与含 $ 通配类名原样转发' \
    -x :app:downloadOnnx -x :app:downloadKnf \
    -Dandroid.builder.sdkDownload=false -Pandroid.builder.sdkDownload=false \
    :app:testDebugUnitTest --tests "$pattern" --max-workers=2 --no-configuration-cache --console=plain

# 11: 缺失预编译 native 库必须在 Gradle 启动前失败。
missing_so="$native_dir/librime_jni.so"
printf 'fake-so\n' > "$missing_so"
: > "$missing_so"
run_wrapper :app:assembleDebug
[[ $run_status -eq 1 ]] || fail "native 缺失应退出 1，实际 $run_status"
[[ ! -s $argv_file ]] || fail 'native 库缺失时仍调用了假 Gradle'
printf 'fake-so\n' > "$missing_so"
pass 'native 库缺失时快速失败'

# 12: Gradle 的退出码不应被 wrapper 吞掉或改写。
run_wrapper :plugin-core:testDebugUnitTest
[[ $run_status -eq 0 ]] || fail '退出码回归的前置安全任务未运行'
read_gradle_args
fake_gradle_exit=37
run_wrapper :plugin-core:testDebugUnitTest
[[ $run_status -eq 37 ]] || fail "Gradle 退出码 37 未保留，实际 $run_status"
pass 'Gradle 退出码 37 原样保留'

printf '全部 12 项 wrapper 回归通过。\n'
