package com.kingzcheung.xime.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertTrue

/**
 * 悬浮卡片几何纯逻辑测试（[FloatingCardGeometry]）。
 *
 * 触摸区与拖动钳制都消费这里的推导，数值是悬浮卡片不错位的底线：
 * - 卡宽 = 短边 × 0.85（横竖屏一致，卡片恒为竖屏形态）
 * - offsetY 下界 = 导航栏高（卡片不得压进导航栏），上界 = 屏高 - 卡高
 */
class FloatingCardGeometryTest {

    @Test
    fun `card width is 85 percent of portrait width`() {
        assertEquals(349, FloatingCardGeometry.cardWidthDp(411))
        assertEquals(0, FloatingCardGeometry.cardWidthDp(0))
    }

    @Test
    fun `portrait screen width takes the shorter side`() {
        assertEquals(411, FloatingCardGeometry.portraitScreenWidthDp(411, 891))
        assertEquals(411, FloatingCardGeometry.portraitScreenWidthDp(891, 411))
        assertEquals(320, FloatingCardGeometry.portraitScreenWidthDp(320, 320))
    }

    @Test
    fun `card width for config matches cardWidthDp of the shorter side`() {
        // 349 = round(411 * 0.85)，与既有 cardWidthDp(411) 用例一致
        assertEquals(349, FloatingCardGeometry.cardWidthDpForConfig(411, 891))
        assertEquals(349, FloatingCardGeometry.cardWidthDpForConfig(891, 411))
        assertEquals(272, FloatingCardGeometry.cardWidthDpForConfig(320, 320))
        assertEquals(612, FloatingCardGeometry.cardWidthDpForConfig(1280, 720))
    }

    @Test
    fun `card width for config agrees with the explicit two-step call`() {
        for (w in listOf(320, 360, 411, 480, 891, 1280)) {
            for (h in listOf(320, 360, 411, 480, 891, 1280)) {
                assertEquals(
                    "w=$w h=$h",
                    FloatingCardGeometry.cardWidthDp(minOf(w, h)),
                    FloatingCardGeometry.cardWidthDpForConfig(w, h),
                )
            }
        }
    }

    @Test
    fun `width fraction equals card scale in portrait`() {
        val fraction = FloatingCardGeometry.widthFraction(portraitScreenWidthDp = 411, windowWidthDp = 411)
        assertEquals(FloatingCardGeometry.CARD_SCALE, fraction, 0.001f)
    }

    @Test
    fun `width fraction shrinks below card scale in landscape window`() {
        // 横屏窗口 891dp：卡宽仍按短边 411dp 算，占窗口 349/891 ≈ 0.392
        val fraction = FloatingCardGeometry.widthFraction(portraitScreenWidthDp = 411, windowWidthDp = 891)
        assertEquals(349f / 891f, fraction, 0.0001f)
    }

    @Test
    fun `degenerate window width falls back to card scale`() {
        assertEquals(FloatingCardGeometry.CARD_SCALE, FloatingCardGeometry.widthFraction(411, 0), 0.0001f)
    }

    @Test
    fun `half margin is half of window minus card`() {
        assertEquals(31, FloatingCardGeometry.halfMarginDp(windowWidthDp = 411, portraitScreenWidthDp = 411))
        assertEquals(271, FloatingCardGeometry.halfMarginDp(windowWidthDp = 891, portraitScreenWidthDp = 411))
    }

    @Test
    fun `half margin never negative when window narrower than card`() {
        assertEquals(0, FloatingCardGeometry.halfMarginDp(windowWidthDp = 300, portraitScreenWidthDp = 411))
    }

    @Test
    fun `half margin for config uses the shorter screen side`() {
        // 竖屏：配置宽即短边
        assertEquals(
            31,
            FloatingCardGeometry.halfMarginDpForConfig(screenWidthDp = 411, screenHeightDp = 891),
        )
        // 横屏：短边是屏高，窗口宽取配置宽
        assertEquals(
            271,
            FloatingCardGeometry.halfMarginDpForConfig(screenWidthDp = 891, screenHeightDp = 411),
        )
    }

    @Test
    fun `half margin for config matches explicit two-step call`() {
        // 合并前调用处自行 minOf(宽, 高) 再调 halfMarginDp，二者必须等价
        for (w in listOf(320, 411, 891, 1280)) {
            for (h in listOf(320, 411, 891, 1280)) {
                assertEquals(
                    "w=$w h=$h",
                    FloatingCardGeometry.halfMarginDp(w, minOf(w, h)),
                    FloatingCardGeometry.halfMarginDpForConfig(w, h),
                )
            }
        }
    }

    @Test
    fun `half margin for config stays positive for real screen sizes`() {
        // 真实屏幕配置下短边 <= 配置宽，故 halfMarginDpForConfig 恒为正：
        // cardWidthDp(短边) = 0.85 × 短边，half = (配置宽 - 0.85 × 短边) / 2 > 0
        for (w in listOf(320, 360, 411, 480, 891, 1280)) {
            for (h in listOf(320, 360, 411, 480, 891, 1280)) {
                assertTrue(
                    "w=$w h=$h",
                    FloatingCardGeometry.halfMarginDpForConfig(w, h) > 0,
                )
            }
        }
    }

    @Test
    fun `fallback card height is scaled keyboard plus drag bar plus padding`() {
        // 0.85×280 = 238，+18 拖条 +16 留白
        assertEquals(272, FloatingCardGeometry.fallbackCardHeightDp(keyboardHeightDp = 280, bottomPaddingDp = 16))
    }

    @Test
    fun `clamp offsetY pushes card above navigation bar`() {
        // 下界 = minY（导航栏高）：拖动不允许把卡片压进导航栏
        assertEquals(16, FloatingCardGeometry.clampOffsetY(-5, minY = 16, screenHeightDp = 800, cardHeightDp = 272))
    }

    @Test
    fun `clamp offsetY caps card inside screen`() {
        assertEquals(528, FloatingCardGeometry.clampOffsetY(600, minY = 0, screenHeightDp = 800, cardHeightDp = 272))
    }

    @Test
    fun `clamp keeps minY reachable when card taller than screen`() {
        // 卡高超过屏高时上界塌缩到 minY，钳制区间不反向
        assertEquals(16, FloatingCardGeometry.clampOffsetY(50, minY = 16, screenHeightDp = 100, cardHeightDp = 272))
    }

    @Test
    fun `fallback bounds center card and honor offsets`() {
        val bounds = FloatingCardGeometry.fallbackBounds(
            windowWidthPx = 1080,
            windowHeightPx = 2000,
            offsetXdp = 10,
            offsetYdp = 30,
            cardWidthDp = 349,
            cardHeightDp = 272,
            density = 2f,
        )
        // 卡宽 698px 水平居中 → left 191，再加 offsetX 20px
        assertEquals(211, bounds.left)
        assertEquals(909, bounds.right)
        // 底边 = 窗口底 - offsetY(60px)，顶边 = 底边 - 卡高(544px)
        assertEquals(1940, bounds.bottom)
        assertEquals(1396, bounds.top)
        assertEquals(544, bounds.heightPx)
    }

    @Test
    fun `fallback bounds with zero offset sits at window bottom`() {
        val bounds = FloatingCardGeometry.fallbackBounds(
            windowWidthPx = 1080,
            windowHeightPx = 2000,
            offsetXdp = 0,
            offsetYdp = 0,
            cardWidthDp = 349,
            cardHeightDp = 272,
            density = 2f,
        )
        assertEquals(2000, bounds.bottom)
    }
}
