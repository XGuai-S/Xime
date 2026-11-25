#!/usr/bin/env bash
set -euo pipefail

usage() {
    printf '%s\n' '用法：bash gradlew-ubuntu.sh <任务> [选项]，或单独使用 --version。' \
        '任务：:app:testDebugUnitTest :plugin-core:testDebugUnitTest' \
        '      :app:assembleDebug :app:assembleDebugAndroidTest help :help' \
        '选项：--tests 类名 --max-workers=N --no-daemon --console=plain --offline --quiet --stacktrace' \
        '将脚本放在项目根或 scripts/ 下，或设置绝对路径 XIME_PROJECT_ROOT。'
}

die() { printf '错误：%s\n' "$*" >&2; exit 1; }
reject() { printf '不允许的 Gradle 任务或参数：%q\n' "$1" >&2; usage >&2; exit 2; }

if (( $# == 0 )); then usage >&2; exit 2; fi
if (( $# == 1 )) && [[ $1 == -h || $1 == --help ]]; then usage; exit 0; fi
has_task=0
app_task=0
expect_test_pattern=0
test_pattern='^[A-Za-z_*][A-Za-z0-9_.$*]*$'
for arg in "$@"; do
    if (( expect_test_pattern )); then
        [[ $arg =~ $test_pattern ]] || reject "$arg"
        expect_test_pattern=0
        continue
    fi
    case "$arg" in
        :app:testDebugUnitTest|:app:assembleDebug|:app:assembleDebugAndroidTest)
            has_task=1
            app_task=1 ;;
        :plugin-core:testDebugUnitTest|help|:help) has_task=1 ;;
        --version) (( $# == 1 )) || reject "$arg"; has_task=1 ;;
        --tests) expect_test_pattern=1 ;;
        --no-configuration-cache) ;;
        --max-workers=*) [[ ${arg#*=} =~ ^[1-9][0-9]*$ ]] || reject "$arg" ;;
        --no-daemon|--console=plain|--offline|--quiet|--stacktrace) ;;
        *) reject "$arg" ;;
    esac
done
if (( ! has_task || expect_test_pattern )); then usage >&2; exit 2; fi

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# 只加载可信的本机 Bash 配置。函数作用域隔离配置中的位置参数。
load_env() {
    local env_file=${XIME_BUILD_ENV:-${HOME:?未设置 HOME}/.config/xime-build/env.sh}
    if [[ $env_file != /* ]]; then env_file=./$env_file; fi
    if [[ -e $env_file || -L $env_file ]]; then
        [[ -f $env_file && -r $env_file ]] || die "无法读取环境文件：$env_file"
        set -a
        source "$env_file"
        set +a
    fi
}
load_env

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-arm64}"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
export XIPM="${XIPM:-/opt/xime-tools/bin/xipm}"
export RUSTUP_AUTO_INSTALL=0
export PATH="${PATH:+$PATH:}/opt/xime-tools/bin${HOME:+:$HOME/.cargo/bin}"
[[ -x $JAVA_HOME/bin/java && -x $JAVA_HOME/bin/javac ]] || die "未找到 JDK 21，请安装或设置有效的 JAVA_HOME（当前：$JAVA_HOME）"
if ! java_version=$("$JAVA_HOME/bin/java" -version 2>&1); then
    printf '%s\n' "$java_version" >&2
    die "无法运行 $JAVA_HOME/bin/java"
fi
java21='version "21([.+-]|")'
[[ $java_version =~ $java21 ]] || die "需要 JDK 21，JAVA_HOME=$JAVA_HOME，实际输出：$java_version"

if [[ -n ${XIME_PROJECT_ROOT:-} ]]; then
    [[ $XIME_PROJECT_ROOT == /* ]] || die 'XIME_PROJECT_ROOT 必须是绝对路径'
    project_dir=$XIME_PROJECT_ROOT
elif [[ -f $script_dir/gradlew ]]; then
    project_dir=$script_dir
else
    project_dir=$script_dir/..
fi
[[ -r $project_dir/gradlew && -f $project_dir/settings.gradle.kts ]] || \
    die '找不到项目根。请从项目根或 scripts/ 放置此脚本，暂存时设置 XIME_PROJECT_ROOT。'
CDPATH= cd -- "$project_dir"

# AGP 用 local.properties 的 skipNative 关闭 CMake。
# 再排除 preBuild 中的原生依赖下载，避免覆盖预编译 ONNX .so。
fixed_args=()
if (( app_task )); then
    if ! awk '
        /^[ \t]*xime\.skipNative[ \t]*[:=]/ {
            sub(/^[^:=]*[:=][ \t]*/, ""); sub(/[ \t\r]+$/, ""); skip=tolower($0)
        }
        /^[ \t]*xime\.abi[ \t]*[:=]/ {
            sub(/^[^:=]*[:=][ \t]*/, ""); sub(/[ \t\r]+$/, ""); abi=$0
        }
        END { exit !(skip == "true" && abi == "arm64-v8a") }
    ' local.properties; then
        die '请确认 local.properties 已配置 xime.skipNative=true 和 xime.abi=arm64-v8a'
    fi
    native_dir=app/src/main/jniLibs/arm64-v8a
    for lib in libonnxruntime libandroidx.graphics.path libasr_jni libhandwriting_jni libonnx_env libonnx_jni libquickjs librime_jni; do
        [[ -r $native_dir/$lib.so && -s $native_dir/$lib.so ]] || die "缺少可读预编译库：$native_dir/$lib.so"
    done
    printf '%s\n' '提示：复用现有 ARM64 .so；跳过 ONNX/KNF 下载，不重编 native。'
    fixed_args=(-x :app:downloadOnnx -x :app:downloadKnf)
fi
if [[ $1 != --version ]]; then
    # 同时覆盖 AGP 从 JVM system property 和 Gradle project property 读取的配置。
    fixed_args+=(-Dandroid.builder.sdkDownload=false -Pandroid.builder.sdkDownload=false)
fi
exec bash ./gradlew "${fixed_args[@]}" "$@"
