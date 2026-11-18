package com.kingzcheung.xime.plugin.core.security

/**
 * 插件错误「降噪」的纯函数集合（不依赖 Android，便于单测）。
 *
 * 背景（用户 2026-11-19 实测）：一次 WebDAV 断网失败会记录**两条**：
 * 宿主侧 `[网络失败] HTTP 请求失败 (PROPFIND …)` + 插件侧 `[其他] list 失败: <同一句异常>`，
 * 而且每条都带 40+ 行 `UnknownHostException` 堆栈。用户看到「4 条」其实只是 2 次失败，
 * 真正有用的信息（哪个域名、哪一步）被埋掉了。
 */
object PluginErrorNoise {

    /**
     * 网络类异常：堆栈里全是 okhttp/InetAddress 的框架帧，对用户和插件作者都没有信息量，
     * 落库时只保留消息即可（消息里已经有域名与失败原因）。
     */
    fun isNetworkLikeCause(t: Throwable?): Boolean {
        var cur = t
        var depth = 0
        while (cur != null && depth < 8) {
            when (cur) {
                is java.net.UnknownHostException,
                is java.net.ConnectException,
                is java.net.NoRouteToHostException,
                is java.net.SocketTimeoutException,
                is java.net.SocketException,
                is java.net.PortUnreachableException,
                is java.io.InterruptedIOException,
                is javax.net.ssl.SSLException,
                -> return true
            }
            cur = cur.cause
            depth++
        }
        return false
    }

    /**
     * 去重键：同插件 + 同类别 + 同操作 + 同消息 = 同一条错误。
     *
     * 时间戳不进键，这样「同一故障反复重试」会命中同一个键、只累加次数。
     */
    fun dedupeKey(
        pluginId: String,
        category: ErrorCategory,
        operation: String,
        message: String,
    ): String = "$pluginId\t${category.name}\t$operation\t$message"

    /**
     * 插件侧（[ErrorCategory.OTHER]）的这条错误，是否只是在重述刚刚由宿主记录过的异常。
     *
     * 典型形态：宿主先记 `HTTP 请求失败 (PROPFIND https://…)`，插件 catch 到同一个
     * `UnknownHostException` 后又记一条 `list 失败: Unable to resolve host …`。
     * 判定用「插件错误消息里包含宿主错误的详情」这条保守规则，宁可漏合并也不误吞。
     */
    fun isRethrowOfHostError(hostErrorDetail: String?, pluginErrorMessage: String): Boolean {
        if (hostErrorDetail.isNullOrBlank() || pluginErrorMessage.isBlank()) return false
        return pluginErrorMessage.contains(hostErrorDetail)
    }
}
