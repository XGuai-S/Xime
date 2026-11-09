#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3.2.95：把「长按直接上屏角标」扩展到横屏紧凑键 SwipeableKeyButtonLandscape。"""
import io

P = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayout.kt'
s = io.open(P, encoding='utf-8').read()


def rep(old, new, n=1):
    global s
    c = s.count(old)
    assert c == n, 'count=%d for %r' % (c, old[:80])
    s = s.replace(old, new)


# 1) import
rep(
    'import com.kingzcheung.xime.settings.KeysConfigHelper\n',
    'import com.kingzcheung.xime.settings.KeysConfigHelper\n'
    'import com.kingzcheung.xime.settings.LongPressDirectCommitState\n',
)

# 2) 在 SwipeableKeyButtonLandscape 内插入长按直上屏判定
anchor = ('    val bubbleShowThresholdUp = swipeUpThreshold * KeyGestureThresholds.BUBBLE_SHOW_RATIO\n'
          '    val bubbleShowThresholdDown = swipeDownThreshold * KeyGestureThresholds.BUBBLE_SHOW_RATIO\n')
rep(anchor, anchor + '''
    // 长按「直接上屏角标」：语义与竖屏渲染器 SwipeableKeyButton 完全一致。
    // 横屏紧凑键此前没接这套逻辑，开着开关长按字母键仍会弹气泡，两套渲染器行为不一致。
    val longPressContext = LocalContext.current
    LongPressDirectCommitState.ensureInitialized(longPressContext)
    // 读 Compose 状态：设置页一关开关，键盘当场按新值走（不必重建键盘）
    val longPressDirectCommit = LongPressDirectCommitState.enabled.value
    // 「角标」= 键面右上角画着的提示字符（上滑目标）
    val cornerBadgeText: String? = (swipeUpKeyLabel ?: swipeText)?.takeIf { it.isNotEmpty() }
    val longPressDirectActive = LongPressDirectPolicy.shouldCommitBadge(
        enabled = longPressDirectCommit,
        hasBadge = cornerBadgeText != null,
        hasSwipeHandler = currentOnSwipe != null,
    )
    val longPressItemsEffective = if (longPressDirectActive) null else currentLongPressItems
    // 长按 = 执行上滑动作；特殊长按行为（如 z 键反查）优先，不被角标覆盖
    val effectiveLongClick: (() -> Unit)? = if (longPressDirectActive) {
        currentOnLongClick ?: ({
            currentOnSwipe?.invoke(currentSwipeText ?: "")
            Unit
        })
    } else currentOnLongClick
''')

# 3) 手势 pointerInput 的 key 与分支条件改用生效值
rep(
    '            .pointerInput(currentText, currentLongPressItems.isNullOrEmpty(), '
    'currentOnLongPressSelect != null, currentOnLongClick != null) {\n'
    '                if (currentLongPressItems.isNullOrEmpty() || currentOnLongPressSelect == null) {\n',
    '            .pointerInput(currentText, longPressItemsEffective.isNullOrEmpty(), '
    'currentOnLongPressSelect != null, effectiveLongClick != null) {\n'
    '                if (longPressItemsEffective.isNullOrEmpty() || currentOnLongPressSelect == null) {\n',
)

# 4) 非气泡分支的长按回调改用生效值
rep(
    '                        onLongPress = currentOnLongClick?.let {\n',
    '                        onLongPress = effectiveLongClick?.let {\n',
)

# 5) 气泡擦作分支里的 items 改用生效值
rep(
    '                        val items = currentLongPressItems ?: return@awaitEachGesture\n',
    '                        val items = longPressItemsEffective ?: return@awaitEachGesture\n',
)

io.open(P, 'w', encoding='utf-8').write(s)
print('patched OK')
