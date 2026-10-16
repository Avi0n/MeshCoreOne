package one.meshcore.shared

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Multicast delivery to any number of subscribers. Each subscriber gets a fresh unbounded channel,
 * so consumers never steal events and a burst is never dropped. Registration happens under the same
 * lock as [yield], so an event yielded right after [subscribe] returns is not lost.
 */
class EventBroadcaster<T> {
    private val mutex = Mutex()
    private val subscribers = mutableListOf<Channel<T>>()

    /** Registers a subscriber and returns its channel; every event yielded afterward reaches it. */
    suspend fun subscribe(): ReceiveChannel<T> = mutex.withLock {
        Channel<T>(Channel.UNLIMITED).also { subscribers += it }
    }

    /** A fresh flow of events yielded after this call; the flow completes when the broadcaster finishes. */
    suspend fun events(): Flow<T> = subscribe().consumeAsFlow()

    /** Delivers the event to every active subscriber, removing subscriptions found closed during this yield. */
    suspend fun yield(event: T): Unit = mutex.withLock {
        subscribers.removeAll { it.trySend(event).isClosed }
    }

    /** Ends every subscriber's stream and unregisters them all. */
    suspend fun finish() = mutex.withLock {
        subscribers.forEach { it.close() }
        subscribers.clear()
    }
}
