package com.kingzcheung.xime.service

import java.util.concurrent.atomic.AtomicLong

internal class EditorKeyGeneration(private val parentJob: kotlinx.coroutines.Job? = null) {
    private val value = AtomicLong(0)
    @Volatile private var boundJob = kotlinx.coroutines.SupervisorJob(parentJob)

    fun capture(): Long = value.get()

    fun job(): kotlinx.coroutines.Job = boundJob

    fun invalidate(): Long {
        val next = value.incrementAndGet()
        val previous = boundJob
        boundJob = kotlinx.coroutines.SupervisorJob(parentJob)
        previous.cancel()
        return next
    }

    fun dispose() {
        boundJob.cancel()
    }

    fun isCurrent(generation: Long): Boolean = value.get() == generation
}
