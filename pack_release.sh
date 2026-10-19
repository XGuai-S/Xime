#!/usr/bin/env bash
# ============================================================
# Xime Release 打包脚本（自适应环境）
# 用法: bash pack_release.sh [版本名] [debug]
#   - 不传版本名默认 3.2.2
#   - 第二个参数传 debug 时同时构建 debug 版
#
# 环境自适应：
#   - 输出目录: OUTPUT_DIR 环境变量可覆盖（默认 /sdcard/Download）
#     * 手机 proot:  OUTPUT_DIR=/sdcard/Download（默认，无需设置）
#     * x86 电脑:    OUTPUT_DIR=./output bash pack_release.sh
#   - 架构自适应: 检测 uname -m，arm64 要求 aapt2-qemu/clang-17/qemu 适配包；
#                 x86_64 跳过这些检查（用官方 aapt2 即可）
#   - 自动安装: 检测到 pm 命令（Android shell）才自动覆盖安装；
#               无法执行时自动导出手动安装命令供参考
# 产物: app/build/outputs/apk/{release,debug}/Xime-<ver>-arm64-v8a.apk
#       复制到 $OUTPUT_DIR/ 并做 MD5 校验（不再强制成功）
# ============================================================
set -u
cd "$(dirname "$0")"

# ---------- 版本 & debug 解析 ----------
VERSION="3.2.3"
[ $# -ge 1 ] && VERSION="$1"
BUILD_DEBUG=0
[ $# -ge 2 ] && [ "$2" = "debug" ] && BUILD_DEBUG=1

# ---------- 环境检测 ----------
START=$(date +%s)
FAILED=0
HOST_ARCH=$(uname -m 2>/dev/null || echo unknown)
IS_ARM64=0
case "$HOST_ARCH" in
    aarch64|arm64)   IS_ARM64=1 ;;
esac
OUTPUT_DIR="${OUTPUT_DIR:-/sdcard/Download}"

echo "==> [0/5] 环境自检 (arch=$HOST_ARCH, out=$OUTPUT_DIR)..."

# ---------- 环境自检 ----------
check() {
    local name="$1" cmd="$2"
    if command -v "$cmd" >/dev/null 2>&1; then
        echo "  [OK] $name: $(command -v $cmd)"
    else
        echo "  [MISS] $name: 未找到命令 '$cmd'"
        FAILED=1
    fi
}

# 1. 基础工具链
check "bash"      "bash"
check "java"      "java"
check "gradlew"   "./gradlew"
check "cmake"     "cmake"
check "md5sum"    "md5sum"

# 2. Android SDK（local.properties 应指向 sdk.dir）
if [ -f local.properties ]; then
    SDK_DIR=$(grep '^sdk.dir' local.properties | head -1 | cut -d= -f2 | tr -d '[:space:]')
    echo "  [OK] local.properties: sdk.dir=$SDK_DIR"
    if [ -n "$SDK_DIR" ] && [ ! -d "$SDK_DIR" ]; then
        echo "  [MISS] sdk.dir 目录不存在: $SDK_DIR"
        FAILED=1
    fi
else
    echo "  [MISS] local.properties 不存在（需 sdk.dir 指向 Android SDK）"
    FAILED=1
fi

# 3. 架构相关适配（仅 arm64 需要 qemu 包装）
if [ $IS_ARM64 -eq 1 ]; then
    echo "  [ARM64] 检测到 arm64 架构，检查 qemu 适配工具..."
    check "aapt2-qemu"  "aapt2-qemu"
    check "clang-17"    "clang-17"
    check "qemu-x86_64" "qemu-x86_64"
    check "qemu-aarch64" "qemu-aarch64"
else
    echo "  [INFO] $HOST_ARCH 非 arm64，跳过 qemu/aapt2-qemu 适配检查（用官方构建工具）"
fi

# 4. NDK（编译 native 用，local.properties 或环境变量指定）
NDK_DIR=""
if [ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ]; then
    NDK_DIR="$ANDROID_NDK_HOME"
elif [ -n "${NDK_HOME:-}" ] && [ -d "$NDK_HOME" ]; then
    NDK_DIR="$NDK_HOME"
elif [ -n "$SDK_DIR" ] && [ -d "$SDK_DIR/ndk" ]; then
    NDK_DIR=$(ls -d "$SDK_DIR"/ndk/* 2>/dev/null | head -1)
fi
if [ -n "$NDK_DIR" ]; then
    echo "  [OK] NDK: $NDK_DIR"
    export ANDROID_NDK_HOME="$NDK_DIR"
else
    echo "  [MISS] NDK 未找到（native 编译需要，位置: <sdk>/ndk/）"
    FAILED=1
fi

# 5. Rust 工具链（仅改插件需重新编译，警告不阻断；自动尝试注入 PATH）
if command -v cargo >/dev/null 2>&1; then
    echo "  [OK] cargo: $(command -v cargo) ($(cargo --version 2>/dev/null))"
else
    echo "  [WARN] cargo 未找到（仅在需重新编译插件时才需要，日常打包可跳过）"
    echo "        如需插件编译: export PATH=\$HOME/.cargo/bin:\$PATH"
    if [ -x "$HOME/.cargo/bin/cargo" ]; then
        export PATH="$HOME/.cargo/bin:$PATH"
        echo "  [OK] 已自动注入 PATH: $HOME/.cargo/bin"
    fi
fi

if [ $FAILED -ne 0 ]; then
    echo "!! 环境自检未通过，请先补齐缺失工具再打包"
    exit 1
fi
echo "==> 环境自检通过 ✅"

# ---------- 构建 ----------
TASKS="assembleRelease"
[ $BUILD_DEBUG -eq 1 ] && TASKS="assembleDebug $TASKS"

echo "==> [1/5] 构建: $TASKS"
./gradlew $TASKS \
    --offline \
    -x compileReleaseArtProfile \
    -x expandReleaseArtProfileWildcards \
    -x mergeReleaseArtProfile \
    --console=plain 2>&1 | tail -8

RC=${PIPESTATUS[0]}
if [ $RC -ne 0 ]; then
    echo "!! 构建失败 (exit=$RC)"
    exit $RC
fi

# ---------- 复制产物 ----------
echo "==> [2/5] 准备输出目录: $OUTPUT_DIR"
mkdir -p "$OUTPUT_DIR" 2>/dev/null || echo "  [WARN] 无法创建输出目录，产物将保留在 build 目录"

RELEASE_APK="app/build/outputs/apk/release/Xime-${VERSION}-arm64-v8a.apk"
if [ -f "$RELEASE_APK" ]; then
    if cp "$RELEASE_APK" "$OUTPUT_DIR/Xime-${VERSION}-arm64-v8a.apk" 2>/dev/null; then
        echo "==> [3/5] release 已复制: $OUTPUT_DIR/Xime-${VERSION}-arm64-v8a.apk"
    else
        echo "  [WARN] 复制 release 到输出目录失败，保留在: $RELEASE_APK"
    fi
else
    echo "!! 未找到 release APK: $RELEASE_APK"
    exit 1
fi

if [ $BUILD_DEBUG -eq 1 ]; then
    DEBUG_APK="app/build/outputs/apk/debug/Xime-${VERSION}-arm64-v8a.apk"
    if [ -f "$DEBUG_APK" ]; then
        if cp "$DEBUG_APK" "$OUTPUT_DIR/Xime-debug-${VERSION}-arm64-v8a.apk" 2>/dev/null; then
            echo "==> [4/5] debug 已复制: $OUTPUT_DIR/Xime-debug-${VERSION}-arm64-v8a.apk"
        else
            echo "  [WARN] 复制 debug 到输出目录失败，保留在: $DEBUG_APK"
        fi
    else
        echo "!! 未找到 debug APK: $DEBUG_APK"
    fi
fi

# ---------- MD5 校验（产物在 build 目录或输出目录均可校验）----------
echo "==> [MD5] 校验 release:"
if [ -f "$OUTPUT_DIR/Xime-${VERSION}-arm64-v8a.apk" ]; then
    md5sum "$OUTPUT_DIR/Xime-${VERSION}-arm64-v8a.apk" 2>/dev/null
else
    md5sum "$RELEASE_APK" 2>/dev/null
fi

# ---------- 自动安装 ----------
if command -v pm >/dev/null 2>&1; then
    echo "==> [5/5] 检测到 pm，自动覆盖安装 release 版..."
    cp "$OUTPUT_DIR/Xime-${VERSION}-arm64-v8a.apk" /data/local/tmp/xime-release.apk 2>/dev/null \
        && chmod 644 /data/local/tmp/xime-release.apk \
        && pm install -r /data/local/tmp/xime-release.apk 2>&1
    rm -f /data/local/tmp/xime-release.apk
else
    echo "==> [5/5] 当前环境无 pm（proot 容器/电脑），跳过自动安装"
    echo "        手动安装：adb install -r \"$OUTPUT_DIR/Xime-${VERSION}-arm64-v8a.apk\""
fi

END=$(date +%s)
echo "==> 完成 ✅ 总耗时 $((END-START))s"