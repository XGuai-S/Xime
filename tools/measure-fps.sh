#!/system/bin/sh
# Xime 打字帧率测量脚本（在设备上以 root 运行：sh /data/local/tmp/measure-fps.sh）
#
# 用法：
#   sh measure-fps.sh                 # 默认 40 次点按
#   sh measure-fps.sh 80              # 自定义次数
#   sh measure-fps.sh 40 warm         # 先等 20s 让词库加载完再测（推荐）
#
# 前置条件：键盘必须已经弹出（先在任意输入框点一下调出 Xime）。
# 若键盘未显示，脚本会直接报错退出，避免测到 0 帧。
#
# 说明：
# - 用 `input tap` 走真实触摸通道，能触发完整的「按键→Rime→候选→绘制」链路；
# - 点按区域固定在键盘字母区（屏幕下部 1750~2150px），避开空格/回车/删除；
# - 只统计 gfxinfo 的渲染帧，不包含键盘弹出动画（所以要先让键盘显示好再测）。

PKG=com.kingzcheung.xime
COUNT=${1:-40}
WARM=${2:-}

# 1. 检查键盘是否已显示
SHOWN=$(dumpsys input_method 2>/dev/null | grep -m1 "mInputShown=" | tr -d ' ')
if [ "$SHOWN" != "mInputShown=true" ]; then
  echo "错误：键盘未显示（$SHOWN）。请先在任意输入框点击调出 Xime 键盘后再运行。" >&2
  echo "（若确认键盘已显示仍报错，可加 --force 跳过本检查）" >&2
  case " $* " in *" --force "*) echo "已指定 --force，继续执行。" ;; *) exit 1 ;; esac
fi

PID=$(pidof $PKG)
if [ -z "$PID" ]; then
  echo "错误：$PKG 进程不存在。" >&2
  exit 1
fi

# 2. 预热：等词库加载稳定（首次安装/刚重启后必须）
if [ "$WARM" = "warm" ]; then
  echo "预热 20 秒（等词库加载完成）..."
  sleep 20
fi

# 3. 重置统计
dumpsys gfxinfo $PKG reset >/dev/null 2>&1
sleep 1

# 4. 连续点按
echo "开始测量：$COUNT 次点按..."
i=0
while [ $i -lt $COUNT ]; do
  X=$((120 + (RANDOM % 780)))
  Y=$((1750 + (RANDOM % 400)))
  input tap $X $Y
  sleep 0.1
  i=$((i + 1))
done
sleep 1

# 5. 输出结果
echo ""
echo "===== 帧率统计（$PKG, pid=$PID）====="
dumpsys gfxinfo $PKG 2>/dev/null | sed -n '/Total frames rendered/,/Number Frame deadline missed (legacy)/p'

echo ""
echo "===== 帧耗时分布（ms=帧数）====="
dumpsys gfxinfo $PKG 2>/dev/null | grep "HISTOGRAM" | head -1 | sed 's/HISTOGRAM: //' | tr ' ' '\n' | grep -v '=0$' | head -20

echo ""
echo "解读要点："
echo "  - 50th percentile 应 < 16ms（60Hz 一帧预算）"
echo "  - 90th percentile 应 < 16ms，< 20ms 可接受"
echo "  - Janky frames 占比 < 5% 为优秀，< 10% 为良好"
echo "  - Number Missed Vsync 应为 0"
