package com.kingzcheung.xime.service

import com.kingzcheung.xime.util.FileLogger
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kingzcheung.xime.clipboard.ClipboardItem
import com.kingzcheung.xime.clipboard.MediaBridge
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文本上屏与剪贴板提交。
 *
 * 承载 commitImage（图片上屏）、剪贴板候选提交（selectClipboardItem/commitClipboardText/
 * deleteClipboardChars）与语音撤销/搜索动作（performUndo/performSearch）。
 * 共享状态通过 service 引用访问。
 */
internal class ImeTextCommit(private val service: XimeInputMethodService) {
    internal fun performUndo() {
        val currentTextBeforeCursor = service.currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        val currentLength = currentTextBeforeCursor.length
        
        val charsToDelete = currentLength - service.voiceRecognitionHandler.textLengthBeforeVoiceInput
        
        if (charsToDelete > 0) {
            for (i in 0 until charsToDelete) {
                service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
        }
        
        service.voiceRecognitionHandler.textBeforeVoiceInput = ""
        service.voiceRecognitionHandler.textLengthBeforeVoiceInput = 0
    }
    
    internal fun performSearch() {
        service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        service.currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
    }

    internal fun commitImage(imagePath: String, mimeType: String = "image/jpeg"): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                FileLogger.e(XimeInputMethodService.TAG, "Image file not found: $imagePath")
                return false
            }

            // 按扩展名修正真实 MIME 类型（PNG/GIF/WebP 表情不应声明为 image/jpeg）
            val actualMimeType = when (imageFile.extension.lowercase()) {
                "png" -> "image/png"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> mimeType
            }

            // 宿主 `contentMimeTypes` 只是一个**声明**，很多应用（微信/QQ/部分 IM 的
            // 聊天输入框）并未声明却实际能收 commitContent。旧实现只看声明就 return false，
            // 导致「点图片没反应、只能复制到剪贴板」，用户以为发送失败。
            // 现在改为：声明支持的按 MIME 校验；未声明/不匹配也**仍然尝试** commitContent，
            // 由 InputConnection 的真实返回值决定（false 才降级剪贴板）。
            val supportedMimeTypes = service.currentInputEditorInfo?.contentMimeTypes
            val declaredOk = supportsMimeType(supportedMimeTypes, actualMimeType)
            if (!declaredOk) {
                Log.i(
                    XimeInputMethodService.TAG,
                    "Host did not declare image MIME (contentMimeTypes=${supportedMimeTypes?.contentToString()}), trying commitContent anyway",
                )
            }

            val cacheDir = File(service.cacheDir, "emoji_cache")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            
            val cacheFile = File(cacheDir, imageFile.name)
            FileInputStream(imageFile).use { input ->
                cacheFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val uri = getContentUriForImage(cacheFile, actualMimeType) ?: return false
            
            val inputContentInfo = InputContentInfo(
                uri,
                android.content.ClipDescription("emoji_image", arrayOf(actualMimeType)),
                null
            )
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
                InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
            } else {
                0
            }
            
