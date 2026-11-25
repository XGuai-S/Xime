#!/system/bin/sh
# Android 主机入口；需要当前已授权的 root/Shizuku 执行通道，不安装 APK。
set -eu
ENTRY=/data/local/tmp/xime-build-env/enter.sh
if [ "$(id -u)" != 0 ]; then
    echo "请通过已授权的 root/Shizuku 运行此脚本。" >&2
    exit 1
fi
if [ ! -r "$ENTRY" ]; then
    echo "构建环境入口不存在：$ENTRY" >&2
    exit 1
fi
for arg in "$@"; do
    case "$arg" in
        clean|*:clean|install*|*:install*|uninstall*|*:uninstall*|publish*|*:publish*)
            echo "此入口不执行清空、安装、卸载或发布任务：$arg" >&2
            exit 2 ;;
    esac
done
exec /system/bin/sh "$ENTRY" /bin/bash ./gradlew "$@"
