package com.kingzcheung.xime.ai.network

object EndpointResolver {

    private val versionedSuffixes = listOf("/v1", "/v2", "/v3", "/v4", "/openai", "/v1beta/openai")
    const val DEFAULT_DEEPSEEK_ASR_URL = "https://api.deepseek.com/v1/tools/asr"

    fun isOfficialDeepSeekHost(rawUrl: String): Boolean {
        val origin = extractHttpOrigin(rawUrl)?.lowercase() ?: return false
        val host = origin.substringAfter("://").substringBefore(":")
        return host == "api.deepseek.com"
    }

    fun isDeepSeekAsrEndpoint(rawUrl: String): Boolean {
        val clean = rawUrl.trim().trimEnd('/')
        if (clean.isEmpty()) return false
        return isOfficialDeepSeekHost(clean) || clean.endsWith("/tools/asr", ignoreCase = true)
    }

    fun resolveSttEndpoint(rawBaseUrl: String): String {
        val base = rawBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.openai.com/v1/audio/transcriptions"
        if (isOfficialDeepSeekHost(base)) return DEFAULT_DEEPSEEK_ASR_URL
        if (base.endsWith("/tools/asr", ignoreCase = true)) return base
        if (base.endsWith("/audio/transcriptions")) return base
        val hasVersionPrefix = versionedSuffixes.any { base.endsWith(it, ignoreCase = true) }
        return if (hasVersionPrefix) {
            "$base/audio/transcriptions"
        } else {
            "$base/v1/audio/transcriptions"
        }
    }

    fun resolveChatEndpoint(rawBaseUrl: String): String {
        val base = rawBaseUrl.trim().trimEnd('/')
            .removeSuffix("/tools/asr")
            .trimEnd('/')
        if (base.isEmpty()) return "https://api.openai.com/v1/chat/completions"
        if (base.endsWith("/chat/completions")) return base
        val hasVersionPrefix = versionedSuffixes.any { base.endsWith(it, ignoreCase = true) }
        return if (hasVersionPrefix) {
            "$base/chat/completions"
        } else {
            "$base/v1/chat/completions"
        }
    }

    fun resolveGeminiEndpoint(rawBaseUrl: String, model: String, apiKey: String): String {
        val base = rawBaseUrl.trim().trimEnd('/').ifEmpty {
            "https://generativelanguage.googleapis.com/v1beta"
        }
        val cleanModel = model.trim().removePrefix("models/").ifEmpty { "gemini-2.5-flash" }
        val urlWithoutQuery = when {
            base.contains(":generateContent") -> base
            base.endsWith("/v1beta") || base.endsWith("/v1") ->
                "$base/models/$cleanModel:generateContent"
            else ->
                "$base/v1beta/models/$cleanModel:generateContent"
        }
        return if (urlWithoutQuery.contains("key=")) {
            urlWithoutQuery
        } else {
            val sep = if (urlWithoutQuery.contains("?")) "&" else "?"
            "$urlWithoutQuery${sep}key=${apiKey.trim()}"
        }
    }

