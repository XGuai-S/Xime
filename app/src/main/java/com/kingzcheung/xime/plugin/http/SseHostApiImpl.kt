package com.kingzcheung.xime.plugin.http

import android.content.Context
import android.util.Log
import com.kingzcheung.xime.plugin.core.js.http.SseHostApi
import com.kingzcheung.xime.plugin.core.js.http.SseHostListener
import com.kingzcheung.xime.plugin.core.security.PluginErrorLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 宿主通用 SSE 流式 HTTP 白名单 API。
 *
 * - URL 域名须通过 [NetworkPolicy]（同 [HttpHostApiImpl]）：插件已声明且经用户授权，
 *   否则拒绝（插件无法静默发起任意网络请求，自定义服务器域名需用户手动授权）
 * - 异步回调模型：[SseHostListener.onData] 每条 SSE 事件回调一次，onDone 收尾，onError 报错
 * - 支持任意 HTTP 方法（AI 对话接口通常为 POST）；解析由 okhttp-sse 的 [EventSourceListener] 承载
 * - 会话句柄：[close] 中断单个会话；中断后不再回调
 */
class SseHostApiImpl(
    private val context: Context,
    private val pluginId: String,
    /**
     * EventSource 工厂注入点：默认走 okhttp-sse。
     *
     * 单测注入「构造期间同步回调终态」的假工厂，用于验证会话登记与终态处理的顺序
     * （真实网络下该窗口只有微秒级，无法稳定复现）。
     */
    private val eventSourceFactory: (OkHttpClient) -> EventSource.Factory = {
        EventSources.createFactory(it)
    },
    /** 域名白名单校验注入点；单测可跳过 PluginManager / SharedPreferences。 */
    private val authorizer: SseUrlAuthorizer = DefaultSseUrlAuthorizer(context, pluginId)
) : SseHostApi {

    companion object {
        private const val TAG = "SseHostApi"

        /** 单会话累计正文字符上限（防止「永不结束的流」把宿主堆吃满）。 */
        private const val MAX_TOTAL_TEXT_CHARS = 8 * 1024 * 1024

        /**
         * 全部会话合计的正文字符上限。
         *
         * 单会话 8M 只是第一道闸；[MAX_SESSIONS] 个会话同时跑满就是 8×8M 字符
         * （UTF-16 约 128MB），而这份缓冲在 QuickJS 堆外，JS 的 96MB 上限管不到，
         * 直接吃的是常驻输入法进程的 Java 堆。这里再加一道总量预算，超出的会话
         * 与单会话超限同口径：标记 truncated、丢弃后续正文，不影响已回调的 onData。
         */
        private const val MAX_TOTAL_TEXT_CHARS_ALL_SESSIONS = 16 * 1024 * 1024

        /** 并发 SSE 会话上限（插件反复 stream 不关会导致 fd/线程耗尽）。 */
        private const val MAX_SESSIONS = 8
    }

    /** 日志脱敏：去掉 query/fragment 与 userinfo（与 [HttpHostApiImpl] 同口径）。 */
    private fun sanitizeUrl(raw: String): String {
        val noQuery = raw.substringBefore('?').substringBefore('#')
        val schemeEnd = noQuery.indexOf("://")
        if (schemeEnd < 0) return noQuery
        val head = noQuery.substring(0, schemeEnd + 3)
        val rest = noQuery.substring(schemeEnd + 3)
        val at = rest.indexOf('@')
        val cleaned = if (at >= 0) rest.substring(at + 1) else rest
        val hasQuery = raw.contains('?') || raw.contains('#')
        return head + cleaned + if (hasQuery) "?***" else ""
    }

    /**
     * 禁止自动跟随重定向（与 [HttpHostApiImpl] 口径一致）。
     *
     * 少这一行时：插件请求的**已授权**域名被 302/307 到别的 host（被墙的中转、被接管的
     * DNS、NAS 的开放跳转、恶意网关）时，OkHttp 会静默把请求重发到未授权域名，而
     * `NetworkPolicy.check` 只校验首个 URL —— 于是 Authorization 头与 SSE 的 POST body
     * （LLM 用户输入）会发到墙外主机。
     */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 不用 0（永不超时）：SSE 服务端会发心跳，120s 没有任何字节说明连接已死，
        // 旧实现 0 会让僵死连接一直占着线程与 fd，直到进程结束。
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val sessions = ConcurrentHashMap<Int, Session>()
    private val nextId = AtomicInteger(1)

    /** 全部会话已累计的正文字符数（总量预算用）。 */
    private val totalCharsAcrossSessions = AtomicLong(0L)

    /** 会话表容量判定 + 登记必须原子（并发 connect 否则可突破 MAX_SESSIONS）。 */
    private val sessionLock = Any()

    @Volatile
    private var lastErrorMsg: String? = null

    /**
     * 移除会话并归还它在全局正文预算里占用的字符数。
     *
     * 所有终态路径（正常结束/失败/主动关闭）都必须走这里：只 `sessions.remove`
     * 而不归还计数，总量预算会被已结束的会话永久占着，后续会话全部被判超限。
     */
    private fun releaseSession(session: Session) {
        if (sessions.remove(session.id) != null) {
            val held = session.totalText.length.toLong()
            if (held > 0L) totalCharsAcrossSessions.addAndGet(-held)
        }
    }

    private fun releaseSessionById(id: Int): Session? {
        val session = sessions.remove(id) ?: return null
        val held = session.totalText.length.toLong()
        if (held > 0L) totalCharsAcrossSessions.addAndGet(-held)
        return session
    }

    private class Session(val id: Int) {
        @Volatile
        var closed = false

        /**
         * 真实连接。登记先于建连，故建连前为 null。
         *
         * 注册顺序必须是「先登记会话、后发起连接」：旧实现先 newEventSource 再
         * `sessions[id] = session`，而连接立即失败/立即结束时 onFailure/onClosed 早已
         * 在另一个线程跑完，`sessions.remove(id)` 空转 → 名额永久泄漏（达 MAX_SESSIONS
         * 后该插件再也建不了 SSE 会话），且终态回调里的失败原因丢失。
         */
        @Volatile
        var eventSource: EventSource? = null

        val totalText = StringBuilder()

        /** 累计正文超限标志（超限后只累加到这里为止，仍会通知插件 truncated）。 */
        @Volatile
        var truncated = false
    }

    override fun connect(
        url: String,
        headers: Map<String, String>,
        listener: SseHostListener,
        timeoutMillis: Int?,
        method: String,
        body: ByteArray?
    ): Int {
        authorizer.check(url)?.let { reason ->
            lastErrorMsg = reason
            return -1
        }
        lastErrorMsg = null

        return try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            // 与 HttpHostApiImpl 一致：尊重调用方显式声明的 Content-Type，避免 OkHttp 用
            // RequestBody 默认 mediaType 覆盖（阿里云 MaaS 网关对 octet-stream 请求挂起）。
            // header 名大小写不敏感：Lua 插件可能传 "content-type"。
            if (body != null) {
                val contentType = headers.entries.firstOrNull {
                    it.key.equals("Content-Type", ignoreCase = true)
                }?.value ?: "application/octet-stream"
                requestBuilder.method(method.uppercase(), body.toRequestBody(contentType.toMediaType()))
            } else {
                requestBuilder.method(method.uppercase(), null)
            }
            val request = requestBuilder.build()

            val effectiveClient = if (timeoutMillis != null && timeoutMillis > 0) {
                client.newBuilder()
                    .connectTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                    .build()
            } else {
                client
            }

            val id = nextId.getAndIncrement()
            // 日志脱敏：URL 的 query 里可能带 S3 预签名 / OAuth token，body 是用户输入。
            // HttpHostApiImpl 为此专门做了 sanitizeUrl 并「release 也写」，这里不能再裸打。
            Log.d(TAG, "[$pluginId] SSE 会话 $id 建立: ${sanitizeUrl(url)} bodyLen=${body?.size ?: 0}")
            val session = Session(id)
            val esListener = object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (session.closed) return
                    // totalText 会攒下**整条流**（只在 onClosed 时一次性交给插件）：
                    // 一个永不结束的流就能把常驻输入法进程的堆吃满（JS 堆的 96MB 管不到宿主侧）。
                    // 两道闸：单会话 8M + 全部会话合计 16M（8 个会话各跑满就是 64M 字符）。
                    if (session.totalText.length + data.length <= MAX_TOTAL_TEXT_CHARS) {
                        val projected = totalCharsAcrossSessions.addAndGet(data.length.toLong())
                        if (projected <= MAX_TOTAL_TEXT_CHARS_ALL_SESSIONS) {
                            session.totalText.append(data)
                        } else {
                            totalCharsAcrossSessions.addAndGet(-data.length.toLong())
                            if (!session.truncated) {
                                session.truncated = true
                                Log.w(
                                    TAG,
                                    "[$pluginId] SSE 会话 ${session.id} 触发全局正文预算 " +
                                        "$MAX_TOTAL_TEXT_CHARS_ALL_SESSIONS 字符，后续内容丢弃",
                                )
                            }
                        }
                    } else if (!session.truncated) {
                        session.truncated = true
                        Log.w(TAG, "[$pluginId] SSE 会话 ${session.id} 正文超过 $MAX_TOTAL_TEXT_CHARS 字符，后续内容丢弃")
                    }
                    // 事件正文是用户输入 / 模型回复，不写进 logcat（只留长度）。
                    Log.d(TAG, "[$pluginId] SSE 会话 ${session.id} 收到事件 len=${data.length}")
                    listener.onData(data)
                }

                override fun onClosed(eventSource: EventSource) {
                    if (session.closed) {
                        // 主动 close 后的收尾回调：幂等清理（可能已被 close 移除）
                        releaseSession(session)
                        return
                    }
                    listener.onDone(session.totalText.toString())
                    releaseSession(session)
                    Log.d(TAG, "[$pluginId] SSE 会话 ${session.id} 正常结束")
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (session.closed) {
                        // 主动 close：静默终止，不再回调
                        releaseSession(session)
                        return
                    }
                    val message = if (response != null && !response.isSuccessful) {
                        // 有界读取（2KB）+ 单行安全摘要：旧实现 body.string() 会把整个错误页
                        // 读进堆再 take(300)，网关/恶意服务端返回大错误页即可让常驻进程 OOM。
                        BoundedErrorBody.failureMessage(response.code, response.body)
                    } else {
                        t?.message ?: "连接失败"
                    }
                    lastErrorMsg = message
                    Log.e(TAG, "[$pluginId] SSE 会话 ${session.id} 失败: $message", t)
                    PluginErrorLog.logError(
                        pluginId,
                        "SSE 会话失败",
                        message,
                        t,
                        category = com.kingzcheung.xime.plugin.core.security.ErrorCategory.STREAM_ERROR
                    )
                    listener.onError(message)
                    releaseSession(session)
                }
            }

            // 先登记、后建连：连接立即失败/立即结束时，终态回调才找得到这个会话。
            synchronized(sessionLock) {
                if (sessions.size >= MAX_SESSIONS) {
                    lastErrorMsg = "并发 SSE 会话已达上限（$MAX_SESSIONS）"
                    Log.w(TAG, "[$pluginId] 拒绝新建 SSE 会话：已达上限 $MAX_SESSIONS")
                    return -1
                }
                sessions[id] = session
            }
            // 通知调用方（runtime）会话 id 已分配：必须在建连之前，否则「立即终态」
            // 会让 runtime 的活动会话集合漏登记该 id（该 id 于 onDone/onError 无处清理）。
            runCatching { listener.onSessionAllocated(id) }
            try {
                val factory = eventSourceFactory(effectiveClient)
                session.eventSource = factory.newEventSource(request, esListener)
            } catch (e: Exception) {
                // 建连在同步路径抛出：撤掉已登记的会话（否则名额永久泄漏）
                releaseSessionById(id)
                throw e
            }
            // 终态回调可能在 newEventSource 返回前就跑完并移除了会话：
            // 此时插件拿到 id 却已结束，交由 onError/onDone 通知；这里只需保证会话表干净。
            id
        } catch (e: Exception) {
            lastErrorMsg = e.message ?: "连接失败"
            Log.e(TAG, "[$pluginId] SSE connect failed", e)
            -1
        }
    }

    override fun close(sessionId: Int) {
        val session = releaseSessionById(sessionId) ?: return
        session.closed = true
        // 建连前 close（终态已先行处理）时 eventSource 为 null，仅登记项需要清理。
        session.eventSource?.cancel()
        Log.d(TAG, "[$pluginId] SSE 会话 $sessionId 已主动关闭")
    }

    override fun lastError(): String? = lastErrorMsg
}