package com.kingzcheung.xime.clipboard

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/** 完整备份的逻辑快照；不包含数据库 id、SQLite 文件或临时验证码。 */
data class ClipboardBackupRecord(
    val text: String = "",
    val code: String = "",
    val timestamp: Long = 0,
    val isPinned: Boolean = false,
    val isQuickSend: Boolean = false,
    val consumed: Boolean = false,
    val type: String = "text",
    val imagePath: String = "",
    val imageHash: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    /** 快捷发送允许同文异码；普通历史仍只按内容去重。 */
    fun contentKey(): List<String> = listOf(
        if (isQuickSend) "quick" else "history", type,
        if (type == "image") imageHash else text, if (isQuickSend) code else "",
    )
}

data class ClipboardBackupCounts(val texts: Int, val images: Int, val quickSend: Int)

/** 只依赖 JVM 文件和 JSON，导入前在 staging 完整验证。 */
object ClipboardBackupCodec {
    const val PREFIX = "clipboard/"
    const val MANIFEST = "${PREFIX}manifest.json"
    const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    const val MAX_RECORDS = 20_000
    const val MAX_IMAGES = ClipboardImageStore.MAX_IMAGES
    // 与图片同步接收的绝对上限一致，不因本机采集设置较小而丢掉换机图片。
    const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
    const val MAX_IMAGE_TOTAL_BYTES = 100L * 1024 * 1024
    private val imageName = Regex("[0-9a-f]{64}\\.(png|jpg|jpeg|gif|webp|bmp|heic|avif)")

    fun isImagePath(path: String): Boolean =
        path.startsWith("clipboard_images/") && imageName.matches(path.removePrefix("clipboard_images/"))

    fun imageEntry(path: String): String {
        require(isImagePath(path)) { "非法剪贴板图片路径" }
        return PREFIX + "images/" + path.substringAfter('/')
    }

    fun isImageEntry(entry: String): Boolean =
        entry.startsWith(PREFIX + "images/") && imageName.matches(entry.removePrefix(PREFIX + "images/"))

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun snapshotCounts(records: List<ClipboardBackupRecord>) = ClipboardBackupCounts(
        texts = records.count { it.type == "text" && !it.isQuickSend },
        images = records.count { it.type == "image" && !it.isQuickSend },
        quickSend = records.count { it.type == "text" && it.isQuickSend },
    )

    fun writeArchive(filesDir: File, records: List<ClipboardBackupRecord>, zip: ZipOutputStream, metaPrefix: String): ClipboardBackupCounts {
        // 验证码与「含高风险凭据的敏感文本」都不进备份：zip 是明文，会落到 Downloads
        // 并上传第三方云。敏感文本本机仍可粘贴，只是不参与备份。
        val safe = records.filter { com.kingzcheung.xime.clipboard.OtpClipboardPolicy.canBackup(it.type) }
        val manifest = encode(safe)
        zip.putNextEntry(ZipEntry(metaPrefix + MANIFEST))
        zip.write(manifest)
        zip.closeEntry()
        safe.filter { it.type == "image" }.distinctBy { it.imagePath }.forEach { record ->
            val file = storedImage(filesDir, record.imagePath)
            require(file.isFile && file.length() == record.sizeBytes) { "剪贴板图片资源缺失或大小不符" }
            val bytes = file.inputStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(out.size().toLong() + n <= MAX_IMAGE_BYTES) { "剪贴板图片过大" }
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            require(bytes.size.toLong() == record.sizeBytes && sha256(bytes) == record.imageHash) { "图片资源哈希不符" }
            zip.putNextEntry(ZipEntry(metaPrefix + imageEntry(record.imagePath)))
            zip.write(bytes)
            zip.closeEntry()
        }
        return snapshotCounts(safe)
    }

