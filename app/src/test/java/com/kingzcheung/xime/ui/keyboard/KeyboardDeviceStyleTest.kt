package com.kingzcheung.xime.ui.keyboard

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kingzcheung.xime.settings.KeyboardKeyConfig
import com.kingzcheung.xime.settings.KeyboardSpacingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设备档键面样式档：**圆角**、**键面提示**（退格的「清空」、字母键的数字上标等）
 * 与**键盘区外围留白**（面板 / 九键 / 笔画 / 手写共用）。
 *
 * 平板 26 键与各键盘必须给出同一档——这些值曾经分散在四处各写各的，
 * 出现过「面板圆角 8dp vs 全键盘 14dp」「面板提示 1f vs 全键盘 1.35f」
 * 「九键/笔画/手写漏了设备档」「九键横屏留白与键盘区不一致」这类不一致，
 * 现在全部收敛到 [keyCornerRadiusFor] / [swipeHintCornerFor] / [hintExtraScaleFor] /
 * [panelOuterPadding] / [keyboardSidePaddingDp]。
 */
class KeyboardDeviceStyleTest {

    private val config = KeyboardKeyConfig(
        cornerRadius = 8,
        spacingOverrides = mapOf("symbol" to KeyboardSpacingConfig(cornerRadius = 10)),
    )

    // ── 圆角 ──────────────────────────────────────────────

    @Test
    fun `圆角未覆盖时按设备档：手机全局值、平板大键面档`() {
        assertEquals(8.dp, keyCornerRadiusFor(config, "qwerty", isTablet = false))
        assertEquals(14.dp, keyCornerRadiusFor(config, "qwerty", isTablet = true))
        // 平板值是「下限」：全局配得更大时取更大者
        assertEquals(16.dp, keyCornerRadiusFor(config.copy(cornerRadius = 16), "qwerty", isTablet = true))
    }

    @Test
    fun `圆角 yaml 按键盘覆盖优先且平板也照用覆盖值`() {
        assertEquals(10.dp, keyCornerRadiusFor(config, "symbol", isTablet = false))
        assertEquals(10.dp, keyCornerRadiusFor(config, "symbol", isTablet = true))
    }

    @Test
    fun `同一设备上圆角：面板与全键盘同值`() {
        val plain = KeyboardKeyConfig()
        for (tablet in listOf(false, true)) {
            val qwerty = keyCornerRadiusFor(plain, "qwerty", isTablet = tablet)
            for (panel in listOf("number", "symbol")) {
                assertEquals(
                    "同一设备（tablet=$tablet）上 $panel 面板必须与全键盘同一圆角",
                    qwerty, keyCornerRadiusFor(plain, panel, isTablet = tablet),
                )
            }
        }
    }

    @Test
    fun `overlay 面板（全量符号与表情）圆角同样按设备档`() {
        // 这两块没有 xime.yaml 段，按全局值 + 平板大键面档，与 26 键/面板同观感
        val plain = KeyboardKeyConfig()
        assertEquals(8.dp, overlayKeyCornerRadius(plain, isTablet = false))
        assertEquals(14.dp, overlayKeyCornerRadius(plain, isTablet = true))
        assertEquals(
            "同一设备上 overlay 与 26 键圆角一致",
            keyCornerRadiusFor(plain, "qwerty", isTablet = true),
            overlayKeyCornerRadius(plain, isTablet = true),
        )
    }

    // ── 键面提示（角标画法 / 放大倍率） ──────────────────────

    @Test
    fun `提示画到键角：手机横屏与平板一致，手机竖屏不平移`() {
        assertEquals(false, swipeHintCornerFor(isLandscape = false, isTablet = false))
        assertEquals(true, swipeHintCornerFor(isLandscape = true, isTablet = false))
        assertEquals(true, swipeHintCornerFor(isLandscape = false, isTablet = true))
        assertEquals(true, swipeHintCornerFor(isLandscape = true, isTablet = true))
    }

    @Test
    fun `提示放大倍率：仅平板放大，其余 1f`() {
        assertEquals(1f, hintExtraScaleFor(isTablet = false))
        assertEquals(TABLET_HINT_EXTRA_SCALE, hintExtraScaleFor(isTablet = true))
        assertEquals(1.35f, TABLET_HINT_EXTRA_SCALE)
    }

    // ── 九键 / 笔画 / 手写：同样并入设备档 ────────────────────

    @Test
    fun `同一设备上圆角：九键笔画手写与全键盘同值`() {
        val plain = KeyboardKeyConfig()
        for (tablet in listOf(false, true)) {
            val qwerty = keyCornerRadiusFor(plain, "qwerty", isTablet = tablet)
            for (name in listOf("t9", "stroke", "handwriting")) {
                assertEquals(
                    "同一设备（tablet=$tablet）上 $name 键盘必须与全键盘同一圆角",
                    qwerty, keyCornerRadiusFor(plain, name, isTablet = tablet),
                )
            }
        }
    }

