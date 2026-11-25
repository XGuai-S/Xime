package com.kingzcheung.xime.association

import android.content.Context
import com.kingzcheung.xime.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 用户输入的 bigram / trigram 缓存。
 *
 * 线程模型：recordInput（输入线程）、getUserCandidates（预测线程）、save（IO 线程）
 * 会并发访问同一份 Trie，所以所有对 Trie / recentInputs 的读写都经过 [lock]。
 * 磁盘写入放在锁外，并用临时文件 + 重命名保证写到一半被杀进程时不会损坏旧缓存。
 */
class UserNgramCache(private val context: Context) {
    companion object {
        private const val TAG = "UserNgramCache"
        private const val CACHE_FILE_NAME = "user_ngram_cache.json"
        private const val MAX_ENTRIES = 1_000
        private const val MAX_HISTORY_SIZE = 100

        private val PUNCTUATION = setOf(
            '，', '。', '！', '？', '、', '；', '：',
            '"', '\'', '\u201C', '\u201D', '\u2018', '\u2019',
            ' ', '\n', '\t'
        )
    }

    private val lock = Any()
    private val saveMutex = Mutex()
    private val bigramTrie = NgramTrie()
    private val trigramTrie = NgramTrie()
    private val bigramOrder = LinkedHashMap<List<String>, Unit>()
    private val trigramOrder = LinkedHashMap<List<String>, Unit>()
    private val recentInputs = mutableListOf<String>()

    @Volatile
    private var dirty = false

