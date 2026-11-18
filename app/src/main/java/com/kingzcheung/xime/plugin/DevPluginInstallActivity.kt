package com.kingzcheung.xime.plugin

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.SettingsPreferences
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 插件热安装入口（`xipm dev` 的主通道；debug 直用，release 由"插件开发模式"开关门禁）。
 *
 * ```
 * adb shell am start -n com.kingzcheung.xime/.plugin.DevPluginInstallActivity \
 *   --es path <设备上的 xipk 路径>
 * ```
 *
 * path 来源：debug 包走 `run-as` 写入的 `files/xipm-dev/<plugin>.xipk`；
 * release 包走 CLI 推送的 `/data/local/tmp/xipm-dev/<plugin>.xipk`（安装后由
 * [DevPluginInstaller] 删除）。
 *
 * 安全门禁（仅 release）：设置 → 关于 → 连点设备信息 7 次解锁的"插件开发模式"
 * 开关。开关关闭时本 Activity 立即 finish、不读 path、不安装，组件无注入面。
 * 组件 exported=true：Android 15 起 adb shell 已无法启动非导出组件，热安装通道
 * 依赖 `am start`；普通应用虽可拉起，但在开发模式关闭时会被门禁直接拒绝，仅开发
 * 模式开启的调试窗口内可安装。debug 包 debuggable（run-as 全量开放），门禁无
 * 意义，直接放行。
 *
 * `adb shell am start` 由 shell（特权）发起，不受应用后台执行限制，宿主在后台
 * （如未使用键盘）时也能可靠触发；透明无界面主题、无历史记录，安装完成后立即
 * finish，用户几乎无感。
 */
class DevPluginInstallActivity : Activity() {

    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isCallerAllowed()) {
            Log.w(TAG, "热安装被拒：调用方=${launchedFromPackage}（release 只允许 adb shell）")
            finish()
            return
        }
        val path = intent?.getStringExtra(EXTRA_PATH)
        // 路径白名单：组件 exported，path 来自外部 Intent，不校验就能让宿主把任意
        // 可读文件当插件包解析。只接受 xipm-dev 暂存目录（私有 files/ 与
        // /data/local/tmp/ 下，后者普通应用不可写）。
        if (!DevPluginInstaller.isAllowedSourcePath(this, path)) {
            Log.w(TAG, "热安装被拒：路径不在允许目录 path=$path")
            DevPluginInstaller.appendResult(
                applicationContext, "done", false, null, "路径不在允许目录: $path",
            )
            finish()
            return
        }
        scope.launch {
            try {
                DevPluginInstaller.install(applicationContext, path)
            } finally {
                finish()
            }
        }
    }

    /**
     * 调用方门禁。
     *
     * - debug 包：放行。包本身 debuggable，`run-as` 已全量开放，再设门禁只有摩擦没有收益。
     * - release 包：要求「插件开发模式」开关已开启 **且** 调用方是 adb shell。
     *
     * 为什么还要判调用方：开发模式开着的时候，若只依赖开关，任何本地应用都能 `startActivity`
     * 拉起本组件并静默装入一个 xipk（等价于在输入法进程里执行第三方 JS），用户完全无感。
     * 文档里的唯一用法是 `adb shell am start`（调用方恒为 com.android.shell），
     * 因此 release 通道按调用方收紧不会影响正常流程；[launchedFromPackage] 由系统填写，
     * 调用方无法伪造。拿不到调用方包名（null）时保守拒绝。
     */
    private fun isCallerAllowed(): Boolean {
        if (BuildConfig.DEBUG) return true
        if (!SettingsPreferences.isPluginDevModeEnabled(this)) return false
        return launchedFromPackage == SHELL_PACKAGE
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "XipmDev"

        /** adb shell 的包名（`am start` 的调用方）。 */
        private const val SHELL_PACKAGE = "com.android.shell"

        const val EXTRA_PATH = "path"
    }
}