    /** 文件/设置换入前先检查本地同名图片，不允许恢复操作覆盖损坏或越界的本地资源。 */
    fun validateExistingImages(filesDir: File, records: List<ClipboardBackupRecord>) {
        validateRecords(records)
        records.filter { it.type == "image" }.distinctBy { it.imagePath }.forEach { record ->
            val target = storedImage(filesDir, record.imagePath)
            if (target.exists()) {
                require(target.isFile && target.length() == record.sizeBytes && sha256(target.readBytes()) == record.imageHash) {
                    "本地剪贴板图片校验失败"
                }
            }
        }
    }

    /** 只落入既有内容寻址图片存储；已有本地文件只验证、不覆盖。调用方持有图片锁。 */
    fun restoreImages(filesDir: File, records: List<ClipboardBackupRecord>, staging: File) {
        validateRecords(records)
        val store = ClipboardImageStore(ClipboardImageStore.rootDirOf(filesDir))
        records.filter { it.type == "image" }.distinctBy { it.imagePath }.forEach { record ->
            val target = storedImage(filesDir, record.imagePath)
            if (target.exists()) {
                require(target.isFile && target.length() == record.sizeBytes && sha256(target.readBytes()) == record.imageHash) {
                    "本地剪贴板图片校验失败"
                }
            } else {
                val bytes = File(staging, imageEntry(record.imagePath)).readBytes()
                require(bytes.size.toLong() == record.sizeBytes && sha256(bytes) == record.imageHash) { "图片资源校验失败" }
                require(store.save(bytes, record.imageHash, record.imagePath.substringAfterLast('.'), MAX_IMAGE_BYTES) == record.imagePath) {
                    "恢复剪贴板图片失败"
                }
            }
        }
    }

    private fun storedImage(filesDir: File, path: String): File {
        require(isImagePath(path)) { "非法剪贴板图片路径" }
        val expectedRoot = File(filesDir.canonicalFile, ClipboardImageStore.DIR_NAME)
        require(expectedRoot.canonicalFile == expectedRoot) { "剪贴板图片目录不能是符号链接" }
        return File(filesDir, path).also {
            require(it.canonicalPath.startsWith(expectedRoot.path + File.separator)) { "剪贴板图片存储越界" }
        }
    }

