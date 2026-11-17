package com.kingzcheung.xime.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UploadResult(
    val fileName: String,
    val success: Boolean,
    val error: String? = null
)

@Serializable
data class FileNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0,
    val mtime: Long = 0,
    val children: List<FileNode>? = null,
)

private val FileTreeSerializer = kotlinx.serialization.serializer<FileNode>()

/** 共享 Json 实例，用于解析 delete 请求体。 */
private val configJson: Json = Json { ignoreUnknownKeys = true }

class WirelessImportHelper(private val context: Context) {
    private var server: EmbeddedServer<*, *>? = null

    private val _uploadResults = Channel<UploadResult>(Channel.BUFFERED)
    val uploadResults: Flow<UploadResult> = _uploadResults.receiveAsFlow()

    fun getLocalIpAddress(): String? {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ip = wifi?.connectionInfo?.ipAddress ?: return null
            String.format("%d.%d.%d.%d",
                ip and 0xff,
                (ip shr 8) and 0xff,
                (ip shr 16) and 0xff,
                (ip shr 24) and 0xff
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing ACCESS_WIFI_STATE permission", e)
            null
        }
    }

    fun findAvailablePort(): Int {
        for (port in 5000..5099) {
            try {
                val sock = java.net.ServerSocket()
                sock.bind(java.net.InetSocketAddress(port))
                sock.close()
                return port
            } catch (_: Exception) { }
        }
        return (5000..5099).firstOrNull { port ->
            try {
                java.net.ServerSocket(port).use { true }
            } catch (_: Exception) { false }
        } ?: 0
    }

    /**
     * 本次会话的一次性访问令牌（H1）。
     *
     * 无线导入把整个 `filesDir`（含 `plugins/`、`rime/`、`logs/`、`schemas/`）暴露成一个
     * HTTP 服务，而它默认监听 0.0.0.0 —— 同 Wi-Fi 的任何设备都能直接访问：
     *   GET  /tree|/read|/download  → 拉走词库、插件脚本、日志
     *   POST /delete                → 删任意文件
     *   POST /upload                → 装 .xipk 插件（= 代码执行）
     * 因此所有路由强制校验令牌；令牌随每次 start() 重新生成、stop() 即失效。
     * 二维码里的 URL 带上 `?t=`，前端后续请求再从 URL 读取并放进请求头。
     */
    @Volatile
    private var sessionToken: String? = null

    /** 当前会话令牌（供前端从 URL 取到后回传；未启动时为 null）。 */
    fun currentToken(): String? = sessionToken

    /** 校验请求是否携带正确令牌。 */
    private fun isAuthorized(call: io.ktor.server.application.ApplicationCall): Boolean {
        val expected = sessionToken ?: return false
        val fromHeader = call.request.headers[TOKEN_HEADER]
        val fromQuery = call.request.queryParameters["t"]
        return expected == fromHeader || expected == fromQuery
    }

