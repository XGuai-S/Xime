#!/bin/bash
# librime 1.17 vs 1.18 性能对比（Linux/WSL）
# 用法：准备更新前版本的 worktree 与构建，然后设 BEFORE_SRC / BEFORE_LIB / AFTER_SRC / AFTER_LIB
set -u
BEFORE_SRC="${BEFORE_SRC:-/root/librime-before}"
BEFORE_LIB="${BEFORE_LIB:-/root/rime-build-before/lib}"
AFTER_SRC="${AFTER_SRC:-/mnt/d/AIWork/Xime-3.4.09-source/app/src/main/jni/librime}"
AFTER_LIB="${AFTER_LIB:-/root/rime-build/lib}"
SHARE=/tmp/rime-bench-share
mkdir -p "$SHARE"
cp /root/rime-build/bin/*.yaml /root/rime-build/bin/*.txt "$SHARE/" 2>/dev/null || true

compile() { g++ -o "$3" "$1" -I"$2/src" -I"$2/include" -L"$4" -lrime -Wl,-rpath,"$4" 2>/dev/null; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
compile "$ROOT/tools/librime-bench.c" "$BEFORE_SRC" /tmp/bench_before "$BEFORE_LIB"
compile "$ROOT/tools/librime-bench.c" "$AFTER_SRC"  /tmp/bench_after  "$AFTER_LIB"

run() {
  echo "=== $1 (3 runs) ==="
  for i in 1 2 3; do
    rm -rf /tmp/rime-bench-user; mkdir -p /tmp/rime-bench-user
    timeout 120 "$2" /tmp/rime-bench-user 2>/dev/null
  done
}
run "BEFORE (1.17.0)" /tmp/bench_before
run "AFTER  (1.18.0)" /tmp/bench_after
