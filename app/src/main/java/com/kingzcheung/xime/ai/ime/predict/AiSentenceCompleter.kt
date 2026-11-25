package com.kingzcheung.xime.ai.ime.predict

import com.kingzcheung.xime.ai.data.AppSettings
import com.kingzcheung.xime.ai.data.ToneStyle
import com.kingzcheung.xime.ai.network.AiPipelineEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

fun interface SentenceCompletionExecutor {
    suspend fun complete(
        settings: AppSettings,
        activeStyle: ToneStyle,
        contextBeforeCursor: String,
        targetAppName: String
    ): Result<String>
}

data class CompletionCacheKey(
    val contextTail: String,
    val styleId: String
)

/**
 * L3 AI Sentence Completer for TypeFree v2.7:
 * - Only triggers when [AppSettings.aiCompletionEnabled] is `true` and not in incognito mode.
 * - Waits [DEBOUNCE_MS] (`350ms`) of typing idle time before issuing a network request.
 * - Immediately cancels any pending debounce or in-flight network request when the user presses
 *   a new key, backspace, punctuation, or switches panels.
 * - Reuses up to 32 cached completions within the same app, settings snapshot and style.
 */
class AiSentenceCompleter(
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEBOUNCE_MS,
    private val cacheCapacity: Int = DEFAULT_CACHE_CAPACITY,
    private val executor: SentenceCompletionExecutor = SentenceCompletionExecutor { _, _, _, _ ->
        Result.success("")
    }
) {

    constructor(
        scope: CoroutineScope,
        aiEngine: AiPipelineEngine,
        debounceMs: Long = DEBOUNCE_MS,
        cacheCapacity: Int = DEFAULT_CACHE_CAPACITY
    ) : this(
        scope = scope,
        debounceMs = debounceMs,
        cacheCapacity = cacheCapacity,
        executor = SentenceCompletionExecutor { settings, activeStyle, contextBeforeCursor, targetAppName ->
            aiEngine.completeSentence(
                settings = settings,
                activeStyle = activeStyle,
                contextBeforeCursor = contextBeforeCursor,
                targetAppName = targetAppName
            )
        }
    )

    private class CacheScope(
        val appName: String,
        val settings: AppSettings,
        val style: ToneStyle,
    )

    private val cacheLock = Any()
    private var cacheScope: CacheScope? = null
    private val lruCache = object : LinkedHashMap<CompletionCacheKey, String>(
        cacheCapacity.coerceAtLeast(1),
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CompletionCacheKey, String>?): Boolean {
            return size > cacheCapacity.coerceAtLeast(1)
        }
    }

    @Volatile
    private var activeJob: Job? = null

    val isPending: Boolean
        get() = activeJob?.isActive == true

    /**
     * Cancels any active 350ms debounce timer or in-flight network completion request immediately.
     */
    fun cancel() {
        synchronized(cacheLock) {
            activeJob?.cancel()
            activeJob = null
        }
    }

    fun getCached(contextBeforeCursor: String, styleId: String): String? {
        val tail = normalizeContextWindow(contextBeforeCursor)
        if (tail.isEmpty()) return null
        val key = CompletionCacheKey(contextTail = tail, styleId = styleId)
        return synchronized(cacheLock) {
            lruCache[key]
        }
    }

    fun putCached(contextBeforeCursor: String, styleId: String, suggestion: String) {
        val tail = normalizeContextWindow(contextBeforeCursor)
        val clean = cleanCompletionOutput(suggestion, tail)
        if (tail.isEmpty() || clean.isEmpty()) return
        val key = CompletionCacheKey(contextTail = tail, styleId = styleId)
        synchronized(cacheLock) {
            lruCache[key] = clean
        }
    }

    fun cacheSize(): Int = synchronized(cacheLock) { lruCache.size }

    fun clearCache() {
        synchronized(cacheLock) {
            cacheScope = null
            lruCache.clear()
        }
    }

    /**
     * Schedules an AI sentence completion request:
     * - Returns `null` without scheduling if `settings.aiCompletionEnabled == false`,
     *   either incognito flag is set, or `contextBeforeCursor` is blank.
     * - Delivers a cache hit synchronously within the current cache scope.
     * - Otherwise debounces for [debounceMs] (`350ms`) before invoking [executor].
     */
    fun scheduleCompletion(
        contextBeforeCursor: String,
        settings: AppSettings,
        activeStyle: ToneStyle,
        targetAppName: String = "",
        isIncognitoMode: Boolean = false,
        onResult: (String) -> Unit
    ): Job? {
        val contextTail = normalizeContextWindow(contextBeforeCursor)
        val key = CompletionCacheKey(contextTail = contextTail, styleId = activeStyle.id)
        val (cached, job) = synchronized(cacheLock) {
            cancel()
            if (!settings.aiCompletionEnabled || isIncognitoMode || settings.manualIncognitoMode) {
                clearCache()
                return null
            }
            val previous = cacheScope
            val requestScope = if (previous != null && previous.appName == targetAppName &&
                previous.settings === settings && previous.style == activeStyle
            ) {
                previous
            } else {
                lruCache.clear()
                CacheScope(targetAppName, settings, activeStyle).also { cacheScope = it }
            }
            if (contextTail.isEmpty()) return null
            val hit = lruCache[key]
            val pending = if (hit == null) scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (debounceMs > 0L) delay(debounceMs)
                    ensureActive()
                    val secondCheck = synchronized(cacheLock) {
                        if (cacheScope !== requestScope) return@launch
                        lruCache[key]
                    }
                    if (secondCheck != null) {
                        ensureActive()
                        onResult(secondCheck)
                        return@launch
                    }

                    val result = executor.complete(settings, activeStyle, contextTail, targetAppName)
                    ensureActive()
                    val cleaned = cleanCompletionOutput(result.getOrThrow(), contextTail)
                    if (cleaned.isEmpty()) return@launch
                    synchronized(cacheLock) {
                        ensureActive()
                        if (cacheScope !== requestScope) return@launch
                        lruCache[key] = cleaned
                    }
                    ensureActive()
                    onResult(cleaned)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // Background completion failures must not disrupt typing.
                }
            } else null
            activeJob = pending
            hit to pending
        }
        if (cached != null) onResult(cached)
        job?.start()
        return job
    }

    companion object {
        const val DEBOUNCE_MS: Long = 350L
        const val MAX_CONTEXT_CHARS: Int = 200
        const val MAX_COMPLETION_CHARS: Int = 20
        const val MAX_CHIP_DISPLAY_CHARS: Int = 12
        const val DEFAULT_CACHE_CAPACITY: Int = 32

        private val WRAPPING_PAIRS: List<Pair<Char, Char>> = listOf(
            '"' to '"',
            '\'' to '\'',
            '“' to '”',
            '‘' to '’',
            '「' to '」',
            '『' to '』',
            '`' to '`'
        )

        fun normalizeContextWindow(contextBeforeCursor: String): String {
            val trimmed = contextBeforeCursor.trimEnd()
            if (trimmed.isEmpty()) return ""
            return if (trimmed.length <= MAX_CONTEXT_CHARS) {
                trimmed
            } else {
                trimmed.takeLast(MAX_CONTEXT_CHARS)
            }
        }

        /**
         * Cleans raw LLM output into a concise continuation phrase (`<= 20` chars):
         * - Takes first non-blank line
         * - Removes wrapping quotes or explanatory prefixes
         * - Strips overlapping prefix if the model repeated the tail of [contextBeforeCursor]
         * - Caps at [MAX_COMPLETION_CHARS] characters
         */
        fun cleanCompletionOutput(
            rawOutput: String,
            contextBeforeCursor: String = "",
            maxChars: Int = MAX_COMPLETION_CHARS
        ): String {
            var line = rawOutput
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (line.isEmpty()) return ""
            if (line.contains("[EMPTY", ignoreCase = true) || line.contains("[NONE", ignoreCase = true)) {
                return ""
            }

            // Strip wrapping quotes
            var unwrapped = true
            while (unwrapped && line.length >= 2) {
                unwrapped = false
                for ((open, close) in WRAPPING_PAIRS) {
                    if (line.first() == open && line.last() == close) {
                        line = line.substring(1, line.length - 1).trim()
                        unwrapped = true
                        break
                    }
                }
            }

            // Strip accidental repetition of contextBeforeCursor or its suffix
            val cleanContext = contextBeforeCursor.trim()
            if (cleanContext.isNotEmpty() && line.isNotEmpty()) {
                if (line.startsWith(cleanContext) && line.length > cleanContext.length) {
                    line = line.substring(cleanContext.length).trimStart()
                } else {
                    val maxOverlap = minOf(cleanContext.length, line.length - 1, 24)
                    for (overlapLen in maxOverlap downTo 2) {
                        val suffix = cleanContext.takeLast(overlapLen)
                        if (line.startsWith(suffix)) {
                            line = line.substring(overlapLen).trimStart()
                            break
                        }
                    }
                }
            }

            if (line.isEmpty()) return ""
            val cap = maxChars.coerceAtLeast(1)
            return if (line.length <= cap) line else line.take(cap)
        }

        /**
         * Formats the AI completion chip preview label (`✦ ` prefix + max 12 chars before truncation).
         */
        fun formatChipPreview(
            suggestion: String,
            maxDisplayChars: Int = MAX_CHIP_DISPLAY_CHARS
        ): String {
            val clean = suggestion.trim()
            if (clean.isEmpty()) return ""
            val limit = maxDisplayChars.coerceAtLeast(1)
            val body = if (clean.length <= limit) clean else clean.take(limit) + "…"
            return "✦ $body"
        }
    }
}
