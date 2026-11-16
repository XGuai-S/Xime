package com.kingzcheung.xime.ai.ime

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.ai.data.KeyboardHeightMode
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Unified keyboard height and scaling metrics for TypeFree v2.7.
 *
 * Rules:
 * - Only the key area scales; the 34.dp top bar and 1.dp top divider remain fixed across all levels.
 * - All panels (VOICE, QWERTY, TRANSLATE, CLIPBOARD, TOOL_PANEL, HELP_ME_WRITE, SPEAK_TO_EDIT, ArcTranslate)
 *   read [frameHeight] from [LocalKeyboardMetrics] so switching panels causes 0px host relayout.
 * - Fixed-size elements on the Voice panel (mic orb, waveform) scale by [elementScale] = `min(scale, 1f)`
 *   (shrink-only, never bloated in comfortable mode).
 * - Landscape mode automatically applies `COMPACT_SCALE * 0.8f`.
 */
@Immutable
data class KeyboardMetrics(
    val level: Int,
    val isLandscape: Boolean,
    val scale: Float,
    val elementScale: Float,
    val topBarHeight: Dp,
    val dividerHeight: Dp,
    val keyAreaHeight: Dp,
    val frameHeight: Dp,
    val label: String,
    /** STRETCH scales the key area; LIFT keeps keys at standard size and adds [liftHeight] at the bottom. */
    val heightMode: KeyboardHeightMode = KeyboardHeightMode.LIFT,
    val liftLevel: Int = 0,
    /** Blank bottom cushion inside [frameHeight] (0.dp in STRETCH mode and in landscape). */
    val liftHeight: Dp = 0.dp,
    val showNumberRow: Boolean = false
) {
    companion object {
        const val MIN_LEVEL = -2
        const val MAX_LEVEL = 2
        const val DEFAULT_LEVEL = 0

        const val SCALE_COMPACT = 0.88f
        const val SCALE_LOW = 0.94f
        const val SCALE_STANDARD = 1.00f
        const val SCALE_HIGH = 1.07f
        const val SCALE_COMFORTABLE = 1.14f
        const val LANDSCAPE_FACTOR = 0.80f

        private const val BASE_KEY_AREA_4ROW_DP = 210f
        private const val BASE_KEY_AREA_5ROW_DP = 252f
        const val FIXED_CHROME_DP = 42 // 1.dp divider + 5.dp top pad + 34.dp top bar + 2.dp gap
        /** Transparent floating preedit capsule overhang above the 1.dp keyboard divider (`0px` host viewport loss via `contentTopInsets`). */
        const val FLOATING_PREEDIT_OVERHANG_DP = 28

        fun computeFloatingOverhangContentTopInset(
            baseContentTopInsets: Int,
            floatingOverhangPx: Int,
            decorHeight: Int
        ): Int {
            if (floatingOverhangPx <= 0 || decorHeight <= floatingOverhangPx) {
                return baseContentTopInsets
            }
            return (baseContentTopInsets + floatingOverhangPx).coerceAtMost(decorHeight)
        }

        val ALL_LEVELS: List<Int> = listOf(-2, -1, 0, 1, 2)

        fun scaleForLevel(level: Int): Float = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            -2 -> SCALE_COMPACT
            -1 -> SCALE_LOW
            0 -> SCALE_STANDARD
            1 -> SCALE_HIGH
            2 -> SCALE_COMFORTABLE
            else -> SCALE_STANDARD
        }

        fun labelForLevel(level: Int): String = when (level.coerceIn(MIN_LEVEL, MAX_LEVEL)) {
            -2 -> "紧凑"
            -1 -> "偏低"
            0 -> "标准"
            1 -> "偏高"
            2 -> "舒适"
            else -> "标准"
        }

        // ---------------------------------------------------------------------------------
        // LIFT mode (垫高): keys keep the standard size; a blank bottom cushion raises the keyboard.
        // ---------------------------------------------------------------------------------
        const val MIN_LIFT_LEVEL = 0
        const val MAX_LIFT_LEVEL = 4
        const val DEFAULT_LIFT_LEVEL = 0
        private const val LIFT_STEP_DP = 8

        val ALL_LIFT_LEVELS: List<Int> = listOf(0, 1, 2, 3, 4)

        /** Bottom cushion height for a lift level: 0 / 8 / 16 / 24 / 32 dp. */
        fun liftDpForLevel(liftLevel: Int): Int =
            liftLevel.coerceIn(MIN_LIFT_LEVEL, MAX_LIFT_LEVEL) * LIFT_STEP_DP

        fun labelForLiftLevel(liftLevel: Int): String = "${liftDpForLevel(liftLevel)}dp"

        fun fromLevel(
            level: Int = DEFAULT_LEVEL,
            isLandscape: Boolean = false,
            heightMode: KeyboardHeightMode = KeyboardHeightMode.LIFT,
            liftLevel: Int = DEFAULT_LIFT_LEVEL,
            showNumberRow: Boolean = false
        ): KeyboardMetrics {
            val clamped = level.coerceIn(MIN_LEVEL, MAX_LEVEL)
            val isLift = heightMode == KeyboardHeightMode.LIFT
            val effectiveScale = when {
                isLandscape -> SCALE_COMPACT * LANDSCAPE_FACTOR
                isLift -> SCALE_STANDARD
                else -> scaleForLevel(clamped)
            }
            val clampedLift = liftLevel.coerceIn(MIN_LIFT_LEVEL, MAX_LIFT_LEVEL)
            // Landscape never adds a cushion: vertical space is already scarce.
            val liftDp = if (isLift && !isLandscape) liftDpForLevel(clampedLift) else 0
            val baseKeyAreaDp = if (showNumberRow) BASE_KEY_AREA_5ROW_DP else BASE_KEY_AREA_4ROW_DP
            val keyAreaDp = (baseKeyAreaDp * effectiveScale).roundToInt()
            val frameDp = FIXED_CHROME_DP + keyAreaDp + liftDp
            return KeyboardMetrics(
                level = clamped,
                isLandscape = isLandscape,
                scale = effectiveScale,
                elementScale = min(effectiveScale, 1.0f),
                topBarHeight = 34.dp,
                dividerHeight = 1.dp,
                keyAreaHeight = keyAreaDp.dp,
                frameHeight = frameDp.dp,
                label = if (isLift) "垫高 · ${labelForLiftLevel(clampedLift)}" else labelForLevel(clamped),
                heightMode = heightMode,
                liftLevel = clampedLift,
                liftHeight = liftDp.dp,
                showNumberRow = showNumberRow
            )
        }
    }
}

val LocalKeyboardMetrics = staticCompositionLocalOf {
    KeyboardMetrics.fromLevel(KeyboardMetrics.DEFAULT_LEVEL)
}
