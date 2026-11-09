#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3.2.97 渲染性能第一步：去掉每个按键的 BoxWithConstraints（SubcomposeLayout）。

背景：SwipeableKeyButton / SwipeableKeyButtonLandscape 各自包一层 BoxWithConstraints，
只为拿 maxHeight 算键帽自适应字号。SubcomposeLayout 每次测量都会 subcompose 一次内容，
一个主键盘 30~40 个键就是 30~40 次/帧。改成普通 Box + onSizeChanged 自测高度：
高度值在尺寸变化时更新一次，正常打字期间不再有任何测量期 subcompose。

首帧高度取进程内缓存（上次测到的键高），首次弹出可能偏一帧，之后稳定。
"""
import io

KB = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyButton.kt'
KL = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayout.kt'


def load(p):
    return io.open(p, encoding='utf-8').read()


def save(p, s):
    io.open(p, 'w', encoding='utf-8').write(s)


def rep(s, old, new, n=1):
    c = s.count(old)
    assert c == n, 'count=%d (expect %d) for %r' % (c, n, old[:70])
    return s.replace(old, new)


# ────────────────────────── KeyButton.kt ──────────────────────────
s = load(KB)

# 1) 进程内键高缓存（只用于首帧估算，之后每个键记住自己实测的高度）
rep(
    s,
    'val LocalKeyVisualPadding = staticCompositionLocalOf {',
    '''/**
 * 键高进程内缓存（dp）。
 *
 * 键帽自适应字号需要「本键可用高度」，原来靠 BoxWithConstraints 拿 maxHeight——那是
 * SubcomposeLayout，每次测量都会 subcompose 一次内容，几十个键就是几十次/帧。
 * 现在改为按键自身 onSizeChanged 实测（尺寸变化才更新），首帧用这里缓存的上一次值，
 * 避免键盘弹出时字号有明显跳变。
 */
internal object KeyHeightCache {
    var value: Float = 44f
}

val LocalKeyVisualPadding = staticCompositionLocalOf {''',
)

# 2) 状态变量 + 容器换成普通 Box（SwipeableKeyButton 唯一一处 BoxWithConstraints）
rep(
    s,
    '''    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(''',
    '''    // 本键可用高度（dp）：由下方 onSizeChanged 实测，取代原来的 BoxWithConstraints
    var measuredKeyHeightDp by remember { mutableStateOf(KeyHeightCache.value) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures(''',
)

# 3) 在 padding 之后实测内容区高度（与原来 maxHeight 的口径一致：padding 扣掉之后）
seg_old = ('.onGloballyPositioned { coordinates ->\n'
           '                buttonBounds = coordinates.boundsInRoot()\n'
           '            }\n'
           '            .padding(LocalKeyVisualPadding.current)\n'
           '            .then(shadowModifier)')
seg_new = ('.onGloballyPositioned { coordinates ->\n'
           '                buttonBounds = coordinates.boundsInRoot()\n'
           '            }\n'
           '            .padding(LocalKeyVisualPadding.current)\n'
           '            // 实测键内容区高度（padding 之前量是键格高，这里量的是可用内容高，\n'
           '            // 与原 BoxWithConstraints 的 maxHeight 同口径）；相等值不会触发重组\n'
           '            .onSizeChanged { size ->\n'
           '                val dp = with(density) { size.height.toDp().value }\n'
           '                if (kotlin.math.abs(dp - measuredKeyHeightDp) > 0.5f) {\n'
           '                    measuredKeyHeightDp = dp\n'
           '                    KeyHeightCache.value = dp\n'
           '                }\n'
           '            }\n'
           '            .then(shadowModifier)')
i = s.index(seg_old)  # 只改 SwipeableKeyButton 这一处（第一处）
s = s[:i] + seg_new + s[i + len(seg_old):]

# 4) 字号改用自己的实测高度
rep(s, 'val contentScale = adaptiveKeyContentScale(maxHeight.value)',
    'val contentScale = adaptiveKeyContentScale(measuredKeyHeightDp)')

save(KB, s)

# ────────────────────── KeyboardLayout.kt（横屏紧凑键）──────────────────────
s = load(KL)

rep(
    s,
    '''    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(currentText, longPressItemsEffective.isNullOrEmpty()''',
    '''    // 本键可用高度（dp）：同竖屏渲染器，改为自身实测，去掉 SubcomposeLayout
    var measuredKeyHeightDp by remember { mutableStateOf(KeyHeightCache.value) }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .pointerInput(currentText, longPressItemsEffective.isNullOrEmpty()''',
)

rep(s,
    ('.onGloballyPositioned { coordinates ->\n'
     '                buttonBounds = coordinates.boundsInRoot()\n'
     '            }\n'
     '            .padding(LocalKeyVisualPadding.current)\n'
     '            .then(shadowModifier)'),
    ('.onGloballyPositioned { coordinates ->\n'
     '                buttonBounds = coordinates.boundsInRoot()\n'
     '            }\n'
     '            .padding(LocalKeyVisualPadding.current)\n'
     '            .onSizeChanged { size ->\n'
     '                val dp = with(density) { size.height.toDp().value }\n'
     '                if (kotlin.math.abs(dp - measuredKeyHeightDp) > 0.5f) {\n'
     '                    measuredKeyHeightDp = dp\n'
     '                    KeyHeightCache.value = dp\n'
     '                }\n'
     '            }\n'
     '            .then(shadowModifier)'))

rep(s, '            keyHeightDp = maxHeight.value,', '            keyHeightDp = measuredKeyHeightDp,')

save(KL, s)
print('patched OK')
