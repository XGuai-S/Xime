#!/usr/bin/env bash
set -euo pipefail

ok() { printf 'OK   %s\n' "$*"; }
warn() { printf '提示 %s\n' "$*"; }
fail() { printf '失败 %s\n' "$*" >&2; status=1; }
status=0
script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)

load_env() {
    local env_file=${XIME_BUILD_ENV:-${HOME:?未设置 HOME}/.config/xime-build/env.sh}
    if [[ $env_file != /* ]]; then env_file=./$env_file; fi
    if [[ -e $env_file || -L $env_file ]]; then
        if [[ ! -f $env_file || ! -r $env_file ]]; then
            fail "无法读取环境文件：$env_file"
            exit 1
        fi
        set -a
        source "$env_file"
        set +a
    fi
}
load_env
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export PATH="${PATH:+$PATH:}/opt/xime-tools/bin${HOME:+:$HOME/.cargo/bin}"
export RUSTUP_AUTO_INSTALL=0

if [[ -n ${XIME_PROJECT_ROOT:-} ]]; then
    if [[ $XIME_PROJECT_ROOT != /* ]]; then fail 'XIME_PROJECT_ROOT 必须是绝对路径'; exit 1; fi
    project_dir=$XIME_PROJECT_ROOT
elif [[ -f $script_dir/gradlew ]]; then
    project_dir=$script_dir
else
    project_dir=$script_dir/..
fi
if [[ ! -r $project_dir/gradlew || ! -f $project_dir/settings.gradle.kts ]]; then
    fail '找不到项目根。请把脚本放在项目根或 scripts/ 下，暂存时设置 XIME_PROJECT_ROOT。'
    exit 1
fi
CDPATH= cd -- "$project_dir"
printf '主机架构 %s，Ubuntu 环境 UID %s（proot 中的 UID 0 不代表 Android root）\n' "$(uname -m)" "$EUID"
printf 'JAVA_HOME=%s\nANDROID_HOME=%s\n' "$JAVA_HOME" "$ANDROID_HOME"

java21='version "21([.+-]|")'
if [[ ! -x $JAVA_HOME/bin/java || ! -x $JAVA_HOME/bin/javac ]]; then
    fail 'JAVA_HOME 下缺少可执行的 java 或 javac，请配置 JDK 21'
elif java_version=$("$JAVA_HOME/bin/java" -version 2>&1) && [[ $java_version =~ $java21 ]]; then
    ok "Java $java_version"
else
    fail "不能运行 JDK 21：$java_version"
fi

# 部分版本的 aidl 与 zipalign 显示自身用法后返回 1 或 2。
probe() {
    local label=$1 usage_marker=$2 output rc=0
    shift 2
    output=$("$@" 2>&1) || rc=$?
    if (( rc == 0 )) || { (( rc == 1 || rc == 2 )) &&
        [[ -n $usage_marker && ${output,,} == *"$usage_marker"* ]]; }; then
        ok "$label ($1) ${output%%$'\n'*}"
    else
        warn "$label ($1) 退出码 $rc，${output%%$'\n'*}"
        return 1
    fi
}

sdk_jar=$ANDROID_HOME/platforms/android-37.2/android.jar
if [[ -f $sdk_jar && -r $sdk_jar && -s $sdk_jar ]]; then ok "$sdk_jar"; else fail "缺少可读的 $sdk_jar"; fi
build_tools=$ANDROID_HOME/build-tools/36.0.0
probe aapt2 '' "$build_tools/aapt2" version || fail 'Build Tools 36.0.0 的 aapt2 无法运行'
probe aidl 'usage: aidl' "$build_tools/aidl" --help || fail 'Build Tools 36.0.0 的 aidl 无法运行'
probe zipalign 'usage: zipalign' "$build_tools/zipalign" -h || fail 'Build Tools 36.0.0 的 zipalign 无法运行'
if grep -Eq '^distributionUrl=.*gradle-9\.8\.1-bin\.zip[[:space:]]*$' gradle/wrapper/gradle-wrapper.properties; then
    ok 'Gradle Wrapper 指向 9.8.1'
else
    fail 'Gradle Wrapper 未指向 Gradle 9.8.1 bin 包'
fi

xipm=${XIPM:-}
if [[ ! -f $xipm ]]; then xipm=$(type -P xipm || :); fi
if [[ -z $xipm ]]; then xipm=./tools/xime-plugin/target/release/xipm; fi
xipm_ok=0 cargo_ok=0 rustc_ok=0
probe xipm '' "$xipm" --version && xipm_ok=1
probe cargo '' cargo --version && cargo_ok=1
rustc_version=$(rustc --version 2>&1) || rustc_version=
if [[ $rustc_version =~ ^rustc\ 1\.100([.]|$) ]]; then ok "$rustc_version"; rustc_ok=1; else warn "Rust 1.100 未就绪：${rustc_version:-rustc 不可运行}"; fi
if (( ! xipm_ok && (! cargo_ok || ! rustc_ok) )); then
    fail '插件单测需要可运行的 xipm，或 cargo 与 rustc（目标 Rust 1.100）'
elif (( ! xipm_ok )); then
    warn 'Gradle 插件单测将尝试用 cargo 构建 xipm，请确认 Rust 工具链版本为 1.100'
fi

jni_dir=app/src/main/jniLibs/arm64-v8a
for lib in libonnxruntime libandroidx.graphics.path libasr_jni libhandwriting_jni libonnx_env libonnx_jni libquickjs librime_jni; do
    so=$jni_dir/$lib.so
    if [[ -f $so && -r $so && -s $so ]]; then ok "$so"; else fail "缺少可读的 $so"; fi
done
exit "$status"
