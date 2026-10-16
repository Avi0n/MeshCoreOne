package one.meshcore.android

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import java.util.concurrent.atomic.AtomicLong

internal class FloodScopePublicationGate {
    private val generation = AtomicLong()
    private val mutex = Mutex()

    fun capture(): Long = generation.get()

    fun invalidate() {
        generation.incrementAndGet()
    }

    suspend fun publishLoaded(
        capturedGeneration: Long,
        expectedRadio: Uuid,
        currentRadio: () -> Uuid?,
        publish: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        if (generation.get() != capturedGeneration || currentRadio() != expectedRadio) return false
        publish()
        true
    }

    suspend fun publishMutation(expectedRadio: Uuid, currentRadio: () -> Uuid?, publish: suspend () -> Unit): Boolean =
        mutex.withLock {
            if (currentRadio() != expectedRadio) return false
            publish()
            true
        }

    suspend fun clear(publish: suspend () -> Unit) {
        invalidate()
        mutex.withLock { publish() }
    }
}
