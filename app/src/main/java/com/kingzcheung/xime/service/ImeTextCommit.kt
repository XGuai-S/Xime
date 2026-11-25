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
import com.kingzcheung.xime.clipboard.OtpClipboardPolicy
import com.kingzcheung.xime.service.otp.OtpCollector
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
        val voice = service.voiceRecognitionHandler
        val connection = service.currentInputConnection
        val expected = voice.textWrittenByVoiceInput
        val queryLength = (voice.voiceInputPrefixAnchor.length.toLong() + expected.length.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val currentTail = if (queryLength == 0) null else runCatching {
            connection?.getTextBeforeCursor(queryLength, 0)?.toString()
        }.getOrNull()
        val charsToDelete = voiceUndoLength(
            expectedText = expected,
            prefixAnchor = voice.voiceInputPrefixAnchor,
            currentTextBeforeCursor = currentTail,
            sameConnection = connection != null && connection === voice.voiceInputConnection &&
                runCatching { connection.getSelectedText(0).isNullOrEmpty() }.getOrDefault(false),
        )
        if (charsToDelete > 0 && connection != null && connection.finishComposingText()) {
            val unchanged = runCatching {
                connection.getSelectedText(0).isNullOrEmpty() &&
                    connection.getTextBeforeCursor(queryLength, 0)?.toString() == currentTail
            }.getOrDefault(false)
            if (unchanged) connection.deleteSurroundingText(charsToDelete, 0)
        }
        voice.clearUndoBookkeeping()
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

            // 关键优化：只有当源文件不在 FileProvider 声明的根目录下时才复制。
            //
            // 旧实现无条件把图片整份 copyTo 到 cacheDir/emoji_cache —— 这一步在**主线程**上
            // （commitImage 由表情面板点选回调直接调用），一张 2~4MB 的图片就是十几毫秒的
            // 主线程 IO，键盘当场掉帧。而 filepaths.xml 里已经声明了
            // `<files-path path="."/>` 与 `<cache-path path="emoji_cache/"/>`：
            // 剪贴板图片（files/clipboard_images/）与插件表情（files/plugins/...）本来就能
            // 直接生成 content URI，压根不需要这份副本。副本还会在 emoji_cache 里堆积，
            // 靠 7 天回收才消掉。
            val providerReady = resolveProviderReadyFile(imageFile)
            val cacheFile = if (providerReady != null) {
                providerReady
            } else {
                val cacheDir = File(service.cacheDir, "emoji_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                File(cacheDir, imageFile.name).also { target ->
                    FileInputStream(imageFile).use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    FileLogger.i(
                        XimeInputMethodService.TAG,
                        "commitImage: source outside provider roots, copied ${imageFile.length()}B -> ${target.absolutePath}",
                    )
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
    /**
     * 剪贴板候选点选上屏。
     *
     * @param id 被点选条目的 id（有则按 id 精确标记已消费；null 走按文本兜底匹配）
     */
    internal fun selectClipboardItem(text: String, id: Long? = null) {
        val selected = if (id != null) {
            service.clipboardManager.clipboardItems.value.firstOrNull { it.id == id && it.text == text }
                ?: return
        } else null
        if (selected?.isOtp == true) {
            if (!OtpClipboardPolicy.isFresh(selected.timestamp, System.currentTimeMillis())) return
            if (service.uiState.value.quickSendFormFocused || service.uiState.value.toolPanelInputFocused ||
                service.uiState.value.clipboardSearchFocused
            ) return
            val connection = service.currentInputConnection ?: return
            if (service.candidateState.value.isComposing) {
                service.keyRouter.postRimeJob {
                    service.rimeEngine.clearComposition()
                    withContext(Dispatchers.Main) { service.updateUI() }
                }
            }
            if (connection.commitText(text, 1)) {
                service.closeClipboardCandidateBar()
                service.clipboardManager.markConsumedExact(selected.id)
                OtpCollector.consume(text)
            }
            return
        }
        if (service.candidateState.value.isComposing) {
            service.keyRouter.postRimeJob {
                service.rimeEngine.clearComposition()
                withContext(Dispatchers.Main) {
                    service.updateUI()
                }
            }
        }
        // 先关掉候选栏的剪贴板展示态（并抑制随后的 consumed 落库回声），
        // 否则「标记已消费 → 数据库 Flow 再发一次 → collector 判定候选栏空闲 →
        // 把剩下的条目又推回来」会让用户看到「上屏了还在候选栏显示」。
        service.closeClipboardCandidateBar()
        // 标记为已消费：候选栏/剪贴板点选上屏后不再重复出现在候选栏。
        // 优先按 id 精确标记 —— 本条上屏后会把内容写回系统剪贴板（下一次 copyToSystemClipboard），
        // 若此刻按文本「最近一条未消费」重新查找，可能标到另一行（文本相同的新行），
        // 被点的那行仍是 consumed=0，于是下次进输入框它又出现（用户 2026-11 反馈）。
        val itemId = id
        if (itemId != null) {
            service.clipboardManager.markConsumedExact(itemId)
        } else {
            service.clipboardManager.markConsumed(text)
        }
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
        service.closeClipboardCandidateBar()
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

    /**
     * 源文件是否已落在 FileProvider 可服务的根目录内（是则返回规范化文件，否则 null）。
     *
     * 判据必须与 `res/xml/filepaths.xml` 的声明保持一致：
     *  - `<files-path name="files" path="."/>` → filesDir 整棵子树
     *  - `<cache-path name="emoji_cache" path="emoji_cache/"/>` → cacheDir/emoji_cache
     *  - `<cache-path name="log_export" path="log_export/"/>` → 与图片无关，不参与判定
     *
     * 用 canonicalPath 比较：否则 `files/../cache/x.png` 这类路径会被误判成「已在 filesDir 下」，
     * 最后 FileProvider 抛 IllegalArgumentException 再走 MediaBridge 兜底（多一次无谓写入）。
     */
    private fun resolveProviderReadyFile(imageFile: File): File? {
        return try {
            val canonical = imageFile.canonicalFile
            val filesRoot = service.filesDir.canonicalFile
            val emojiCacheRoot = File(service.cacheDir, "emoji_cache").canonicalFile
            when {
                canonical.startsWith(filesRoot) || canonical.startsWith(emojiCacheRoot) -> canonical
                else -> null
            }
        } catch (e: Exception) {
            FileLogger.w(XimeInputMethodService.TAG, "resolveProviderReadyFile failed", e)
            null
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
