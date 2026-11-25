package com.kingzcheung.xime.service

internal fun voiceUndoLength(
    expectedText: String,
    prefixAnchor: String,
    currentTextBeforeCursor: String?,
    sameConnection: Boolean,
): Int {
    if (!sameConnection || expectedText.isEmpty() || currentTextBeforeCursor == null) return 0
    return if (currentTextBeforeCursor == prefixAnchor + expectedText) expectedText.length else 0
}
