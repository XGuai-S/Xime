package com.kingzcheung.xime.association

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TrieNodeData(
    val children: ByteArray = ByteArray(0),
    val childIndices: IntArray = IntArray(0),
    val word: String? = null,
    val frequency: Int = 0
)

/**
 * 英文联想前缀 Trie。
 *
 * 懒加载设计：完整 Trie（english_trie.bin 展开为约 2.4 万节点对象，常驻数 MB 堆）
 * 只在首次真正发生英文联想（predict）时才加载，避免服务启动即无条件占用内存。
 * loadedNodes 为 @Volatile，保证已加载后的读路径无锁可见；首次加载由
 * synchronized(this) 保证幂等（多线程并发首次触发时只加载一次）。
 */
class TrieAssociationEngine {
    private var nodes: Array<TrieNodeData>? = null
    private var isInitialized = false
    private var cachedContext: Context? = null

    @Volatile
    private var loadedNodes: Array<TrieNodeData>? = null

    companion object {
        private const val TAG = "TrieAssociationEngine"
        private const val BINARY_FILE = "english_trie.bin"
        private const val TEXT_FILE = "english.txt"
        private var instance: TrieAssociationEngine? = null

        fun getInstance(): TrieAssociationEngine {
            return instance ?: TrieAssociationEngine().also { instance = it }
        }
    }

    /**
     * 记录初始化上下文并标记就绪；真正的 Trie 加载延后到首次 [predict]。
     * 服务启动仍会调用本方法，但不再立即展开 Trie 占用内存。
     */
    suspend fun initialize(context: Context): Boolean = withContext(Dispatchers.IO) {
        cachedContext = context
        isInitialized = true
        Log.i(TAG, "Trie engine marked lazily-initializable (load deferred to first recall)")
        true
    }

    /**
     * 确保 Trie 已加载（幂等、线程安全）。返回是否加载成功。
     */
    private fun ensureLoaded(): Boolean {
        if (loadedNodes != null) {
            nodes = loadedNodes
            return true
        }
        synchronized(this) {
            if (loadedNodes != null) {
                nodes = loadedNodes
                return true
            }
            val ctx = cachedContext ?: return false
            try {
                val startTime = System.currentTimeMillis()
                val binaryExists = try {
                    ctx.assets.list("")?.contains(BINARY_FILE) ?: false
                } catch (e: Exception) {
                    false
                }
                if (binaryExists) {
                    loadBinaryTrie(ctx)
                } else {
                    Log.w(TAG, "Binary trie not found, building from text")
                    buildTrieFromText(ctx)
                }
                loadedNodes = nodes
                val elapsed = System.currentTimeMillis() - startTime
                Log.i(TAG, "Trie engine loaded in $elapsed ms (${nodes?.size ?: 0} nodes)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load trie engine", e)
                return false
            }
        }
    }

    private fun loadBinaryTrie(context: Context) {
        val bytes = context.assets.open(BINARY_FILE).readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)

        val magic = ByteArray(4)
        buffer.get(magic)
        require(magic.contentEquals("TRIE".toByteArray())) { "Invalid trie file" }

        val version = buffer.get()
        require(version.toInt() == 1) { "Unsupported trie version: $version" }

        val nodeCount = buffer.getInt()
        val newNodes = arrayOfNulls<TrieNodeData>(nodeCount)

        for (i in 0 until nodeCount) {
            val childCount = buffer.get().toInt()
            val children = ByteArray(childCount)
            val childIndices = IntArray(childCount)

            for (j in 0 until childCount) {
                children[j] = buffer.get()
                childIndices[j] = buffer.getInt()
            }

            val hasWord = buffer.get().toInt()
            var word: String? = null
            var freq = 0

            if (hasWord == 1) {
                val wordLen = buffer.get().toInt()
                val wordBytes = ByteArray(wordLen)
                buffer.get(wordBytes)
                word = String(wordBytes, Charsets.UTF_8)
                freq = buffer.getInt()
            }

            newNodes[i] = TrieNodeData(children, childIndices, word, freq)
        }

        nodes = newNodes.filterNotNull().toTypedArray()
    }

    private fun buildTrieFromText(context: Context) {
        val tempChildren = mutableListOf<ByteArray>()
        val tempIndices = mutableListOf<IntArray>()
        val tempWords = mutableListOf<String?>()
        val tempFreqs = mutableListOf<Int>()

        tempChildren.add(ByteArray(0))
        tempIndices.add(IntArray(0))
        tempWords.add(null)
        tempFreqs.add(0)

        fun getOrCreateChild(parentIdx: Int, char: Char): Int {
            val pChildren = tempChildren[parentIdx]
            val pIndices = tempIndices[parentIdx]
            val charByte = char.code.toByte()

            for (i in pChildren.indices) {
                if (pChildren[i] == charByte) return pIndices[i]
            }

            val newIdx = tempChildren.size
            tempChildren.add(ByteArray(0))
            tempIndices.add(IntArray(0))
            tempWords.add(null)
            tempFreqs.add(0)

            val newChildren = ByteArray(pChildren.size + 1)
            val newIndices = IntArray(pIndices.size + 1)
            pChildren.copyInto(newChildren)
            pIndices.copyInto(newIndices)
            newChildren[pChildren.size] = charByte
            newIndices[pIndices.size] = newIdx

            tempChildren[parentIdx] = newChildren
            tempIndices[parentIdx] = newIndices
            return newIdx
        }

        context.assets.open(TEXT_FILE).bufferedReader().use { reader ->
            var lineNum = 0
            reader.lineSequence().forEach { line ->
                lineNum++
                val word = line.trim().lowercase()
                if (word.isNotEmpty()) {
                    var current = 0
                    for (char in word) {
                        current = getOrCreateChild(current, char)
                    }
                    if (tempWords[current] == null) {
                        tempWords[current] = word
                        tempFreqs[current] = lineNum
                    }
                }
            }
        }

        nodes = Array(tempChildren.size) { i ->
            TrieNodeData(tempChildren[i], tempIndices[i], tempWords[i], tempFreqs[i])
        }
    }

    suspend fun predict(prefix: String, topK: Int = 5): List<AssociationCandidate> = withContext(Dispatchers.Default) {
        if (prefix.isEmpty()) return@withContext emptyList()
        if (!ensureLoaded()) return@withContext emptyList()

        val normalizedPrefix = prefix.lowercase()
        val startIdx = findNode(normalizedPrefix)

        if (startIdx < 0) return@withContext emptyList()

        val candidates = mutableListOf<AssociationCandidate>()
        collectWords(startIdx, candidates)

        candidates.sortedBy { it.score }.take(topK)
    }

    private fun findNode(prefix: String): Int {
        val table = nodes ?: return -1
        var current = 0
        for (char in prefix) {
            val node = table[current]
            val charByte = char.code.toByte()
            var found = -1
            for (i in node.children.indices) {
                if (node.children[i] == charByte) {
                    found = node.childIndices[i]
                    break
                }
            }
            if (found < 0) return -1
            current = found
        }
        return current
    }

    private fun collectWords(nodeIdx: Int, candidates: MutableList<AssociationCandidate>) {
        val node = nodes?.getOrNull(nodeIdx) ?: return

        if (node.word != null) {
            candidates.add(AssociationCandidate(node.word, node.frequency.toFloat()))
        }

        for (childIdx in node.childIndices) {
            collectWords(childIdx, candidates)
        }
    }

    fun isInitialized(): Boolean = isInitialized

    fun release() {
        nodes = null
        loadedNodes = null
        cachedContext = null
        isInitialized = false
    }
}