    private val cacheFile: File
        get() = File(context.filesDir, CACHE_FILE_NAME)

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = cacheFile
            if (file.exists()) {
                val json = JSONObject(file.readText())
                synchronized(lock) { loadFromJson(json) }
            }
            dirty = false
            true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to initialize cache: ${e.message}", e)
            false
        }
    }

    fun recordInput(text: String) {
        if (text.isBlank()) return

        val tokens = tokenize(text)
        if (tokens.isEmpty()) return

        synchronized(lock) {
            updateRecentInputs(tokens)
            tokens.windowed(2).forEach { addNgram(bigramTrie, bigramOrder, it) }
            tokens.windowed(3).forEach { addNgram(trigramTrie, trigramOrder, it) }
            dirty = true
        }
    }

    private fun addNgram(
        trie: NgramTrie,
        order: LinkedHashMap<List<String>, Unit>,
        tokens: List<String>,
        count: Int = 1,
    ) {
        if (count <= 0 || tokens.isEmpty()) return
        val key = tokens.toList()
        if (order.containsKey(key)) {
            trie.insert(key, count)
            order.remove(key)
            order[key] = Unit
            return
        }
        if (order.size >= MAX_ENTRIES) {
            val oldest = order.entries.iterator()
            if (oldest.hasNext()) {
                val keyToRemove = oldest.next().key
                oldest.remove()
                trie.remove(keyToRemove)
            }
        }
        trie.insert(key, count)
        order[key] = Unit
    }

    private fun tokenize(text: String): List<String> {
        return text.filter { it.isLetterOrDigit() || it in PUNCTUATION }
            .map { it.toString() }
    }

    private fun updateRecentInputs(newTokens: List<String>) {
        recentInputs.addAll(newTokens)
        if (recentInputs.size > MAX_HISTORY_SIZE) {
            recentInputs.subList(0, recentInputs.size - MAX_HISTORY_SIZE).clear()
        }
    }

    fun getBigramScore(word1: String, word2: String): Float = synchronized(lock) {
        bigramTrie.getFrequency(listOf(word1, word2))
    }

    fun getTrigramScore(word1: String, word2: String, word3: String): Float = synchronized(lock) {
        trigramTrie.getFrequency(listOf(word1, word2, word3))
    }

    fun getContextualScore(context: String, candidate: String): Float {
        val tokens = tokenize(context)
        if (tokens.isEmpty()) return 0f

        val lastToken = tokens.last()
        val bigramScore = getBigramScore(lastToken, candidate)
        val trigramScore = if (tokens.size >= 2) {
            getTrigramScore(tokens[tokens.size - 2], lastToken, candidate)
        } else {
            0f
        }
        return maxOf(bigramScore, trigramScore)
    }

    /**
     * 只访问上下文末尾 1~2 个 token 对应的子节点，耗时与历史总量无关；
     * 旧实现每次预测都要遍历并重新查询全部 n-gram。
     * 同一个候选同时命中 bigram 与 trigram 时取较大值。
     */
    fun getUserCandidates(context: String, topK: Int = 5): List<Pair<String, Float>> {
        val tokens = tokenize(context)
        if (tokens.isEmpty()) return emptyList()

        val lastToken = tokens.last()
        val merged = HashMap<String, Float>()

        synchronized(lock) {
            bigramTrie.getChildScores(listOf(lastToken)).forEach { (word, score) ->
                merged[word] = score
            }
            if (tokens.size >= 2) {
                trigramTrie.getChildScores(listOf(tokens[tokens.size - 2], lastToken))
                    .forEach { (word, score) ->
                        merged[word] = maxOf(merged[word] ?: 0f, score)
                    }
            }
        }

        return merged.entries
            .filter { it.value > 0f }
            .sortedByDescending { it.value }
            .take(topK)
            .map { it.key to it.value }
    }

    suspend fun save() {
        if (!dirty) return
        saveMutex.withLock {
            if (!dirty) return
            withContext(Dispatchers.IO) {
                val text = synchronized(lock) {
                    dirty = false
                    toJson().toString()
                }
                try {
                    com.kingzcheung.xime.util.AtomicFileWriter.writeOrThrow(cacheFile, text)
                } catch (e: Exception) {
                    dirty = true
                    FileLogger.e(TAG, "Failed to save cache: ${e.message}", e)
                }
            }
        }
    }

    fun clear() {
        synchronized(lock) {
            bigramTrie.clear()
            trigramTrie.clear()
            bigramOrder.clear()
            trigramOrder.clear()
            recentInputs.clear()
            dirty = false
        }
        val file = cacheFile
        if (file.exists()) {
            file.delete()
        }
    }

    fun getCacheSize(): Int = synchronized(lock) { bigramTrie.size() + trigramTrie.size() }

    private fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("bigrams", entriesToJson(bigramTrie, bigramOrder))
        json.put("trigrams", entriesToJson(trigramTrie, trigramOrder))
        json.put("recentInputs", JSONArray(recentInputs))
        return json
    }

    private fun entriesToJson(
        trie: NgramTrie,
        order: LinkedHashMap<List<String>, Unit>,
    ): JSONArray {
        val array = JSONArray()
        order.keys.forEach { tokens ->
            val count = trie.getCount(tokens)
            if (count <= 0) return@forEach
            val entry = JSONObject()
            entry.put("tokens", JSONArray(tokens))
            entry.put("count", count)
            array.put(entry)
        }
        return array
    }

    private fun loadTrie(
        trie: NgramTrie,
        order: LinkedHashMap<List<String>, Unit>,
        array: JSONArray,
    ) {
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val tokenArray = entry.getJSONArray("tokens")
            val tokens = (0 until tokenArray.length()).map { tokenArray.getString(it) }
            addNgram(trie, order, tokens, entry.getInt("count"))
        }
    }

    private fun loadFromJson(json: JSONObject) {
        bigramTrie.clear()
        trigramTrie.clear()
        bigramOrder.clear()
        trigramOrder.clear()
        recentInputs.clear()

        loadTrie(bigramTrie, bigramOrder, json.optJSONArray("bigrams") ?: JSONArray())
        loadTrie(trigramTrie, trigramOrder, json.optJSONArray("trigrams") ?: JSONArray())

        val recentArray = json.optJSONArray("recentInputs") ?: JSONArray()
        for (i in 0 until recentArray.length()) {
            recentInputs.add(recentArray.getString(i))
        }
    }
}
