package com.kingzcheung.xime.util

/**
 * 预编辑文本合并工具
 *
 * 将 partial commit 累积文本与 RIME 返回的 displayText 进行智能拼接，
 * 通过最长后缀重叠检测避免重复字符。
 */
object PreeditMergeHelper {

    /**
     * 合并 partial commit 文本与 RIME 返回的 displayText。
     *
     * 检测 partialText 的最长后缀与 displayText 前缀的重叠部分，
     * 仅拼接非重叠部分，避免重复。
     *
     * @param partialTexts 累积的 partial commit 文本列表
     * @param displayText  RIME 返回的预编辑/输入文本
     * @return 合并后的完整显示文本
     */
    fun mergePartialCommitText(partialTexts: List<String>, displayText: String): String {
        if (partialTexts.isEmpty()) return displayText

        val partialText = partialTexts.joinToString("")
        if (partialText.isEmpty()) return displayText
        if (displayText.isEmpty()) return partialText
        if (displayText.startsWith(partialText)) return displayText

        // 寻找 partialText 最长后缀与 displayText 前缀的重叠部分
        var overlapLen = 0
        val maxOverlap = minOf(partialText.length, displayText.length)
        for (len in 1..maxOverlap) {
            if (partialText.endsWith(displayText.substring(0, len))) {
                overlapLen = len
            }
        }

        return if (overlapLen > 0) {
            partialText.substring(0, partialText.length - overlapLen) + displayText
        } else {
            partialText + displayText
        }
    }
}
