package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 键盘按键立体感（预渲染位图 + 每帧一次 blit）。
 *
 * ## 为什么不再逐帧画阴影
 *
 * 旧实现每个按键每帧提交一条带模糊的圆角 path，被实测否决：30 个键同时提交时
 * 中文键盘整帧 p90 16.24 → 9.70ms、掉帧 8% → 1%（关掉阴影后），而布局/测量耗时不变。
 * 瓶颈就在每帧的 path 绘制与抗锯齿重算。
 *
 * ## 拆成两层的原因（踩过的坑）
 *
 * Compose 里同一条 Modifier 链上，**越靠后的绘制修饰符画在越上层**。按键的结构是：
 *
 * ```
 * .then(阴影)  .clip(圆角)  .background(键面色)
 * ```
 *
 * 若把「阴影 + 键面 + 高光」整张不透明位图放在 `background` 之前，键面背景会把位图
 * 完全盖住 —— 表现就是「立体感一点都没有、看起来还是平的」。
 *
 * 因此拆成两张**互不重叠**的位图：
 * - [ShadowLayer]：只画外扩软阴影 + 键面底色，放在 `.clip` 之前（不会被圆角裁掉），
 *   同时承担键面填色，避免与 background 叠加；
 * - [BevelLayer]：只画顶部高光与底部压暗，**透明背景**，放在 `.background` 之后，
 *   因此能浮在键面之上。
 *
 * 两张都按实际尺寸缓存，同一尺寸的键共享。
 */
internal object KeyDepthCache {

    /** 缓存键：只有这些量变化时才需要重建位图。 */
    data class Spec(
        val widthPx: Int,
        val heightPx: Int,
        val cornerRadiusPx: Int,
        val backgroundColor: Color,
        val pressed: Boolean,
        val strengthPercent: Int,
    )

    /**
     * 阴影外扩量。
     *
     * 双层阴影的远层模糊半径达键高的 16%，须留足约 2 倍半径的空白，
     * 否则扩散会被位图边界硬切，边缘出现一道直边（比没有阴影更难看）。
     */
    fun shadowPaddingOf(spec: Spec): Int = if (spec.pressed) {
        (spec.heightPx * 0.12f + 4f).toInt().coerceAtLeast(3)
    } else {
        (spec.heightPx * 0.22f + 6f).toInt().coerceAtLeast(5)
    }

    /**
     * 带方向性的阴影绘制模式。
     *
     * [UNIFORM] 四边都有外扩阴影，适合独立的方形按键。
     *
     * [BLEED_NONE] **不向外扩**，只在键面内侧做上亮下暗：用于运算符键这类
     * 「多个键紧贴拼成一个整体」的场景。它们之间只有 2dp 间距，外扩阴影会在
     * 接缝处叠出暗线，看起来像被切成几层；不外扩则保持同一条面，观感统一。
     */
    internal enum class BleedMode { UNIFORM, BLEED_NONE }

    private const val MAX_ENTRIES = 48

    /**
     * 上 / 左 / 右三边相对底部的阴影强度折减比例（0.75 = 三边只剩四分之一）。
     *
     * 历史：0.5（三边一半）→ 0.75（再轻一半，三边为底部的 1/4）。
     */
    internal const val SIDE_FACTOR = 0.75f

    /** 三边衰减的过渡带宽度，取键高的比例，避免出现硬接缝。 */
    internal const val FADE_WIDE_RATIO = 0.12f

    private val shadowCache = lruCache<Spec, ImageBitmap>()
    private val bevelCache = lruCache<Spec, ImageBitmap>()

