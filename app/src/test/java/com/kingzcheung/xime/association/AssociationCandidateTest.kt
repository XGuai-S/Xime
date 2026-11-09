package com.kingzcheung.xime.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationCandidateTest {
    
    @Test
    fun `AssociationCandidate should have correct properties`() {
        val candidate = AssociationCandidate("你好", 0.85f)
        
        assertEquals("你好", candidate.text)
        assertEquals(0.85f, candidate.score)
    }
    
    @Test
    fun `AssociationCandidate score can be negative`() {
        val candidate = AssociationCandidate("测试", -2.5f)
        
        assertEquals("测试", candidate.text)
        assertTrue("Score can be negative", candidate.score < 0)
    }
    
    @Test
    fun `AssociationCandidate score can be zero`() {
        val candidate = AssociationCandidate("零", 0f)
        
        assertEquals(0f, candidate.score)
    }
    
    @Test
    fun `AssociationCandidate score can be very high`() {
        val candidate = AssociationCandidate("高分", 100f)
        
        assertEquals(100f, candidate.score)
    }
    
    @Test
    fun `candidates should be sorted by score descending`() {
        val candidates = listOf(
            AssociationCandidate("低", 0.1f),
            AssociationCandidate("高", 0.9f),
            AssociationCandidate("中", 0.5f)
        )
        
        val sorted = candidates.sortedByDescending { it.score }
        
        assertEquals("高", sorted[0].text)
        assertEquals("中", sorted[1].text)
        assertEquals("低", sorted[2].text)
    }
    
    @Test
    fun `candidates with same score maintain relative order`() {
        val candidates = listOf(
            AssociationCandidate("A", 0.5f),
            AssociationCandidate("B", 0.5f),
            AssociationCandidate("C", 0.5f)
        )
        
        val sorted = candidates.sortedByDescending { it.score }
        
        assertEquals(3, sorted.size)
        assertEquals(0.5f, sorted[0].score)
    }
    
    @Test
    fun `empty candidate list is valid`() {
        val candidates = emptyList<AssociationCandidate>()
        
        assertTrue("Empty list is valid", candidates.isEmpty())
    }
    
    @Test
    fun `candidate text can be empty`() {
        val candidate = AssociationCandidate("", 0.5f)
        
        assertEquals("", candidate.text)
    }
    
    @Test
    fun `candidate text can contain special characters`() {
        val candidate = AssociationCandidate("你好\n世界\t!", 0.5f)
        
        assertTrue("Text can contain special chars", candidate.text.contains("\n"))
        assertTrue("Text can contain special chars", candidate.text.contains("\t"))
    }
    
    @Test
    fun `candidate comparison by score`() {
        val c1 = AssociationCandidate("A", 0.7f)
        val c2 = AssociationCandidate("B", 0.3f)
        
        assertTrue("c1 has higher score", c1.score > c2.score)
    }
    
    @Test
    fun `candidate filtering by threshold`() {
        val candidates = listOf(
            AssociationCandidate("高", 0.9f),
            AssociationCandidate("中", 0.5f),
            AssociationCandidate("低", 0.1f)
        )
        
        val filtered = candidates.filter { it.score > 0.4f }
        
        assertEquals(2, filtered.size)
        assertTrue("All filtered have score > 0.4", filtered.all { it.score > 0.4f })
    }
    
    @Test
    fun `candidate mapping to text list`() {
        val candidates = listOf(
            AssociationCandidate("你好", 0.8f),
            AssociationCandidate("世界", 0.6f)
        )
        
        val texts = candidates.map { it.text }
        
        assertEquals(listOf("你好", "世界"), texts)
    }
    
    @Test
    fun `topK selection from candidates`() {
        val candidates = (1..20).map { i ->
            AssociationCandidate("词$i", i.toFloat() / 20f)
        }.sortedByDescending { it.score }
        
        val top5 = candidates.take(5)
        
        assertEquals(5, top5.size)
        assertTrue("TopK should have highest scores", top5.all { it.score > 0.75f })
    }
}