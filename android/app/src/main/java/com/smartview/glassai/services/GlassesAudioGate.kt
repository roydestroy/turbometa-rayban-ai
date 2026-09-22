package com.smartview.glassai.services

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

/** A feature requests priority, then waits for the wake recorder to release its lease. */
object GlassesAudioGate {
    private val mutex = Mutex()
    private val requests = MutableStateFlow(0)
    val featureRequests = requests.asStateFlow()

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        override fun close() { if (closed.compareAndSet(false, true)) release() }
    }

    private fun changeRequests(delta: Int) = synchronized(requests) {
        requests.value += delta
    }

    suspend fun acquireFeature(): Lease {
        changeRequests(1)
        try { mutex.lock() } catch (error: Throwable) {
            changeRequests(-1)
            throw error
        }
        return Lease { mutex.unlock(); changeRequests(-1) }
    }

    fun tryAcquireWake(): Lease? {
        if (requests.value != 0 || !mutex.tryLock()) return null
        if (requests.value != 0) { mutex.unlock(); return null }
        return Lease { mutex.unlock() }
    }
}
