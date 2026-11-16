package com.kingzcheung.xime.ai.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object TypeFreeIcons {

    /**
     * Iconic TypeFree crescent wave/wing brand mark from `ps_02.png` & `keyboard_swipe_frames.png`.
     */
    val LogoMark: ImageVector by lazy {
        ImageVector.Builder("LogoMark", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.6f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(6.5f, 5.5f)
                curveTo(14.5f, 5.5f, 18.5f, 9.5f, 18.5f, 17.5f)
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.4f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(5.5f, 11.5f)
                curveTo(10.5f, 11.5f, 13f, 14f, 13f, 19f)
            }
            path(fill = SolidColor(Color.White)) {
                moveTo(7.5f, 17.5f)
                arcTo(1.7f, 1.7f, 0f, true, true, 7.49f, 17.5f)
                close()
            }
        }.build()
    }

    /**
     * Signature 7-element symmetrical TypeFree Voice Waveform (`· | ||| | ·`)
     * used on the Voice Bubble (`bubble_hero_light.png`), top mode switcher, and active recording circle.
     */
    val VoiceWaveform: ImageVector by lazy {
        ImageVector.Builder("VoiceWaveform", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.15f,
                strokeLineCap = StrokeCap.Round
            ) {
                // Left dot
                moveTo(3.2f, 11.9f)
                lineTo(3.2f, 12.1f)
                // Left outer bar
                moveTo(6.4f, 8.8f)
                lineTo(6.4f, 15.2f)
                // Left inner bar
                moveTo(9.3f, 7.2f)
                lineTo(9.3f, 16.8f)
                // Center tall bar
                moveTo(12f, 4.5f)
                lineTo(12f, 19.5f)
                // Right inner bar
                moveTo(14.7f, 7.2f)
                lineTo(14.7f, 16.8f)
                // Right outer bar
                moveTo(17.6f, 8.8f)
                lineTo(17.6f, 15.2f)
                // Right dot
                moveTo(20.8f, 11.9f)
                lineTo(20.8f, 12.1f)
            }
        }.build()
    }

    /**
     * Authentic TypeFree bottom-left "Help me write / Speak to edit" Voice-Pen icon (`||` + diagonal pen).
     */
    val VoicePen: ImageVector by lazy {
        ImageVector.Builder("VoicePen", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Mini voice bars on upper-left
                moveTo(4.5f, 8.5f)
                lineTo(4.5f, 12.5f)
                moveTo(7.8f, 6.2f)
                lineTo(7.8f, 14.8f)
                moveTo(11.1f, 8.2f)
                lineTo(11.1f, 11.2f)
                // Diagonal pen nib on lower-right
                moveTo(17.8f, 6.8f)
                lineTo(20.2f, 9.2f)
                lineTo(12.2f, 17.2f)
                lineTo(9.2f, 17.8f)
                lineTo(9.8f, 14.8f)
                close()
            }
        }.build()
    }

    /**
     * Translate / Multi-language (`文A`) icon.
     */
    val Translate: ImageVector by lazy {
        ImageVector.Builder("Translate", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Upper-left CJK character strokes
                moveTo(3.5f, 6f)
                lineTo(12.5f, 6f)
                moveTo(8f, 3.5f)
                lineTo(8f, 6f)
                moveTo(10.8f, 6f)
                curveTo(10.2f, 9.8f, 7.8f, 12.8f, 4f, 14.5f)
                moveTo(5.8f, 8.2f)
                curveTo(7.2f, 10.5f, 9.2f, 12.4f, 11.8f, 13.8f)
                // Lower-right Latin 'A'
                moveTo(13.2f, 20f)
                lineTo(16.8f, 11.2f)
                lineTo(20.4f, 20f)
                moveTo(14.3f, 17.4f)
                lineTo(19.3f, 17.4f)
            }
        }.build()
    }

    val ChevronDown: ImageVector by lazy {
        ImageVector.Builder("ChevronDown", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(6f, 9.5f)
                lineTo(12f, 15.5f)
                lineTo(18f, 9.5f)
            }
        }.build()
    }

    val KeyboardGrid: ImageVector by lazy {
        ImageVector.Builder("KeyboardGrid", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.85f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 6f)
                lineTo(21f, 6f)
                lineTo(21f, 18f)
                lineTo(3f, 18f)
                close()
                moveTo(7f, 10f)
                lineTo(7.2f, 10f)
                moveTo(12f, 10f)
                lineTo(12.2f, 10f)
                moveTo(17f, 10f)
                lineTo(17.2f, 10f)
                moveTo(8f, 14.5f)
                lineTo(16f, 14.5f)
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(7f, 7f)
                lineTo(17f, 17f)
                moveTo(17f, 7f)
                lineTo(7f, 17f)
            }
        }.build()
    }

    val BubbleOverlay: ImageVector by lazy {
        ImageVector.Builder("BubbleOverlay", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(12f, 3f)
                arcTo(9f, 9f, 0f, true, true, 11.99f, 3f)
                close()
                moveTo(8.5f, 10.5f)
                lineTo(8.5f, 13.5f)
                moveTo(12f, 8f)
                lineTo(12f, 16f)
                moveTo(15.5f, 10.5f)
                lineTo(15.5f, 13.5f)
            }
        }.build()
    }

    val Mic: ImageVector by lazy {
        ImageVector.Builder("Mic", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                lineTo(15f, 5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                lineTo(9f, 11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
                moveTo(17f, 11f)
                curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
                curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f)
                lineTo(5f, 11f)
                curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f)
                lineTo(11f, 21f)
                lineTo(13f, 21f)
                lineTo(13f, 17.92f)
                curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f)
                lineTo(17f, 11f)
                close()
            }
        }.build()
    }

    val Sparkles: ImageVector by lazy {
        ImageVector.Builder("Sparkles", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(11f, 2f)
                lineTo(12.8f, 7.2f)
                lineTo(18f, 9f)
                lineTo(12.8f, 10.8f)
                lineTo(11f, 16f)
                lineTo(9.2f, 10.8f)
                lineTo(4f, 9f)
                lineTo(9.2f, 7.2f)
                close()
                moveTo(18.5f, 14f)
                lineTo(19.4f, 16.6f)
                lineTo(22f, 17.5f)
                lineTo(19.4f, 18.4f)
                lineTo(18.5f, 21f)
                lineTo(17.6f, 18.4f)
                lineTo(15f, 17.5f)
                lineTo(17.6f, 16.6f)
                close()
            }
        }.build()
    }

    val Globe: ImageVector by lazy {
        ImageVector.Builder("Globe", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 2f)
                arcTo(10f, 10f, 0f, true, true, 11.99f, 2f)
                close()
                moveTo(2f, 12f)
                lineTo(22f, 12f)
                moveTo(12f, 2f)
                curveTo(15f, 5.5f, 16f, 8.8f, 16f, 12f)
                curveTo(16f, 15.2f, 15f, 18.5f, 12f, 22f)
                curveTo(9f, 18.5f, 8f, 15.2f, 8f, 12f)
                curveTo(8f, 8.8f, 9f, 5.5f, 12f, 2f)
            }
        }.build()
    }

    val Backspace: ImageVector by lazy {
        ImageVector.Builder("Backspace", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(21f, 5f)
                lineTo(9f, 5f)
                lineTo(3f, 12f)
                lineTo(9f, 19f)
                lineTo(21f, 19f)
                close()
                moveTo(16f, 9.5f)
                lineTo(11f, 14.5f)
                moveTo(11f, 9.5f)
                lineTo(16f, 14.5f)
            }
        }.build()
    }

    val Undo: ImageVector by lazy {
        ImageVector.Builder("Undo", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 14f)
                lineTo(4f, 9f)
                lineTo(9f, 4f)
                moveTo(4f, 9f)
                lineTo(14.5f, 9f)
                curveTo(17.5f, 9f, 20f, 11.5f, 20f, 14.5f)
                curveTo(20f, 17.5f, 17.5f, 20f, 14.5f, 20f)
                lineTo(11f, 20f)
            }
        }.build()
    }

    val ReturnEnter: ImageVector by lazy {
        ImageVector.Builder("ReturnEnter", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(19f, 6f)
                lineTo(19f, 13f)
                lineTo(5f, 13f)
                moveTo(9f, 9f)
                lineTo(5f, 13f)
                lineTo(9f, 17f)
            }
        }.build()
    }

    val History: ImageVector by lazy {
        ImageVector.Builder("History", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 12f)
                arcTo(9f, 9f, 0f, true, false, 6f, 5.3f)
                lineTo(3f, 8f)
                moveTo(3f, 3f)
                lineTo(3f, 8f)
                lineTo(8f, 8f)
                moveTo(12f, 7f)
                lineTo(12f, 12f)
                lineTo(15.5f, 14f)
            }
        }.build()
    }

    val Tune: ImageVector by lazy {
        ImageVector.Builder("Tune", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(4f, 6f)
                lineTo(20f, 6f)
                moveTo(4f, 12f)
                lineTo(20f, 12f)
                moveTo(4f, 18f)
                lineTo(20f, 18f)
                moveTo(9f, 4f)
                lineTo(9f, 8f)
                moveTo(15f, 10f)
                lineTo(15f, 14f)
                moveTo(11f, 16f)
                lineTo(11f, 20f)
            }
        }.build()
    }

    val Book: ImageVector by lazy {
        ImageVector.Builder("Book", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 19.5f)
                curveTo(4f, 18.1f, 5.1f, 17f, 6.5f, 17f)
                lineTo(20f, 17f)
                moveTo(6.5f, 2f)
                lineTo(20f, 2f)
                lineTo(20f, 22f)
                lineTo(6.5f, 22f)
                curveTo(5.1f, 22f, 4f, 20.9f, 4f, 19.5f)
                lineTo(4f, 4.5f)
                curveTo(4f, 3.1f, 5.1f, 2f, 6.5f, 2f)
                close()
            }
        }.build()
    }

    val Copy: ImageVector by lazy {
        ImageVector.Builder("Copy", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(9f, 9f)
                lineTo(20f, 9f)
                lineTo(20f, 20f)
                lineTo(9f, 20f)
                close()
                moveTo(5f, 15f)
                lineTo(4f, 15f)
                lineTo(4f, 4f)
                lineTo(15f, 4f)
                lineTo(15f, 5f)
            }
        }.build()
    }

    val Eye: ImageVector by lazy {
        ImageVector.Builder("Eye", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(2f, 12f)
                curveTo(4.5f, 7f, 8f, 5f, 12f, 5f)
                curveTo(16f, 5f, 19.5f, 7f, 22f, 12f)
                curveTo(19.5f, 17f, 16f, 19f, 12f, 19f)
                curveTo(8f, 19f, 4.5f, 17f, 2f, 12f)
                close()
                moveTo(12f, 9f)
                arcTo(3f, 3f, 0f, true, true, 11.99f, 9f)
                close()
            }
        }.build()
    }

    val EyeOff: ImageVector by lazy {
        ImageVector.Builder("EyeOff", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(3f, 3f)
                lineTo(21f, 21f)
                moveTo(10.5f, 6.2f)
                curveTo(11f, 6.07f, 11.5f, 6f, 12f, 6f)
                curveTo(16f, 6f, 19.5f, 8f, 22f, 12f)
                curveTo(21.1f, 13.6f, 20f, 15f, 18.7f, 16f)
                moveTo(6.5f, 8.2f)
                curveTo(4.7f, 9.2f, 3.2f, 10.5f, 2f, 12f)
                curveTo(4.5f, 16f, 8f, 18f, 12f, 18f)
                curveTo(13.6f, 18f, 15.1f, 17.6f, 16.5f, 17f)
            }
        }.build()
    }

    val Clipboard: ImageVector by lazy {
        ImageVector.Builder("Clipboard", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Top clip clasp
                moveTo(9f, 3.5f)
                lineTo(15f, 3.5f)
                lineTo(15f, 6.5f)
                lineTo(9f, 6.5f)
                close()
                // Board outline
                moveTo(9f, 5f)
                lineTo(6f, 5f)
                curveTo(5.17f, 5f, 4.5f, 5.67f, 4.5f, 6.5f)
                lineTo(4.5f, 19.5f)
                curveTo(4.5f, 20.33f, 5.17f, 21f, 6f, 21f)
                lineTo(18f, 21f)
                curveTo(18.83f, 21f, 19.5f, 20.33f, 19.5f, 19.5f)
                lineTo(19.5f, 6.5f)
                curveTo(19.5f, 5.67f, 18.83f, 5f, 18f, 5f)
                lineTo(15f, 5f)
                // Content lines
                moveTo(8.5f, 11f)
                lineTo(15.5f, 11f)
                moveTo(8.5f, 15f)
                lineTo(13.5f, 15f)
            }
        }.build()
    }

    val Pin: ImageVector by lazy {
        ImageVector.Builder("Pin", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(9f, 4f)
                lineTo(15f, 4f)
                lineTo(14f, 10.5f)
                lineTo(17.5f, 13.5f)
                lineTo(6.5f, 13.5f)
                lineTo(10f, 10.5f)
                close()
                moveTo(12f, 13.5f)
                lineTo(12f, 20.5f)
            }
        }.build()
    }

    val Trash: ImageVector by lazy {
        ImageVector.Builder("Trash", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 6.5f)
                lineTo(20f, 6.5f)
                moveTo(9.5f, 6.5f)
                lineTo(9.5f, 4f)
                lineTo(14.5f, 4f)
                lineTo(14.5f, 6.5f)
                moveTo(6.5f, 6.5f)
                lineTo(7.5f, 20f)
                lineTo(16.5f, 20f)
                lineTo(17.5f, 6.5f)
                moveTo(10.5f, 10.5f)
                lineTo(10.5f, 16.5f)
                moveTo(13.5f, 10.5f)
                lineTo(13.5f, 16.5f)
            }
        }.build()
    }

    val Plus: ImageVector by lazy {
        ImageVector.Builder("Plus", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(12f, 5f)
                lineTo(12f, 19f)
                moveTo(5f, 12f)
                lineTo(19f, 12f)
            }
        }.build()
    }

    val ShiftOff: ImageVector by lazy {
        ImageVector.Builder("ShiftOff", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.0f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 4f)
                lineTo(20.5f, 12.8f)
                lineTo(15.6f, 12.8f)
                lineTo(15.6f, 19.8f)
                lineTo(8.4f, 19.8f)
                lineTo(8.4f, 12.8f)
                lineTo(3.5f, 12.8f)
                close()
            }
        }.build()
    }

    val ShiftOnce: ImageVector by lazy {
        ImageVector.Builder("ShiftOnce", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 4f)
                lineTo(20.5f, 12.8f)
                lineTo(15.6f, 12.8f)
                lineTo(15.6f, 19.8f)
                lineTo(8.4f, 19.8f)
                lineTo(8.4f, 12.8f)
                lineTo(3.5f, 12.8f)
                close()
            }
        }.build()
    }

    val ShiftCapsLock: ImageVector by lazy {
        ImageVector.Builder("ShiftCapsLock", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 3.2f)
                lineTo(20.5f, 11.6f)
                lineTo(15.6f, 11.6f)
                lineTo(15.6f, 17.2f)
                lineTo(8.4f, 17.2f)
                lineTo(8.4f, 11.6f)
                lineTo(3.5f, 11.6f)
                close()
            }
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(8.4f, 20.6f)
                lineTo(15.6f, 20.6f)
            }
        }.build()
    }

    /** Four-point sparkle star with micro companion star for AI Copilot / Smart Actions. */
    val CopilotStar: ImageVector by lazy {
        ImageVector.Builder("CopilotStar", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(11f, 3.5f)
                lineTo(13.1f, 8.9f)
                lineTo(18.5f, 11f)
                lineTo(13.1f, 13.1f)
                lineTo(11f, 18.5f)
                lineTo(8.9f, 13.1f)
                lineTo(3.5f, 11f)
                lineTo(8.9f, 8.9f)
                close()
                // Companion mini star
                moveTo(18.5f, 15.5f)
                lineTo(18.5f, 20.5f)
                moveTo(16f, 18f)
                lineTo(21f, 18f)
            }
        }.build()
    }

    /** Monochrome incognito mask/glasses icon replacing 🕵️ emoji. */
    val IncognitoMask: ImageVector by lazy {
        ImageVector.Builder("IncognitoMask", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Hat brim
                moveTo(3f, 10.5f)
                lineTo(21f, 10.5f)
                // Hat crown
                moveTo(6.5f, 10.5f)
                lineTo(8.2f, 5f)
                lineTo(15.8f, 5f)
                lineTo(17.5f, 10.5f)
                // Left lens
                moveTo(9.5f, 16f)
                curveTo(9.5f, 17.66f, 8.16f, 19f, 6.5f, 19f)
                curveTo(4.84f, 19f, 3.5f, 17.66f, 3.5f, 16f)
                curveTo(3.5f, 14.34f, 4.84f, 13f, 6.5f, 13f)
                curveTo(8.16f, 13f, 9.5f, 14.34f, 9.5f, 16f)
                // Right lens
                moveTo(20.5f, 16f)
                curveTo(20.5f, 17.66f, 19.16f, 19f, 17.5f, 19f)
                curveTo(15.84f, 19f, 14.5f, 17.66f, 14.5f, 16f)
                curveTo(14.5f, 14.34f, 15.84f, 13f, 17.5f, 13f)
                curveTo(19.16f, 13f, 20.5f, 14.34f, 20.5f, 16f)
                // Bridge
                moveTo(9.5f, 15.6f)
                lineTo(14.5f, 15.6f)
            }
        }.build()
    }

    /** Monochrome screen context viewfinder frame replacing 📸 emoji. */
    val ScreenContext: ImageVector by lazy {
        ImageVector.Builder("ScreenContext", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Top-left corner
                moveTo(4f, 8.5f)
                lineTo(4f, 5.5f)
                curveTo(4f, 4.67f, 4.67f, 4f, 5.5f, 4f)
                lineTo(8.5f, 4f)
                // Top-right corner
                moveTo(15.5f, 4f)
                lineTo(18.5f, 4f)
                curveTo(19.33f, 4f, 20f, 4.67f, 20f, 5.5f)
                lineTo(20f, 8.5f)
                // Bottom-right corner
                moveTo(20f, 15.5f)
                lineTo(20f, 18.5f)
                curveTo(20f, 19.33f, 19.33f, 20f, 18.5f, 20f)
                lineTo(15.5f, 20f)
                // Bottom-left corner
                moveTo(8.5f, 20f)
                lineTo(5.5f, 20f)
                curveTo(4.67f, 20f, 4f, 19.33f, 4f, 18.5f)
                lineTo(4f, 15.5f)
                // Center context lines
                moveTo(8.5f, 10f)
                lineTo(15.5f, 10f)
                moveTo(8.5f, 14f)
                lineTo(13f, 14f)
            }
        }.build()
    }

    /** Monochrome low-amplitude whisper acoustic wave replacing 🤫 emoji. */
    val WhisperWave: ImageVector by lazy {
        ImageVector.Builder("WhisperWave", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Soft wave bars
                moveTo(4.5f, 11f)
                lineTo(4.5f, 13f)
                moveTo(8.2f, 8.5f)
                lineTo(8.2f, 15.5f)
                moveTo(12f, 6.5f)
                lineTo(12f, 17.5f)
                moveTo(15.8f, 9f)
                lineTo(15.8f, 15f)
                moveTo(19.5f, 11f)
                lineTo(19.5f, 13f)
            }
        }.build()
    }

    /** Monochrome token segmentation / word split icon replacing 💥 emoji. */
    val SplitWord: ImageVector by lazy {
        ImageVector.Builder("SplitWord", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                // Left chip
                moveTo(9.5f, 7f)
                lineTo(5f, 7f)
                curveTo(4.17f, 7f, 3.5f, 7.67f, 3.5f, 8.5f)
                lineTo(3.5f, 15.5f)
                curveTo(3.5f, 16.33f, 4.17f, 17f, 5f, 17f)
                lineTo(9.5f, 17f)
                // Right chip
                moveTo(14.5f, 7f)
                lineTo(19f, 7f)
                curveTo(19.83f, 7f, 20.5f, 7.67f, 20.5f, 8.5f)
                lineTo(20.5f, 15.5f)
                curveTo(20.5f, 16.33f, 19.83f, 17f, 19f, 17f)
                lineTo(14.5f, 17f)
                // Center split divider
                moveTo(12f, 4.5f)
                lineTo(12f, 19.5f)
            }
        }.build()
    }

    /** Monochrome hash / verification code icon replacing 🔢 emoji. */
    val HashCode: ImageVector by lazy {
        ImageVector.Builder("HashCode", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(5f, 9f)
                lineTo(19f, 9f)
                moveTo(5f, 15f)
                lineTo(19f, 15f)
                moveTo(10f, 4.5f)
                lineTo(8.5f, 19.5f)
                moveTo(15.5f, 4.5f)
                lineTo(14f, 19.5f)
            }
        }.build()
    }

    /** Monochrome parcel / tracking code box icon replacing 📦 emoji. */
    val PackageBox: ImageVector by lazy {
        ImageVector.Builder("PackageBox", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(12f, 3.5f)
                lineTo(20f, 8f)
                lineTo(20f, 16f)
                lineTo(12f, 20.5f)
                lineTo(4f, 16f)
                lineTo(4f, 8f)
                close()
                moveTo(4f, 8f)
                lineTo(12f, 12.5f)
                lineTo(20f, 8f)
                moveTo(12f, 12.5f)
                lineTo(12f, 20.5f)
            }
        }.build()
    }
}
