package one.meshcore.shared.remotenode

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Shared lock and the session plus contact halves of [InMemoryRemoteNodeStore]. */
internal class InMemoryRemoteNodeParts {
    private val mutex = Mutex()
    val contacts = InMemoryRemoteNodeContacts(mutex)
    val sessions = InMemoryRemoteNodeSessions(mutex, contacts)
}

internal class InMemoryRemoteNodeContacts(private val mutex: Mutex) : RemoteNodeContactStore {
    val savedContactFrames = mutableListOf<ContactFrame>()
    private val contactRouting = mutableMapOf<String, Boolean>()
    private val contacts = mutableMapOf<String, ContactDto>()

    suspend fun setContactRouting(radioID: Uuid, publicKey: ByteArray, isFloodRouted: Boolean) = mutex.withLock {
        contactRouting[routingKey(radioID, publicKey)] = isFloodRouted
        Unit
    }

    suspend fun setContact(radioID: Uuid, dto: ContactDto) = mutex.withLock {
        contacts[routingKey(radioID, dto.publicKey)] = dto
        Unit
    }

    override suspend fun contactFloodRouted(radioID: Uuid, publicKey: ByteArray): Boolean? =
        mutex.withLock { contactRouting[routingKey(radioID, publicKey)] }

    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? =
        mutex.withLock { contacts[routingKey(radioID, publicKey)] }

    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame) = mutex.withLock {
        savedContactFrames += frame
        Unit
    }

    private fun routingKey(radioID: Uuid, publicKey: ByteArray): String =
        "${radioID.value}:${publicKey.joinToString(",")}"
}

@OptIn(ExperimentalTime::class)
internal class InMemoryRemoteNodeSessions(private val mutex: Mutex, contacts: InMemoryRemoteNodeContacts) :
    RemoteNodeStore,
    RemoteNodeContactStore by contacts {
    private val sessions = mutableMapOf<Uuid, RemoteNodeSessionDto>()

    override suspend fun fetchSessionById(id: Uuid): RemoteNodeSessionDto? = mutex.withLock { sessions[id] }

    override suspend fun fetchSessionByPublicKey(publicKey: ByteArray): RemoteNodeSessionDto? = mutex.withLock {
        sessions.values.firstOrNull { it.publicKey.contentEquals(publicKey) }
    }

    override suspend fun fetchSessionByPrefix(prefix: ByteArray): RemoteNodeSessionDto? = mutex.withLock {
        sessions.values.firstOrNull {
            it.publicKey.size >= prefix.size && it.publicKey.copyOf(prefix.size).contentEquals(prefix)
        }
    }

    override suspend fun fetchConnectedSessions(): List<RemoteNodeSessionDto> = mutex.withLock {
        sessions.values.filter { it.isConnected }
    }

    override suspend fun fetchSessionsByRadioId(radioID: Uuid): List<RemoteNodeSessionDto> = mutex.withLock {
        sessions.values.filter { it.radioID == radioID }
    }

    override suspend fun saveSession(dto: RemoteNodeSessionDto) = mutex.withLock {
        sessions[dto.id] = dto
        Unit
    }

    override suspend fun cleanupDuplicateSessions(publicKey: ByteArray, keepID: Uuid) = mutex.withLock {
        sessions.values
            .filter { it.publicKey.contentEquals(publicKey) && it.id != keepID }
            .forEach { sessions.remove(it.id) }
    }

    override suspend fun deleteSession(id: Uuid) = mutex.withLock {
        sessions.remove(id)
        Unit
    }

    override suspend fun updateSessionConnection(
        id: Uuid,
        isConnected: Boolean,
        permissionLevel: RoomPermissionLevel,
    ) = mutex.withLock {
        sessions[id]?.let {
            sessions[id] = it.copy(
                isConnected = isConnected,
                permissionLevel = permissionLevel,
                lastConnectedDate = if (isConnected) {
                    Clock.System.now().epochSeconds.toDouble()
                } else {
                    it.lastConnectedDate
                },
            )
        }
        Unit
    }

    override suspend fun markSessionDisconnected(id: Uuid) = mutex.withLock {
        sessions[id]?.let { sessions[id] = it.copy(isConnected = false) }
        Unit
    }
}
