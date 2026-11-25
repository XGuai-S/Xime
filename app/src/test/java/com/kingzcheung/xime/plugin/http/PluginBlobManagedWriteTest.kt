package com.kingzcheung.xime.plugin.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PluginBlobManagedWriteTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `aggregate quota rejects a chunk before any excess bytes reach disk`() {
        val store = PluginBlobStore(File(temporary.root, "blobs"), maxTotalBytes = 10, maxEntries = 8)
        val first = store.createTempFile("a")
        val second = store.createTempFile("b")
        try {
            store.openTempOutputStream("a", first).use { a ->
                store.openTempOutputStream("b", second).use { b ->
                    a.write(ByteArray(6))
                    assertThrows(IOException::class.java) { b.write(ByteArray(6)) }
                    assertEquals(6L, store.usedBytes())
                    assertEquals(0L, second.length())
                }
            }
        } finally {
            store.discardTempFile("a", first)
            store.discardTempFile("b", second)
        }
    }

    @Test
    fun `small downloads use the exact remaining budget without a full-file reservation`() {
        val store = PluginBlobStore(File(temporary.root, "blobs"), maxTotalBytes = 10, maxEntries = 8)
        val first = store.createTempFile("a")
        var second: File? = null
        try {
            store.openTempOutputStream("a", first).use { it.write(ByteArray(6)) }
            val next = store.createTempFile("b")
            second = next
            store.openTempOutputStream("b", next).use { it.write(ByteArray(4)) }
            assertEquals(10L, store.usedBytes())
        } finally {
            store.discardTempFile("a", first)
            second?.let { store.discardTempFile("b", it) }
        }
    }
}
