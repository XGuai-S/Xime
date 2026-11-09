#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""3.3.3：KeyboardLayoutScreen 参数最小化。

它原来收整个 KeyboardUiState（data class，每次按键都是新实例），于是每次击键
整棵键盘树都要重组一遍。实际只用到 11 个字段，改成就传字段后，打字期间
（主题/模式/编码态都没变）参数相等，Compose 才能跳过这次重组。
"""
import io

P = '/root/xime/app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardLayoutScreen.kt'
s = io.open(P, encoding='utf-8').read()

# 1) 签名
old_sig = '''fun KeyboardLayoutScreen(
    keyboardState: KeyboardLayoutState,
    uiState: KeyboardUiState,
    viewModel: KeyboardViewModel,'''
new_sig = '''fun KeyboardLayoutScreen(
    keyboardState: KeyboardLayoutState,
    // 参数最小化：这里原来收整个 KeyboardUiState（data class，每次击键都是新实例），
    // 导致整棵键盘树每次击键都重组。实际只用到下面这些字段，传字段后打字期间
    // 参数相等，Compose 才能跳过重组（见 3.3.3 的帧时间对比）。
    themeId: String,
    isDarkTheme: Boolean,
    isAsciiMode: Boolean,
    schemaName: String,
    enterKeyText: String,
    isSttEnabled: Boolean,
    isVoiceMode: Boolean,
    isVoiceSticky: Boolean,
    isFloatingMode: Boolean,
    swipeCancelEpoch: Int,
    capitalAmountExitSignal: Int,
    viewModel: KeyboardViewModel,'''
assert s.count(old_sig) == 1
s = s.replace(old_sig, new_sig)

# 2) 体内引用
pairs = [
    ('uiState.themeId', 'themeId'),
    ('uiState.isDarkTheme', 'isDarkTheme'),
    ('uiState.isAsciiMode', 'isAsciiMode'),
    ('uiState.schemaName', 'schemaName'),
    ('uiState.enterKeyText', 'enterKeyText'),
    ('uiState.isSttEnabled', 'isSttEnabled'),
    ('uiState.isVoiceMode', 'isVoiceMode'),
    ('uiState.voiceSticky', 'isVoiceSticky'),
    ('uiState.isFloatingMode', 'isFloatingMode'),
    ('uiState.swipeCancelEpoch', 'swipeCancelEpoch'),
    ('uiState.capitalAmountExitSignal', 'capitalAmountExitSignal'),
]
for old, new in pairs:
    s = s.replace(old, new)
assert 'uiState.' not in s, 'still has uiState references'

io.open(P, 'w', encoding='utf-8').write(s)
print('KeyboardLayoutScreen patched; uiState refs left =', s.count('uiState'))
