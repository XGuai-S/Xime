package com.kingzcheung.xime.clipboard

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 图片中转：把图片写进 MediaStore 的 Download/Xime（相册不索引该目录），换一个可跨应用读取的
 * content URI，[TTL_MS] 到点即删。只在 FileProvider 走不通时用（部分 ROM 上 getUriForFile 抛错，
 * 或接收方只认 MediaStore）。API 29+ 写/删自己这份文件免权限；API < 29 直接跳过。
 */
internal object MediaBridge {

    private const val TAG = "MediaBridge"

    /** 中转目录名。 */
    internal const val DIR_NAME = "Xime"

    /** 上级目录 = Download（必须，写 Pictures 会让相册出现临时图）。 */
    internal const val BRIDGE_PARENT_DIR = "Download"

    /** 中转文件保留时长。 */
    internal const val TTL_MS = 5 * 60 * 1000L

    /** 中转目录（MediaStore `RELATIVE_PATH` 口径，带尾斜杠）。 */
    internal fun relativeDir(): String = "$BRIDGE_PARENT_DIR/$DIR_NAME/"

    /** 清理条件：只删本应用插入、位于中转目录、且早于 [cutoffSeconds] 的行。 */
    internal fun cleanupSelection(packageName: String, cutoffSeconds: Long): Pair<String, Array<String>> =
        "(${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?) AND " +
            "(${MediaStore.MediaColumns.RELATIVE_PATH} = ?) AND " +
            "(${MediaStore.MediaColumns.DATE_ADDED} < ?)" to
            arrayOf(packageName, relativeDir(), cutoffSeconds.toString())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cleanupJobs = ConcurrentHashMap<Uri, Job>()

    /**
     * 写入中转目录，返回可跨应用读取的 content URI；API < 29 或失败返回 null。
     *
     * @param autoCleanup true（拖拽/粘贴：读一次即可，到点删）；false（commitContent：宿主可能留到
     *   消息发出时才读，交给下次启动的 [sweepStale] 兜底）。
     */
    fun insert(context: Context, file: File, mimeType: String, autoCleanup: Boolean = true): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        try {
            val appContext = context.applicationContext
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(file).use { input -> input.copyTo(output) }
                } ?: run {
                    resolver.delete(uri, null, null)
                    return null
                }
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, publish, null, null)
                if (autoCleanup) scheduleCleanup(appContext, uri)
                return uri
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
        } catch (e: Exception) {
            Log.w(TAG, "bridge insert failed (${file.name})", e)
            return null
        }
    }

    /** 到点删除中转文件。 */
    private fun scheduleCleanup(context: Context, uri: Uri) {
        cleanupJobs.remove(uri)?.cancel()
        cleanupJobs[uri] = scope.launch {
            delay(TTL_MS)
            cleanupJobs.remove(uri)
            runCatching { context.contentResolver.delete(uri, null, null) }
                .onFailure { Log.w(TAG, "bridge cleanup failed: $uri", it) }
        }
    }

    /** 启动自检：清理过期未删的中转图。 */
    fun sweepStale(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val appContext = context.applicationContext
            val cutoffSeconds = (System.currentTimeMillis() - TTL_MS) / 1000
            val (selection, args) = cleanupSelection(appContext.packageName, cutoffSeconds)
            val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val deleted = appContext.contentResolver.delete(files, selection, args)
            if (deleted > 0) Log.i(TAG, "swept $deleted stale bridge image(s)")
        } catch (e: Exception) {
            Log.w(TAG, "bridge sweep failed", e)
        }
    }
}
