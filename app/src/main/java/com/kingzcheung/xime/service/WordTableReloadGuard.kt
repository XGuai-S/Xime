package com.kingzcheung.xime.service

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Rejects disk snapshots made stale by live input or another publication. */
internal class WordTableReloadGuard<S>(
    private val lock: ReentrantLock,
    private val read: () -> S,
) {
    fun snapshot(): S = lock.withLock { read() }

    fun publish(base: S, update: () -> Unit): Boolean = lock.withLock {
        if (read() != base) return false
        update()
        true
    }
}
