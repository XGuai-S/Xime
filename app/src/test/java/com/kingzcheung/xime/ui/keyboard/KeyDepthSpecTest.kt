package com.kingzcheung.xime.ui.keyboard

import com.kingzcheung.xime.RepoFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键立体感：位图缓存的身份与上限（纯逻辑，不触碰 Android Bitmap）。
 *
 * 背景：立体感从「每键每帧画带模糊的圆角 path」改为「预渲染位图 + 每帧一次 blit」。
 * 优化的成立条件全在缓存身份上——规格相同必须命中同一张图，配色/圆角/按下态变化必须
 * 产生新条目，且条目总数有上限。这些可以用纯数据断言。
 *
 * 像素级正确性（阴影是否真的画出来、键面是否不透明）依赖 android.graphics.Bitmap，
 * 在 JVM 单测里是桩实现（createBitmap 返回 null），因此不在本类验证，留给真机。
 */
class KeyDepthSpecTest {

    private fun spec(
        widthPx: Int = 120,
        heightPx: Int = 90,
        cornerRadiusPx: Int = 18,
        color: Long = 0xFF3E3E46,
        pressed: Boolean = false,
        strength: Int = 100,
    ) = KeyDepthCache.Spec(
        widthPx = widthPx,
        heightPx = heightPx,
        cornerRadiusPx = cornerRadiusPx,
        backgroundColor = androidx.compose.ui.graphics.Color(color),
        pressed = pressed,
        strengthPercent = strength,
    )

    @Test
    fun `identical specs are equal so the cache can hit`() {
        assertEquals(
            "规格相同必须能命中同一缓存条目，否则每帧重建位图",
            spec(), spec(),
        )
    }

    @Test
    fun `pressed state is part of the cache identity`() {
        assertTrue(
            "按下态必须单独占一个条目（键面压暗、阴影收敛），不能与静止态共用",
            spec(pressed = true) != spec(pressed = false),
        )
    }

    @Test
    fun `color, radius and size are part of the cache identity`() {
        val base = spec()
        assertTrue("配色变化必须换图", spec(color = 0xFFE0E0E8) != base)
        assertTrue("圆角变化必须换图", spec(cornerRadiusPx = 4) != base)
        assertTrue("尺寸变化必须换图", spec(widthPx = 200) != base)
        assertTrue("强度变化必须换图", spec(strength = 50) != base)
    }

    @Test
    fun `pressed padding is smaller so the key looks pressed in`() {
        assertTrue(
            "按下态阴影外扩必须小于静止态，形成陷入观感",
            KeyDepthCache.shadowPaddingOf(spec(pressed = true)) <
                KeyDepthCache.shadowPaddingOf(spec(pressed = false)),
        )
        assertTrue(
            "外扩留白必须为正，否则阴影会被按键裁剪切掉",
            KeyDepthCache.shadowPaddingOf(spec()) > 0,
        )
    }

    @Test
    fun `padding scales with key height`() {
        val short = KeyDepthCache.shadowPaddingOf(spec(heightPx = 60))
        val tall = KeyDepthCache.shadowPaddingOf(spec(heightPx = 140))
        assertTrue("更高的键需要按比例更厚的阴影，实际 $short vs $tall", tall > short)
    }

    @Test
    fun `shadow and bevel are separate layers drawn at different depths`() {
        val source = RepoFiles.mainKotlinSources
            .single { it.name == "KeyDepth.kt" }
            .readText()
            .replace("\r\n", "\n")

        assertTrue(
            "必须通过 drawImage 走位图 blit，而不是逐帧 path 绘制",
            source.contains("drawImage("),
        )
        assertTrue(
            "缓存必须有上限，主题切换不能无限增长",
            source.contains("MAX_ENTRIES"),
        )
        assertTrue(
            "阴影层必须用 drawBehind：它在背景之下绘制，且不受后续 clip 影响",
            source.contains("Modifier.drawBehind"),
        )
        assertTrue(
            "高光层必须用 drawWithContent 且先画内容再画高光（浮在键面之上）",
            source.contains("drawContent()") && source.contains("drawKeyBevel("),
        )
    }

    @Test
    fun `bevel layer is applied after the key background`() {
        // 关键回归：高光/压暗若排在 .background() 之前，会被键面底色整块盖住，
        // 真机表现就是「立体感完全看不到」。
        val source = RepoFiles.mainKotlinSources
            .single { it.name == "KeyButton.kt" }
            .readText()
            .replace("\r\n", "\n")

        val shadowIdx = source.indexOf(".then(shadowLayer)")
        val backgroundIdx = source.indexOf(".background(", shadowIdx)
        val bevelIdx = source.indexOf(".then(bevelLayer)", backgroundIdx)

        assertTrue("阴影层必须存在", shadowIdx >= 0)
        assertTrue("背景必须紧跟阴影层之后", backgroundIdx > shadowIdx)
        assertTrue(
            "高光层必须排在 background 之后，否则被键面底色盖住（实测踩过）",
            bevelIdx > backgroundIdx,
        )
    }

    @Test
    fun `compat entry delegates to the shadow layer`() {
        val shadow = RepoFiles.mainKotlinSources
            .single { it.name == "KeyboardShadow.kt" }
            .readText()
            .replace("\r\n", "\n")

        assertTrue(
            "旧入口必须委托给位图实现，否则各键盘布局拿不到立体感",
            shadow.contains("rememberKeyShadowModifier("),
        )
        assertTrue(
            "配色推导函数仍被多处调用，不能再次删除",
            shadow.contains("internal fun crispShadowColor("),
        )
    }
}