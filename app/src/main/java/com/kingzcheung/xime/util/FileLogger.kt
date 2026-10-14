package com.kingzcheung.xime.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.kingzcheung.xime.BuildConfig
import com.kingzcheung.xime.settings.LogLevel
import com.kingzcheung.xime.settings.LogLevelResolver
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue

object FileLogger {
    private const val TAG = "FileLogger"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024
    private const val MAX_LOG_FILES = 10
    private const val QUEUE_CAPACITY = 4096
    
    private var logFile: File? = null
    private var logsDir: File? = null
    private var isInitialized = false

    /**
     * 当前日志级别（见 [LogLevel]）。默认 [LogLevel.ERROR]：只记崩溃与错误。
     *
     * 语义（按用户 2026-10 要求修正）：默认**只记录崩溃、ERROR/WARN 级错误**，
     * 需要更详细的信息时在 `xime.yaml` 的 `logging.level` 里调低门槛（如 `debug`）。
     *
     * 为什么不再默认全开：输入法常驻后台，VERBOSE/DEBUG 级在正式包里持续写盘，
     * 既耗电又产生用户不会看的垃圾数据；而错误级信息量最高、体积最小。
     *
     * 旧实现把 v/d/i 写文件卡在 `BuildConfig.DEBUG` 上，导致 release 包「日志功能等于不存在」
     * ——用户在正式包里既看不到开关，也拿不到任何日志。该缺陷已修（写文件只受级别控制）。
     */
    @Volatile
    private var logLevel: LogLevel = LogLevel.DEFAULT

    /** 把异常堆栈附加到消息尾部，保证日志里有可定位的调用链。 */
    private fun withStackTrace(message: String, throwable: Throwable?): String =
        if (throwable == null) message else "$message\n${throwable.stackTraceToString()}"
    
