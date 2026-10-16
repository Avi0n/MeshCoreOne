package one.meshcore.shared.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

internal class InMemoryIngestState {
    val mutex = Mutex()
    val messages = mutableListOf<MessageDto>()
    val contactsByPrefix = mutableMapOf<String, ResolvedContact>()
    val channelsBySlot = mutableMapOf<Pair<Uuid, Int>, ResolvedChannel>()
    val rxEntries = mutableMapOf<IngestRxLogKey, RxLogCorrelation>()
    val contactUnread = mutableMapOf<Uuid, Int>()
    val contactUnreadMention = mutableMapOf<Uuid, Int>()
    val channelUnread = mutableMapOf<Uuid, Int>()
    val channelUnreadMention = mutableMapOf<Uuid, Int>()
    val contactLastMessage = mutableMapOf<Uuid, Double>()
    val channelLastMessage = mutableMapOf<Uuid, Double>()
    val contactHeard = mutableMapOf<Uuid, Long>()
}

internal data class IngestRxLogKey(val radioID: Uuid, val channelIndex: Int?, val senderTimestamp: Long)

internal class InMemoryIngestParts {
    val state = InMemoryIngestState()
    val bookkeeping = InMemoryIngestBookkeeping(state)
    val counters = InMemoryIngestCounters(state)
}

/** Unread, last-message, and hear writes sharing [InMemoryIngestStore]'s mutex. */
internal class InMemoryIngestBookkeeping(private val state: InMemoryIngestState) : IngestBookkeeping {
    override suspend fun updateContactLastMessage(contactID: Uuid, dateEpochSeconds: Double) = state.mutex.withLock {
        state.contactLastMessage[contactID] = dateEpochSeconds
    }

    override suspend fun updateChannelLastMessage(channelID: Uuid, dateEpochSeconds: Double) = state.mutex.withLock {
        state.channelLastMessage[channelID] = dateEpochSeconds
    }

    override suspend fun incrementContactUnread(contactID: Uuid) = state.mutex.withLock {
        state.contactUnread[contactID] = (state.contactUnread[contactID] ?: 0) + 1
    }

    override suspend fun incrementContactUnreadMention(contactID: Uuid) = state.mutex.withLock {
        state.contactUnreadMention[contactID] = (state.contactUnreadMention[contactID] ?: 0) + 1
    }

    override suspend fun incrementChannelUnread(channelID: Uuid) = state.mutex.withLock {
        state.channelUnread[channelID] = (state.channelUnread[channelID] ?: 0) + 1
    }

    override suspend fun incrementChannelUnreadMention(channelID: Uuid) = state.mutex.withLock {
        state.channelUnreadMention[channelID] = (state.channelUnreadMention[channelID] ?: 0) + 1
    }

    override suspend fun touchContactHeard(contactID: Uuid, atEpochSeconds: Long) = state.mutex.withLock {
        val stamp = maxOf(state.contactHeard[contactID] ?: 0L, atEpochSeconds)
        state.contactHeard[contactID] = stamp
        Unit
    }
}

/** Counter reads over the same maps [InMemoryIngestBookkeeping] writes. */
internal class InMemoryIngestCounters(private val state: InMemoryIngestState) : IngestCounterRead {
    override suspend fun contactUnreadCount(contactID: Uuid): Int =
        state.mutex.withLock { state.contactUnread[contactID] ?: 0 }

    override suspend fun contactUnreadMentionCount(contactID: Uuid): Int =
        state.mutex.withLock { state.contactUnreadMention[contactID] ?: 0 }

    override suspend fun channelUnreadCount(channelID: Uuid): Int =
        state.mutex.withLock { state.channelUnread[channelID] ?: 0 }

    override suspend fun channelUnreadMentionCount(channelID: Uuid): Int =
        state.mutex.withLock { state.channelUnreadMention[channelID] ?: 0 }

    override suspend fun contactLastMessageDate(contactID: Uuid): Double? =
        state.mutex.withLock { state.contactLastMessage[contactID] }

    override suspend fun contactHeardAt(contactID: Uuid): Long? = state.mutex.withLock { state.contactHeard[contactID] }

    override suspend fun channelLastMessageDate(channelID: Uuid): Double? =
        state.mutex.withLock { state.channelLastMessage[channelID] }
}
