#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3.2.95 补充：横屏键长按直上屏后，抑制同一次按下的重复 onClick。"""
import io

P = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayout.kt'
s = io.open(P, encoding='utf-8').read()


def rep(old, new, n=1):
    global s
    c = s.count(old)
    assert c == n, 'count=%d for %r' % (c, old[:80])
    s = s.replace(old, new)


# 1) 状态变量：与竖屏渲染器同名的 hasTriggeredLongPress 语义（本文件横屏函数里没有）
rep(
    '''    val effectiveLongClick: (() -> Unit)? = if (longPressDirectActive) {
        currentOnLongClick ?: ({
            currentOnSwipe?.invoke(currentSwipeText ?: "")
            Unit
        })
    } else currentOnLongClick
''',
    '''    val effectiveLongClick: (() -> Unit)? = if (longPressDirectActive) {
        currentOnLongClick ?: ({
            currentOnSwipe?.invoke(currentSwipeText ?: "")
            Unit
        })
    } else currentOnLongClick
    // 长按已就地执行动作（上屏角标）——抬手时不能因为手指微小位移再补一次 onClick。
    var longPressDirectFired by remember { mutableStateOf(false) }
''',
)

# 2) 长按回调里置位
rep(
    '''                        onLongPress = effectiveLongClick?.let {
                            {
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                it()
                            }
                        },''',
    '''                        onLongPress = effectiveLongClick?.let { action ->
                            {
                                if (longPressDirectActive) longPressDirectFired = true
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                action()
                            }
                        },''',
)

# 3) onPress 里复位（下指即复位，不能放进 onDragStart：长按后移动才触发 drag）
rep(
    '''                        onPress = {
                            isPressed = true
                            currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentPressText), buttonBounds)
                            currentOnPress?.invoke()
                            tryAwaitRelease()''',
    '''                        onPress = {
                            isPressed = true
                            longPressDirectFired = false
                            currentOnSwipeStateChange?.invoke(SwipeState(isPressed = true, pressedText = currentPressText), buttonBounds)
                            currentOnPress?.invoke()
                            tryAwaitRelease()''',
)

# 4) 拖动收尾的点击判定排除「长按已执行」
rep(
    '''                                if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown &&
                                    !hasTriggeredSwipeLeft && !hasTriggeredSwipeRight) {
                                    onClick()
                                }''',
    '''                                if (!hasTriggeredSwipeUp && !hasTriggeredSwipeDown &&
                                    !hasTriggeredSwipeLeft && !hasTriggeredSwipeRight &&
                                    !longPressDirectFired) {
                                    onClick()
                                }''',
)

io.open(P, 'w', encoding='utf-8').write(s)
print('patched OK')
