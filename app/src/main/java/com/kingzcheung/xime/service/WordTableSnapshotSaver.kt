package com.kingzcheung.xime.service

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Serializes slow persistence without holding the lock used by live input updates. */
internal class WordTableSnapshotSaver<S>(
    private val liveLock: ReentrantLock,
    private val read: () -> S,
    private val persist: (S) -> S,
    private val publish: (base: S, persisted: S) -> Boolean,
) {
    private val writerLock = ReentrantLock()

    fun save() = writerLock.withLock {
        do {
            val snapshot = liveLock.withLock { read() }
            val persisted = persist(snapshot)
            val changedDuringSave = liveLock.withLock { publish(snapshot, persisted) }
        } while (changedDuringSave)
    }
}