    fun start(port: Int): String? {
        if (server != null) return null
        val ip = getLocalIpAddress() ?: return null
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        sessionToken = token
        val url = "http://$ip:$port/?t=$token"

        // watchPaths 置空：ktor 的 stop() 会无条件 cleanupWatcher()，访问 lazy watcher 会在
        // Android 上凭空创建再销毁一个平台 WatchService（自动重载并未启用），其 finalizer
        // 随 GC 抛 ClosedWatchServiceException 刷日志；置空后该路径完全不触发。
        server = embeddedServer(CIO, port = port, watchPaths = emptyList()) {
            routing {
                // 鉴权拦截：除首页与静态资源（本身无数据，且首页需要靠 URL 里的 token 引导）
                // 之外，一律要求令牌。放在路由最前面，任何新增接口默认受保护。
                intercept(io.ktor.server.application.ApplicationCallPipeline.Plugins) {
                    val path = call.request.path()
                    val isStatic = path == "/" || path.startsWith("/assets/")
                    if (!isStatic && !isAuthorized(call)) {
                        Log.w(TAG, "Wireless import: unauthorized ${call.request.httpMethod.value} $path")
                        call.respond(HttpStatusCode.Unauthorized)
                        finish()
                    }
                }
                // React 前端：index.html 与静态资源打包在 assets/www/
                get("/") {
                    serveAsset(call, "www/index.html")
                }
                get("/assets/{file...}") {
                    val file = call.parameters.getAll("file")?.joinToString("/")
                    if (file.isNullOrBlank() || file.contains("..")) {
                        call.respond(HttpStatusCode.NotFound)
                        return@get
                    }
                    serveAsset(call, "www/assets/$file")
                }
                // 返回 app 数据目录树（filesDir 下递归），供前端右侧展示
                get("/tree") {
                    val root = context.filesDir
                    if (root == null) {
                        call.respond(HttpStatusCode.InternalServerError)
                    } else {
                        val json = Json.encodeToString(FileTreeSerializer, buildNode(root))
                        call.respondText(json, ContentType.Application.Json)
                    }
                }
                // 读取文本文件内容（前端查看文件用）
                get("/read") {
                    val path = call.request.queryParameters["path"]
                    val f = safeResolve(path)
                    if (f == null || !f.isFile) {
                        call.respondText("""{"error":"File not found"}""",
                            ContentType.Application.Json, HttpStatusCode.NotFound)
                    } else if (f.length() > MAX_INLINE_READ_BYTES) {
                        // 文本预览只服务小文件；大文件走 /download 流式下载，
                        // 否则一次 readBytes 就能把输入法进程打爆（filesDir 里有数十 MB 的模型/词库）。
                        call.respondText(
                            """{"error":"File too large, use /download"}""",
                            ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)
                    } else {
                        val text = f.readBytes().toString(Charsets.UTF_8)
                        call.respondText(text, ContentType.Text.Plain, HttpStatusCode.OK)
                    }
                }
                // 下载文件（流式，避免大文件 OOM）
                get("/download") {
                    val path = call.request.queryParameters["path"]
                    val f = safeResolve(path)
                    if (f == null || !f.isFile) {
                        call.respondText("""{"error":"File not found"}""",
                            ContentType.Application.Json, HttpStatusCode.NotFound)
                    } else {
                        call.response.header(
                            io.ktor.http.HttpHeaders.ContentDisposition,
                            "attachment; filename=\"${f.name}\""
                        )
                        call.respondFile(f)
                    }
                }
                // 删除文件或空目录
                post("/delete") {
                    val path = call.receiveText()
                    val p = try {
                        configJson.parseToJsonElement(path).jsonObject["path"]?.jsonPrimitive?.content
                    } catch (_: Exception) { null }
                    val f = safeResolve(p)
                    if (f == null) {
                        call.respondText("""{"success":false,"error":"Not found"}""",
                            ContentType.Application.Json, HttpStatusCode.NotFound)
                    } else {
                        val ok = if (f.isDirectory) f.listFiles()?.isEmpty() == true && f.delete()
                                 else f.delete()
                        call.respondText(
                            if (ok) """{"success":true}""" else """{"success":false,"error":"Delete failed"}""",
                            ContentType.Application.Json)
                    }
                }
                post("/upload") { routeUpload(call) }
            }
        }.start(wait = false)

        Log.i(TAG, "Server started at $url")
        return url
    }

    fun stop() {
        // 令牌随服务一起失效：下次 start() 会重新生成，旧二维码立即作废
        sessionToken = null
        server?.stop(1000, 2000)
        server = null
        Log.i(TAG, "Server stopped")
    }
    /** 从 app assets 读取静态文件并响应，避免把前端源码硬编码进 Kotlin。 */

    /**
     * multipart 流式上传路由：临时文件 + RandomAccessFile 解析，避免大文件 OOM。
     *
     * 正文自 start() 的 post("/upload") lambda 逐字搬出，仅整体缩进减 12 空格。
     */

    /**
     * 将 multipart 单个 part 落盘并交给 ImportManager 导入。
     *
     * 返回 null 表示该 part 类型不受支持（调用方保持 saved 不变）；true/false 表示
     * 插件导入成功 / 内容导入的成败，与原 when 首分支里对 saved 的赋值语义逐一对应。
     * 正文自 routeUpload 的 when 首分支逐字搬出，仅整体缩进减 20 空格。
     */
    private suspend fun importUploadPart(
        name: String,
        raf: java.io.RandomAccessFile,
        contentStart: Long,
        contentLen: Long,
    ): Boolean? {
        // 经临时文件传递给 ImportManager，避免大文件 ByteArray OOM
        val partFile = File.createTempFile("part_", "_$name", context.cacheDir)
        try {
            partFile.outputStream().use { out ->
                raf.channel.transferTo(contentStart, contentLen, out.channel)
            }
            when (val result = ImportManager.importFile(
                context, name, partFile, autoEnable = false
            )) {
                is ImportManager.ImportResult.Plugin -> {
                    PluginManager.loadEnabledPlugins()
                    _uploadResults.trySend(UploadResult(fileName = name, success = true))
                    return true
                }
                is ImportManager.ImportResult.Content -> {
                    _uploadResults.trySend(
                        if (result.success) UploadResult(fileName = name, success = true)
                        else UploadResult(
                            fileName = name, success = false,
                            error = if (result.conflicts.isNotEmpty())
                                "${result.conflicts.first().fileName} 与已安装内容不同，已取消"
                            else "保存失败"
                        )
                    )
                    return result.success
                }
                else -> {
                    _uploadResults.trySend(
                        UploadResult(fileName = name, success = false, error = "不支持的文件类型")
                    )
                    return null
                }
            }
        } finally {
            partFile.delete()
        }
    }

