package one.meshcore.shared.persistence

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.MessageStatusGuards
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeContactStore
import one.meshcore.shared.remotenode.RemoteNodeStore
import one.meshcore.shared.room.RoomMessageStore
import one.meshcore.shared.room.RoomStore
import one.meshcore.shared.room.advancedRoomSyncTimestamp

/** DAOs and the shared session lock for [RoomNodeStore]. */
internal class RoomNodeParts(
    initialRadioId: Uuid,
    val sessionDao: RemoteNodeSessionDao,
    val roomMessageDao: RoomMessageDao,
    val contactDao: ContactDao,
    val now: () -> Double,
) {
    var radioId: Uuid = initialRadioId
    val mutex = Mutex()
    val contacts = RoomNodeContacts(this)
    val messages = RoomNodeMessages(this)
    val remoteSessions = RoomNodeRemoteSessions(this)
    val roomSessions = RoomNodeRoomSessions(this)
    val facade = RoomNodeFacade(this)
    val roomStore: RoomStore = facade
    val remoteStore: RemoteNodeStore = facade
}

/** Room-only session reads and mutations. [RemoteNodeStore] keeps the shared session methods. */
internal interface RoomNodeRoomOps {
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
 * One object implements both session seams so [RoomNodeStore] can delegate the public interfaces.
 * [markFailedSendsSeen] is explicit because [RoomStore]'s default would leave failed rows unseen.
 */
internal class RoomNodeFacade(private val parts: RoomNodeParts) :
    RoomStore,
    RemoteNodeStore by parts.remoteSessions,
    RoomMessageStore by parts.messages,
    RoomNodeRoomOps by parts.roomSessions {
    override suspend fun markFailedSendsSeen(sessionID: Uuid) = parts.roomSessions.markFailedSendsSeen(sessionID)
}

/** Contact routing and upserts for the remote-node seam, locked with the session mutex. */
internal class RoomNodeContacts(private val parts: RoomNodeParts) : RemoteNodeContactStore {
    override suspend fun contactFloodRouted(radioID: Uuid, publicKey: ByteArray): Boolean? =
        parts.contactDao.listByRadio(radioID)
            .firstOrNull { it.publicKey.contentEquals(publicKey) }
            ?.toDto()
            ?.isFloodRouted

    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? =
        parts.contactDao.listByRadio(radioID).firstOrNull { it.publicKey.contentEquals(publicKey) }?.toDto()

    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame) {
        val owner = currentCoroutineContext().job
        parts.mutex.withLock {
            parts.contactDao.saveContactFrame(radioID, frame, owner, Uuid::random)
        }
    }
}

/** Room-message reads and writes. By-id lookups scan this connection's sessions. */
internal class RoomNodeMessages(private val parts: RoomNodeParts) : RoomMessageStore {
    override suspend fun saveRoomMessage(dto: RoomMessageDto) = parts.roomMessageDao.upsert(dto.toEntity())

    override suspend fun fetchRoomMessage(id: Uuid): RoomMessageDto? = findMessageEntity(id)?.toDto()