    private fun <K, V> lruCache() = object : LinkedHashMap<K, V>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
            size > MAX_ENTRIES
    }

    fun shadow(spec: Spec): ImageBitmap = synchronized(shadowCache) {
        shadowCache[spec] ?: renderShadow(spec).also { shadowCache[spec] = it }
    }

    fun bevel(spec: Spec): ImageBitmap = synchronized(bevelCache) {
        bevelCache[spec] ?: renderBevel(spec).also { bevelCache[spec] = it }
    }

    /** 仅测试用：清空两张缓存。 */
    internal fun clear() {
        synchronized(shadowCache) { shadowCache.clear() }
        synchronized(bevelCache) { bevelCache.clear() }
    }

    internal fun size(): Int =
        synchronized(shadowCache) { shadowCache.size } + synchronized(bevelCache) { bevelCache.size }

    /**
     * 阴影 + 键面底色（不透明）。位图比按键本体大一圈，承载外扩的软阴影。
     *
     * 键面在此填色，因此调用方的 `.background()` 即使画在上面也只是同色覆盖，
     * 不会让立体感消失；高光/压暗由 [renderBevel] 单独叠在上面。
     */
    internal fun renderShadow(spec: Spec): ImageBitmap {
        val w = spec.widthPx.coerceAtLeast(1)
        val h = spec.heightPx.coerceAtLeast(1)
        val pad = shadowPaddingOf(spec)
        val radius = spec.cornerRadiusPx.coerceIn(0, minOf(w, h) / 2)
        val strength = spec.strengthPercent.coerceIn(0, 100) / 100f
        val pressed = spec.pressed

        val bitmap = android.graphics.Bitmap.createBitmap(
            w + pad * 2, h + pad * 2, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)

        val base = spec.backgroundColor.toArgb()
        val faceArgb = if (pressed) darken(base, 0.2f) else base
        val face = android.graphics.RectF(
            pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()
        )
        val r = radius.toFloat()

        val shadowAlpha = ((if (pressed) 35 else 60) * strength).toInt().coerceIn(0, 255)
        if (shadowAlpha > 0) {
            // 底部阴影完整，上 / 左 / 右三边减半。
            //
            // 真实光照下按键下方遮挡最多，三边只需要一点轮廓光。做法：把阴影画进
            // **独立图层**，再按「像素到键面边界的距离」逐像素做半强度衰减，最后合成。
            //
            // 为什么不用 PorterDuff 挖洞：DST_OUT 会连键面一起挖掉，且 xfermode 在
            // 硬件加速层上的行为依版本而异。逐像素衰减只作用于阴影层，键面完全不受影响。
            val layer = android.graphics.Bitmap.createBitmap(
                bitmap.width, bitmap.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            val layerCanvas = android.graphics.Canvas(layer)
            layerCanvas.save()
            layerCanvas.translate(0f, if (pressed) h * 0.010f else h * 0.018f)
            layerCanvas.drawRoundRect(
                face, r, r,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        (shadowAlpha * 0.55f).toInt().coerceIn(0, 255), 0, 0, 0
                    )
                    maskFilter = android.graphics.BlurMaskFilter(
                        (if (pressed) h * 0.09f else h * 0.16f).coerceAtLeast(1.5f),
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
            )
            layerCanvas.drawRoundRect(
                face, r, r,
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.argb(
                        (shadowAlpha * 0.85f).toInt().coerceIn(0, 255), 0, 0, 0
                    )
                    maskFilter = android.graphics.BlurMaskFilter(
                        (if (pressed) h * 0.035f else h * 0.055f).coerceAtLeast(1f),
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
            )
            layerCanvas.restore()

            attenuateThreeSides(
                layer = layer,
                faceLeft = face.left.toInt(),
                faceTop = face.top.toInt(),
                faceRight = face.right.toInt(),
                faceBottom = face.bottom.toInt(),
            )
            canvas.drawBitmap(layer, 0f, 0f, null)
            layer.recycle()
        }

        canvas.drawRoundRect(
            face, r, r,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = faceArgb }
        )
        return bitmap.asImageBitmap()
    }

    /**
     * 顶部高光 + 底部压暗（**透明背景**，只含这两条渐变）。
     *
     * 单独成图是为了能在 `.background()` 之后绘制，浮在键面之上。
     * 圆角处用圆角矩形遮罩裁剪，避免溢出到键外。
     */
    internal fun renderBevel(spec: Spec): ImageBitmap {
        val w = spec.widthPx.coerceAtLeast(1)
        val h = spec.heightPx.coerceAtLeast(1)
        val radius = spec.cornerRadiusPx.coerceIn(0, minOf(w, h) / 2)
        val strength = spec.strengthPercent.coerceIn(0, 100) / 100f
        val pressed = spec.pressed

        val bitmap = android.graphics.Bitmap.createBitmap(
            w, h, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)
        val face = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())
        val r = radius.toFloat()

        // 用圆角路径裁剪，保证渐变不越过圆角
        val clipPath = android.graphics.Path().apply {
            addRoundRect(face, r, r, android.graphics.Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(clipPath)

        // 按下时高光收敛（键面被压下，正面受光减少）
        val highlightScale = if (pressed) 0.6f else 1f
        // 悬浮卡片风格里，键面本身几乎是**纯色**：立体感主要来自外圈阴影，
        // 键面上只留极轻的顶部提亮与底部收边，避免出现「金属按键」式的强渐变。
        val highlightH = (h * 0.34f).coerceAtLeast(2f)
        canvas.drawRect(
            0f, 0f, w.toFloat(), highlightH,
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    0f, 0f, 0f, highlightH,
                    android.graphics.Color.argb(
                        (34 * strength * highlightScale).toInt().coerceIn(0, 255), 255, 255, 255
                    ),
                    android.graphics.Color.argb(0, 255, 255, 255),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
        )

        val depthH = (h * 0.18f).coerceAtLeast(2f)
        canvas.drawRect(
            0f, h - depthH, w.toFloat(), h.toFloat(),
            android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    0f, h - depthH, 0f, h.toFloat(),
                    android.graphics.Color.argb(0, 0, 0, 0),
                    android.graphics.Color.argb((26 * strength).toInt().coerceIn(0, 255), 0, 0, 0),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
        )
        canvas.restore()
        return bitmap.asImageBitmap()
    }

    /**
     * 把阴影层在上 / 左 / 右三边按 [SIDE_FACTOR] 折减，底部保持不变。
     *
     * 逐像素处理，只作用在独立的阴影层上，键面不受影响。
     * 衰减量按「像素到键面边界的距离」线性过渡，过渡宽度取键高的 12%，
     * 因此边缘不会出现硬接缝。
     */
    private fun attenuateThreeSides(
        layer: android.graphics.Bitmap,
        faceLeft: Int,
        faceTop: Int,
        faceRight: Int,
        faceBottom: Int,
    ) {
        val width = layer.width
        val height = layer.height
        val fade = ((faceBottom - faceTop) * FADE_WIDE_RATIO).toInt().coerceAtLeast(2)
        val pixels = IntArray(width * height)
        layer.getPixels(pixels, 0, width, 0, 0, width, height)

        val keep = 1f - SIDE_FACTOR   // 三边保留比例
        for (y in 0 until height) {
            // 垂直方向：键面之上（含过渡带）逐渐衰减；键面下方保持完整
            val above = faceTop - y
            val verticalKeep = if (above > 0) {
                keep + SIDE_FACTOR * (1f - (above.toFloat() / fade).coerceIn(0f, 1f))
            } else 1f

            for (x in 0 until width) {
                // 水平方向：键面左右两侧衰减
                val dx = when {
                    x < faceLeft -> faceLeft - x
                    x >= faceRight -> x - faceRight + 1
                    else -> 0
                }
                val horizontalKeep = if (dx > 0) {
                    keep + SIDE_FACTOR * (1f - (dx.toFloat() / fade).coerceIn(0f, 1f))
                } else 1f

                val factor = minOf(verticalKeep, horizontalKeep)
                if (factor >= 1f) continue

                val idx = y * width + x
                val argb = pixels[idx]
                val a = (argb ushr 24) and 0xFF
                if (a == 0) continue
                pixels[idx] = (argb and 0x00FFFFFF) or ((a * factor).toInt().coerceIn(0, 255) shl 24)
            }
        }
        layer.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun darken(argb: Int, factor: Float): Int {
        val keep = 1 - factor
        return android.graphics.Color.argb(
            (argb ushr 24) and 0xFF,
            (((argb shr 16) and 0xFF) * keep).toInt(),
            (((argb shr 8) and 0xFF) * keep).toInt(),
            ((argb and 0xFF) * keep).toInt(),
        )
    }
}

/**
 * 阴影 + 键面层：放在 `.clip` 之前，向外扩出 padding 承载软阴影。
 *
 * 用 [drawBehind]：它在背景与内容之下绘制，且不受后续 `clip` 影响（clip 只作用于之后的绘制）。
 */
@Composable
internal fun rememberKeyShadowModifier(
    backgroundColor: Color,
    shadowEnabled: Boolean,
    shadowShapeRadius: Dp,
    pressed: Boolean,
    strengthPercent: Int,
    bleed: KeyDepthCache.BleedMode = KeyDepthCache.BleedMode.UNIFORM,
): Modifier {
    if (!shadowEnabled || strengthPercent <= 0) return Modifier
    // BLEED_NONE：紧贴拼接的键不向外画阴影，避免接缝处叠出「分层」暗线。
    if (bleed == KeyDepthCache.BleedMode.BLEED_NONE) return Modifier
    return remember(backgroundColor, pressed, shadowShapeRadius, strengthPercent) {
        Modifier.drawBehind { drawKeyShadow(backgroundColor, shadowShapeRadius, pressed, strengthPercent) }
    }
}

/**
 * 高光 + 压暗层：**必须放在 `.background()` 之后**，否则会被键面底色盖住。
 */
@Composable
internal fun rememberKeyBevelModifier(
    backgroundColor: Color,
    shadowEnabled: Boolean,
    shadowShapeRadius: Dp,
    pressed: Boolean,
    strengthPercent: Int,
): Modifier {
    if (!shadowEnabled || strengthPercent <= 0) return Modifier
    return remember(backgroundColor, pressed, shadowShapeRadius, strengthPercent) {
        Modifier.drawWithContent {
            drawContent()
            drawKeyBevel(backgroundColor, shadowShapeRadius, pressed, strengthPercent)
        }
    }
}

private fun DrawScope.drawKeyShadow(
    backgroundColor: Color,
    shadowShapeRadius: Dp,
    pressed: Boolean,
    strengthPercent: Int,
) {
    val heightPx = size.height.toInt().coerceAtLeast(1)
    val widthPx = size.width.toInt().coerceAtLeast(1)
    val spec = KeyDepthCache.Spec(
        widthPx = widthPx,
        heightPx = heightPx,
        cornerRadiusPx = shadowShapeRadius.toPx().toInt().coerceIn(0, minOf(widthPx, heightPx) / 2),
        backgroundColor = backgroundColor,
        pressed = pressed,
        strengthPercent = strengthPercent,
    )
    val image = KeyDepthCache.shadow(spec)
    val pad = KeyDepthCache.shadowPaddingOf(spec)
    drawImage(
        image = image,
        dstOffset = IntOffset(-pad, -pad),
        dstSize = IntSize(image.width, image.height),
    )
}

private fun DrawScope.drawKeyBevel(
    backgroundColor: Color,
    shadowShapeRadius: Dp,
    pressed: Boolean,
    strengthPercent: Int,
) {
    val heightPx = size.height.toInt().coerceAtLeast(1)
    val widthPx = size.width.toInt().coerceAtLeast(1)
    val spec = KeyDepthCache.Spec(
        widthPx = widthPx,
        heightPx = heightPx,
        cornerRadiusPx = shadowShapeRadius.toPx().toInt().coerceIn(0, minOf(widthPx, heightPx) / 2),
        backgroundColor = backgroundColor,
        pressed = pressed,
        strengthPercent = strengthPercent,
    )
    val image = KeyDepthCache.bevel(spec)
    drawImage(
        image = image,
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(image.width, image.height),
    )
}