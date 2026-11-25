package com.kingzcheung.xime.association

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.json.JSONObject

class UserNgramCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun context(): Context = mock<Context>().also {
        whenever(it.filesDir).thenReturn(temp.root)
    }

    @Test
    fun `cache stays bounded and evicts least recently used entries`() = runBlocking {
        val context = context()
        val cache = UserNgramCache(context)
        assertTrue(cache.initialize())

        val chars = (0 until 1_001).map { (0x4E00 + it).toChar() }
        chars.forEachIndexed { index, first ->
            val second = (0x5200 + index).toChar()
            cache.recordInput("中$first$second")
        }

        val bigramCandidates = cache.getUserCandidates("中", 2_000).map { it.first }
        assertTrue("最旧 bigram 应被淘汰", chars.first().toString() !in bigramCandidates)
        assertTrue("最新 bigram 应保留", chars.last().toString() in bigramCandidates)
        val trigramCandidates = cache.getUserCandidates("中${chars.last()}", 2_000).map { it.first }
        assertTrue("最新 trigram 应保留", (0x5200 + 1_000).toChar().toString() in trigramCandidates)

        cache.save()
        val saved = JSONObject(java.io.File(temp.root, "user_ngram_cache.json").readText())
        assertEquals(1_000, saved.getJSONArray("bigrams").length())
        assertEquals(1_000, saved.getJSONArray("trigrams").length())

        val restored = UserNgramCache(context)
        assertTrue(restored.initialize())
        assertEquals(bigramCandidates.toSet(), restored.getUserCandidates("中", 2_000).map { it.first }.toSet())
    }

    @Test
    fun `save and reload preserve ngram counts and conditional scores`() = runBlocking {
        val context = context()
        val cache = UserNgramCache(context)
        assertTrue(cache.initialize())

        repeat(3) { cache.recordInput("ab") }
        cache.recordInput("ac")
        val before = cache.getUserCandidates("a", 10).toMap()

        cache.save()

        val restored = UserNgramCache(context)
        assertTrue(restored.initialize())
        val after = restored.getUserCandidates("a", 10).toMap()

        assertEquals(before, after)
        assertEquals(0.75f, after.getValue("b"), 1e-6f)
        assertEquals(0.25f, after.getValue("c"), 1e-6f)
        assertEquals(cache.getCacheSize(), restored.getCacheSize())
    }
}
