package com.kingzcheung.xime.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.kingzcheung.xime.clipboard.db.ClipboardDatabase
import com.kingzcheung.xime.clipboard.db.ClipboardEntry
import com.kingzcheung.xime.plugin.core.api.ClipboardProfile
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileInputStream
import android.content.ClipboardManager as AndroidClipboardManager

data class ClipboardItem(
    val id: Long = 0,
    val text: String,
    /** 快捷发送触发编码（如 dh），空 = 仅内容命中不参与编码匹配。 */
    val code: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isQuickSend: Boolean = false,
    val consumed: Boolean = false,
    /** 条目类型：[ClipboardEntry.TYPE_TEXT] / [ClipboardEntry.TYPE_IMAGE]（图片条目 text 为空串）。 */
    val type: String = ClipboardEntry.TYPE_TEXT,
    /** 图片文件在 `files/` 下的相对路径（如 `clipboard_images/<sha256>.png`）。 */
    val imagePath: String = "",
    /** 图片内容 SHA-256（去重键）。 */
    val imageHash: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    val isImage: Boolean get() = type == ClipboardEntry.TYPE_IMAGE
}

/** 图片占用快照（剪贴板设置页"已用空间"用）。 */
data class ImageUsage(
    val count: Int = 0,
    val bytes: Long = 0,
    val fileBytes: Long = 0,
)

class ClipboardManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ClipboardManager"
        private const val MAX_ITEMS = 1000
        private const val MAX_QUICK_SEND_ITEMS = 2000
        private const val PREFS_NAME = "clipboard_prefs"
        private const val KEY_CLIPBOARD_ITEMS = "clipboard_items"
        private const val KEY_QUICK_SEND_ITEMS = "quick_send_items"

        /** 拒收提示（图片过大/尺寸过大）的去重窗口，避免连续复制时连环弹 Toast。 */
        private const val REJECT_TOAST_INTERVAL_MS = 10_000L

        /**
         * 同步导入图片的**绝对**上限 = 设置允许的最大单张（1–20MB 的最大值）。
         *
         * 远端设备可能把单张上限调到比本机大，按本机上限拒收会让同步"看起来成功但没内容"；
         * 因此导入只用这个绝对上限兜底，不跟随本机的「单张最大体积」。
         */
        private val SYNC_IMPORT_MAX_BYTES: Long =
            SettingsPreferences.CLIPBOARD_IMAGE_MAX_MB_RANGE.last * 1024L * 1024L

        @Volatile
        private var instance: ClipboardManager? = null

        fun getInstance(context: Context): ClipboardManager {
            return instance ?: synchronized(this) {
                instance ?: ClipboardManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val androidClipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as AndroidClipboardManager

    /** 图片文件存储（内容寻址：`files/clipboard_images/<sha256>.<ext>`）。 */
    private val imageStore = ClipboardImageStore(ClipboardImageStore.rootDirOf(context.filesDir))

    /** 图片落盘/入库/配额收敛互斥：避免并发采集时孤儿清理误删刚写入的文件。 */
    private val imageMutex = Mutex()

    /** 拒收提示需在主线程弹 Toast。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 上次拒收提示时间（[REJECT_TOAST_INTERVAL_MS] 去重；采集在 IO 线程，故 volatile）。 */
    @Volatile
    private var lastRejectToastAt = 0L

    /**
     * 当前系统剪贴板仍指向的"本应用图片"相对路径：淘汰时豁免，
     * 否则粘贴方后续读取会因文件已删而失败。由 [copyImageToSystemClipboard] 写入，
     * 剪贴板变为其它内容时清空。
     */
    @Volatile
    private var selfClipboardImagePath: String? = null

    private val clipboardListener = AndroidClipboardManager.OnPrimaryClipChangedListener {
        // 回调即代表剪贴板变了；即使随后因后台限制读不到内容，这个事实也要留给补采用。
        clipboardChangedSinceRecord = true
        readClipboard()
    }

    /**
     * 本应用刚写回系统剪贴板的**文本**（用于抑制自我采集）。
     *
     * 为什么需要它：点选剪贴板条目上屏时会把该内容写回系统剪贴板（让用户能在别处粘贴），
     * 这会让 OnPrimaryClipChangedListener 立刻回调、把**刚点过的同一条内容**当成新内容重新
     * 入库（upsertAndTrim 生成新 id），候选栏于是又把它显示出来——用户看到的「反复出现」。
     *
     * 写入前设置此值，readClipboard 读到相同文本就直接丢弃并清空标记。
     */
    @Volatile
    private var selfWrittenText: String? = null

    /** 单条文本剪贴板入库上限（字符）。超长内容（日志/JSON）不入库。 */
    private val MAX_TEXT_CHARS = 100_000

    /**
     * 本次 [readClipboard] 的触发来源。
     *
     * 系统剪贴板监听回调 = 用户真的复制了东西，应当「复活」同文本条目（重置 consumed、
     * 刷新时间戳），让它重新进候选栏。
     *
     * [PROBE] = [captureCurrentClipboardNow] 在键盘弹出时补采，目的是捡回「复制时本进程
     * 没有焦点、primaryClip 返回 null」而漏掉的内容。它读到的大多是**早就入过库的旧内容**，
     * 若也走复活路径，就变成「每次弹出键盘都把上次那条重新激活一遍」——用户看到的是
     * 复制内容在候选栏反复出现（且 consumed 永远回不去 1）。
     */
    private enum class CaptureSource { LISTENER, PROBE }

    @Volatile
    private var currentCaptureSource = CaptureSource.LISTENER

    /**
     * 系统剪贴板自「上次成功记录」以来是否发生过变更。
     *
     * 由 [clipboardListener] 置位——**监听回调本身就能证明剪贴板变了**，哪怕后台限制让
     * `primaryClip` 返回 null、我们读不到内容。补采（[captureCurrentClipboardNow]）据此判断
     * 读到的是「用户刚复制、但当时读不到的新内容」还是「一直没变过的旧剪贴板」：
     * 后者绝不能再走 revive，否则每次弹出键盘都会把同一条旧内容重新激活，表现为
     * 复制内容在候选栏反复出现。
     */
    @Volatile
    private var clipboardChangedSinceRecord = false

    /**
     * 上次处理过的图片剪贴板 URI（字符串形式）。
     *
     * 图片没有「已入库列表」可按内容廉价比对（要读盘 + 算 hash），而补采每次键盘弹出都会
     * 命中同一份系统剪贴板。用原样 URI 做最外层比较：没变就连读盘都省掉。
     */
    @Volatile
    private var lastSeenImageUri: String? = null

    /**
     * 采集系统剪贴板。
     *
     * 文本分支与旧行为一致（text → uri 字符串 → intent）；图片分支（[ClipData] 声明图片 MIME
     * 且带 uri）改为读取字节落盘为图片条目——旧实现会把 `content://…` 当**文本**存进历史，
     * 属既存缺陷，本次一并修掉（读取失败/非支持类型直接跳过，不再退化成 URI 文本）。
     */
    private fun readClipboard() {
        try {
            val clipData = androidClipboardManager.primaryClip
            // 关键诊断：后台读剪贴板在 Android 10+ 会返回 null（非焦点应用）。
            // 这条日志是「复制了没进面板」唯一可定位的线索，必须写进 xime 日志文件。
            if (clipData == null) {
                FileLogger.e(TAG, "primaryClip=null（后台限制或剪贴板为空）")
            }
            if (clipData != null && clipData.itemCount > 0) {
                val description = clipData.description

                // 敏感剪贴板（密码管理器等，API 33+ 系统标记）不入库
                if (isSensitiveClip(description)) {
                    FileLogger.d(TAG, "Sensitive clip ignored")
                    clearSelfClipboardImage()
                    return
                }

                val imageUri = imageUriOf(clipData, description)
                if (imageUri != null) {
                    // 本应用自己写回的图片（FileProvider URI）：已入库，跳过采集避免重复读盘
                    if (isSelfProviderUri(imageUri)) {
                        FileLogger.d(TAG, "Self-written image clip, skip capture")
                        return
                    }
                    // 非文本剪贴板：此前写回的系统剪贴板图片保护集失效
                    clearSelfClipboardImage()
                    // 设置页关闭"收集图片"时，图片剪贴板整体跳过（文本分支不受影响）
                    if (!SettingsPreferences.isClipboardImageCaptureEnabled(context)) {
                        FileLogger.d(TAG, "Image capture disabled by settings, skip")
                        return
                    }
                    // 补采路径读到的是没变更过的旧图片剪贴板：连读盘都不必做。
                    // 旧实现会一路走到 addImage，命中同 hash 时 updateTimestamp + emit，
                    // 于是候选栏里那张图的时间戳被反复刷新、永远不过期 —— 与文本同样的
                    // 「反复出现」，代价还多出一次整图读盘与 hash 计算。
                    if (currentCaptureSource == CaptureSource.PROBE &&
                        !clipboardChangedSinceRecord &&
                        lastSeenImageUri == imageUri.toString()
                    ) {
                        FileLogger.d(TAG, "补采命中未变更的旧图片剪贴板，跳过")
                        return
                    }
                    clipboardChangedSinceRecord = false
                    lastSeenImageUri = imageUri.toString()
                    captureImage(imageUri, description)
                    return
                }

                // 非图片剪贴板：此前写入的系统剪贴板图片已不再是当前内容，保护集失效
                clearSelfClipboardImage()

                val item = clipData.getItemAt(0)
                val text = when {
                    item.text != null -> item.text.toString()
                    item.uri != null -> item.uri.toString()
                    item.intent != null -> item.intent.toUri(0)
                    else -> null
                }
                if (!text.isNullOrEmpty()) {
                    // 本应用刚写回的同一条文本：不重复入库（否则会生成新 id 被当成新内容）
                    val selfText = selfWrittenText
                    if (selfText != null) {
                        selfWrittenText = null
                        if (selfText == text) {
                            // 这条变更**已经处理过了**（就是本应用自己写回的），必须清标志：
                            // 留着它的话，下一次 onWindowShown 补采会认为「剪贴板变过」，
                            // 于是绕过「未变更旧剪贴板」的拦截，把刚上屏的那条又复活一遍 ——
                            // 表现就是「点选上屏后，候选栏里它又出现」。
                            clipboardChangedSinceRecord = false
                            FileLogger.d(TAG, "Self-written text clip, skip capture")
                            return
                        }
                    }
                    if (currentCaptureSource == CaptureSource.PROBE) {
                        recordProbeText(text)
                    } else {
                        // 监听路径：这次变更已经被如实记录，补采不必再处理它
                        clipboardChangedSinceRecord = false
                        addItem(text)
                    }
                    return
                }
            }
            // 不做兜底重试（用户约定）：延迟重试会与真实回调交错，把一次性
            // selfWrittenText 标记提前吃掉，导致自写内容被重新入库。
            FileLogger.w(TAG, "Clipboard read returned no usable item")
        } catch (e: SecurityException) {
            FileLogger.w(TAG, "Cannot read clipboard: missing permission", e)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Unexpected error reading clipboard", e)
        }
    }

    /** 敏感剪贴板判定（API 33+ 由系统标记 `ClipDescription.EXTRA_IS_SENSITIVE`）。 */
    private fun isSensitiveClip(description: ClipDescription): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        } catch (e: Exception) {
            false
        }
    }

    /** 剪贴板图片 URI：仅接受声明图片 MIME 且首项带 uri 的 ClipData。 */
    private fun imageUriOf(clipData: ClipData, description: ClipDescription): Uri? {
        val hasImage = (0 until (description.mimeTypeCount)).any {
            description.getMimeType(it)?.startsWith("image/") == true
        }
        if (!hasImage) return null
        val uri = clipData.getItemAt(0).uri ?: return null
        return when (uri.scheme?.lowercase()) {
            ContentResolver.SCHEME_CONTENT, ContentResolver.SCHEME_FILE -> uri
            else -> null
        }
    }

    private fun isSelfProviderUri(uri: Uri): Boolean =
        uri.authority == "${context.packageName}.fileprovider"

    /**
     * 读取图片 URI 字节 → 尺寸/体积准入 → 落盘 → 入库。
     *
     * 大小限制（决策 D8：不压缩，超限直接拒收 + 提示）：阈值由设置页可配，
     * 默认单张 5MB / 最长边 4096px（[ClipboardImageStore] 常量）。
     *
     * 其它失败路径（无读取权限/文件不存在/类型不支持）一律记日志后放弃，
     * **不**回退成"把 uri 当文本"（那会污染剪贴板历史）。
     */
    private fun captureImage(uri: Uri, description: ClipDescription) {
        scope.launch {
            try {
                val limits = imageLimits()
                val mimeHint = description.getMimeType(0) ?: context.contentResolver.getType(uri)
                val read = readImageBytes(uri, limits.maxBytes)
                if (read.tooLarge) {
                    Log.w(TAG, "Clipboard image exceeds ${limits.maxBytes} bytes, rejected: $uri")
                    notifyImageRejected(ClipboardImageStore.RejectReason.TOO_LARGE_BYTES, limits)
                    return@launch
                }
                val bytes = read.bytes ?: return@launch
                if (bytes.isEmpty()) {
                    Log.w(TAG, "Clipboard image is empty: $uri")
                    return@launch
                }
                val (width, height) = decodeBounds(bytes)
                val reason = ClipboardImageStore.rejectReason(
                    sizeBytes = bytes.size.toLong(),
                    width = width,
                    height = height,
                    maxBytes = limits.maxBytes,
                )
                if (reason != null) {
                    Log.w(TAG, "Clipboard image rejected ($reason): ${bytes.size} bytes, ${width}x$height")
                    notifyImageRejected(reason, limits)
                    return@launch
                }
                val extension = ClipboardImageStore.resolveExtension(mimeHint, bytes)
                if (extension == null) {
                    Log.w(TAG, "Unsupported clipboard image type (mime=$mimeHint), skipped")
                    return@launch
                }
                addImage(bytes, extension, mimeHint, width, height, limits)
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot read clipboard image (no URI grant): $uri", e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to capture clipboard image: $uri", e)
            }
        }
    }

    /**
     * 拒收提示：主线程弹 Toast；连续复制大图时 10s 内只提示一次，
     * 避免用户在选择图片时被连环打扰（与插件网络授权提示同策略）。
     * 文案按当前生效阈值生成（设置页可调，写死常量会与实际判定不一致）。
     */
    private fun notifyImageRejected(reason: ClipboardImageStore.RejectReason, limits: ImageLimits) {
        val message = when (reason) {
            ClipboardImageStore.RejectReason.TOO_LARGE_BYTES ->
                "图片超过 ${limits.maxBytes / 1024 / 1024}MB，未保存到剪贴板"
            ClipboardImageStore.RejectReason.TOO_LARGE_DIMENSION ->
                "图片尺寸超过 ${limits.maxEdge}px，未保存到剪贴板"
        }
        val now = System.currentTimeMillis()
        if (now - lastRejectToastAt < REJECT_TOAST_INTERVAL_MS) return
        lastRejectToastAt = now
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** 图片字节读取结果。[tooLarge] 为真表示超过单张上限（提前放弃，不整包读进内存）。 */
    private class ImageReadResult(val bytes: ByteArray?, val tooLarge: Boolean = false)

    /** 读取 URI 字节，超过单张上限即提前放弃。 */
    private fun readImageBytes(uri: Uri, maxBytes: Long): ImageReadResult {
        val input = context.contentResolver.openInputStream(uri) ?: return ImageReadResult(null)
        input.use {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val read = it.read(chunk)
                if (read <= 0) break
                buffer.write(chunk, 0, read)
                if (buffer.size().toLong() > maxBytes) {
                    return ImageReadResult(null, tooLarge = true)
                }
            }
            return ImageReadResult(buffer.toByteArray())
        }
    }

    /**
     * 图片入库（与 [addItem] 对称：落盘 + 去重 + 配额 + 事件）。
     * @return 入库条目的相对路径（`clipboard_images/<hash>.<ext>`）；同图重复入库返回已存在的路径，
     *   落盘失败/配额淘汰后缺失返回 null。调用方（本地采集）可忽略返回值。
     */
    private suspend fun addImage(
        bytes: ByteArray,
        extension: String,
        mimeHint: String?,
        width: Int,
        height: Int,
        limits: ImageLimits,
    ): String? {
        return imageMutex.withLock {
            val hash = ClipboardProfile.sha256Hex(bytes)
            val mime = ClipboardImageStore.mimeForExtension(extension)
                ?: mimeHint?.takeIf { it.startsWith("image/") }
                ?: "application/octet-stream"
            val now = System.currentTimeMillis()
            val existing = dao.findImageByHash(hash)
            if (existing != null) {
                // 同图重复复制：仅更新时间戳置顶，不重复落盘
                dao.updateTimestamp(existing.id, now)
            } else {
                val relativePath = imageStore.save(bytes, hash, extension, limits.maxBytes) ?: run {
                    Log.e(TAG, "Failed to save clipboard image ($hash.$extension)")
                    return@withLock null
                }
                dao.upsertImage(
                    ClipboardEntry(
                        text = "",
                        timestamp = now,
                        type = ClipboardEntry.TYPE_IMAGE,
                        imagePath = relativePath,
                        imageHash = hash,
                        mimeType = mime,
                        sizeBytes = bytes.size.toLong(),
                        width = width,
                        height = height,
                    ),
                )
            }
            // 数量上限（DAO 内已裁）与容量上限：统一回收被删行的文件 + 孤儿文件
            enforceImageQuotaLocked(limits)
            // 回读入库结果（带 id；极端情况下容量超限且其余条目全受保护时可能已被淘汰）
            val row = dao.findImageByHash(hash) ?: return@withLock null
            _clipboardChanged.emit(row.toClipboardItem())
            row.imagePath
        }
    }

    /**
     * 导入远端同步来的图片（Phase 3 / 决策 D12）。
     *
     * 与本地采集路径的**刻意差异**：
     * - **不按本机「单张最大体积」拒收**：远端设备的上限可能更大（1–20MB 可调），
     *   按本机上限拒收会表现为"同步成功但什么都没发生"；这里只用绝对上限
     *   [SYNC_IMPORT_MAX_BYTES]（设置允许的最大值）兜底。
     * - 类型以**字节魔数**为准，`dataName` 的扩展名只作兜底（远端文件名不可当凭据）。
     * - 不做最长边拒收：超限图在源设备上已按同一规则处理过，这里再拒会让两台设备行为不一致；
     *   展示侧的内存上限由 Coil 的显式解码方框保证（见 `ui/ClipboardImageRequests.kt`）。
     *
     * 入库会经 [addImage] 触发 `clipboardChanged`——同步引擎在调用本方法**之前**就把该内容
     * 记为"自写"，避免自己把自己再推一轮（见 `ClipboardSyncBridge.pullRemoteAttachment`）。
     *
     * @param dataName 远端文件名（`<sha256>.<ext>`），仅用于兜底推断类型
     * @return 落盘后的**绝对路径**（可直接写系统剪贴板）；失败返回 null
     */
    suspend fun importImageFromSync(data: ByteArray, dataName: String?): String? {
        if (data.isEmpty()) return null
        if (data.size.toLong() > SYNC_IMPORT_MAX_BYTES) {
            Log.w(TAG, "Synced image exceeds hard cap (${data.size} bytes), skipped")
            return null
        }
        val extension = ClipboardImageStore.sniffExtension(data)
            ?: ClipboardImageStore.extensionForMime(mimeHintOf(dataName))
            ?: run {
                Log.w(TAG, "Unsupported synced image (dataName=$dataName), skipped")
                return null
            }
        val (width, height) = decodeBounds(data)
        val limits = ImageLimits(
            maxBytes = SYNC_IMPORT_MAX_BYTES,
            maxEdge = ClipboardImageStore.MAX_IMAGE_EDGE,
            maxCount = ClipboardImageStore.MAX_IMAGES,
            maxTotalBytes = maxOf(ClipboardImageStore.MAX_TOTAL_BYTES, SYNC_IMPORT_MAX_BYTES),
        )
        val relativePath = addImage(
            bytes = data,
            extension = extension,
            mimeHint = ClipboardImageStore.mimeForExtension(extension),
            width = width,
            height = height,
            limits = limits,
        ) ?: return null
        Log.d(TAG, "Imported synced image ($relativePath, ${data.size} bytes)")
        return imageStore.absoluteFile(relativePath).absolutePath
    }

    /** 从远端 `data_name`（`<hash>.<ext>`）推断 MIME，仅作类型兜底。 */
    private fun mimeHintOf(dataName: String?): String? {
        val extension = dataName?.substringAfterLast('.', "")?.lowercase()
        return if (extension.isNullOrEmpty()) null else ClipboardImageStore.mimeForExtension(extension)
    }

    /** 解析图片尺寸（只读头部，不解码像素，避免 OOM）；失败返回 (0, 0)。 */
    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            options.outWidth.coerceAtLeast(0) to options.outHeight.coerceAtLeast(0)
        } catch (e: Exception) {
            0 to 0
        }
    }

    /**
     * 容量/数量配额收敛（须持有 [imageMutex] 调用）。
     * 淘汰候选由纯函数 [ClipboardImageStore.selectEvictions] 计算（置顶与保护集豁免），
     * 删除行后回收文件，最后做一次孤儿清理兜底。
     */
    private suspend fun enforceImageQuotaLocked(limits: ImageLimits) {
        val images = dao.allImages().map { it.toClipboardItem() }
        val evicted = ClipboardImageStore.selectEvictions(
            images = images,
            protectedPaths = protectedImagePaths(),
            maxImages = limits.maxCount,
            maxTotalBytes = limits.maxTotalBytes,
        )
        if (evicted.isNotEmpty()) {
            dao.deleteImageByIds(evicted.map { it.id })
            Log.d(TAG, "Image quota: evicted ${evicted.size} item(s)")
        }
        val known = dao.allImagePaths().toSet()
        imageStore.cleanupOrphans(known)
    }

    /**
     * 当前生效的图片配额。**只有单张上限**可由设置页调整（设置 → 数据与同步 → 剪贴板），
     * 其余三项取 [ClipboardImageStore] 内置常量。
     */
    private class ImageLimits(
        val maxBytes: Long,
        val maxEdge: Int,
        val maxCount: Int,
        val maxTotalBytes: Long,
    )

    /** 总容量至少为单张上限（否则刚采集的图片会因容量超限被立刻淘汰，表现为"复制了却什么都没发生"）。 */
    private fun imageLimits(): ImageLimits {
        val maxBytes = SettingsPreferences.getClipboardImageMaxMb(context) * 1024L * 1024L
        return ImageLimits(
            maxBytes = maxBytes,
            maxEdge = ClipboardImageStore.MAX_IMAGE_EDGE,
            maxCount = ClipboardImageStore.MAX_IMAGES,
            maxTotalBytes = maxOf(ClipboardImageStore.MAX_TOTAL_BYTES, maxBytes),
        )
    }

    /** 当前受保护的图片路径（系统剪贴板仍指向本应用文件时不淘汰）。 */
    private fun protectedImagePaths(): Set<String> =
        selfClipboardImagePath?.let { setOf(it) } ?: emptySet()

    private fun clearSelfClipboardImage() {
        selfClipboardImagePath = null
    }

    private val database = ClipboardDatabase.getInstance(context)
    private val dao = database.clipboardDao()
    private val scope = ClipboardDatabase.scope()

    private val _clipboardItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val clipboardItems: StateFlow<List<ClipboardItem>> = _clipboardItems.asStateFlow()

    private val _quickSendItems = MutableStateFlow<List<ClipboardItem>>(emptyList())
    val quickSendItems: StateFlow<List<ClipboardItem>> = _quickSendItems.asStateFlow()

    /** 本地剪贴板变更事件流（新增/更新条目时发射，供剪贴板同步等外部消费）。 */
    private val _clipboardChanged = MutableSharedFlow<ClipboardItem>(extraBufferCapacity = 16)
    val clipboardChanged: SharedFlow<ClipboardItem> = _clipboardChanged.asSharedFlow()

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyData()
        scope.launch {
            dao.observeAll().collect { entries ->
                _clipboardItems.value = orderForPanel(entries)
            }
        }
        scope.launch {
            dao.observeQuickSend().collect { entries ->
                _quickSendItems.value = entries.map { it.toClipboardItem() }
            }
        }
        scope.launch {
            // 启动自检：多余图片文件（含上次被淘汰行残留、.tmp 半成品）与容量超限收敛
            runCatching {
                imageMutex.withLock { enforceImageQuotaLocked(imageLimits()) }
            }.onFailure { Log.w(TAG, "Clipboard image startup cleanup failed", it) }
            // 启动自检：上次没删干净的中转图
            runCatching { MediaBridge.sweepStale(context) }
                .onFailure { Log.w(TAG, "Media bridge startup sweep failed", it) }
        }
        startListening()
    }

    /**
     * 将旧版 SharedPreferences 中的剪贴板/快捷发送数据一次性迁移到 Room。
     * 幂等：prefs 无数据时直接返回；迁移成功后删除 prefs 键，避免重复迁移。
     */
    fun migrateLegacyData() {
        val legacyClipboard = prefs.getString(KEY_CLIPBOARD_ITEMS, null)
        val legacyQuickSend = prefs.getString(KEY_QUICK_SEND_ITEMS, null)
        if (legacyClipboard == null && legacyQuickSend == null) return
        scope.launch {
            try {
                val entries = mutableListOf<ClipboardEntry>()
                legacyClipboard?.let { str ->
                    deserializeItems(str).forEach { item ->
                        entries.add(item.toEntry())
                    }
                }
                legacyQuickSend?.let { str ->
                    deserializeItems(str).forEach { item ->
                        entries.add(item.toEntry())
                    }
                }
                if (entries.isNotEmpty()) {
                    dao.insertAll(entries)
                }
                prefs.edit()
                    .remove(KEY_CLIPBOARD_ITEMS)
                    .remove(KEY_QUICK_SEND_ITEMS)
                    .apply()
                Log.i(TAG, "Migrated ${entries.size} legacy clipboard items to Room")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to migrate legacy clipboard items", e)
            }
        }
    }

    /**
     * 面板展示顺序。
     *
     * 规则（用户 2026-11-17 明确要求）：
     *   1. **最新复制的那一条永远第一** —— 复制完立刻打开面板就要看到它，这是最高频场景；
     *   2. 其余条目里**置顶优先**，同组内按时间倒序；
     *   3. 一旦又复制了新内容，上一条「临时第一」自动回到第 2 步的位置
     *      （非置顶 ⇒ 落到置顶卡片之后），不需要任何额外状态。
     *
     * 入参来自 [ClipboardDao.observeAll]（已按 timestamp DESC），所以 [entries] 的第 0 项
     * 就是最新一条，无需再比较时间。
     */
    private fun orderForPanel(entries: List<ClipboardEntry>): List<ClipboardItem> {
        if (entries.size <= 1) return entries.map { it.toClipboardItem() }
        val newest = entries.first()
        val rest = entries.drop(1).sortedWith(
            compareByDescending<ClipboardEntry> { it.isPinned }.thenByDescending { it.timestamp }
        )
        return (listOf(newest) + rest).map { it.toClipboardItem() }
    }

    private fun deserializeItems(json: String): List<ClipboardItem> {
        if (json.isEmpty()) return emptyList()
        return json.split("|||").mapNotNull { itemStr ->
            val parts = itemStr.split(":::")
            if (parts.size == 5) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = parts[4].toBoolean()
                    )
                } catch (e: Exception) {
                    null
                }
            } else if (parts.size == 4) {
                try {
                    ClipboardItem(
                        id = parts[0].toLong(),
                        text = parts[1].unescape(),
                        timestamp = parts[2].toLong(),
                        isPinned = parts[3].toBoolean(),
                        isQuickSend = false
                    )
                } catch (e: Exception) {
                    null
                }
            } else null
        }
    }

    private fun String.unescape(): String {
        return this.replace("〈PIPE〉", "|||").replace("〈COLON〉", ":::")
    }

    private fun ClipboardItem.toEntry(): ClipboardEntry {
        return ClipboardEntry(
            id = 0,
            text = text,
            timestamp = timestamp,
            isPinned = isPinned,
            isQuickSend = isQuickSend,
            consumed = consumed,
            type = type,
            imagePath = imagePath,
            imageHash = imageHash,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height
        )
    }

    private fun ClipboardEntry.toClipboardItem(): ClipboardItem {
        return ClipboardItem(
            id = id,
            text = text,
            code = code,
            timestamp = timestamp,
            isPinned = isPinned,
            isQuickSend = isQuickSend,
            consumed = consumed,
            type = type,
            imagePath = imagePath,
            imageHash = imageHash,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            width = width,
            height = height
        )
    }

    /**
     * 系统剪贴板监听是否已注册（回归防线：b592c882 曾漏调用 [startListening]，
     * 使复制图片/文本整体不入库。androidTest 断言此状态，防止再次误删）。
     */
    @Volatile
    internal var isListeningToSystemClipboard = false
        private set

    /**
     * 注册系统剪贴板变化监听。
     *
     * 幂等：系统侧重复注册同一 listener 会让每次剪贴板变化回调两次。
     * 除 init 外，[ensureListening] 也会调用它（服务重建后必须重新挂上）。
     */
    private fun startListening() {
        if (isListeningToSystemClipboard) return
        androidClipboardManager.addPrimaryClipChangedListener(clipboardListener)
        isListeningToSystemClipboard = true
    }

    /**
     * 确保监听已注册（可重复调用）。输入法服务每次重建都会调用：
     * 单例在本进程内只构造一次，而 [release] 在服务销毁时会把监听摘掉；
     * 若不在这里补回来，切一次输入法/被系统回收后**剪贴板采集永久失效**
     * （真机症状：复制任何内容都不进剪贴板面板，且没有任何日志）。
     */
    /**
     * 主动补采一次系统剪贴板。
     *
     * 为什么需要：Android 10+ 限制「非焦点应用读剪贴板」，监听器虽然会回调，
     * 但回调发生时输入法窗口往往还没显示（用户刚在别的 App 里点复制），
     * `primaryClip` 直接返回 null → 内容丢失。
     * 键盘弹出时输入法已获得焦点，是唯一可靠的读取时机，于是在 [onWindowShown]
     * 等可见性回调里补采一次，把"复制时没读到"的内容捡回来。
     *
     * 幂等：同一内容由 [addItem] 的 upsertAndTrim 按文本去重，重复采集不会产生新行。
     */
    fun captureCurrentClipboardNow() {
        currentCaptureSource = CaptureSource.PROBE
        try {
            readClipboard()
        } finally {
            currentCaptureSource = CaptureSource.LISTENER
        }
    }

    fun ensureListening() {
        startListening()
        FileLogger.i(TAG, "Clipboard listener ensured (listening=$isListeningToSystemClipboard)")
    }

    /** 注销系统剪贴板监听（服务销毁时调用；下次由 [ensureListening] 重新挂上）。 */
    fun release() {
        if (!isListeningToSystemClipboard) return
        runCatching { androidClipboardManager.removePrimaryClipChangedListener(clipboardListener) }
        isListeningToSystemClipboard = false
    }

    /**
     * 补采命中文本时的入库判定。
     *
     * 键盘每次弹出都会补采一次（Android 10+ 后台读不到剪贴板，只有键盘可见时可靠），
     * 所以这里必须区分「用户刚复制的新内容」与「一直没变过的旧剪贴板」——后者若也入库，
     * 等于每弹一次键盘就把同一条旧内容 revive 一遍：timestamp 被刷新 → 永远落在 30 秒
     * 窗口内 → 候选栏反复出现。日志表现就是同一条 len=5 在 20 分钟内「入库」7 次。
     *
     * 判据用三条事实，而不是模糊的时间窗口：
     * - [clipboardChangedSinceRecord]：本进程内监听器是否报告过变更（后台读不到内容时，
     *   回调本身仍能证明剪贴板变了）；
     * - 面板里是否已有同文本条目（`known`）；
     * - 该条目是否已被消费（`consumed`）。
     *
     * 四种组合：
     * | known | 变过 | 动作 |
     * |-------|------|------|
     * | null  | 任意 | 入库 —— 面板里从没见过这段内容（含「复制时本进程还没起来」的场景，
     * |       |      |   此时监听器不会回调、标志为 false，但内容确实是新的） |
     * | 有    | 否   | 跳过 —— 旧剪贴板，重复入库只会无谓刷新时间戳 |
     * | 有    | 是   | 未消费 → 跳过（已在候选栏）；已消费 → 入库复活（用户又复制了同一段） |
     */
    private fun recordProbeText(text: String) {
        if (text.isBlank()) return
        if (text.length > MAX_TEXT_CHARS) {
            // 超长文本在补采路径同样丢弃，但要留痕：这里以前用 android.util.Log，
            // 默认日志级别下不落盘，用户只看到「复制了没进面板」而无从排查。
            FileLogger.w(TAG, "补采命中超长文本 len=${text.length}，未入库")
            return
        }
        val known = _clipboardItems.value.firstOrNull { it.text == text }
        // 「还在候选栏窗口内」与「未被消费」是两件事：复制后隔一段时间才弹出键盘时，
        // 条目可能未消费但已超出展示窗口，此时必须重新入库让它回到候选栏。
        // 判据必须与 [getRecentItems] 的展示窗口一致（CANDIDATE_WINDOW_MS），
        // 否则会出现「判定说在窗口里、候选栏实际已把它过滤掉」的自相矛盾。
        val stillVisible = known != null &&
            System.currentTimeMillis() - known.timestamp < CANDIDATE_WINDOW_MS
        val decision = ClipboardProbePolicy.decide(
            seenInPanel = known != null,
            consumed = known?.consumed == true,
            changedSinceRecord = clipboardChangedSinceRecord,
            stillVisible = stillVisible,
        )
        if (decision == ClipboardProbePolicy.Action.SKIP) {
            FileLogger.d(TAG, "补采跳过 id=${known?.id}（未变更的旧剪贴板或已在候选栏）")
            return
        }
        clipboardChangedSinceRecord = false
        if (known != null) {
            FileLogger.d(TAG, "补采命中已消费条目 id=${known.id}，按重新复制复活")
        }
        addItem(text)
    }

    /**
     * 防抖：同一条文本在极短时间内被重复提交时只入库一次。
     *
     * 为什么会重复：监听回调与「键盘弹出补采」是两条独立路径，同一次复制可能两边
     * 都判定为「需要入库」，真机日志里表现为同一条文本在 8 毫秒内「入库」两次。
     * 库里有文本唯一约束不会产生重复行，但会多一次 revive（刷新 timestamp）
     * 与一次多余的 `clipboardChanged` 发射，表现为候选栏被无谓打断。
     */
    @Volatile
    private var lastAddText: String? = null
    @Volatile
    private var lastAddAt = 0L
    private val ADD_DEBOUNCE_MS = 500L

    /**
     * 剪贴板条目在候选栏的**展示窗口**（毫秒）。
     *
     * 用户要求是「复制后每次进入输入框都展示，直到被消费」。但纯按 consumed 判定会让
     * 很久以前的条目无限期挂着（实测会把前一天的复制内容也推上候选栏）。
     * 折中为 30 分钟：覆盖「复制 → 切应用 → 回来粘贴」的真实节奏，
     * 又不会让陈旧内容常驻。旧实现是 30 秒 —— 太短，复制完切个应用回来就没了，
     * 这是「复制了内容有时候不在候选栏显示」的主因之一。
     */
    internal val CANDIDATE_WINDOW_MS = 30 * 60 * 1000L

    fun addItem(text: String) {
        if (text.isBlank()) return
        // 超大文本（几 MB 的 JSON/日志）会把整串写进 Room + StateFlow + 候选栏，
        // 直接卡 UI 帧；超过上限不入库。
        if (text.length > MAX_TEXT_CHARS) {
            FileLogger.w(TAG, "剪贴板文本超长 len=${text.length}（上限 $MAX_TEXT_CHARS），未入库")
            return
        }
        val now = System.currentTimeMillis()
        if (text == lastAddText && now - lastAddAt < ADD_DEBOUNCE_MS) {
            FileLogger.d(TAG, "剪贴板入库去抖：同文本 500ms 内重复提交，跳过")
            return
        }
        lastAddText = text
        lastAddAt = now
        scope.launch {
            var rowId = 0L
            runCatching {
                rowId = dao.upsertAndTrim(text, System.currentTimeMillis(), MAX_ITEMS)
            }.onFailure {
                // 入库失败必须留痕：用户只会看到"复制了没进面板"，无从判断是采集没触发
                // 还是写库失败。写进 xime 日志文件才可排查。
                FileLogger.e(TAG, "剪贴板入库失败: len=${text.length}", it)
                return@launch
            }
            FileLogger.i(TAG, "剪贴板入库: len=${text.length} id=$rowId")
            // 发射真实 id：消费方（同步推送/插件/消费标记）依赖它做去重与回写，
            // 旧实现这里恒为 0，按 id 的逻辑会静默失效。
            _clipboardChanged.emit(
                ClipboardItem(
                    id = rowId,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    fun removeItem(id: Long) {
        scope.launch {
            deleteEntriesAndFiles(listOf(id))
        }
    }

    /** 批量删除剪贴板条目（仅 isQuickSend = 0，不影响快捷发送）。 */
    fun removeItems(ids: List<Long>) {
        if (ids.isEmpty()) return
        scope.launch {
            deleteEntriesAndFiles(ids)
        }
    }

    /** 清空剪贴板（异步；仅 isQuickSend = 0，不影响快捷发送）。见 [clearClipboardAndWait]。 */
    fun clearClipboard() {
        scope.launch { clearClipboardAndWait() }
    }

    /**
     * 清空剪贴板并等待完成（存储空间页清理后需立即重新统计，不能等异步任务）。
     * 文本条目随数据库删除；图片条目额外回收磁盘文件，最后兜底清理
     * 数据库已无引用的残留（半截 .tmp、异常退出遗留），保证统计页清理后占用归零。
     */
    suspend fun clearClipboardAndWait() {
        // 与图片落盘串行，避免清理过程中又有新图写入（清完即变孤儿）
        imageMutex.withLock {
            val paths = dao.allImages().map { it.imagePath }
            dao.clearAllClipboard()
            paths.forEach { imageStore.delete(it) }
            val removed = imageStore.cleanupOrphans(emptySet())
            if (removed > 0) Log.d(TAG, "Clipboard clear: removed $removed orphan image file(s)")
            // 历史已清空，系统剪贴板若仍指向本应用文件也不再需要保护
            clearSelfClipboardImage()
        }
    }

    /** 删除条目并回收对应图片文件（文本条目无文件，行为不变）。 */
    private suspend fun deleteEntriesAndFiles(ids: List<Long>) {
        val paths = dao.findByIds(ids).filter { it.imagePath.isNotEmpty() }.map { it.imagePath }
        dao.deleteClipboardByIds(ids)
        paths.forEach { imageStore.delete(it) }
    }

    fun splitItem(id: Long) {
        scope.launch {
            val item = _clipboardItems.value.find { it.id == id } ?: return@launch
            dao.deleteClipboardById(id)
            val now = System.currentTimeMillis()
            item.text.forEachIndexed { index, char ->
                dao.insert(
                    ClipboardEntry(
                        text = char.toString(),
                        timestamp = now + index
                    )
                )
            }
        }
    }

    fun clearAll() {
        scope.launch {
            // 与图片落盘串行，避免并发采集时清掉刚写入的文件（与 clearClipboardAndWait 一致）；
            // 清完同步清除保护标记——被删的非置顶图已不存在，系统剪贴板保护不再有意义
            imageMutex.withLock {
                // 仅清非置顶；图片条目同步回收文件（与 clearClipboard 一致）
                val paths = dao.allImages().filter { !it.isPinned }.map { it.imagePath }
                dao.clearUnpinned()
                paths.forEach { imageStore.delete(it) }
                clearSelfClipboardImage()
            }
        }
    }

    fun addToQuickSend(id: Long) {
        scope.launch {
            dao.addQuickSend(id, System.currentTimeMillis(), MAX_QUICK_SEND_ITEMS)
        }
    }

    fun removeFromQuickSend(id: Long) {
        scope.launch {
            dao.deleteQuickSendById(id)
        }
    }

    fun togglePinQuickSend(id: Long) {
        scope.launch {
            dao.updateTimestamp(id, System.currentTimeMillis())
        }
    }

    /**
     * 剪贴板条目置顶开关。
     *
     * 置顶的语义与「快捷发送置顶」不同：这里是真的固定——置顶条目永不被
     * 数量上限/图片配额淘汰（DAO 的 trimUnpinned / trimImages 都带 isPinned = 0），
     * 且在列表中排在未置顶项之前（observeAll 的 ORDER BY isPinned DESC）。
     */
    fun togglePinClipboardItem(id: Long) {
        scope.launch {
            val current = dao.findById(id) ?: return@launch
            dao.setPinned(id, !current.isPinned)
        }
    }

    fun updateQuickSendItem(id: Long, newText: String, newCode: String = ""): Boolean {
        if (newText.isBlank()) return false
        val index = _quickSendItems.value.indexOfFirst { it.id == id }
        if (index < 0) return false
        scope.launch {
            dao.updateQuickSendItem(id, newText, newCode.trim(), System.currentTimeMillis())
        }
        return true
    }

    fun addQuickSendItem(text: String, code: String = "") {
        if (text.isBlank()) return
        scope.launch {
            dao.insertQuickSend(text, code.trim(), System.currentTimeMillis(), MAX_QUICK_SEND_ITEMS)
        }
    }

    /**
     * 把文本写回系统剪贴板。
     *
     * 两个防护（此前都没有，可由远端同步内容直接触发进程崩溃）：
     * 1. **长度闸**：与采集侧同一把尺子 [MAX_TEXT_CHARS]。同步拉取的远端文本不受
     *    本地采集上限约束，几 MB 的 blob 会直接抛 TransactionTooLargeException。
     * 2. **异常兜底**：setPrimaryClip 会抛 TransactionTooLargeException /
     *    SecurityException / IllegalStateException（部分 ROM 在无焦点时拦截）。
     *    调用方 ClipboardSyncBridge 的协程没有异常处理器，裸抛 = 输入法进程崩溃。
     *    失败时必须回滚 selfWrittenText，否则这个残留标记会把下一次**真实**的同文本
     *    复制误判为自写而丢弃。
     *
     * @return true 表示已成功写入系统剪贴板
     */
    fun copyToSystemClipboard(text: String): Boolean {
        if (text.length > MAX_TEXT_CHARS) {
            FileLogger.w(TAG, "拒绝写回超长文本 len=${text.length}（上限 $MAX_TEXT_CHARS）")
            return false
        }
        // 先打标记再写：监听回调是异步的，必须先置位才能拦住自我采集
        selfWrittenText = text
        return try {
            androidClipboardManager.setPrimaryClip(ClipData.newPlainText("kime_clipboard", text))
            true
        } catch (e: Exception) {
            selfWrittenText = null
            FileLogger.w(TAG, "setPrimaryClip 失败 len=${text.length}: ${e.message}", e)
            false
        }
    }

    fun getCurrentClipboardText(): String? {
        val clipData = androidClipboardManager.primaryClip
        return if (clipData != null && clipData.itemCount > 0) {
            clipData.getItemAt(0).text?.toString()
        } else null
    }

    /**
     * 候选栏展示的剪贴板项：**最近若干条未消费的条目**。
     *
     * 展示判据是 `consumed`（用户点选上屏后不再显示）+ 一个**宽松的**时间窗口
     * [CANDIDATE_WINDOW_MS]（30 分钟）+ 条数上限 [maxItems]。
     *
     * 旧实现的时间窗口是 30 秒，等于「复制后超过 30 秒没弹键盘就不再提示」。
     * 真实场景极易触发：复制 → 切到别的应用 → 过一会儿才点输入框，内容就"消失"了。
     * 用户反馈的「复制了内容有时候不在候选栏显示」主要就是这条。
     * 放宽到 30 分钟后，又用 [maxItems] 和窗口一起挡住「昨天的内容常驻候选栏」。
     *
     * 文本与**图片一并返回**（决策 D9：图片候选与文本候选同栏混排，图片条目 `text` 为空串）：
     * 返回顺序即候选栏索引顺序，调用方按 index 取用，**此处不可再过滤**，否则索引错位。
     * 图片点选走 [selectClipboardImage][com.kingzcheung.xime.service.ImeTextCommit.selectClipboardImage]。
     */
    fun getRecentItems(
        maxItems: Int = 5,
        withinMs: Long = CANDIDATE_WINDOW_MS,
    ): List<ClipboardItem> {
        val now = System.currentTimeMillis()
        // 两个约束缺一不可：
        // - consumed：用户点选上屏后不再显示（这是主要语义）；
        // - withinMs：复制后很久都没用的条目不该无限期挂在候选栏上。
        //   窗口从 30 秒放宽到 30 分钟 —— 30 秒太短，复制完切个应用回来就没了，
        //   这正是「复制了内容有时候不显示」的主因；30 分钟足够覆盖「复制→切应用→
        //   回来粘贴」的真实节奏，又不会让昨天的内容一直挂着。
        return _clipboardItems.value.asSequence()
            .filter { !it.consumed && now - it.timestamp < withinMs }
            .take(maxItems)
            .toList()
    }

    /**
     * 标记指定文本的剪贴板条目为"已消费"（候选栏不再显示）。
     *
     * 按 id 更新，而不是按文本再查一遍：调用方（[com.kingzcheung.xime.service.ImeTextCommit]）
     * 手里本来就有被点选条目的 id。旧实现按文本 `maxByOrNull { timestamp }` 兜底匹配，
     * 上屏时会把该内容写回系统剪贴板，如果此刻恰好又插入了同文本的新行（revive/补采竞态），
     * 就会标到**另一行**上——被点的那行仍是 consumed=0，下次进输入框继续显示。
     *
     * 保留文本入参：调用点较多，且需要兼容「没拿到 id」的旧调用路径。
     */
    fun markConsumed(text: String) {
        scope.launch {
            val item = _clipboardItems.value
                .filter { it.text == text && !it.consumed }
                .maxByOrNull { it.timestamp } ?: return@launch
            dao.markConsumed(item.id)
        }
    }

    /**
     * 按 id 精确标记已消费（剪贴板候选点选上屏的主路径）。
     *
     * 与 [markConsumed] 的区别：不重新按文本查找，直接用点选时的 id，
     * 避免「文本相同的新行」把标记吃掉。
     */
    fun markConsumedExact(id: Long) {
        scope.launch {
            dao.markConsumed(id)
        }
    }

    /** 按 id 标记已消费（图片条目经面板点选后调用）。 */
    fun markConsumedById(id: Long) {
        scope.launch {
            dao.markConsumed(id)
        }
    }

    /** 图片条目的绝对文件（不存在返回 null）——点选回写/直插用。 */
    fun imageFileOf(item: ClipboardItem): File? {
        if (!item.isImage || item.imagePath.isEmpty()) return null
        val file = imageStore.absoluteFile(item.imagePath)
        return if (file.exists()) file else null
    }

    /**
     * 图片占用快照（剪贴板设置页展示"已用空间"）。
     * @param count 图片条目数
     * @param bytes 条目登记的原始字节合计（用于与容量上限对比）
     * @param fileBytes 图片目录实际占用（含残留，用于展示清理效果）
     */
    suspend fun imageUsage(): ImageUsage = imageMutex.withLock {
        ImageUsage(
            count = dao.countImages(),
            bytes = dao.totalImageBytes(),
            fileBytes = imageStore.usedBytes(),
        )
    }

    /**
     * 把图片写入系统剪贴板。
     *
     * @param imagePath 图片文件绝对路径
     * @param label ClipData 标签（默认沿用 Emoji 路径的 emoji_image）
     * @param shareCacheCopy true（Emoji 原行为）：先复制到 `cacheDir/emoji_cache` 再共享，
     *   避免把插件资源/临时文件直接暴露；false（剪贴板图片）：直接共享 `files/clipboard_images`
     *   原文件，配合淘汰保护集保证粘贴方读取期间文件不被回收。
     * @param useMediaStoreUri true 时优先用 MediaStore 中转 URI（[MediaBridge]）而非
     *   FileProvider URI：MediaStore 的 `content://media/...` 是系统公共媒体库，
     *   部分应用读第三方 `fileprovider` URI 拿不到权限；此参数作为这类场景的兼容路径。
     */
    fun copyImageToSystemClipboard(
        imagePath: String,
        label: String = "emoji_image",
        shareCacheCopy: Boolean = true,
        useMediaStoreUri: Boolean = false,
    ): Boolean {
        return try {
            val imageFile = File(imagePath)
            if (!imageFile.exists()) {
                Log.e(TAG, "Image file not found: $imagePath")
                return false
            }

            val sharedFile = if (shareCacheCopy) {
                val cacheDir = File(context.cacheDir, "emoji_cache")
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs()
                }
                val cacheFile = File(cacheDir, imageFile.name)
                FileInputStream(imageFile).use { input ->
                    cacheFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                cacheFile
            } else {
                imageFile
            }

            val uri = if (useMediaStoreUri) {
                // MediaStore 公共 URI（接收方按媒体权限即可读，兼容性更好）
                val mime = ClipboardImageStore.mimeForExtension(imageFile.extension) ?: "image/jpeg"
                MediaBridge.insert(context, sharedFile, mime)
                    ?: getContentUriForImage(sharedFile, imageFile)
            } else {
                getContentUriForImage(sharedFile, imageFile)
            } ?: return false

            val clip = ClipData.newUri(context.contentResolver, label, uri)
            androidClipboardManager.setPrimaryClip(clip)

            // 只有共享原文件（files/clipboard_images）时才需要淘汰保护；
            // cache 副本不受图片配额约束，无需保护
            // MediaStore 中转文件由 MediaBridge 的 TTL 自动清理，不进 files 淘汰保护集
            selfClipboardImagePath = if (shareCacheCopy || useMediaStoreUri) {
                null
            } else {
                ClipboardImageStore.relativePathOf(context.filesDir, sharedFile)
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image to clipboard", e)
            false
        }
    }

    /**
     * 拖拽发送用：图片的可共享 content URI。
     * FileProvider 优先（零写入，接收方靠拖拽的 URI 读授权）；仅 FileProvider 抛错时走
     * [MediaBridge] 中转（Download/Xime，不写相册，用完即删）。取不到返回 null（回退长按菜单）。
     */
    fun dragImageUri(item: ClipboardItem): Uri? {
        val file = imageFileOf(item) ?: return null
        try {
            return fileProviderUri(file)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider getUriForFile failed for drag, falling back to MediaStore bridge", e)
        }
        val mime = ClipboardImageStore.mimeForExtension(file.extension) ?: "image/jpeg"
        return MediaBridge.insert(context, file, mime)
    }

    /**
     * 生成图片 content URI（复制到系统剪贴板 / Emoji 共享）：FileProvider 优先，
     * 抛错时降级 [MediaBridge] 中转（不写相册）。
     *
     * @param sharedFile 共享给外部的文件（FileProvider 用）
     * @param sourceFile 原始图片文件（中转与 MIME 推断用）
     */
    private fun getContentUriForImage(sharedFile: File, sourceFile: File = sharedFile): Uri? {
        try {
            return fileProviderUri(sharedFile)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider getUriForFile failed, falling back to MediaStore bridge", e)
        }
        val mime = ClipboardImageStore.mimeForExtension(sourceFile.extension) ?: "image/jpeg"
        return MediaBridge.insert(context, sourceFile, mime)
    }

    /** FileProvider URI（`files-path` 已覆盖 `files/clipboard_images` 与 `cache/emoji_cache`）。 */
    private fun fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
}
