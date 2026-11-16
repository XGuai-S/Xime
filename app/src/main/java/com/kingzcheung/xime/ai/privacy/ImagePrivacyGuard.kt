package com.kingzcheung.xime.ai.privacy

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * Viewport Screenshot Privacy Guard (`ImagePrivacyGuard`):
 * - Drops screenshots automatically when `chatContextSendScreenshot == false`, when any high-risk
 *   credential (`PASSWORD_SECRET`, `OTP_CODE`, `ID_CARD`, `BANK_CARD`, `API_KEY`) is detected on
 *   screen, or when any password node / incognito / sensitive blacklisted app is present.
 * - When only reversible contact PII (`PHONE`, `EMAIL`, `ADDRESS`, `PICKUP_CODE`) is present and
 *   screenshots are enabled, maps the sensitive `RawScreenTextNode` screen bounds into the cropped
 *   and scaled `Bitmap` coordinate space (with a `4px` safety margin) and paints opaque dark
 *   charcoal (`#18181B`) blackout rectangles prior to JPEG encoding.
 */
object ImagePrivacyGuard {

    const val CHARCOAL_MASK_COLOR: Int = 0xFF18181B.toInt()
    const val DEFAULT_SAFETY_PADDING_PX: Int = 4

    /**
     * Determines whether the viewport screenshot must be discarded before encoding/sending.
     */
    fun shouldDropScreenshot(
        chatContextSendScreenshot: Boolean,
        hasHighRiskCredentials: Boolean,
        hasPasswordNode: Boolean = false,
        isIncognito: Boolean = false,
        isBlacklistedPackage: Boolean = false
    ): Boolean {
        return !chatContextSendScreenshot ||
            hasHighRiskCredentials ||
            hasPasswordNode ||
            isIncognito ||
            isBlacklistedPackage
    }

    /**
     * Pure JVM-testable coordinate transformer that maps full-screen node bounds
     * (`[left, top, right, bottom]`) into the cropped (`cropTopPx`, `cropHeightPx`) and scaled
     * (`targetWidthPx` x `targetHeightPx`) bitmap coordinate space, expanding each rect by
     * [expandPaddingPx] (`4px` default) on all four sides and clamping to bitmap bounds.
     */
    fun computeMaskedRectsOnCroppedBitmap(
        rawScreenRects: List<IntArray>,
        cropTopPx: Int,
        cropHeightPx: Int,
        sourceWidthPx: Int,
        targetWidthPx: Int,
        targetHeightPx: Int,
        expandPaddingPx: Int = DEFAULT_SAFETY_PADDING_PX
    ): List<IntArray> {
        if (rawScreenRects.isEmpty() ||
            cropHeightPx <= 0 ||
            sourceWidthPx <= 0 ||
            targetWidthPx <= 0 ||
            targetHeightPx <= 0
        ) {
            return emptyList()
        }

        val cropBottomPx = cropTopPx + cropHeightPx
        val scaleX = targetWidthPx.toFloat() / sourceWidthPx.toFloat()
        val scaleY = targetHeightPx.toFloat() / cropHeightPx.toFloat()

        val result = mutableListOf<IntArray>()
        for (rect in rawScreenRects) {
            if (rect.size < 4) continue
            val rawLeft = rect[0]
            val rawTop = rect[1]
            val rawRight = rect[2]
            val rawBottom = rect[3]
            if (rawRight <= rawLeft || rawBottom <= rawTop) continue

            // Check intersection with cropped vertical viewport [cropTopPx, cropBottomPx]
            val intersectTop = rawTop.coerceAtLeast(cropTopPx)
            val intersectBottom = rawBottom.coerceAtMost(cropBottomPx)
            if (intersectBottom <= intersectTop) continue

            val intersectLeft = rawLeft.coerceIn(0, sourceWidthPx)
            val intersectRight = rawRight.coerceIn(0, sourceWidthPx)
            if (intersectRight <= intersectLeft) continue

            val mappedLeft = ((intersectLeft * scaleX).roundToInt() - expandPaddingPx)
                .coerceIn(0, targetWidthPx)
            val mappedTop = (((intersectTop - cropTopPx) * scaleY).roundToInt() - expandPaddingPx)
                .coerceIn(0, targetHeightPx)
            val mappedRight = ((intersectRight * scaleX).roundToInt() + expandPaddingPx)
                .coerceIn(0, targetWidthPx)
            val mappedBottom = (((intersectBottom - cropTopPx) * scaleY).roundToInt() + expandPaddingPx)
                .coerceIn(0, targetHeightPx)

            if (mappedRight > mappedLeft && mappedBottom > mappedTop) {
                result.add(intArrayOf(mappedLeft, mappedTop, mappedRight, mappedBottom))
            }
        }
        return result
    }

    /**
     * Paints opaque dark charcoal (`#18181B`) rectangles over [maskedBitmapRects] on [source] bitmap.
     */
    fun applyCharcoalRedactionOnBitmap(
        source: Bitmap,
        maskedBitmapRects: List<IntArray>,
        maskColor: Int = CHARCOAL_MASK_COLOR
    ): Bitmap {
        if (maskedBitmapRects.isEmpty()) return source
        val mutableBitmap = if (source.isMutable && source.config == Bitmap.Config.ARGB_8888) {
            source
        } else {
            val copy = source.copy(Bitmap.Config.ARGB_8888, true)
            if (copy != null && copy !== source) {
                runCatching { source.recycle() }
            }
            copy ?: return source
        }

        val canvas = Canvas(mutableBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = maskColor
        }
        for (rect in maskedBitmapRects) {
            if (rect.size < 4) continue
            canvas.drawRect(
                rect[0].toFloat(),
                rect[1].toFloat(),
                rect[2].toFloat(),
                rect[3].toFloat(),
                paint
            )
        }
        return mutableBitmap
    }
}
