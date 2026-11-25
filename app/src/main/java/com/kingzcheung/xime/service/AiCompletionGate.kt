package com.kingzcheung.xime.service

import java.util.concurrent.atomic.AtomicLong

/** 请求、编辑器连接或配置变化后，旧回调不得再读光标或回填。 */
internal class AiCompletionGate {
    class State(
        val sessionId: Long,
        val editorSignature: String?,
        val configuration: Any,
        val connection: Any?,
        val enabled: Boolean,
        val incognito: Boolean,
        val secret: Boolean,
        val noLearn: Boolean,
    )

    class Ticket internal constructor(internal val epoch: Long, internal val state: State)

    private val epoch = AtomicLong()

    fun begin(state: State): Ticket = Ticket(epoch.incrementAndGet(), state)

    fun invalidate() {
        epoch.incrementAndGet()
    }

    fun runIfCurrent(ticket: Ticket, current: State, action: () -> Unit): Boolean {
        val original = ticket.state
        if (ticket.epoch != epoch.get() || !allowed(original) || !allowed(current) ||
            original.sessionId != current.sessionId ||
            original.editorSignature != current.editorSignature ||
            original.connection !== current.connection ||
            original.configuration !== current.configuration
        ) return false
        action()
        return true
    }

    private fun allowed(state: State): Boolean =
        state.enabled && !state.incognito && !state.secret && !state.noLearn &&
            state.connection != null && state.editorSignature != null
}
