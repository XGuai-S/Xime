#!/bin/bash
# 构建内置插件 xipk 包（Rust CLI: tools/xime-plugin，内置 rolldown，无需 Node）
#
# 作用：把 ./plugins/<name> 的 TS 源码编译成单文件 IIFE，打包成 xipk，
#       并拷进 app/src/main/assets/plugins/，供 release APK 内置。
#
# 为什么必须有这一步：release APK 的内置插件来自 assets/plugins/*.xipk。
# 不跑本脚本就没有 xipk，release 装出来插件列表里不会出现内置插件
# （BuiltinPluginAssetsTest 守的就是这个坑）。
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="$PROJECT_DIR/tools/xime-plugin/target/release/xipm"

# 随包内置的插件清单（新增内置插件时加到这里）
BUNDLED_PLUGINS="webdav-backup"

# 1) 编译 CLI（首次或源码变更时；之后 cargo 会快速跳过）
if [ ! -x "$CLI" ]; then
    echo "→ 构建插件工具链 xipm ..."
    ( cd "$PROJECT_DIR/tools/xime-plugin" && cargo build --release )
fi

if [ ! -x "$CLI" ]; then
    echo "✗ 未找到 $CLI，构建失败" >&2
    exit 1
fi

# 2) 逐个打包
for name in $BUNDLED_PLUGINS; do
    src="$PROJECT_DIR/plugins/$name"
    if [ ! -d "$src" ]; then
        echo "✗ 缺少插件源码目录 plugins/$name" >&2
        exit 1
    fi
    echo "→ 打包 $name"
    "$CLI" pack "$src" --with-assets
done

echo ""
echo "=== 内置插件产物 ==="
ls -lh "$PROJECT_DIR/app/src/main/assets/plugins"/*.xipk