    override suspend fun fetchRoomMessages(sessionID: Uuid, limit: Int?, offset: Int?): List<RoomMessageDto> {
        val ordered = parts.roomMessageDao.listBySession(sessionID)
            .sortedWith(compareBy({ it.timestamp }, { it.createdAt }))
            .map { it.toDto() }
        val dropped = if (offset != null) ordered.drop(offset) else ordered
        return if (limit != null) dropped.take(limit) else dropped
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
    private suspend fun rewriteRoomMessage(
        id: Uuid,
        status: MessageStatus,
        copy: (RoomMessageEntity) -> RoomMessageEntity,
    ) = parts.mutex.withLock {
        findMessageEntity(id)?.let { current ->
            parts.roomMessageDao.upsert(
                copy(current).copy(
                    statusRawValue = status.raw,
                    failureSeen = MessageStatusGuards.failureSeenAfterWrite(
                        current.status,
                        status,
                        current.failureSeen,
                    ),
                ),
            )
        }
        Unit
    }

    override suspend fun isDuplicateRoomMessage(sessionID: Uuid, deduplicationKey: String): Boolean =
        parts.roomMessageDao.listBySession(sessionID).any { it.deduplicationKey == deduplicationKey }

    // The room message table has no by-id DAO read, so a by-id lookup scans this connection's sessions.
    private suspend fun findMessageEntity(id: Uuid): RoomMessageEntity? = parts.sessionDao.getAll()
        .firstNotNullOfOrNull { session ->
            parts.roomMessageDao.listBySession(session.id).firstOrNull { it.id == id }
        }
}

/** Remote-node session reads and mutations over the shared session table. */
internal class RoomNodeRemoteSessions(private val parts: RoomNodeParts) :
    RemoteNodeStore,
    RemoteNodeContactStore by parts.contacts {
    override suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto? = parts.sessionDao.getById(id)?.toDto()

    override suspend fun fetchSessionByPublicKey(publicKey: ByteArray): RemoteNodeSessionDto? =
        parts.sessionDao.getAll().firstOrNull { it.publicKey.contentEquals(publicKey) }?.toDto()

    override suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto? =
        parts.sessionDao.getAll().firstOrNull { it.publicKey.startsWith(prefix) }?.toDto()

    override suspend fun fetchConnectedSessions(): List<RemoteNodeSessionDto> =
        parts.sessionDao.getAll().filter { it.isConnected }.map { it.toDto() }

    override suspend fun fetchSessionsByRadioId(radioID: Uuid): List<RemoteNodeSessionDto> =
        parts.sessionDao.listByRadio(radioID).map { it.toDto() }

    override suspend fun saveSession(dto: RemoteNodeSessionDto) {
        parts.sessionDao.upsertPreservingPin(dto.toEntity())
    }

    override suspend fun cleanupDuplicateSessions(publicKey: ByteArray, keepID: Uuid) = parts.mutex.withLock {
        parts.sessionDao.getAll()
            .filter { it.publicKey.contentEquals(publicKey) && it.id != keepID }
            .forEach {
                parts.roomMessageDao.deleteBySession(it.id)
                parts.sessionDao.deleteById(it.id)
            }
    }

    override suspend fun deleteSession(id: Uuid) {
        parts.roomMessageDao.deleteBySession(id)
        parts.sessionDao.deleteById(id)
    }

    override suspend fun updateSessionConnection(
        id: Uuid,
        isConnected: Boolean,
        permissionLevel: RoomPermissionLevel,
    ) = parts.mutex.withLock {
        val owner = currentCoroutineContext().job
        parts.sessionDao.updateConnectionIfActive(
            id,
            isConnected,
            permissionLevel.raw,
            if (isConnected) parts.now() else null,
            owner,
        )
    }

    override suspend fun markSessionDisconnected(id: Uuid) = parts.mutex.withLock {
        val owner = currentCoroutineContext().job
        parts.sessionDao.markDisconnectedIfActive(id, owner)
    }
}

/** Room-server session mutations and the contact-name lookup. */
internal class RoomNodeRoomSessions(private val parts: RoomNodeParts) : RoomNodeRoomOps {
    override suspend fun fetchSession(id: Uuid): RemoteNodeSessionDto? = parts.sessionDao.getById(id)?.toDto()

    override suspend fun fetchSessions(radioID: Uuid): List<RemoteNodeSessionDto> =
        parts.sessionDao.listByRadio(radioID).map { it.toDto() }

    override suspend fun markRoomSessionConnected(id: Uuid): Boolean = parts.mutex.withLock {
        val entity = parts.sessionDao.getById(id) ?: return@withLock false
        if (entity.isConnected) return@withLock false
        parts.sessionDao.upsertPreservingPin(entity.copy(isConnected = true, lastConnectedDate = parts.now()))
        true
    }

    override suspend fun updateRoomActivity(id: Uuid, syncTimestamp: Long?) = parts.mutex.withLock {
        parts.sessionDao.getById(id)?.let { entity ->
            parts.sessionDao.upsertPreservingPin(
                entity.copy(
                    lastMessageDate = parts.now(),
                    lastSyncTimestamp = advancedRoomSyncTimestamp(entity.lastSyncTimestamp, syncTimestamp),
                ),
            )
        }
        Unit
    }

    override suspend fun incrementRoomUnreadCount(id: Uuid) = parts.mutex.withLock {
        parts.sessionDao.getById(id)?.let {
            parts.sessionDao.upsertPreservingPin(it.copy(unreadCount = it.unreadCount + 1))
        }
        Unit
    }

    override suspend fun resetRoomUnreadCount(id: Uuid) = parts.mutex.withLock {
        parts.sessionDao.getById(id)?.let { parts.sessionDao.upsertPreservingPin(it.copy(unreadCount = 0)) }
        Unit
    }

    override suspend fun markFailedSendsSeen(sessionID: Uuid) = parts.mutex.withLock {
        parts.roomMessageDao.markFailedSendsSeenForSession(sessionID, MessageStatus.FAILED.raw)
    }

    override suspend fun findContactNameByKeyPrefix(keyPrefix: ByteArray): String? =
        parts.contactDao.listByRadio(parts.radioId)
            .firstOrNull { it.publicKey.startsWith(keyPrefix) }
            ?.toDto()
            ?.displayName
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && copyOf(prefix.size).contentEquals(prefix)
