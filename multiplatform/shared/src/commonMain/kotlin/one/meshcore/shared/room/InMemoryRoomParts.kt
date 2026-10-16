package one.meshcore.shared.room

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.MessageStatusGuards
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeContactStore
import one.meshcore.shared.remotenode.RemoteNodeStore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Shared session maps and the one mutex behind [InMemoryRoomStore]. */
internal class InMemoryRoomParts {
    val mutex = Mutex()
    val sessions = mutableMapOf<Uuid, RemoteNodeSessionDto>()
    val messages = mutableMapOf<Uuid, RoomMessageDto>()
    val contactNames = mutableListOf<Pair<ByteArray, String>>()
    val contactRouting = mutableMapOf<String, Boolean>()
    val contacts = InMemoryRoomContacts(this)
    val messageOps = InMemoryRoomMessages(this)
    val remoteSessions = InMemoryRoomRemoteSessions(this)
    val roomSessions = InMemoryRoomSessions(this)
    val facade = InMemoryRoomFacade(this)
    val roomStore: RoomStore = facade
    val remoteStore: RemoteNodeStore = facade
}

/** Room-only session reads and mutations. [RemoteNodeStore] keeps the shared session methods. */
internal interface InMemoryRoomSessionOps {
    suspend fun fetchSession(id: Uuid): RemoteNodeSessionDto?

    suspend fun fetchSessions(radioID: Uuid): List<RemoteNodeSessionDto>

    suspend fun markRoomSessionConnected(id: Uuid): Boolean

    suspend fun updateRoomActivity(id: Uuid, syncTimestamp: Long?)

    suspend fun incrementRoomUnreadCount(id: Uuid)

    suspend fun resetRoomUnreadCount(id: Uuid)

    suspend fun markFailedSendsSeen(sessionID: Uuid)

    suspend fun findContactNameByKeyPrefix(keyPrefix: ByteArray): String?
}

/**
 * One object implements both session seams so [InMemoryRoomStore] can delegate the public interfaces.
 * [markFailedSendsSeen] is explicit because [RoomStore]'s default would leave failed rows unseen.
 */
internal class InMemoryRoomFacade(private val parts: InMemoryRoomParts) :
    RoomStore,
    RemoteNodeStore by parts.remoteSessions,
    RoomMessageStore by parts.messageOps,
    InMemoryRoomSessionOps by parts.roomSessions {
    override suspend fun markFailedSendsSeen(sessionID: Uuid) = parts.roomSessions.markFailedSendsSeen(sessionID)
}

/** Contact names and flood-routing flags. Absent entries stay "unknown" / "contact not found". */
internal class InMemoryRoomContacts(private val parts: InMemoryRoomParts) : RemoteNodeContactStore {
    suspend fun setContactName(prefix: ByteArray, name: String) = parts.mutex.withLock {
        parts.contactNames += prefix to name
        Unit
    }

    suspend fun setContactRouting(radioID: Uuid, publicKey: ByteArray, isFloodRouted: Boolean) = parts.mutex.withLock {
        parts.contactRouting[routingKey(radioID, publicKey)] = isFloodRouted
        Unit
    }

    override suspend fun contactFloodRouted(radioID: Uuid, publicKey: ByteArray): Boolean? =
        parts.mutex.withLock { parts.contactRouting[routingKey(radioID, publicKey)] }

    private fun routingKey(radioID: Uuid, publicKey: ByteArray): String =
        "${radioID.value}:${publicKey.joinToString(",")}"
}

/** In-memory room messages for one store. */
internal class InMemoryRoomMessages(private val parts: InMemoryRoomParts) : RoomMessageStore {
    override suspend fun saveRoomMessage(dto: RoomMessageDto) = parts.mutex.withLock {
        parts.messages[dto.id] = dto
        Unit
    }

    override suspend fun fetchRoomMessage(id: Uuid): RoomMessageDto? = parts.mutex.withLock { parts.messages[id] }

    override suspend fun fetchRoomMessages(sessionID: Uuid, limit: Int?, offset: Int?): List<RoomMessageDto> =
        parts.mutex.withLock {
            val ordered = parts.messages.values
                .filter { it.sessionID == sessionID }
                .sortedWith(compareBy({ it.timestamp }, { it.createdAt }))
            val dropped = if (offset != null) ordered.drop(offset) else ordered
            if (limit != null) dropped.take(limit) else dropped
        }

    override suspend fun updateRoomMessageStatus(
        id: Uuid,
        status: MessageStatus,
        ackCode: Long?,
        roundTripTime: Long?,
    ) = rewriteRoomMessage(id, status) { current ->
        current.copy(ackCode = ackCode, roundTripTime = roundTripTime)
    }

    override suspend fun updateRoomMessageRetryStatus(
        id: Uuid,
        status: MessageStatus,
        retryAttempt: Int,
        maxRetryAttempts: Int,
    ) = rewriteRoomMessage(id, status) { current ->
        current.copy(retryAttempt = retryAttempt, maxRetryAttempts = maxRetryAttempts)
    }

    // The copy sets the fields that belong to this write. Status and failureSeen are applied after it.
    private suspend fun rewriteRoomMessage(id: Uuid, status: MessageStatus, copy: (RoomMessageDto) -> RoomMessageDto) =
        parts.mutex.withLock {
            parts.messages[id]?.let { current ->
                val previous = MessageStatus.fromRaw(current.statusRawValue) ?: MessageStatus.DELIVERED
                parts.messages[id] = copy(current).copy(
                    statusRawValue = status.raw,
                    failureSeen = MessageStatusGuards.failureSeenAfterWrite(previous, status, current.failureSeen),
                )
            }
            Unit
        }

