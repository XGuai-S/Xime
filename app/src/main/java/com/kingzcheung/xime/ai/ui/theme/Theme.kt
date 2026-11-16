package com.kingzcheung.xime.ai.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.kingzcheung.xime.ai.data.ThemePreference

// Authentic TypeFree Monochrome Minimalist Tokens + Semantic Status Accents
val TypeFreeBgDark = Color(0xFF0E0F12)
val TypeFreeSurfaceDark = Color(0xFF17181D)
val TypeFreeCardDark = Color(0xFF22232A)
val TypeFreeBorderDark = Color(0xFF2E303A)

val TypeFreeCharcoal = Color(0xFF18181B)
val TypeFreeCharcoalSoft = Color(0xFF27272A)
val TypeFreeWarmCanvas = Color(0xFFF2F2F4)
val XimeAiAppBgLight = Color(0xFFF6F6F8)

// Retained semantic accents for status badges & backward compatibility
val TypeFreeIndigo = Color(0xFF18181B)
val TypeFreeIndigoLight = Color(0xFFF4F4F6)
val TypeFreeEmerald = Color(0xFF10B981)
val TypeFreeEmeraldLight = Color(0xFF34D399)
val TypeFreeAmber = Color(0xFFF59E0B)
val TypeFreeRose = Color(0xFFE11D48)

@Immutable
data class TypeFreeKeyboardPalette(
    val isDark: Boolean,
    val keyboardCanvas: Color,
    val keyCapBg: Color,
    val keyCapSecondaryBg: Color,
    val keyBorder: Color,
    val keyShadow: Color,
    val keyPopupBg: Color,
    val keyTextPrimary: Color,
    val keyTextMuted: Color,
    val capsuleTrackBg: Color,
    val capsuleSelectedBg: Color,
    val primaryMicBrush: Brush,
    val primaryMicSolid: Color,
    val onPrimaryMic: Color,
    val pulseHaloColor: Color,
    val cardElevatedBg: Color
)

private val LightKeyboardPalette = TypeFreeKeyboardPalette(
    isDark = false,
    keyboardCanvas = Color(0xFFF0F1F4),
    keyCapBg = Color(0xFFFFFFFF),
    keyCapSecondaryBg = Color(0xFFE3E5EC),
    keyBorder = Color(0xFFDFDFE6),
    keyShadow = Color(0xFFC6C8D2),
    keyPopupBg = Color(0xFFFFFFFF),
    keyTextPrimary = Color(0xFF18181B),
    keyTextMuted = Color(0xFF6E6E75),
    capsuleTrackBg = Color(0xFFFFFFFF),
    capsuleSelectedBg = Color(0xFFEAEBEF),
    primaryMicBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF141417), Color(0xFF2A2A2F))
    ),
    primaryMicSolid = Color(0xFF18181B),
    onPrimaryMic = Color(0xFFFFFFFF),
    pulseHaloColor = Color(0xFFDADAE2),
    cardElevatedBg = Color(0xFFFFFFFF)
)

private val DarkKeyboardPalette = TypeFreeKeyboardPalette(
    isDark = true,
    keyboardCanvas = Color(0xFF141519),
    keyCapBg = Color(0xFF262830),
    keyCapSecondaryBg = Color(0xFF31333E),
    keyBorder = Color(0xFF383A46),
    keyShadow = Color(0xFF090A0D),
    keyPopupBg = Color(0xFF343744),
    keyTextPrimary = Color(0xFFF4F4F6),
    keyTextMuted = Color(0xFF9A9AA2),
    capsuleTrackBg = Color(0xFF202127),
    capsuleSelectedBg = Color(0xFF32343E),
    primaryMicBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFF8F8FA), Color(0xFFE2E2E8))
    ),
    primaryMicSolid = Color(0xFFF4F4F6),
    onPrimaryMic = Color(0xFF141519),
    pulseHaloColor = Color(0xFF3A3C46),
    cardElevatedBg = Color(0xFF1E1F25)
)

val LocalTypeFreeKeyboardPalette = staticCompositionLocalOf { LightKeyboardPalette }

private val DarkColorPalette = darkColorScheme(
    primary = Color(0xFFF4F4F6),
    onPrimary = Color(0xFF121316),
    primaryContainer = Color(0xFF2B2D36),
    onPrimaryContainer = Color(0xFFF4F4F6),
    secondary = TypeFreeEmeraldLight,
    onSecondary = Color(0xFF052E16),
    secondaryContainer = Color(0xFF13382B),
    onSecondaryContainer = Color(0xFFD1FAE5),
    tertiary = TypeFreeAmber,
    background = TypeFreeBgDark,
    onBackground = Color(0xFFF4F4F6),
    surface = TypeFreeSurfaceDark,
    onSurface = Color(0xFFF4F4F6),
    surfaceVariant = TypeFreeCardDark,
    onSurfaceVariant = Color(0xFF9E9EA8),
    outline = TypeFreeBorderDark,
    error = Color(0xFFFB7185)
)

private val LightColorPalette = lightColorScheme(
    primary = TypeFreeCharcoal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8E8ED),
    onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF064E3B),
    tertiary = Color(0xFFD97706),
    background = XimeAiAppBgLight,
    onBackground = Color(0xFF18181B),
    surface = Color.White,
    onSurface = Color(0xFF18181B),
    surfaceVariant = Color(0xFFEFEFF3),
    onSurfaceVariant = Color(0xFF636369),
    outline = Color(0xFFDDDDE3),
    error = TypeFreeRose
)

@Composable
fun resolveDarkTheme(themePreference: ThemePreference): Boolean {
    return when (themePreference) {
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
}

@Composable
fun TypeFreeTheme(
    themePreference: ThemePreference? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val useDark = if (themePreference != null) {
        resolveDarkTheme(themePreference)
    } else {
        darkTheme
    }
    val colors = if (useDark) DarkColorPalette else LightColorPalette
    val kbPalette = if (useDark) DarkKeyboardPalette else LightKeyboardPalette

    CompositionLocalProvider(LocalTypeFreeKeyboardPalette provides kbPalette) {
        MaterialTheme(
            colorScheme = colors,
            content = content
        )
    }
}
