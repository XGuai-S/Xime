package com.kingzcheung.xime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCompletionGateTest {
    private val configuration = Any()
    private val connection = Any()

    private fun state(
        session: Long = 1,
        signature: String? = "app.one|7|1",
        config: Any = configuration,
        inputConnection: Any? = connection,
        enabled: Boolean = true,
        incognito: Boolean = false,
        secret: Boolean = false,
        noLearn: Boolean = false,
    ) = AiCompletionGate.State(session, signature, config, inputConnection, enabled, incognito, secret, noLearn)

    private fun assertDoesNotRead(current: AiCompletionGate.State) {
        val gate = AiCompletionGate()
        val ticket = gate.begin(state())
        var reads = 0
        assertFalse(gate.runIfCurrent(ticket, current) { reads++ })
        assertEquals(0, reads)
    }

    @Test
    fun `current request may read and apply`() {
        val gate = AiCompletionGate()
        val ticket = gate.begin(state())
        var reads = 0
        assertTrue(gate.runIfCurrent(ticket, state()) { reads++ })
        assertEquals(1, reads)
    }

    @Test
    fun `new request rejects old queued result even in same editor`() {
        val gate = AiCompletionGate()
        val old = gate.begin(state())
        val next = gate.begin(state())
        var reads = 0
        assertFalse(gate.runIfCurrent(old, state()) { reads++ })
        assertTrue(gate.runIfCurrent(next, state()) { reads++ })
        assertEquals(1, reads)
    }

    @Test
    fun `different session rejects before cursor read`() = assertDoesNotRead(state(session = 2))

    @Test
    fun `different editor signature rejects before cursor read`() =
        assertDoesNotRead(state(signature = "app.two|8|1"))

    @Test
    fun `equal text in a different connection is still a different editor`() {
        data class FakeConnection(val before: String)
        val first = FakeConnection("same text")
        val second = FakeConnection("same text")
        assertEquals(first, second)
        val gate = AiCompletionGate()
        val ticket = gate.begin(state(inputConnection = first))
        var reads = 0
        assertFalse(gate.runIfCurrent(ticket, state(inputConnection = second)) { reads++ })
        assertEquals(0, reads)
    }

    @Test
    fun `password and no-learn reject without reading`() {
        assertDoesNotRead(state(secret = true))
        assertDoesNotRead(state(noLearn = true))
    }

    @Test
    fun `disabled or incognito reject without reading`() {
        assertDoesNotRead(state(enabled = false))
        assertDoesNotRead(state(incognito = true))
    }

    @Test
    fun `missing connection or editor info rejects without reading`() {
        assertDoesNotRead(state(inputConnection = null))
        assertDoesNotRead(state(signature = null))
    }

    @Test
    fun `closing then reopening settings cannot revive an old request`() {
        data class Settings(val enabled: Boolean)
        val before = Settings(true)
        val disabled = before.copy(enabled = false)
        val reopened = disabled.copy(enabled = true)
        assertEquals(before, reopened)
        val gate = AiCompletionGate()
        val old = gate.begin(state(config = before))
        var reads = 0
        assertFalse(gate.runIfCurrent(old, state(config = reopened)) { reads++ })
        val fresh = gate.begin(state(config = reopened))
        assertTrue(gate.runIfCurrent(fresh, state(config = reopened)) { reads++ })
        assertEquals(1, reads)
    }

    @Test
    fun `cancel drops both cached and network callbacks already queued`() {
        val gate = AiCompletionGate()
        val ticket = gate.begin(state())
        var reads = 0
        val queued = listOf<() -> Unit>(
            { gate.runIfCurrent(ticket, state()) { reads++ } },
            { gate.runIfCurrent(ticket, state()) { reads++ } },
        )
        gate.invalidate()
        queued.forEach { it() }
        assertEquals(0, reads)
    }

    @Test
    fun `same signature quick restart still invalidates the previous request`() {
        val gate = AiCompletionGate()
        val old = gate.begin(state())
        gate.invalidate()
        var reads = 0
        assertFalse(gate.runIfCurrent(old, state()) { reads++ })
        val next = gate.begin(state())
        assertTrue(gate.runIfCurrent(next, state()) { reads++ })
        assertEquals(1, reads)
    }

    @Test
    fun `invalidation during a slow cursor read prevents subsequent fill`() {
        val gate = AiCompletionGate()
        val ticket = gate.begin(state())
        var reads = 0
        var fills = 0
        gate.runIfCurrent(ticket, state()) {
            reads++
            gate.invalidate()
            gate.runIfCurrent(ticket, state()) { fills++ }
        }
        assertEquals(1, reads)
        assertEquals(0, fills)
    }

    @Test
    fun `a request captured in private state cannot become permitted later`() {
        val gate = AiCompletionGate()
        val ticket = gate.begin(state(secret = true))
        var reads = 0
        assertFalse(gate.runIfCurrent(ticket, state()) { reads++ })
        assertEquals(0, reads)
    }
}
