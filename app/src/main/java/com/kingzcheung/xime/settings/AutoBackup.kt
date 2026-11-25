package com.kingzcheung.xime.settings

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 自动备份调度器：周期由 xime.yaml `backup.auto_interval_hours` 配置（默认 24 小时），
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

    /**
     * 配置复核 / 关闭态等待的轮询粒度。
     *
     * 5 分钟：既能让「关闭 → 重新开启」在可接受延迟内恢复调度，
     * 也不会给常驻进程带来可观的唤醒开销（对比原来的 12h 一次 delay）。
     */
    private const val POLL_MS: Long = 5 * 60 * 1000L

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
            while (scope.isActive) {
                // 每轮重新读取周期：改 backup.auto_interval_hours 立即生效。
                //
                // 关闭时**不能 return@launch 退出整个循环**：那样用户把周期从 0 改回 12h 后，
                // 必须重启输入法进程才会恢复调度（输入法常驻，可能数天不冷启），
                // 表现为「自动备份打开了却一直不跑」。这里改成短周期轮询：
                // 关闭时按 POLL_MS 睡一小会继续等，重新开启后最多 POLL_MS 就能接上。
                val interval = intervalMs(context)
                if (interval == null) {
                    // 关闭态：低频轮询等待重新开启（不打日志，避免刷屏）
                    delay(POLL_MS)
                    continue
                }
                // maybeAutoBackup 内部已把「还没到周期」放在最前面判（一次 SharedPreferences 读），
                // 未到期的轮询是近乎零成本的，不需要在这里再做前置判断。
                try {
                    val ran = BackupManager.maybeAutoBackup(context)
                    if (ran) FileLogger.i(TAG, "自动备份检查已执行（周期 ${interval / 3600_000}h）")
                } catch (t: Throwable) {
                    // 备份异常不致命：记录后等下个周期再试；同时写入 xime 日志文件，
                    // 否则用户只在 logcat 里能找到失败原因，App 内日志完全无痕。
                    Log.w(TAG, "自动备份检查异常", t)
                    FileLogger.w(TAG, "自动备份检查异常: ${t.message}", t)
                }
                // 长周期用「分片睡眠」：每片 POLL_MS 复核一次配置，改周期能更快生效，
                // 同时避免一次 delay(12h) 期间配置变化完全无感知。
                var slept = 0L
                while (slept < interval && scope.isActive) {
                    val slice = minOf(POLL_MS, interval - slept)
                    delay(slice)
                    slept += slice
                    // 配置变了（关闭或改短）就提前结束本轮的剩余等待
                    if (intervalMs(context) != interval) break
                }
            }
        }
    }
}