    @Test
    fun `圆角按键盘覆盖：九键手写的覆盖值在平板上也照用`() {
        val config = KeyboardKeyConfig(
            cornerRadius = 8,
            spacingOverrides = mapOf("t9" to KeyboardSpacingConfig(cornerRadius = 12)),
        )
        assertEquals(12.dp, keyCornerRadiusFor(config, "t9", isTablet = true))
        assertEquals(14.dp, keyCornerRadiusFor(config, "stroke", isTablet = true))
        assertEquals(14.dp, keyCornerRadiusFor(config, "handwriting", isTablet = true))
    }

    // ── 键盘区外围留白 / 两侧留白档（面板 · 九键 · 笔画 · 手写共用） ──────

    @Test
    fun `外围留白档：手机竖屏 4-4-8、手机横屏 50-2、平板 16-16-8`() {
        val portrait = panelOuterPadding(isLandscape = false, isTablet = false)
        assertEquals(4.dp, portrait.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(4.dp, portrait.calculateRightPadding(LayoutDirection.Ltr))
        assertEquals(8.dp, portrait.calculateBottomPadding())

        val phoneLandscape = panelOuterPadding(isLandscape = true, isTablet = false)
        assertEquals(50.dp, phoneLandscape.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(2.dp, phoneLandscape.calculateTopPadding())

        // 平板不分方向：横竖屏都 16/16/8（键面距屏幕边 20dp，与 26 键同档）
        for (isLandscape in listOf(false, true)) {
            val tabletPad = panelOuterPadding(isLandscape = isLandscape, isTablet = true)
            assertEquals(16.dp, tabletPad.calculateLeftPadding(LayoutDirection.Ltr))
            assertEquals(16.dp, tabletPad.calculateRightPadding(LayoutDirection.Ltr))
            assertEquals(8.dp, tabletPad.calculateBottomPadding())
        }
    }

    @Test
    fun `两侧留白档：手机 4dp、平板 16dp`() {
        assertEquals(4f, keyboardSidePaddingDp(isTablet = false))
        assertEquals(16f, keyboardSidePaddingDp(isTablet = true))
    }

    @Test
    fun `九键左栏：手机横屏不显示，平板横竖屏都显示`() {
        assertTrue(t9HasLeftRail(isLandscape = false, isTablet = false))
        assertFalse(t9HasLeftRail(isLandscape = true, isTablet = false))
        assertTrue(t9HasLeftRail(isLandscape = false, isTablet = true))
        assertTrue(t9HasLeftRail(isLandscape = true, isTablet = true))
    }

    @Test
    fun `九键左栏列宽按设备档：与键盘态同公式（页宽−两侧留白−列间隙）`() {
        // 页宽 1000dp：手机基数 1000−8−4 = 988 → 988×0.8/5 = 158.08
        //              平板基数 1000−32−4 = 964 → 964×0.8/5 = 154.24
        assertEquals(158.08f, t9LeftRailWidthDp(1000f, isTablet = false), 0.001f)
        assertEquals(154.24f, t9LeftRailWidthDp(1000f, isTablet = true), 0.001f)
    }

    // ── 手写：键面（底栏 / 右侧符号栏）尺寸档 ────────────────

    @Test
    fun `手写键面档：手机用历史尺寸，平板按提示档放大且圆角走设备档`() {
        val phone = handwritingChromeFor(
            isTablet = false,
            keyCornerRadius = keyCornerRadiusFor(KeyboardKeyConfig(), "handwriting", isTablet = false),
        )
        assertEquals(56f, phone.sheetWidthDp, 0.001f)
        assertEquals(48f, phone.barHeightDp, 0.001f)
        assertEquals(1f, phone.keyPaddingDp, 0.001f)
        assertEquals(4f, phone.gapDp, 0.001f)
        assertEquals(16f, phone.fontSizeSp, 0.001f)
        assertEquals(4f, phone.sidePaddingDp, 0.001f)
        assertEquals(8.dp, phone.keyCornerRadius)

        val tablet = handwritingChromeFor(
            isTablet = true,
            keyCornerRadius = keyCornerRadiusFor(KeyboardKeyConfig(), "handwriting", isTablet = true),
        )
        assertEquals(56f * TABLET_HINT_EXTRA_SCALE, tablet.sheetWidthDp, 0.001f)
        assertEquals(48f * TABLET_HINT_EXTRA_SCALE, tablet.barHeightDp, 0.001f)
        assertEquals(1f * TABLET_HINT_EXTRA_SCALE, tablet.keyPaddingDp, 0.001f)
        assertEquals(4f * TABLET_HINT_EXTRA_SCALE, tablet.gapDp, 0.001f)
        assertEquals(16f * TABLET_HINT_EXTRA_SCALE, tablet.fontSizeSp, 0.001f)
        // 两侧留白与 26 键/面板/九键/笔画同档；圆角与全键盘同值
        assertEquals(16f, tablet.sidePaddingDp, 0.001f)
        assertEquals(14.dp, tablet.keyCornerRadius)
        assertEquals(
            keyCornerRadiusFor(KeyboardKeyConfig(), "qwerty", isTablet = true),
            tablet.keyCornerRadius,
        )
    }
}
