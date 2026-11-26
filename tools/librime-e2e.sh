#!/bin/bash
# librime 1.18.0 端到端测试（Linux/WSL）：真实运行 librime，部署 schema、输入拼音、打印候选
# 前置：先用 tools/librime-host-test.sh 构建出 librime（BUILD_SHARED_LIBS=ON）
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${LIME_BUILD_DIR:-/root/rime-build}"
SRC="$ROOT/app/src/main/jni/librime"
E2ED="${LIME_E2E_DIR:-/root/rime-e2e}"
SHARE="$E2ED/share/rime"; USERD="$E2ED/user_data"; LOG="$E2ED/log"
mkdir -p "$SHARE" "$USERD" "$LOG"
cp "$BUILD"/bin/*.yaml "$BUILD"/bin/*.txt "$SHARE/" 2>/dev/null || true

echo "==> 编译 e2e 测试程序"
g++ -o "$E2ED/e2e" "$ROOT/tools/librime-e2e.c" \
  -I"$SRC/src" -I"$SRC/include" -L"$BUILD/lib" -lrime -Wl,-rpath,"$BUILD/lib" || exit 2

echo "==> 运行（部署 + 输入拼音 zhang）"
cd "$E2ED" && timeout 120 ./e2e 2>&1 | grep -v -E '\[msg\]|Error opening db' | head -40
