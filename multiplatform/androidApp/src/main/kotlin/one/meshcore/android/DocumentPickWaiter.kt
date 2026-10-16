package one.meshcore.android

import kotlinx.coroutines.channels.Channel

/**
 * Holds a document-picker result across Activity recreation. The launcher lives on [MainActivity],
 * which is recreated while the system picker is up; the wait state must live on the process-scoped
 * [AppContainer] so a chosen file is not dropped.
 */
class DocumentPickWaiter<T> {
    private val results = Channel<T?>(Channel.CONFLATED)

    fun offer(value: T?) {
        results.trySend(value)
    }

    fun takeLeftover(): T? = results.tryReceive().getOrNull()

    suspend fun awaitPick(): T? = results.receive()
}