            val ok = service.currentInputConnection?.commitContent(inputContentInfo, flags, null) ?: false
            if (!ok) {
                FileLogger.i(
                    XimeInputMethodService.TAG,
                    "commitImage: host rejected content (mime=$actualMimeType, declared=$declaredOk, pkg=${service.currentInputEditorInfo?.packageName})",
                )
            } else {
                FileLogger.i(
                    XimeInputMethodService.TAG,
                    "commitImage: inserted into input box (mime=$actualMimeType, pkg=${service.currentInputEditorInfo?.packageName})",
                )
            }
            ok
            
        } catch (e: Exception) {
            FileLogger.e(XimeInputMethodService.TAG, "Failed to commit image", e)
            false
        }
    }
    internal fun selectClipboardItem(text: String) {
        if (service.candidateState.value.isComposing) {
            service.keyRouter.postRimeJob {
                service.rimeEngine.clearComposition()
                withContext(Dispatchers.Main) {
                    service.updateUI()
                }
            }
        }
        // 标记为已消费：候选栏/剪贴板点选上屏后不再重复出现在候选栏
        service.clipboardManager.markConsumed(text)
        // 粘贴不计打字统计（不投 text_committed），联想照常
        service.commitPastedText(text)
        service.clipboardManager.copyToSystemClipboard(text)
    }

    /**
     * 剪贴板**图片**条目点选。
     *
     * 发送链路（三段式）：
     * 1. commitContent 直插输入框（微信等支持时图片直接进聊天框）；
     * 2. Operit AI 专用定向发送（输入框完全不吃图，走它的 ACTION_SEND 入口）；
     * 3. 写剪贴板 + 触发宿主粘贴动作（其余应用，静默，不判定结果）。
     *
     * 不为「输入框不收图」的宿主做额外兜底（京东聊天 / TRAE / QQ 等）：
     * 强行补偿只会把用户引到别的页面或触发系统隐私弹窗，反而干扰正常应用。
     */
    internal fun selectClipboardImage(item: ClipboardItem) {
        service.clipboardManager.markConsumedById(item.id)
        val file = service.clipboardManager.imageFileOf(item)
        if (file == null) {
            FileLogger.w(XimeInputMethodService.TAG, "Clipboard image file missing: ${item.imagePath}")
            Toast.makeText(service, "图片已不在本地，无法发送", Toast.LENGTH_SHORT).show()
            return
        }
        val mimeType = item.mimeType.ifEmpty { "image/jpeg" }

        // 1. commitContent 直插
        if (commitImage(file.absolutePath, mimeType)) return

        // 2. Operit AI 定向发送（唯一例外）
        if (isOperitHost() && launchDirectImageSend(file.absolutePath, mimeType)) return

        // 3. 写剪贴板 + paste（静默）
        val copied = service.clipboardManager.copyImageToSystemClipboard(
            imagePath = file.absolutePath,
            label = "xime_clipboard_image",
            shareCacheCopy = false,
            useMediaStoreUri = true,
        )
        if (copied) {
            try {
                service.currentInputConnection?.performContextMenuAction(android.R.id.paste)
            } catch (e: Exception) {
                FileLogger.w(XimeInputMethodService.TAG, "performContextMenuAction(paste) failed", e)
            }
        }
    }


    /** 当前输入框是否属于 Operit AI（唯一需要定向发送的宿主）。 */
    private fun isOperitHost(): Boolean {
        val pkg = service.currentInputEditorInfo?.packageName ?: return false
        return pkg.startsWith("com.ai.assistance.operit")
    }

    /**
     * 定向调起宿主自己的图片接收入口。
     *
     * 用 `setPackage` 限定只唤起当前宿主，不弹全应用分享选择器；URI 走
     * MediaStore 公共库（外部可读），保证对方能拿到图。调用方
     * 调用方已限定为 Operit AI，其余应用不会走到这里。
     */
    private fun launchDirectImageSend(imagePath: String, mimeType: String): Boolean {
        val pkg = service.currentInputEditorInfo?.packageName ?: return false
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) return false
            val uri = MediaBridge.insert(service, imageFile, mimeType)
                ?: getContentUriForImage(imageFile, mimeType)
                ?: return false
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage(pkg)
            }
            service.startActivity(
                Intent.createChooser(send, "发送图片")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            FileLogger.i(XimeInputMethodService.TAG, "launchDirectImageSend: dispatched to $pkg")
            true
        } catch (e: Exception) {
            FileLogger.w(XimeInputMethodService.TAG, "launchDirectImageSend failed", e)
            false
        }
    }

    internal fun commitClipboardText(text: String) {
        service.commitPastedText(text)
    }

    internal fun deleteClipboardChars(count: Int) {
        service.currentInputConnection?.deleteSurroundingText(count, 0)
    }

    private fun supportsMimeType(declaredMimeTypes: Array<String>?, mimeType: String): Boolean {
        if (declaredMimeTypes.isNullOrEmpty()) return false
        return declaredMimeTypes.any { declared ->
            declared == "*/*" ||
                declared.equals(mimeType, ignoreCase = true) ||
                (declared.endsWith("/*") && mimeType.startsWith(declared.removeSuffix("/*"), ignoreCase = true))
        }
    }

    private fun getContentUriForImage(imageFile: File, mimeType: String): Uri? {
        try {
            return FileProvider.getUriForFile(
                service,
                "${service.packageName}.fileprovider",
                imageFile
            )
        } catch (e: IllegalArgumentException) {
            FileLogger.w(XimeInputMethodService.TAG, "FileProvider unavailable, falling back to MediaBridge", e)
        } catch (e: Exception) {
            FileLogger.w(XimeInputMethodService.TAG, "FileProvider getUriForFile failed, falling back to MediaBridge", e)
        }
        return MediaBridge.insert(service, imageFile, mimeType, autoCleanup = false)
    }
}