    private val logQueue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)
    private var writer: BufferedWriter? = null
    private var running = false
    private val writeLock = Any()
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    
    fun init(context: Context) {
        if (isInitialized) return
        try {
            logsDir = File(context.filesDir, "logs")
            if (!logsDir!!.exists()) {
                logsDir!!.mkdirs()
            }
            
            // 级别来源：设置页写入的偏好优先，其次 xime.yaml 的 logging.level，
            // 最后回落到默认 ERROR。见 LogLevelResolver。
            logLevel = LogLevelResolver.resolve(context)
            
            openLogFile()
            cleanOldLogs()
            cleanRimeLogs(context)
            cleanOrphanedLogs()
            
            running = true
            Thread({ flusherLoop() }, "log-flusher").also { it.isDaemon = true }.start()
            
            isInitialized = true
            i(TAG, "FileLogger initialized, log file: ${logFile!!.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FileLogger", e)
        }
    }
    
    private fun openLogFile() {
        val today = fileDateFormat.format(Date())
        logFile = File(logsDir!!, "kime_$today.log")
        
        if (logFile!!.exists() && logFile!!.length() > MAX_LOG_SIZE) {
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val newLogFile = File(logsDir!!, "kime_$ts.log")
            logFile!!.renameTo(newLogFile)
            logFile = File(logsDir!!, "kime_$today.log")
        }
        
        writer?.close()
        writer = BufferedWriter(FileWriter(logFile, true))
    }
    
    private fun flusherLoop() {
        val batch = ArrayList<String>(100)
        while (running) {
            try {
                batch.clear()
                batch.add(logQueue.take())
                logQueue.drainTo(batch, 99)

                val w = writer ?: continue
                synchronized(writeLock) {
                    for (line in batch) {
                        w.write(line)
                    }
                    w.flush()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (e: Exception) {
                Log.e(TAG, "Log flusher error", e)
            }
        }
    }
    
    fun isInitialized(): Boolean = isInitialized

    /** 运行时切换日志级别（跨进程通过设置项/onStartInput 同步）。 */
    fun setLogLevel(level: LogLevel) {
        logLevel = level
    }

    /** 当前日志级别（供设置页回显）。 */
    fun getLogLevel(): LogLevel = logLevel

    /** 是否在写文件（级别非 OFF）。 */
    fun isLoggingEnabled(): Boolean = logLevel != LogLevel.OFF

    private fun write(level: LogLevel, tag: String, message: String) {
        if (logLevel.allows(level)) writeToFile(level.name.take(1), tag, message)
    }

    fun v(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.v(tag, message)
        write(LogLevel.VERBOSE, tag, message)
    }

    fun d(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(tag, message)
        write(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.i(tag, message)
        write(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        write(LogLevel.WARN, tag, withStackTrace(message, throwable))
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        write(LogLevel.ERROR, tag, withStackTrace(message, throwable))
    }
    
    fun wtf(tag: String, message: String, throwable: Throwable? = null) {
        Log.wtf(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        writeToFile("F", tag, fullMessage)
    }
    
    private fun writeToFile(level: String, tag: String, message: String) {
        if (!isInitialized) return
        try {
            val timestamp = dateFormat.format(Date())
            val logLine = "$timestamp [$level] $tag: $message\n"
            logQueue.offer(logLine)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to queue log", e)
        }
    }
    
    private fun cleanOldLogs() {
        try {
            logsDir?.let { dir ->
                val logFiles = dir.listFiles()
                    ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
                    ?.sortedByDescending { it.lastModified() }
                    ?: emptyList()
                
                if (logFiles.size > MAX_LOG_FILES) {
                    logFiles.drop(MAX_LOG_FILES).forEach { oldFile ->
                        oldFile.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs", e)
        }
    }
    
    fun getCurrentLogFile(): File? = logFile
    
    fun getAllLogFiles(): List<File> {
        return logsDir?.listFiles()
            ?.filter { it.name.startsWith("kime_") && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /**
     * 把所有日志（含 Rime 引擎日志与插件错误日志）汇总成一个文件，供用户一次性导出反馈。
     *
     * 汇总而非逐个导出：用户反馈问题时需要的是「一整份上下文」，分十个文件发过来反而
     * 拼不回现场。输出带分隔头，标明每段来源与文件时间。
     *
     * @return 生成的汇总文件；目录不可用时返回 null。
     */
    fun exportAllLogs(context: Context): File? {
        // 先把队列里尚未落盘的行刷出去，否则最近几秒的关键日志不在文件里
        flushNow()
        val dir = logsDir ?: return null
        val exportDir = File(context.cacheDir, "log_export")
        return try {
            if (exportDir.exists()) exportDir.listFiles()?.forEach { it.delete() }
            exportDir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val out = File(exportDir, "xime_logs_$stamp.txt")
            out.bufferedWriter().use { w ->
                w.write(collectionHeader(context))
                w.newLine()
                for (file in collectLogSources(context)) {
                    w.write("\n")
                    w.write("=".repeat(72))
                    w.newLine()
                    w.write("## ${file.sourceLabel} — ${file.file.name}")
                    w.newLine()
                    w.write("## 修改时间：${dateFormat.format(Date(file.file.lastModified()))}  " +
                        "大小：${file.file.length()} 字节")
                    w.newLine()
                    w.write("=".repeat(72))
                    w.newLine()
                    runCatching { w.write(file.file.readText()) }
                        .onFailure { w.write("<读取失败：${it.message}>") }
                    w.newLine()
                }
            }
            out
        } catch (t: Throwable) {
            Log.e(TAG, "exportAllLogs failed", t)
            null
        }
    }

    /** 一个待汇总的日志来源。 */
    private data class LogSource(val sourceLabel: String, val file: File)

    /** 收集所有日志来源：应用日志 + Rime 引擎日志 + 插件错误日志。 */
    private fun collectLogSources(context: Context): List<LogSource> {
        val sources = mutableListOf<LogSource>()
        getAllLogFiles().forEach { sources += LogSource("APP 日志", it) }

        val rimeLogDir = File(File(context.filesDir, "rime"), "logs")
        rimeLogDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { sources += LogSource("RIME 日志", it) }

        val pluginErrors = File(context.filesDir, "logs/plugins/errors.jsonl")
        if (pluginErrors.isFile) sources += LogSource("插件错误", pluginErrors)

        return sources
    }

    /** 汇总文件的头部：设备与版本信息，便于开发者对照复现环境。 */
    private fun collectionHeader(context: Context): String = buildString {
        appendLine("Xime 输入法 · 日志导出")
        appendLine("=".repeat(72))
        appendLine("导出时间：${dateFormat.format(Date())}")
        appendLine("应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("构建类型：${if (BuildConfig.DEBUG) "debug" else "release"}")
        appendLine("日志级别：${logLevel.label}（${logLevel.name}）")
        appendLine("Android：${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("ABI：${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("=".repeat(72))
    }
    
    fun clearAllLogs() {
        try {
            logsDir?.listFiles()
                ?.filter { it.name.startsWith("kime_") }
                ?.forEach { it.delete() }
            
            val today = fileDateFormat.format(Date())
            logFile = File(logsDir!!, "kime_$today.log")
            openLogFile()
            
            i(TAG, "All logs cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear logs", e)
        }
    }
    
    private fun cleanOrphanedLogs() {
        try {
            logsDir?.let { dir ->
                dir.listFiles()
                    ?.filter { it.name.endsWith(".log") && !it.name.startsWith("kime_") }
                    ?.forEach { file ->
                        if (file.length() > MAX_LOG_SIZE) {
                            file.delete()
                        }
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean orphaned logs", e)
        }
    }

    private fun cleanRimeLogs(context: Context) {
        try {
            val rimeLogDir = File(File(context.filesDir, "rime"), "logs")
            if (!rimeLogDir.exists()) return
            rimeLogDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_LOG_FILES)
                ?.forEach { file ->
                    file.delete()
                }
            rimeLogDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".log") && it.length() > MAX_LOG_SIZE }
                ?.forEach { file ->
                    file.delete()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean rime logs", e)
        }
    }

    fun reopenLogFile() {
        openLogFile()
    }

    fun flush() {
        writer?.flush()
    }

    /**
     * 同步把队列中尚未落盘的日志全部写出并 flush。
     * 崩溃处理器必须用这个而不是 [flush]：[flush] 只刷 writer，
     * 队列里的行仍依赖后台 flusher 线程，进程即将被杀时会丢失。
     */
    fun flushNow() {
        if (!isInitialized) return
        try {
            val batch = ArrayList<String>(logQueue.size)
            logQueue.drainTo(batch)
            val w = writer ?: return
            synchronized(writeLock) {
                for (line in batch) {
                    w.write(line)
                }
                w.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushNow failed", e)
        }
    }
}