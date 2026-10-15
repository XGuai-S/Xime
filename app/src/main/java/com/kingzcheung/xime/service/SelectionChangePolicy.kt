package com.kingzcheung.xime.service

/**
 * 判断光标/选区变化（onUpdateSelection）是否应打断待上屏组合态。
 *
 * 用户移动光标后，旧编码继续与新按键合并上屏会造成错位；应丢弃组合态，
 * 让下一键从新光标处重新开始。两类非用户移动的选区变化除外：
 * 选区未变化；光标仍在编辑器 composing 区域内（input-box 模式
 * setComposingText 自身引起的光标回调）。
 */
internal fun shouldBreakCompositionOnSelectionChange(
    oldSelStart: Int,
    oldSelEnd: Int,
    newSelStart: Int,
    newSelEnd: Int,
    candidatesStart: Int,
    candidatesEnd: Int,
): Boolean {
    if (oldSelStart == newSelStart && oldSelEnd == newSelEnd) return false
    // 无有效选区信息（宿主文本不可见等）时不打断
    if (newSelStart < 0 || newSelEnd < 0) return false
    // 光标仍在 composing 区域内：自身写入编码引起，非用户移动
    if (candidatesStart >= 0 && newSelStart >= candidatesStart && newSelEnd <= candidatesEnd) return false
    return true
}

/**
 * 丢弃输入框内的未上屏编码后应恢复的光标位置；null = 不删除编码文本。
 *
 * 编码在输入框里以 composing 区域存在（宿主不会在光标移出时定稿），
 * 借 setComposingText("") 可原地删除；但它会把光标带到编码起点，故需返回
 * 用户位置在删除后的等效坐标。拖动选区（不动文本）、无组合区、
 * 光标仍在编码内（策略已跳过，此处兜底）时返回 null。
 */
internal fun restoreCaretAfterInputBoxComposingRemoval(
    newSelStart: Int,
    newSelEnd: Int,
    candidatesStart: Int,
    candidatesEnd: Int,
): Int? {
    if (candidatesStart < 0 || candidatesEnd <= candidatesStart) return null
    if (newSelStart != newSelEnd) return null
    if (newSelStart > candidatesStart && newSelStart < candidatesEnd) return null
    return if (newSelStart >= candidatesEnd) {
        newSelStart - (candidatesEnd - candidatesStart)
    } else {
        newSelStart
    }
}
