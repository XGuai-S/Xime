package com.kingzcheung.xime.association

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class NativeInitializationTest {
    @Test
    fun `native false is a completed failure rather than success or missing library`() {
        var calls = 0
        val result = attemptNativeInitialization { calls++; false }
        assertEquals(false, result)
        assertEquals(1, calls)
    }

    @Test
    fun `native true remains success`() {
        assertEquals(true, attemptNativeInitialization { true })
    }

    @Test
    fun `only unavailable JNI allows the library-loading fallback`() {
        assertNull(attemptNativeInitialization { throw UnsatisfiedLinkError("fixture") })
    }

    @Test
    fun `other initialization exceptions retain the existing caller error path`() {
        val failure = IllegalStateException("fixture initialization error")
        try {
            attemptNativeInitialization { throw failure }
            fail("Exception must reach the caller")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }

    @Test
    fun `unrelated linkage failure is not treated as a missing JNI entry`() {
        val failure = NoClassDefFoundError("fixture missing class")
        try {
            attemptNativeInitialization { throw failure }
            fail("Unrelated linkage failure must reach the caller")
        } catch (actual: NoClassDefFoundError) {
            assertSame(failure, actual)
        }
    }
}
