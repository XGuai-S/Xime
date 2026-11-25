package com.kingzcheung.xime.plugin.core.api

import com.kingzcheung.xime.plugin.core.config.IPluginConfigurable

/**
 * 远端备份条目（listBackups 返回的单条）。
 *
 * @param id        远端标识（WebDAV 路径 / S3 key 等），恢复与删除时原样回传给插件
 * @param name      展示名（如 "Xime配置-2026-09-06.zip"）
 * @param createdAt 创建时间（毫秒时间戳；远端不提供时为 0）
 * @param size      字节数（远端不提供时为 -1）
 */
data class RemoteBackupEntry(
    val id: String,
    val name: String,
    val createdAt: Long = 0,
    val size: Long = -1
)

/**
 * pushBackup 的执行结果。
 *
 * @param ok      是否成功
 * @param id      成功时远端条目 id（插件未返回时为 null，宿主随后 listBackups 获取）
 * @param message 失败原因（成功时为 null）
 */
data class BackupResult(
    val ok: Boolean,
    val id: String? = null,
    val message: String? = null
)

/**
 * 下载产物：宿主缓存文件 + 其 blob 句柄。
 *
 * @param blobId 宿主 [com.kingzcheung.xime.plugin.core.js.http.BlobStore] 句柄
 *   （[BackupPlugin.releaseBackup] 用；插件侧返回的就是它）
 * @param file   落盘的备份包（恢复流程直接解压，**不经过 JS 堆**）
 * @param size   字节数
 */
data class BackupDownload(
    val blobId: String,
    val file: java.io.File,
    val size: Long
)

/**
 * 远端单个文件的元信息（文件级增量备份用）。
 *
 * @param path   远端相对路径（如 `rime/user.yaml`、`clipboard/images/ab12.png`），与宿主本地清单同构
 * @param sha256 内容哈希（小写十六进制）；插件不支持返回哈希时为空串，宿主会退化为「大小相同即视为不变」的保守判断
 * @param size   字节数
 */
data class RemoteFileEntry(
    val path: String,
    val sha256: String = "",
    val size: Long = -1L
)

/**
 * 备份插件能力接口（宿主侧，由 JS 适配器实现，协议逻辑在 JS）。
 *
 * 分工与 [ClipboardSyncPlugin] 一致：备份包的**生成与恢复**（zip 打包、路径校验、
 * 落盘）全部由宿主 BackupManager 承载，插件只负责**传输协议**
 * （WebDAV / S3 / 自建 HTTP），用 `host.http` + `host.crypto` + `host.config` 实现。
 * 服务器地址、账号等配置由插件 getSettingsSchema 表单承载（host.config 存取）。
 *
 * **大包走流式句柄**（v3.1 契约，替代历史 `Uint8Array` 传参）：
 * - 上传：归档以宿主文件形式交给 [pushBackup]，适配器登记为 blob 句柄后只把
 *   `{name, size, archiveId}` 传给插件；插件用 `host.http.upload` 流式 PUT，
 *   全程不把包读进内存（旧实现把整包 base64 进 JS 源码，数十 MB 即 OOM）
 * - 下载：插件用 `host.http.download` 让宿主把响应体流式落盘并返回 blob 句柄；
 *   适配器解析为 [BackupDownload] 供宿主解压，恢复完由 [releaseBackup] 释放
 */
interface BackupPlugin : IPluginEntryClass, IPluginConfigurable {

    /**
     * 上传备份包到远端。
     *
     * @param name    建议的远端文件名（如 "Xime配置-2026-09-06.zip"），插件可自行附加目录前缀
     * @param archive 宿主已落盘的 zip（**所有权归调用方**，实现方只登记句柄、不删除文件）
     */
    suspend fun pushBackup(name: String, archive: java.io.File): BackupResult

    /**
     * 下载指定备份包到宿主缓存文件。
     *
     * @param id listBackups 返回的远端标识
     * @return 落盘产物（含 blob 句柄）；失败返回 null
     */
    suspend fun pullBackup(id: String): BackupDownload?

    /** 释放 [pullBackup] 产物的 blob 句柄（删除宿主缓存文件；幂等）。 */
    fun releaseBackup(blobId: String)

    /**
     * 列出远端备份条目。
     *
     * @return 按创建时间倒序；失败返回 null（与"远端为空"的空列表区分）
     */
    suspend fun listBackups(): List<RemoteBackupEntry>?

    /** 删除远端备份。 */
    suspend fun deleteBackup(id: String): Boolean

    /** 校验配置可用性（连接测试），返回错误消息（null 表示成功）。 */
    suspend fun testConnection(): String?

    // ------------------------------------------------------------------
    // 文件级增量备份（v3.1 新增）
    //
    // 默认实现返回「不支持」，宿主会自动回退到整包 [pushBackup] 路径——
    // 老插件（只实现了上面五个方法）不需要改代码也能继续工作。
    //
    // 为什么值得做：整包模式下每次备份都要重传几十 MB 的 zip，哪怕只改了几个词；
    // 文件级模式按内容哈希只传变化的文件，且直接传**原始文件**，
    // 恢复时逐文件写回，不需要先导出再合并 userdb 文本快照。
    // ------------------------------------------------------------------

    /**
     * 是否支持文件级增量备份。
     *
     * 宿主据此选择走增量还是整包。返回 false 时后面四个方法不会被调用。
     */
    fun supportsFileLevel(): Boolean = false

    /**
     * 列出远端已存文件。
     *
     * @return 文件列表；远端目录不存在时返回**空列表**（不是 null）。
     *   null 表示"查询失败"，宿主会放弃本次增量并回退整包，不会误判成"远端全空"而重传所有文件。
     */
    suspend fun listRemoteFiles(): List<RemoteFileEntry>? = null

    /**
     * 上传单个文件（覆盖同名）。
     *
     * @param path   远端相对路径，插件可自行附加目录前缀
     * @param file   宿主已落盘的临时文件（**所有权归调用方**，实现方只登记句柄、不删除）
     * @param sha256 文件内容哈希，宿主已算好；插件可存进远端元数据供下次比对，不要求
     */
    suspend fun pushFile(path: String, file: java.io.File, sha256: String): BackupResult =
        BackupResult(ok = false, message = "插件不支持文件级备份")

    /**
     * 下载单个文件到宿主缓存。
     *
     * @param path 远端相对路径
     * @return 落盘产物（含 blob 句柄）；失败返回 null
     */
    suspend fun pullFile(path: String): BackupDownload? = null

    /** 删除远端单个文件；文件不存在也视为成功。 */
    suspend fun deleteRemoteFile(path: String): Boolean = false
}
