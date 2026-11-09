#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3.2.97 第二步（只改 KeyboardLayout.kt 的横屏紧凑键）。"""
import io

P = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayout.kt'
s = io.open(P, encoding='utf-8').read()
orig = s


def rep(old, new):
    global s
    c = s.count(old)
    assert c == 1, 'count=%d for %r' % (c, old[:60])
    s = s.replace(old, new)


rep('''    BoxWithConstraints(
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
            .pointerInput(currentText, longPressItemsEffective.isNullOrEmpty()''')

rep('''.onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(LocalKeyVisualPadding.current)
            .then(shadowModifier)''',
    '''.onGloballyPositioned { coordinates ->
                buttonBounds = coordinates.boundsInRoot()
            }
            .padding(LocalKeyVisualPadding.current)
            .onSizeChanged { size ->
                val dp = with(density) { size.height.toDp().value }
                if (kotlin.math.abs(dp - measuredKeyHeightDp) > 0.5f) {
                    measuredKeyHeightDp = dp
                    KeyHeightCache.value = dp
                }
            }
            .then(shadowModifier)''')

rep('            keyHeightDp = maxHeight.value,', '            keyHeightDp = measuredKeyHeightDp,')

assert s != orig
with io.open(P, 'w', encoding='utf-8') as f:
    f.write(s)
print('KL patched, len', len(s))
