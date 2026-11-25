package com.kingzcheung.xime.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveBudgetTest {

    @Test
    fun `copies content within budget`() {
        val data = ByteArray(1000) { it.toByte() }
        val out = ByteArrayOutputStream()
        ArchiveBudget(maxTotalBytes = 1000).copy(ByteArrayInputStream(data), out)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `total bytes accumulate across entries and abort when exceeded`() {
        val budget = ArchiveBudget(maxTotalBytes = 1500)
        budget.copy(ByteArrayInputStream(ByteArray(1000)), ByteArrayOutputStream())
        val out = ByteArrayOutputStream()
        assertThrows(IOException::class.java) {
            budget.copy(ByteArrayInputStream(ByteArray(1000)), out)
        }
        // 中止前写出的量不超过剩余额度
        assertTrue(out.size() <= 500)
    }

    @Test
    fun `entry count limit aborts`() {
        val budget = ArchiveBudget(maxEntries = 2)
        budget.onEntry()
        budget.onEntry()
        assertThrows(IOException::class.java) { budget.onEntry() }
    }
}