    private suspend fun routeUpload(call: ApplicationCall) {
        val rimeDir = File(context.filesDir, "rime")
        rimeDir.mkdirs()
        val tmpFile = File.createTempFile("upload_", ".tmp", rimeDir)
        var lastName = ""

        try {
            // 1. 流式写入临时文件（不占堆内存）
            val ch = call.receiveChannel()
            java.io.FileOutputStream(tmpFile).use { fos ->
                val buf = ByteArray(8192)
                while (!ch.isClosedForRead) {
                    val n = ch.readAvailable(buf, 0, buf.size)
                    if (n <= 0) break
                    fos.write(buf, 0, n)
                }
            }

            // 2. 从临时文件解析 multipart（RandomAccessFile 流式读取）
            val ctHeader = call.request.headers["Content-Type"] ?: ""
            val boundary = ctHeader.split("boundary=").getOrNull(1)?.trim()
            if (boundary.isNullOrEmpty()) {
                call.respondText("""{"success":false,"error":"No boundary"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest)
                return
            }
            val boundaryBytes = ("\r\n--$boundary").toByteArray()
            val firstBoundary = ("--$boundary").toByteArray()
            val headerEndMarker = "\r\n\r\n".toByteArray()

            var saved = false
            java.io.RandomAccessFile(tmpFile, "r").use { raf ->
                var pos = findBytes(raf, firstBoundary, 0)
                if (pos < 0) return@use

                while (true) {
                    val partStart = pos + firstBoundary.size
                    val nextB = findBytes(raf, boundaryBytes, partStart)
                    if (nextB < 0) break
                    val partEnd = nextB

                    // 解析 part header
                    raf.seek(partStart.toLong())
                    val hdr = ByteArray(4096)
                    val hdrLen = readUntil(raf, hdr, headerEndMarker)
                    if (hdrLen < 0) { pos = nextB + 2; continue }

                    val headerStr = hdr.decodeToString(0, hdrLen)
                    val fn = Regex("""filename="([^"]*)"""").find(headerStr)
                    val name = fn?.groupValues?.getOrNull(1)
                    if (name.isNullOrEmpty() || name == "blob") { pos = nextB + 2; continue }

                    lastName = name
                    val contentStart = raf.filePointer
                    val contentLen = partEnd - contentStart

                    when {
                        ImportManager.isPluginFile(name) ||
                        name.endsWith(".zip", ignoreCase = true) ||
                        name.endsWith(".tar.gz", ignoreCase = true) ||
                        name.endsWith(".tgz", ignoreCase = true) ||
                        name.endsWith(".yaml") || name.endsWith(".schema.yaml") || name.endsWith(".dict.yaml") ||
                        name.endsWith(".jpg", ignoreCase = true) || name.endsWith(".jpeg", ignoreCase = true) ||
                        name.endsWith(".png", ignoreCase = true) ||
                        name.endsWith(".ttf", ignoreCase = true) || name.endsWith(".otf", ignoreCase = true) ||
                        name.endsWith(".woff", ignoreCase = true) || name.endsWith(".woff2", ignoreCase = true) -> {
                            saved = importUploadPart(name, raf, contentStart, contentLen) ?: saved
                        }
                        else -> {
                            _uploadResults.trySend(UploadResult(fileName = name, success = false, error = "不支持的文件类型"))
                        }
                    }
                    pos = nextB + 2
                }
            }

            if (saved) {
                call.respondText("""{"success":true,"file":"$lastName"}""", ContentType.Application.Json)
            } else {
                call.respondText("""{"success":false,"error":"No valid file (supported: .yaml, .zip, .tar.gz, .jpg/.png, .ttf/.otf/.woff/.woff2)"}""",
                    ContentType.Application.Json, HttpStatusCode.BadRequest)
            }
        } finally {
            tmpFile.delete()
        }
    }
    private suspend fun serveAsset(call: ApplicationCall, assetPath: String) {
        val mime = when {
            assetPath.endsWith(".html") -> ContentType.Text.Html
            assetPath.endsWith(".js") -> ContentType.Text.JavaScript
            assetPath.endsWith(".css") -> ContentType.Text.CSS
            else -> ContentType.Application.OctetStream
        }
        val bytes = try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (e: java.io.IOException) {
            call.respond(HttpStatusCode.NotFound)
            return
        }
        call.respondBytes(bytes, mime)
    }

    /** 解析前端传来的路径（filesDir 的绝对路径或其相对路径），阻止目录穿越（..）。 */
    private fun safeResolve(rawPath: String?): File? {
        if (rawPath.isNullOrBlank()) return null
        val root = context.filesDir ?: return null
        val normalized = rawPath.replace('\\', '/')
        val candidate = File(normalized).canonicalFile
        val rootCanonical = root.canonicalFile
        return if (candidate.canonicalPath.startsWith(rootCanonical.canonicalPath)) {
            candidate
        } else {
            // 回退：按相对 filesDir 解析
            val rel = normalized.trimStart('/')
            if (rel.split('/').any { it == ".." }) return null
            val c2 = File(root, rel).canonicalFile
            if (c2.canonicalPath.startsWith(rootCanonical.canonicalPath)) c2 else null
        }
    }

    /** 递归构建数据目录树。 */
private fun buildNode(file: File): FileNode {
        if (!file.isDirectory) {
            return FileNode(
                name = file.name,
                path = file.path,
                isDir = false,
                size = file.length(),
                mtime = file.lastModified(),
            )
        }
        val children = file.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.map { buildNode(it) }
            .orEmpty()
        return FileNode(
            name = file.name,
            path = file.path,
            isDir = true,
            size = children.sumOf { it.size },
            mtime = file.lastModified(),
            children = children,
        )
    }

    val isRunning: Boolean get() = server != null

    /** 在 RandomAccessFile 中查找字节模式，返回起始位置，未找到返回 -1 */
    private fun findBytes(raf: java.io.RandomAccessFile, pattern: ByteArray, startPos: Long): Long {
        val buf = ByteArray(8192)
        var pos = startPos
        while (pos < raf.length()) {
            raf.seek(pos)
            val n = raf.read(buf)
            if (n <= 0) break
            val end = n - pattern.size
            for (i in 0..end) {
                var match = true
                for (j in pattern.indices) {
                    if (buf[i + j] != pattern[j]) { match = false; break }
                }
                if (match) return pos + i
            }
            pos += maxOf(1, n - pattern.size + 1)
        }
        return -1
    }

    /** 从 RandomAccessFile 当前位置读取到 endMarker 为止，返回读取的字节数 */
    private fun readUntil(raf: java.io.RandomAccessFile, buf: ByteArray, endMarker: ByteArray): Int {
        var bufPos = 0
        while (bufPos < buf.size) {
            val b = raf.read()
            if (b < 0) break
            buf[bufPos++] = b.toByte()
            // 检查是否匹配 endMarker
            if (bufPos >= endMarker.size) {
                var match = true
                for (i in endMarker.indices) {
                    if (buf[bufPos - endMarker.size + i] != endMarker[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    return bufPos - endMarker.size
                }
            }
        }
        return -1
    }

    fun generateQrCode(url: String, size: Int = 512): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            // setPixel 逐点写 512*512=26 万次，每点都有 JNI 开销。
            // 改为一次性构建 int 数组后 setPixels 批写，性能提升数倍，
            // 同时避免对 RGB_565 的逐点 setPixel 频繁格式转换。
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val row = y * size
                for (x in 0 until size) {
                    pixels[row + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "QR generation failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "WirelessImport"

        /** 携带会话令牌的请求头（二维码 URL 里用 `?t=` 传递，前端转成这个头）。 */
        private const val TOKEN_HEADER = "X-Xime-Token"

        /** `/read` 内联预览的大小上限：超过就走 `/download` 流式下载，避免整文件进内存。 */
        private const val MAX_INLINE_READ_BYTES: Long = 2L * 1024 * 1024
    }
}
