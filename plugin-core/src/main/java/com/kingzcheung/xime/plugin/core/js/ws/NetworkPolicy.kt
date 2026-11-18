package com.kingzcheung.xime.plugin.core.js.ws

/**
 * 插件网络访问策略（纯函数，可单测）。
 *
 * 放行条件（满足其一）：
 * 1. 目标域名 ∈ 宿主可信池（官方域名，静默放行）
 * 2. 目标域名 ∈ 用户已授权的域名集合（authorizedHosts）——用户显式授权（插件中心
 *    手动授权 / 自定义服务器保存配置时自动授权）即用户明确信任，不要求同时声明
 *    （插件可能只声明 allowCustomHosts 而域名来自配置或手动授权，先卡声明会误伤）
 * 3. 目标域名 ∈ 插件 manifest 声明的域名（declaredHosts）
 *    或 ∈ 用户配置的自定义服务器域名（customHosts，allowCustomHosts 插件保存配置时自动授权）
 *    且 ∈ 用户已授权的域名集合（authorizedHosts）
 *
 * 否则拒绝——第三方插件必须声明域名并经用户授权才能联网。
 */
object NetworkPolicy {

    /** 允许联网的 scheme：HTTP(S) + WebSocket（插件用 wss 连实时 ASR/LLM）。 */
    private val ALLOWED_SCHEMES = setOf("http", "https", "ws", "wss")

    /** 校验 URL 是否允许访问。reason 为拒绝原因（null 表示放行）。 */
    fun check(
        url: String,
        trustedHosts: Set<String>,
        declaredHosts: List<String>,
        authorizedHosts: Set<String>,
        customHosts: Set<String> = emptySet()
    ): String? {
        val host = extractHost(url) ?: return "无法解析 URL: $url"
        if (host in trustedHosts) return null

        // 用户已授权 = 用户明确信任（授权只能经用户显式操作写入：手动授权按钮 /
        // 保存配置时从自定义服务器 URL 提取）。必须先放行再判声明，否则
        // "已授权但未声明"（插件只声明 allowCustomHosts、域名来自配置/手动授权）
        // 会被下面的声明检查误伤——真机实证：授权了 api.openai.com 仍报
        // "插件未声明访问域名"，用户困惑"明明已经授权了"。
        if (host in authorizedHosts) return null

        if (host !in declaredHosts && host !in customHosts) {
            return "插件未声明访问域名 $host，已阻止联网"
        }
        return "访问 $host 未获用户授权，请在插件中心授权"
    }

    /**
     * 从 URL 提取域名（小写，不含端口）。
     *
     * **必须用权威解析器，不能手写字符串切割**。旧实现是：
     * ```
     * rest = url.substringAfter("://")
     * host = rest.substringBefore(':')
     * ```
     * 对 `https://api.openai.com:8443@evil.com/steal` 会得到 `api.openai.com` ——
     * 而按 WHATWG 规则（OkHttp 的实际行为）`api.openai.com:8443` 是 **userinfo**，
     * 真实 host 是 `evil.com`。于是鉴权放行了已授权域名，请求却真的发往 evil.com，
     * 用户已配置的 Authorization / WebDAV / S3 凭据被原样带走，UI 上还显示
     * 「访问 api.openai.com」。这是网络隔离层的总开关失效，不是边角情况。
     *
     * 现在用 `java.net.URI`（语义与 OkHttp 的 HttpUrl 一致）并做三项硬约束：
     * 1. 只接受 http/https/ws/wss —— file/gopher 等 scheme 一律拒绝；
     * 2. **显式拒绝带 userinfo 的 URL** —— 这是上面的绕过手法，正常请求不需要它；
     * 3. host 统一小写并去掉尾部点（`api.openai.com.` 与 `api.openai.com` 同义）。
     *
     * 解析失败返回 null（调用方 [check] 会因此拒绝，属 fail-closed）。
     */
    fun extractHost(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = java.net.URI(trimmed)
            // 允许的 scheme：HTTP(S) 与 WebSocket(ws/wss)——插件用 wss 连实时 ASR/LLM。
            // 其余（file/gopher/ftp…）一律拒绝。
            val scheme = uri.scheme?.lowercase()
            if (scheme !in ALLOWED_SCHEMES) return null
            // 带 userinfo 一律拒绝：既是绕过手法，也说明调用方在 URL 里塞了凭据
            if (!uri.rawUserInfo.isNullOrEmpty()) return null
            uri.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            // URI 解析失败（非法字符 / 相对路径 / 缺 host）：fail-closed
            null
        }
    }

    /** 从配置值文本提取 HTTP(S) URL 的域名（非 http/https 值返回 null，供 UI 展示授权候选）。 */
    fun extractHttpHost(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        return extractHost(trimmed)
    }
}
