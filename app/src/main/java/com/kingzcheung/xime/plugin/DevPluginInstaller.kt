package com.kingzcheung.xime.plugin

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.model.PluginSource
import com.kingzcheung.xime.plugin.core.runtime.PluginManager
import com.kingzcheung.xime.plugin.core.runtime.installer.InstallerManager
import org.json.JSONObject
import java.io.File

/**
 * 插件热安装公共逻辑（`xipm dev` 调试用，debug/release 通用，开关门禁见
 * [DevPluginInstallActivity]）。
 *
 * 结果双通道：
 * - logcat：`INSTALL_OK ...` / `INSTALL_FAIL ...`（tag=XipmDev；release 通道 CLI
 *   经 `logcat -s XipmDev` 轮询解析）
 * - 落盘：`files/logs/xipm-dev-result.jsonl`（stage=received/done，debug 通道 CLI
 *   经 run-as 读取）
 */
object DevPluginInstaller {

    private const val TAG = "XipmDev"

    /** release 兼容通道的暂存目录前缀（shell 0644，任何应用可读，装完即删）。 */
    private const val COMPAT_DIR = "/data/local/tmp/"

    /** 热安装暂存子目录名（debug 通道在 `files/` 下，release 通道在 `/data/local/tmp/` 下）。 */
    const val DEV_SUBDIR = "xipm-dev"

    /**
     * 热安装来源路径白名单（纯函数，便于单测）。
     *
     * 只接受 [DEV_SUBDIR] 暂存目录内的文件：
     * - 应用私有 `files/xipm-dev/`（debug 通道：`run-as` 写入）
     * - `/data/local/tmp/xipm-dev/`（release 通道：CLI 推送；该目录 0771 且属 shell，
     *   普通第三方应用**无法写入**）
     *
     * 为什么需要：热安装入口组件 `exported=true`（Android 15 起 adb shell 无法启动非导出
     * 组件），安装路径完全来自外部 Intent。不做路径校验时，任何能拉起该组件的应用都可以
     * 让宿主去读任意可读文件并当插件包解析（把别的应用私有文件、甚至系统文件塞进 zip
     * 解析器）。这里用 `canonicalPath` 比较，同时挡住 `..` 穿越与
     * `/data/local/tmp/xipm-dev-evil/` 这类前缀混淆。
     */
    fun isAllowedSourcePath(context: Context, path: String?): Boolean =
        isAllowedSourcePath(context.filesDir, path)

    /** [isAllowedSourcePath] 的实现（不依赖 Context，便于单测）。 */
    fun isAllowedSourcePath(filesDir: File, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        val roots = listOf(
            File(filesDir, DEV_SUBDIR),
            File(COMPAT_DIR + DEV_SUBDIR),
        ).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
        return roots.any { root ->
            candidate.path == root || candidate.path.startsWith(root + File.separator)
        }
    }

    /** 覆盖安装并重载；返回是否成功。 */
    suspend fun install(context: Context, path: String?): Boolean {
        appendResult(context, stage = "received", ok = null, pluginId = null, message = "path=$path")
        if (path.isNullOrBlank()) {
            Log.e(TAG, "INSTALL_FAIL 缺少安装路径")
            appendResult(context, "done", false, null, "缺少安装路径")
            return false
        }
        if (!isAllowedSourcePath(context, path)) {
            Log.e(TAG, "INSTALL_FAIL 路径不在允许目录: $path")
            appendResult(context, "done", false, null, "路径不在允许目录: $path")
            return false
        }
        val ok = try {
            PluginManager.awaitInitialization()
            val file = File(path)
            if (!file.isFile) {
                Log.e(TAG, "INSTALL_FAIL 文件不存在或不可读: $path")
                appendResult(context, "done", false, null, "文件不存在或不可读: $path")
                false
            } else when (
                val result = PluginManager.installerManager.installPlugin(
                    file,
                    forceOverwrite = true,
                    source = PluginSource.FILE,
                )
            ) {
                is InstallerManager.InstallResult.Success -> {
                    val id = result.pluginInfo.id
                    val reloaded = PluginManager.launchPlugin(id)
                    Log.i(TAG, "INSTALL_OK $id reload=$reloaded version=${result.pluginInfo.version}")
                    appendResult(
                        context, "done", true, id,
                        "reload=$reloaded version=${result.pluginInfo.version}"
                    )
                    true
                }

                is InstallerManager.InstallResult.Failure -> {
                    Log.e(TAG, "INSTALL_FAIL ${result.reason}")
                    appendResult(context, "done", false, null, result.reason)
                    false
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "INSTALL_FAIL 安装异常", e)
            appendResult(context, "done", false, null, "安装异常: ${e.message}")
            false
        }
        deleteCompatSource(path)
        return ok
    }

    /** release 兼容通道：清理 /data/local/tmp 暂存副本（无论成败）。 */
    private fun deleteCompatSource(path: String) {
        if (path.startsWith(COMPAT_DIR)) {
            runCatching { File(path).delete() }
        }
    }

    /** 回执落盘（debug 通道 CLI 经 run-as 读取；失败不影响主流程）。 */
    fun appendResult(
        context: Context,
        stage: String,
        ok: Boolean?,
        pluginId: String?,
        message: String,
    ) {
        try {
            val file = File(context.filesDir, "logs/xipm-dev-result.jsonl")
            file.parentFile?.mkdirs()
            val json = JSONObject().apply {
                put("t", System.currentTimeMillis())
                put("stage", stage)
                ok?.let { put("ok", it) }
                pluginId?.let { put("p", it) }
                put("m", message)
            }
            file.appendText(json.toString() + "\n")
        } catch (_: Throwable) {
            // 回执失败不影响安装
        }
    }
}
