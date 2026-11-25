package com.kingzcheung.xime.plugin.http

import java.io.IOException

/**
 * 错误响应正文的**有界读取**（SSE / WS 失败路径共用）。
 *
 * 把这些纯函数放在 app 层而非 plugin-core：SSE / WS 的宿主实现都在 app 层，
 * 而「读多少」是安全问题（堆占用 + 敏感内容进日志），需要能独立单测。
 */
internal object BoundedErrorBody {

    /**
     * 失败响应正文读取上限（2KB）。
     *
     * 旧实现用 `response.body.string()` 把**整个**错误页读进堆再 `.take(300)`：
     * 一个网关返回的几百 MB 错误页（或恶意服务端）就能让常驻输入法进程 OOM，
     * 而截断发生得太晚——堆峰值已经产生。
     */
    const val MAX_ERROR_BODY_BYTES = 2 * 1024

    /** 摘要里最多保留的字符数（即使上限内也截短，避免正文进日志）。 */
    const val MAX_SUMMARY_CHARS = 300

    /**
     * 有界读取失败响应正文。
     *
     * @return 读取到的文本（最多 [MAX_ERROR_BODY_BYTES] 字节）；响应体为空 / 读取抛错时返回 null
     *   —— 此时调用方只报 HTTP 码。
     */
    fun readErrorBody(body: okhttp3.ResponseBody?): String? {
        if (body == null) return null
        // 必须关闭：SSE/WS 在非 2xx 时把 Response 交给监听器后不再处理，
        // 不消费也不关闭的 body 会让连接无法归还连接池——反复失败会耗尽 fd 与连接。
        return try {
            body.use { active ->
                val source = active.source()
                // request(n) 只保证「缓冲到 n 字节或流结束」，不会把整个正文读进内存
                // （不能用 readByteArray(n)：不足 n 字节时会抛 EOFException，短错误页全被当成读不到）
                //
                // 实测（okio 3.17.0）：`request(2048)` 实际会从流里拉满**一个 segment（8KB）**，
                // 因为 okio 按 segment 粒度填充缓冲。所以真实堆峰值上限是 8KB 而不是 2KB——
                // 相比旧实现「把整个错误页读进堆」，这仍是根本性的改善；对外暴露的摘要
                // 仍然只取前 MAX_ERROR_BODY_BYTES 字节。改动这段时不要假设 request 精确等于 n。
                source.request(MAX_ERROR_BODY_BYTES.toLong())
                val available = minOf(source.buffer.size, MAX_ERROR_BODY_BYTES.toLong())
                if (available <= 0L) return null
                String(source.buffer.readByteArray(available), Charsets.UTF_8)
            }
        } catch (e: IOException) {
            null
        } catch (e: IllegalStateException) {
            // ResponseBody 已被消费 / 已关闭
            null
        }
    }

    /**
     * 从（已限长的）正文里提取**安全**摘要：去空白、压成单行、截断到 [MAX_SUMMARY_CHARS]。
     *
     * 不保留多行结构：错误页常含 HTML/堆栈/凭据片段，逐行进日志会放大泄露面。
     */
    fun summarize(text: String?): String? {
        val trimmed = text?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (trimmed.isEmpty()) return null
        return if (trimmed.length <= MAX_SUMMARY_CHARS) trimmed else trimmed.take(MAX_SUMMARY_CHARS) + "…"
    }

    /** 失败消息：`HTTP <code>[: <摘要>]`；读不到正文时只报状态码。 */
    fun failureMessage(code: Int, body: okhttp3.ResponseBody?): String {
        val summary = summarize(readErrorBody(body))
        return if (summary == null) "HTTP $code" else "HTTP $code: $summary"
    }
}
