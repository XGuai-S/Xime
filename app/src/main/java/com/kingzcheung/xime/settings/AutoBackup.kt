package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自动备份调度器：周期由 xime.yaml `backup.auto_interval_hours` 配置（默认 12 小时），
 * 距上次成功备份超过周期时自动执行云备份（配置了 WebDAV 等备份插件且启用、已配置就绪时）。
 *
 * 为什么用「Application 协程 + 时间戳」而不是 AlarmManager/WorkManager：
 * - 项目未引入 WorkManager（避免新增依赖与初始化开销）；
 * - 备份依赖已启用的备份插件（QuickJS 运行时）与 rime 引擎就绪，只能在本进程内做，
 *   系统级定时器（AlarmManager）唤醒后插件/引擎未必可用，复杂度高且收益低；
 * - [SettingsPreferences.getLastBackupAt] 持久化时间戳，进程重启后仍能正确计算
 *   「距上次备份」是否超时，不因进程被杀而漏备份（下次启动补做）。
 *
 * 注意：协程随进程生命周期结束，若应用长期不被拉起则无法执行（输入法是常驻
 * 服务，正常使用场景下进程会频繁启动；被杀后下次启动会立即补做超时备份）。
 */
object AutoBackup {

    private const val TAG = "AutoBackup"

    /** 启动后延迟多久做首次检查（等插件/rime 引擎初始化完成）。 */
    private const val INITIAL_DELAY_MS: Long = 60 * 1000L

    /** 从 xime.yaml 读取自动备份周期；`auto_interval_hours <= 0` 表示关闭。 */
    private fun intervalMs(context: Context): Long? {
        val hours = KeysConfigHelper.loadBackupConfig(context).autoIntervalHours
        if (hours <= 0) return null
        return hours * 60 * 60 * 1000L
    }

    /**
     * 启动周期调度。在 Application.onCreate 调用一次即可；幂等（重复调用会多一个协程，
     * 但检查与备份本身有互斥锁保护，不会重复备份）。
     */
    fun start(scope: CoroutineScope, context: Context) {
        scope.launch {
            delay(INITIAL_DELAY_MS)
            val interval = intervalMs(context) ?: run {
                Log.i(TAG, "自动备份已关闭（backup.auto_interval_hours <= 0）")
                return@launch
            }
            Log.i(TAG, "自动备份调度启动（周期 ${interval / 3600_000}h）")
            while (scope.isActive) {
                try {
                    BackupManager.maybeAutoBackup(context)
                } catch (t: Throwable) {
                    // 备份异常不致命：记录后等下个周期再试
                    Log.w(TAG, "自动备份检查异常", t)
                }
                delay(interval)
            }
        }
    }
}
