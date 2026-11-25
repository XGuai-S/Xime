package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语音会话归属：只有当前会话持有者能操作，新会话顶替旧会话。 */
class AsrSessionGateTest {

    @Test
    fun `no session means nobody owns, including id zero`() {
        val gate = AsrSessionGate()
        assertFalse(gate.owns(0L))
        assertFalse(gate.owns(1L))
    }

    @Test
    fun `begin issues a positive id owned by the caller`() {
        val gate = AsrSessionGate()
        val id = gate.begin()
        assertTrue(id > 0L)
        assertTrue(gate.owns(id))
    }

    @Test
    fun `new session supersedes the old one`() {
        val gate = AsrSessionGate()
        val first = gate.begin()
        val second = gate.begin()
        assertNotEquals(first, second)
        assertFalse(gate.owns(first))
        assertTrue(gate.owns(second))
    }

    @Test
    fun `stale caller cannot end the current session`() {
        val gate = AsrSessionGate()
        val first = gate.begin()
        val second = gate.begin()
        assertFalse(gate.end(first))
        assertTrue(gate.owns(second))
        assertTrue(gate.end(second))
        assertFalse(gate.owns(second))
    }

    @Test
    fun `reset invalidates the session and ids never repeat`() {
        val gate = AsrSessionGate()
        val first = gate.begin()
        gate.reset()
        assertFalse(gate.owns(first))
        val second = gate.begin()
        assertEquals(first + 1, second)
        assertFalse(gate.owns(first))
    }
}