    override suspend fun isDuplicateRoomMessage(sessionID: Uuid, deduplicationKey: String): Boolean =
        parts.mutex.withLock {
            parts.messages.values.any { it.sessionID == sessionID && it.deduplicationKey == deduplicationKey }
        }
}

/** Remote-node session rows, including the test seed. Does not list sessions by radio. */
internal class InMemoryRoomRemoteSessions(private val parts: InMemoryRoomParts) :
    RemoteNodeStore,
    RemoteNodeContactStore by parts.contacts {
    suspend fun seedSession(dto: RemoteNodeSessionDto) = parts.mutex.withLock {
        parts.sessions[dto.id] = dto
        Unit
    }

    override suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto? = parts.mutex.withLock { parts.sessions[id] }

    override suspend fun fetchSessionByPublicKey(publicKey: ByteArray): RemoteNodeSessionDto? = parts.mutex.withLock {
        parts.sessions.values.firstOrNull { it.publicKey.contentEquals(publicKey) }
    }

    override suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto? = parts.mutex.withLock {
        parts.sessions.values.firstOrNull {
            it.publicKey.size >= prefix.size && it.publicKey.copyOf(prefix.size).contentEquals(prefix)
        }
    }

    override suspend fun fetchConnectedSessions(): List<RemoteNodeSessionDto> = parts.mutex.withLock {
        parts.sessions.values.filter { it.isConnected }
    }

    override suspend fun saveSession(dto: RemoteNodeSessionDto) = parts.mutex.withLock {
        parts.sessions[dto.id] = dto
        Unit
    }

    override suspend fun cleanupDuplicateSessions(publicKey: ByteArray, keepID: Uuid) = parts.mutex.withLock {
        parts.sessions.values
            .filter { it.publicKey.contentEquals(publicKey) && it.id != keepID }
            .toList()
            .forEach { dropped ->
                parts.messages.entries.removeAll { it.value.sessionID == dropped.id }
                parts.sessions.remove(dropped.id)
            }
    }

    override suspend fun deleteSession(id: Uuid) = parts.mutex.withLock {
        parts.sessions.remove(id)
        parts.messages.entries.removeAll { it.value.sessionID == id }
        Unit
    }

    override suspend fun updateSessionConnection(
        id: Uuid,
        isConnected: Boolean,
        permissionLevel: RoomPermissionLevel,
    ) = parts.mutex.withLock {
        parts.sessions[id]?.let {
            parts.sessions[id] = it.copy(
                isConnected = isConnected,
                permissionLevel = permissionLevel,
                lastConnectedDate = if (isConnected) connectedAt() else it.lastConnectedDate,
            )
        }
        Unit
    }

    override suspend fun markSessionDisconnected(id: Uuid) = parts.mutex.withLock {
        parts.sessions[id]?.let { parts.sessions[id] = it.copy(isConnected = false) }
        Unit
    }
}

/** Room-server session mutations, unread counts, and contact-name lookup. */
internal class InMemoryRoomSessions(private val parts: InMemoryRoomParts) : InMemoryRoomSessionOps {
    private var activityClock: Long = 0

    override suspend fun fetchSession(id: Uuid): RemoteNodeSessionDto? = parts.mutex.withLock { parts.sessions[id] }

    override suspend fun fetchSessions(radioID: Uuid): List<RemoteNodeSessionDto> =
        parts.mutex.withLock { parts.sessions.values.filter { it.radioID == radioID } }

    override suspend fun markRoomSessionConnected(id: Uuid): Boolean = parts.mutex.withLock {
        val session = parts.sessions[id] ?: return@withLock false
        if (session.isConnected) return@withLock false
        parts.sessions[id] = session.copy(isConnected = true, lastConnectedDate = connectedAt())
        true
    }

    override suspend fun updateRoomActivity(id: Uuid, syncTimestamp: Long?) = parts.mutex.withLock {
        parts.sessions[id]?.let { session ->
            activityClock += 1
            parts.sessions[id] = session.copy(
                lastMessageDate = activityClock.toDouble(),
                lastSyncTimestamp = advancedRoomSyncTimestamp(session.lastSyncTimestamp, syncTimestamp),
            )
        }
        Unit
    }

    override suspend fun incrementRoomUnreadCount(id: Uuid) = parts.mutex.withLock {
        parts.sessions[id]?.let { parts.sessions[id] = it.copy(unreadCount = it.unreadCount + 1) }
        Unit
    }

    override suspend fun resetRoomUnreadCount(id: Uuid) = parts.mutex.withLock {
        parts.sessions[id]?.let { parts.sessions[id] = it.copy(unreadCount = 0) }
        Unit
    }

    override suspend fun markFailedSendsSeen(sessionID: Uuid) = parts.mutex.withLock {
        val ids = parts.messages.values
            .filter {
                it.sessionID == sessionID &&
                    it.isFromSelf &&
                    it.statusRawValue == MessageStatus.FAILED.raw &&
                    !it.failureSeen
            }
            .map { it.id }
        for (id in ids) {
            parts.messages[id]?.let { parts.messages[id] = it.copy(failureSeen = true) }
        }
    }

    override suspend fun findContactNameByKeyPrefix(keyPrefix: ByteArray): String? = parts.mutex.withLock {
        parts.contactNames.firstOrNull { (key, _) ->
            key.size >= keyPrefix.size && key.copyOf(keyPrefix.size).contentEquals(keyPrefix)
        }?.second
    }
}

@OptIn(ExperimentalTime::class)
private fun connectedAt(): Double = Clock.System.now().epochSeconds.toDouble()
