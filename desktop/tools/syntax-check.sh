#!/usr/bin/env bash
# 快速语法检查：只编译本项目与关键插件源码（-fsyntax-only），不链接 librime。
# 用于在慢机器上快速发现编译错误；完整链接交给 CI(MSVC)。
set -uo pipefail
cd "$(dirname "$0")/../.." || exit 1
JNI="app/src/main/jni"
BOOST_INC=$(ls -d "$JNI"/boost/libs/*/include | sed 's#^#-I#' | tr '\n' ' ')
COMMON="-std=gnu++17 -fsyntax-only -DBOOST_DLL_USE_STD_FS -DRIME_VERSION=\"1.18.0\" -DYAML_CPP_STATIC_DEFINE -D_CRT_SECURE_NO_WARNINGS"
INC="-I$JNI/boost/boost $BOOST_INC -I$JNI/librime/include -I$JNI/librime/src -I$JNI/librime/deps/yaml-cpp/include -I$JNI/librime/deps/marisa-trie/include -I$JNI/librime/deps/leveldb/include -I$JNI/librime/deps/opencc/src -Idesktop/build/librime/src -Idesktop/src -I$JNI/librime-t9/src"

FAIL=0
check() {
  local f="$1"; shift
  echo "=== $f ==="
  if g++ $COMMON "$@" $INC "$f" 2> /tmp/err.txt; then
    echo "OK"
  else
    echo "FAIL"; head -30 /tmp/err.txt; FAIL=1
  fi
}

# 本项目源码（-DUNICODE 供 windows 头文件）
check desktop/src/core/KeyMap.cpp -DUNICODE -D_UNICODE
check desktop/src/core/RimeEngine.cpp
check desktop/src/tsf/XimeTextService.cpp -DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN
check desktop/src/tsf/Dll.cpp -DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN
check desktop/src/ui/CandidateWindow.cpp -DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN
check desktop/tools/Register.cpp -DUNICODE -D_UNICODE -DWIN32_LEAN_AND_MEAN

# 关键定制插件
check "$JNI/librime/plugins/librime-octagram/src/gram_db.cc"
check "$JNI/librime/plugins/librime-octagram/src/gram_encoding.cc"
# T9 定制插件全部源文件
for f in "$JNI"/librime-t9/src/*.cc; do
  check "$f"
done

# librime 核心（确认安卓定制在非 Android 下仍可编译）
check "$JNI/librime/src/rime/gear/script_translator.cc"

exit $FAIL
