package com.kingzcheung.xime.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NgramTrieTest {
    @Test
    fun `frequency is conditional probability within the same prefix`() {
        val trie = NgramTrie()
        repeat(3) { trie.insert(listOf("我", "们")) }
        trie.insert(listOf("我", "的"))

        assertEquals(0.75f, trie.getFrequency(listOf("我", "们")), 1e-6f)
        assertEquals(0.25f, trie.getFrequency(listOf("我", "的")), 1e-6f)
    }

    @Test
    fun `unknown ngram scores zero`() {
        val trie = NgramTrie()
        trie.insert(listOf("a", "b"))

        assertEquals(0f, trie.getFrequency(listOf("a", "c")), 0f)
        assertEquals(0f, trie.getFrequency(listOf("x", "b")), 0f)
        assertEquals(0f, trie.getFrequency(emptyList()), 0f)
    }

    @Test
    fun `child scores only cover the requested prefix`() {
        val trie = NgramTrie()
        trie.insert(listOf("a", "b"), 2)
        trie.insert(listOf("a", "c"), 2)
        trie.insert(listOf("z", "y"), 5)

        val scores = trie.getChildScores(listOf("a")).toMap()
        assertEquals(setOf("b", "c"), scores.keys)
        assertEquals(0.5f, scores.getValue("b"), 1e-6f)
        assertTrue(trie.getChildScores(listOf("missing")).isEmpty())
    }

    @Test
    fun `entries round trip through insert with counts`() {
        val trie = NgramTrie()
        trie.insert(listOf("a", "b"), 4)
        trie.insert(listOf("a", "c"))

        val copy = NgramTrie()
        trie.getAllEntries().forEach { (tokens, count) -> copy.insert(tokens, count) }

        assertEquals(trie.size(), copy.size())
        assertEquals(trie.getFrequency(listOf("a", "b")), copy.getFrequency(listOf("a", "b")), 1e-6f)
    }
}
