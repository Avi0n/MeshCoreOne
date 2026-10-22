// AndroidOnly: WP-208 Process-owned channel-query progress across connection graph replacement.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.CancellationException

/**
 * Retain one instance in the process graph and inject it into every replacement send queue.
 * Only query-failure progress survives; drains, signals and radio services remain connection-owned.
 * There is no close operation on this owner. Retiring a connection keeps its pending progress.
 */
class ChatSendQueueProgress {
    private data class FailureCount(val pendingID: UUID, val count: Long)
    private val lock = Any()
    private val failures = mutableMapOf<EntityKey, FailureCount>()
    private val connections = mutableMapOf<RadioId, Connection>()

    internal fun attach(token: SessionToken): Connection = synchronized(lock) {
        connections[token.radioId]?.let { previous ->
            if (previous.token.epoch != token.epoch || previous.token.generation.value >= token.generation.value) {
                throw CancellationException("Queue progress requires a newer connection generation in the same process")
            }
        }
        Connection(token).also { connections[token.radioId] = it }
    }

    internal inner class Connection internal constructor(val token: SessionToken) {
        private var retired = false

        private fun checkCurrent() {
            if (retired || connections[token.radioId] !== this) {
                throw CancellationException("Retired send-queue progress generation")
            }
        }

        fun reconcile(rows: List<PendingSendDTO>) = synchronized(lock) {
            checkCurrent()
            val pending = rows.associate { it.messageID to it.id }
            failures.entries.removeAll { (key, value) ->
                key.radioId == token.radioId && pending[key.id] != value.pendingID
            }
        }

        fun increment(messageID: UUID, pendingID: UUID): Long = synchronized(lock) {
            checkCurrent()
            val key = EntityKey(token.radioId, messageID)
            val previous = failures[key]?.takeIf { it.pendingID == pendingID }?.count ?: 0
            Math.incrementExact(previous).also { failures[key] = FailureCount(pendingID, it) }
        }

        fun clear(messageID: UUID) = synchronized(lock) {
            checkCurrent()
            failures.remove(EntityKey(token.radioId, messageID))
            Unit
        }

        fun retire() = synchronized(lock) { retired = true }
    }
}
