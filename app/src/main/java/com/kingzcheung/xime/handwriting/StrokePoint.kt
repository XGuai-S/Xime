package com.kingzcheung.xime.handwriting

data class StrokePoint(
    val x: Float,
    val y: Float,
    val timeMs: Long = System.currentTimeMillis(),
)