    fun resolveGeminiStreamEndpoint(rawBaseUrl: String, model: String, apiKey: String): String {
        val base = rawBaseUrl.trim().trimEnd('/').ifEmpty {
            "https://generativelanguage.googleapis.com/v1beta"
        }
        val cleanModel = model.trim().removePrefix("models/").ifEmpty { "gemini-2.5-flash" }
        val urlWithoutSse = when {
            base.contains(":streamGenerateContent") -> base
            base.contains(":generateContent") -> base.replace(":generateContent", ":streamGenerateContent")
            base.endsWith("/v1beta") || base.endsWith("/v1") ->
                "$base/models/$cleanModel:streamGenerateContent"
            else ->
                "$base/v1beta/models/$cleanModel:streamGenerateContent"
        }
        var result = if (urlWithoutSse.contains("alt=sse")) {
            urlWithoutSse
        } else {
            val sep = if (urlWithoutSse.contains("?")) "&" else "?"
            "${urlWithoutSse}${sep}alt=sse"
        }
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty() && !result.contains("key=")) {
            result = "$result&key=$trimmedKey"
        }
        return result
    }

    fun resolveOpenAiModelsEndpoint(rawBaseUrl: String): String {
        var base = rawBaseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return "https://api.openai.com/v1/models"
        base = base
            .removeSuffix("/chat/completions")
            .removeSuffix("/audio/transcriptions")
            .removeSuffix("/tools/asr")
            .removeSuffix("/completions")
            .trimEnd('/')
        if (base.endsWith("/models", ignoreCase = true)) return base
        val hasVersionPrefix = versionedSuffixes.any { base.endsWith(it, ignoreCase = true) }
        return if (hasVersionPrefix) {
            "$base/models"
        } else {
            "$base/v1/models"
        }
    }

    fun resolveGeminiModelsEndpoint(rawBaseUrl: String, apiKey: String): String {
        var base = rawBaseUrl.trim().trimEnd('/').ifEmpty {
            "https://generativelanguage.googleapis.com/v1beta"
        }
        val modelsSegmentIdx = base.indexOf("/models/")
        if (modelsSegmentIdx >= 0) {
            base = base.substring(0, modelsSegmentIdx)
        }
        val urlWithoutQuery = when {
            base.endsWith("/models", ignoreCase = true) -> base
            base.endsWith("/v1beta", ignoreCase = true) || base.endsWith("/v1", ignoreCase = true) ->
                "$base/models"
            else ->
                "$base/v1beta/models"
        }
        val params = mutableListOf("pageSize=200")
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isNotEmpty() && !urlWithoutQuery.contains("key=")) {
            params.add("key=$trimmedKey")
        }
        val sep = if (urlWithoutQuery.contains("?")) "&" else "?"
        return "$urlWithoutQuery$sep${params.joinToString("&")}"
    }

    fun resolveGatewayOpenAiModelsFallback(rawBaseUrl: String): String {
        var base = rawBaseUrl.trim().trimEnd('/')
        val modelsSegmentIdx = base.indexOf("/models")
        if (modelsSegmentIdx >= 0) {
            base = base.substring(0, modelsSegmentIdx)
        }
        base = base
            .removeSuffix("/v1beta")
            .removeSuffix("/v1")
            .trimEnd('/')
        return if (base.isEmpty()) {
            "https://api.openai.com/v1/models"
        } else {
            "$base/v1/models"
        }
    }

    fun resolveDashScopeWsEndpoint(rawBaseUrl: String): String {
        val clean = rawBaseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) {
            return DEFAULT_DASHSCOPE_WS_URL
        }
        if (clean.startsWith("wss://", ignoreCase = true) || clean.startsWith("ws://", ignoreCase = true)) {
            return if (clean.contains("/api-ws/")) clean else "$clean/api-ws/v1/inference"
        }
        if (clean.contains("dashscope", ignoreCase = true) || clean.contains("aliyuncs.com", ignoreCase = true)) {
            val hostOrigin = extractHttpOrigin(clean)
                ?.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                ?.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
                ?: "wss://dashscope.aliyuncs.com"
            return "$hostOrigin/api-ws/v1/inference"
        }
        return DEFAULT_DASHSCOPE_WS_URL
    }

    fun resolveVolcengineSaucWsEndpoint(rawBaseUrl: String): String {
        val clean = rawBaseUrl.trim().trimEnd('/')
        if (clean.isEmpty()) {
            return DEFAULT_VOLCENGINE_SAUC_WS_URL
        }
        if (clean.startsWith("wss://", ignoreCase = true) || clean.startsWith("ws://", ignoreCase = true)) {
            return if (clean.contains("/sauc/")) clean else "$clean/api/v3/sauc/bigmodel_async"
        }
        return DEFAULT_VOLCENGINE_SAUC_WS_URL
    }

    fun resolveGeminiLiveWsEndpoint(rawBaseUrl: String, apiKey: String): String {
        val clean = rawBaseUrl.trim().trimEnd('/')
        val wsBase = when {
            clean.isEmpty() -> DEFAULT_GEMINI_LIVE_WS_URL
            clean.startsWith("wss://", ignoreCase = true) || clean.startsWith("ws://", ignoreCase = true) -> {
                val noQuery = clean.substringBefore("?")
                if (noQuery.contains("/ws/") || noQuery.contains("BidiGenerateContent", ignoreCase = true)) {
                    clean
                } else {
                    "$noQuery$GEMINI_LIVE_WS_PATH"
                }
            }
            else -> {
                val origin = extractHttpOrigin(clean)
                    ?.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "wss://")
                    ?.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "ws://")
                    ?: "wss://generativelanguage.googleapis.com"
                "$origin$GEMINI_LIVE_WS_PATH"
            }
        }
        val trimmedKey = apiKey.trim()
        return if (trimmedKey.isEmpty() || wsBase.contains("key=")) {
            wsBase
        } else {
            val sep = if (wsBase.contains("?")) "&" else "?"
            "$wsBase${sep}key=$trimmedKey"
        }
    }

    fun isGeminiNativeHttpUrl(rawBaseUrl: String): Boolean {
        val lower = rawBaseUrl.trim().trimEnd('/').lowercase()
        if (lower.isEmpty()) return false
        if (lower.contains("/openai")) return false
        return lower.contains("generativelanguage.googleapis.com") ||
            lower.endsWith("/v1beta") ||
            lower.contains(":generatecontent")
    }

    /**
     * Extracts `http(s)://host[:port]` origin from an HTTP or WebSocket URL for connection pool pre-warming.
     */
    fun extractHttpOrigin(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val normalized = when {
            trimmed.startsWith("wss://", ignoreCase = true) -> "https://" + trimmed.substring(6)
            trimmed.startsWith("ws://", ignoreCase = true) -> "http://" + trimmed.substring(5)
            trimmed.startsWith("https://", ignoreCase = true) || trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            else -> return null
        }
        val schemeEnd = normalized.indexOf("://")
        if (schemeEnd <= 0) return null
        val hostStart = schemeEnd + 3
        val pathStart = normalized.indexOf('/', hostStart)
        val origin = if (pathStart >= 0) normalized.substring(0, pathStart) else normalized
        return origin.takeIf { it.length > hostStart }
    }

    /**
     * 该地址是否是「公网 + 明文 HTTP」——最需要告警的组合。
     *
     * 应用允许明文是为了支持**局域网自建**服务（Ollama / 自建网关），但把 apiKey
     * 与录音、聊天上下文发到**公网明文**地址时，同链路任何一跳都能直接读走。
     * 局域网/回环地址不算风险（流量不出网段），返回 false。
     *
     * 供设置页显示警示、以及请求前写日志，便于用户自查配置。
     */
    fun isInsecurePublicUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()
        // 非明文（https / wss）没有该风险
        val isCleartext = lower.startsWith("http://") || lower.startsWith("ws://")
        if (!isCleartext) return false
        // 回环 / 局域网 / 模拟器宿主机：流量不出网段
        val host = lower.substringAfter("://", "").substringBefore('/').substringBefore(':')
        if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "10.0.2.2") return false
        if (host.startsWith("192.168.") || host.startsWith("10.")) return false
        if (host.startsWith("172.")) {
            // 172.16.0.0 – 172.31.255.255 是私有段
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return false
        }
        return true
    }

    const val DEFAULT_DASHSCOPE_WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"
    const val DEFAULT_VOLCENGINE_SAUC_WS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    const val GEMINI_LIVE_WS_PATH =
        "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    const val DEFAULT_GEMINI_LIVE_WS_URL =
        "wss://generativelanguage.googleapis.com$GEMINI_LIVE_WS_PATH"
}
