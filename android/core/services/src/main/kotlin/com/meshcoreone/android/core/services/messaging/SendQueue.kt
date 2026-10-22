// PortedFrom: MC1Services/Sources/MC1Services/Services/SendQueue.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.messaging

import kotlinx.coroutines.*

class SendQueue<Envelope : Any>(
    private val scope: CoroutineScope,
    private val send: suspend (Envelope) -> Unit,
    private val onError: suspend (Exception, Envelope) -> Unit,
    private val onDrain: suspend (Exception?) -> Unit,
) {
    private val lock = Any()
    private val pending = ArrayDeque<Envelope>()
    private var task: Deferred<Unit>? = null
    private var halted = false
    private var closed = false
    private var callbackFailure: Exception? = null
    val count: Int get() = synchronized(lock) { pending.size }

    fun enqueue(envelope: Envelope) {
        synchronized(lock) {
            check(!closed) { "Send queue is closed" }
            scope.ensureActive()
            pending.addLast(envelope)
            halted = false
            ensureDraining()
        }
    }

    private fun ensureDraining() {
        if (task != null || halted || closed || pending.isEmpty()) return
        val work = scope.async(start = CoroutineStart.LAZY) { drain() }
        task = work
        work.invokeOnCompletion { cause ->
            synchronized(lock) {
                if (task !== work) return@invokeOnCompletion
                task = null
                if (cause is Exception && cause !is CancellationException) callbackFailure = cause
                if (!work.isCancelled && cause == null) ensureDraining()
            }
        }
        work.start()
    }

    private suspend fun drain() {
        var lastError: Exception? = null
        do {
            while (true) {
                currentCoroutineContext().ensureActive()
                val envelope = synchronized(lock) {
                    if (pending.isEmpty()) null else pending.removeFirst()
                } ?: break
                try { send(envelope) }
                catch (cancelled: CancellationException) {
                    synchronized(lock) { pending.addFirst(envelope) }
                    return
                } catch (failure: Exception) {
                    // Sender is an injected throwing contract; its complete failure reaches the owner.
                    lastError = failure
                    try { onError(failure, envelope) }
                    catch (callback: Exception) {
                        callback.addSuppressed(failure)
                        throw callback
                    }
                }
            }
            onDrain(lastError)
        } while (synchronized(lock) { pending.isNotEmpty() })
    }

    fun cancelDrain() {
        synchronized(lock) {
            halted = true
            task?.cancel()
        }
    }

    suspend fun awaitDrainCompletion() {
        while (true) {
            val work = synchronized(lock) { task } ?: break
            work.await()
        }
        synchronized(lock) { callbackFailure }?.let { throw it }
    }

    suspend fun shutdown(): Exception? {
        val work = synchronized(lock) {
            closed = true
            halted = true
            task.also { it?.cancel() }
        }
        withContext(NonCancellable) { work?.join() }
        return synchronized(lock) { callbackFailure }
    }
}