    fun encode(records: List<ClipboardBackupRecord>): ByteArray {
        // 双重过滤：DAO 查询已排除 otp/sensitive，codec 仍不允许它们进入包。
        val safe = records.filter { com.kingzcheung.xime.clipboard.OtpClipboardPolicy.canBackup(it.type) }
        validateRecords(safe)
        val root = buildJsonObject {
            put("version", 1)
            put("entries", buildJsonArray {
                safe.forEach { r -> add(buildJsonObject {
                    put("type", r.type); put("text", r.text); put("code", r.code)
                    put("timestamp", r.timestamp); put("isPinned", r.isPinned)
                    put("isQuickSend", r.isQuickSend); put("consumed", r.consumed)
                    if (r.type == "image") {
                        put("imagePath", r.imagePath); put("imageHash", r.imageHash)
                        put("mimeType", r.mimeType); put("sizeBytes", r.sizeBytes)
                        put("width", r.width); put("height", r.height)
                    }
                }) }
            })
        }
        return root.toString().toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_MANIFEST_BYTES) { "剪贴板清单超过大小上限" }
        }
    }

    fun decode(bytes: ByteArray): List<ClipboardBackupRecord> {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "剪贴板清单超过大小上限" }
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1) { "不支持的剪贴板备份版本" }
        val array = root.getValue("entries").jsonArray
        require(array.size <= MAX_RECORDS) { "剪贴板条目过多" }
        val records = array.map { value ->
            val o = value.jsonObject
            fun string(key: String, default: String = "") = o[key]?.jsonPrimitive?.content ?: default
            fun bool(key: String) = o[key]?.jsonPrimitive?.boolean ?: false
            fun number(key: String) = o[key]?.jsonPrimitive?.long ?: 0L
            ClipboardBackupRecord(
                type = string("type", "text"), text = string("text"), code = string("code"),
                timestamp = number("timestamp"), isPinned = bool("isPinned"),
                isQuickSend = bool("isQuickSend"), consumed = bool("consumed"),
                imagePath = string("imagePath"), imageHash = string("imageHash"),
                mimeType = string("mimeType"), sizeBytes = number("sizeBytes"),
                width = o["width"]?.jsonPrimitive?.int ?: 0,
                height = o["height"]?.jsonPrimitive?.int ?: 0,
            )
        }
        // 包内即便伪造 otp，也不能通过恢复入口重新持久化。
        require(records.none { it.type == "otp" }) { "剪贴板备份不允许临时验证码" }
        // 包内即便伪造 sensitive，也不能通过恢复入口重新持久化
        require(records.none { it.type == "sensitive" }) { "剪贴板备份不允许高风险凭据" }
        validateRecords(records)
        return records
    }

    private fun validateRecords(records: List<ClipboardBackupRecord>) {
        require(records.size <= MAX_RECORDS) { "剪贴板条目过多" }
        records.forEach { r ->
            require(r.timestamp >= 0 && r.type in setOf("text", "image")) { "非法剪贴板条目" }
            if (r.type == "text") {
                require(r.text.isNotEmpty() && r.imagePath.isEmpty() && r.imageHash.isEmpty()) { "非法文本条目" }
            } else {
                require(!r.isQuickSend && r.text.isEmpty() && r.code.isEmpty()) { "非法图片条目" }
                require(isImagePath(r.imagePath) && r.imagePath.substringAfter('/').substringBefore('.') == r.imageHash) {
                    "非法剪贴板图片路径或哈希"
                }
                val ext = r.imagePath.substringAfterLast('.')
                require(ClipboardImageStore.extensionForMime(r.mimeType) ==
                    ClipboardImageStore.extensionForMime(ClipboardImageStore.mimeForExtension(ext))) { "图片类型不匹配" }
                require(r.sizeBytes in 1..MAX_IMAGE_BYTES && r.width in 0..ClipboardImageStore.MAX_IMAGE_EDGE &&
                    r.height in 0..ClipboardImageStore.MAX_IMAGE_EDGE) { "图片超过大小或尺寸上限" }
            }
        }
        val images = records.filter { it.type == "image" }.distinctBy { it.imagePath }
        require(images.size <= ClipboardImageStore.MAX_IMAGES && images.sumOf { it.sizeBytes } <= MAX_IMAGE_TOTAL_BYTES) {
            "剪贴板图片超过总量上限"
        }
    }

    /** 不接受未声明资源、缺失资源、大小/哈希不符，也不跟随 staging 外的符号链接。 */
    fun validateResources(records: List<ClipboardBackupRecord>, staging: File, actualEntries: Set<String>) {
        validateRecords(records)
        val images = records.filter { it.type == "image" }
        require(actualEntries == images.map { imageEntry(it.imagePath) }.toSet()) { "剪贴板图片清单与资源不一致" }
        images.forEach { r ->
            val file = File(staging, imageEntry(r.imagePath))
            require(file.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "图片资源越界" }
            require(file.isFile && file.length() == r.sizeBytes) { "图片资源缺失或大小不符" }
            require(sha256(file.readBytes()) == r.imageHash) { "图片资源哈希不符" }
        }
    }

    /** 导出为 timestamp/id 降序，反序插入使新自增 id 在相同 timestamp 下保持原顺序。 */
    fun mergeInsertionOrder(records: List<ClipboardBackupRecord>): List<ClipboardBackupRecord> =
        missingRecords(emptyList(), records).asReversed()

    fun missingRecords(local: List<ClipboardBackupRecord>, incoming: List<ClipboardBackupRecord>): List<ClipboardBackupRecord> {
        val seen = local.mapTo(HashSet()) { it.contentKey() }
        return incoming.filter { com.kingzcheung.xime.clipboard.OtpClipboardPolicy.canBackup(it.type) && seen.add(it.contentKey()) }
    }
}
