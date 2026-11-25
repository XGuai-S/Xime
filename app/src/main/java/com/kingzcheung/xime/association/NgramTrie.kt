package com.kingzcheung.xime.association

class TrieNode(
    val children: MutableMap<String, TrieNode> = HashMap(),
    var count: Int = 0
)

/**
 * 用户 n-gram 前缀树。非线程安全，由调用方（UserNgramCache）统一加锁。
 *
 * 频率定义为条件概率：count(ngram) / sum(count(同一前缀下的所有 ngram))。
 * 旧实现用 prefixCount 做分母，但它只在新建路径时自增，结果恒等于 min(count, 1)，
 * 所有出现过的 n-gram 得分都是 1.0，无法区分高频与低频。
 */
class NgramTrie {
    private val root = TrieNode()
    private var entryCount = 0

    /** 返回该 n-gram 是否为新条目。 */
    fun insert(ngram: List<String>, times: Int = 1): Boolean {
        if (ngram.isEmpty() || times <= 0) return false
        var node = root
        for (token in ngram) {
            node = node.children.getOrPut(token) { TrieNode() }
        }
        val isNew = node.count == 0
        if (isNew) entryCount++
        node.count += times
        return isNew
    }

    fun getCount(ngram: List<String>): Int {
        if (ngram.isEmpty()) return 0
        return findNode(ngram)?.count ?: 0
    }

    /** 删除一条完整 n-gram 并剪除空分支。 */
    fun remove(ngram: List<String>): Boolean {
        if (ngram.isEmpty()) return false
        val parents = ArrayList<TrieNode>(ngram.size)
        var node = root
        for (token in ngram) {
            parents.add(node)
            node = node.children[token] ?: return false
        }
        if (node.count <= 0) return false
        node.count = 0
        entryCount--
        for (index in ngram.indices.reversed()) {
            val child = parents[index].children[ngram[index]] ?: break
            if (child.count == 0 && child.children.isEmpty()) {
                parents[index].children.remove(ngram[index])
            } else {
                break
            }
        }
        return true
    }

    private fun findNode(prefix: List<String>): TrieNode? {
        var node = root
        for (token in prefix) {
            node = node.children[token] ?: return null
        }
        return node
    }

    private fun totalCount(node: TrieNode): Int {
        var total = 0
        for (child in node.children.values) total += child.count
        return total
    }

    fun getFrequency(ngram: List<String>): Float {
        if (ngram.isEmpty()) return 0f
        val parent = findNode(ngram.dropLast(1)) ?: return 0f
        val last = parent.children[ngram.last()] ?: return 0f
        val total = totalCount(parent)
        if (total == 0) return 0f
        return (last.count.toFloat() / total).coerceIn(0f, 1f)
    }

    /** 返回 [prefix] 之后所有已出现的下一个 token 及其条件概率，只访问该前缀的直接子节点。 */
    fun getChildScores(prefix: List<String>): List<Pair<String, Float>> {
        val parent = findNode(prefix) ?: return emptyList()
        val total = totalCount(parent)
        if (total == 0) return emptyList()
        val result = ArrayList<Pair<String, Float>>(parent.children.size)
        for ((token, child) in parent.children) {
            if (child.count > 0) {
                result.add(token to (child.count.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        return result
    }

    fun getAllEntries(): List<Pair<List<String>, Int>> {
        val entries = ArrayList<Pair<List<String>, Int>>()
        collectEntries(root, ArrayList(), entries)
        return entries
    }

    private fun collectEntries(
        node: TrieNode,
        path: ArrayList<String>,
        entries: MutableList<Pair<List<String>, Int>>
    ) {
        if (node.count > 0) {
            entries.add(path.toList() to node.count)
        }
        for ((token, child) in node.children) {
            path.add(token)
            collectEntries(child, path, entries)
            path.removeAt(path.size - 1)
        }
    }

    fun clear() {
        root.children.clear()
        root.count = 0
        entryCount = 0
    }

    fun distinctSize(): Int = entryCount

    /** 所有 n-gram 的出现次数之和。 */
    fun size(): Int {
        var count = 0
        fun countNodes(node: TrieNode) {
            count += node.count
            node.children.values.forEach { countNodes(it) }
        }
        countNodes(root)
        return count
    }
}
