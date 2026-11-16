package com.kingzcheung.xime.ai.bubble

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * Bundled On-Device Offline Chinese Viewport OCR (`ViewportOcrExtractor`):
 * - Uses bundled ML Kit `com.google.mlkit:text-recognition-chinese:16.0.1` (zero network, zero
 *   Google Play Services dependency, instant offline startup).
 * - Recognizes custom-drawn chat bubbles (e.g., Mobile QQ NT / WeChat `MMNeat7extView` custom
 *   `Canvas` bubbles and inline screenshot text) directly from the cropped in-memory viewport bitmap.
 * - Maps recognized `Text.Line` bounding boxes back into full-screen coordinates so
 *   [ChatScreenContextExtractor.classifySpeaker], [LocalPiiRedactor], and [com.kingzcheung.xime.ai.privacy.ImagePrivacyGuard]
 *   can classify `[对方]`/`[我]`, redact PII/high-risk credentials, and paint charcoal blackout masks.
 */
object ViewportOcrExtractor {

    private val recognizer: TextRecognizer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    @Volatile
    var debugSimulatedOcrNodesForTest: List<RawScreenTextNode>? = null

    /**
     * Maps a recognized OCR text line and its bounding box in the cropped/scaled bitmap coordinate
     * space back to the original full-screen coordinate space.
     */
    fun mapOcrLineToScreenNode(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        cropX: Int,
        cropY: Int,
        scaleRatio: Float
    ): RawScreenTextNode? {
        val clean = text.trim()
        if (clean.isEmpty() || right <= left || bottom <= top) return null
        val safeScale = if (scaleRatio > 0.01f && scaleRatio.isFinite()) scaleRatio else 1.0f
        val screenLeft = cropX + (left / safeScale).roundToInt()
        val screenTop = cropY + (top / safeScale).roundToInt()
        val screenRight = cropX + (right / safeScale).roundToInt()
        val screenBottom = cropY + (bottom / safeScale).roundToInt()
        if (screenRight <= screenLeft || screenBottom <= screenTop) return null
        return RawScreenTextNode(
            text = clean,
            boundsTop = screenTop,
            boundsBottom = screenBottom,
            boundsLeft = screenLeft,
            boundsRight = screenRight,
            isEditable = false,
            isClickable = false,
            viewIdResourceName = "mlkit_ocr_line",
            className = "android.widget.TextView",
            isPassword = false,
            isVisibleToUser = true
        )
    }

    private fun extractNodesFromVisionText(
        visionText: Text,
        cropX: Int,
        cropY: Int,
        scaleRatio: Float
    ): List<RawScreenTextNode> {
        val out = ArrayList<RawScreenTextNode>(32)
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val mapped = mapOcrLineToScreenNode(
                    text = line.text,
                    left = box.left,
                    top = box.top,
                    right = box.right,
                    bottom = box.bottom,
                    cropX = cropX,
                    cropY = cropY,
                    scaleRatio = scaleRatio
                )
                if (mapped != null) {
                    out.add(mapped)
                }
            }
        }
        return out.sortedBy { it.boundsTop }
    }

    /**
     * Runs on-device Chinese OCR on [bitmap] with a hard [timeoutMs] (`750ms` default) budget.
     * Any failure or timeout degrades silently to `emptyList()`.
     */
    suspend fun recognizeViewportNodes(
        bitmap: Bitmap,
        cropX: Int,
        cropY: Int,
        scaleRatio: Float,
        timeoutMs: Long = 750L
    ): List<RawScreenTextNode> {
        debugSimulatedOcrNodesForTest?.let { return it }
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyList()
        }
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    try {
                        val inputImage = InputImage.fromBitmap(bitmap, 0)
                        recognizer.process(inputImage)
                            .addOnSuccessListener { visionText ->
                                if (cont.isActive) {
                                    val nodes = runCatching {
                                        extractNodesFromVisionText(visionText, cropX, cropY, scaleRatio)
                                    }.getOrDefault(emptyList())
                                    cont.resume(nodes)
                                }
                            }
                            .addOnFailureListener {
                                if (cont.isActive) {
                                    cont.resume(emptyList())
                                }
                            }
                            .addOnCanceledListener {
                                if (cont.isActive) {
                                    cont.resume(emptyList())
                                }
                            }
                    } catch (_: Throwable) {
                        if (cont.isActive) {
                            cont.resume(emptyList())
                        }
                    }
                }
            } ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
