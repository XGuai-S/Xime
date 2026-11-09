#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3.3.3 续：把 KeyboardLayoutScreen 彻底去 uiState 化。

- HandwritingLookupKeyboard / PanelKeyboardLayout 只用到 uiState 的一个字段，改成直接传字段
- T9KeyboardLayout 内部三层都在传整个 uiState，改造成本高；这里只在 T9 分支用
  稳定的 provider 取最新值（全键盘分支根本不调用它，因此不会订阅 uiState，
  键盘区才能被 Compose 跳过）
"""
import io

KL = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayoutScreen.kt'
HW = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/HandwritingLookupKeyboard.kt'
PANEL = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/PanelKeyboardLayout.kt'


def rw(path, pairs, count_check=None):
    s = io.open(path, encoding='utf-8').read()
    for old, new, n in pairs:
        c = s.count(old)
        assert c == n, '%s: count=%d (expect %d) for %r' % (path, c, n, old[:60])
        s = s.replace(old, new)
    if count_check:
        for token, n in count_check:
            assert s.count(token) == n, '%s: %r left %d' % (path, token, s.count(token))
    io.open(path, 'w', encoding='utf-8').write(s)
    print('patched', path)


# 1) HandwritingLookupKeyboard：uiState -> isFloatingMode
rw(HW, [
    ('    uiState: KeyboardUiState,\n', '    /** 悬浮模式下底部内边距取不同口径（原先从整个 uiState 里取）。 */\n    isFloatingMode: Boolean,\n', 1),
    ('uiState.isFloatingMode', 'isFloatingMode', 1),
])

# 2) PanelKeyboardLayout：uiState -> isAsciiMode
rw(PANEL, [
    ('    uiState: KeyboardUiState,\n', '    /** 面板按键的 ascii 态（原先从整个 uiState 里取）。 */\n    isAsciiMode: Boolean,\n', 1),
    ('uiState.isAsciiMode', 'isAsciiMode', 1),
])

# 3) KeyboardLayoutScreen：调用点 + T9 provider 参数
rw(KL, [
    # 三处手写键盘调用
    ('                        uiState = uiState,\n', '                        isFloatingMode = isFloatingMode,\n', 3),
    # 面板调用
    ('                    uiState = uiState,\n', '                    isAsciiMode = isAsciiMode,\n', 1),
    # T9 调用：走稳定 provider（只在 T9 分支求值，全键盘分支不读 State）
    ('                        uiState = uiState,\n', '                        uiState = t9UiState(),\n', 1),
    # 签名里加 provider 参数
    ('    capitalAmountExitSignal: Int,\n    viewModel: KeyboardViewModel,',
     '    capitalAmountExitSignal: Int,\n'
     '    /**\n'
     '     * T9 键盘要用整个 uiState（它内部三层都在传）。这里传一个实例恒定的 provider，\n'
     '     * 只在 T9 分支求值：全键盘分支不调用它，就不会订阅 uiState，键盘区才跳得动。\n'
     '     */\n'
     '    t9UiState: () -> KeyboardUiState,\n'
     '    viewModel: KeyboardViewModel,', 1),
], count_check=[('uiState = uiState', 0)])
