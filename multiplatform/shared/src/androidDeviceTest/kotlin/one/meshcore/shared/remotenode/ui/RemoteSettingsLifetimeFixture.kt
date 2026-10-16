package one.meshcore.shared.remotenode.ui

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ui.ContactClipboardFixtures
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomAdminSession
import kotlin.time.Duration

internal class RemoteSettingsLifetimeFixture(private val context: Context, scope: CoroutineScope) {
    private val databaseName = "remote-settings-lifetime-${Uuid.random().value}.db"
    private val database = meshDatabase(context, Dispatchers.IO, databaseName)
    val contact = ContactClipboardFixtures.contact.copy(
        radioID = Uuid.random(),
        typeRawValue = ContactType.REPEATER.raw,
        name = "Retained remote node",
    )
    val session = RemoteNodeSessionDto(
        id = Uuid.random(),
        radioID = contact.radioID,
        publicKey = contact.publicKey.copyOf(),
        name = contact.name,
        role = RemoteNodeRole.REPEATER,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = true,
        permissionLevel = RoomPermissionLevel.GUEST,
        unreadCount = 0,
        notificationLevel = NotificationLevel.ALL,
        isPinned = false,
        neighborCount = 0,
        lastSyncTimestamp = 0,
    )
    val roomContact = contact.copy(
        id = Uuid.random(),
        name = "Retained room",
        publicKey = contact.publicKey.copyOf().also { it[it.lastIndex]++ },
        typeRawValue = ContactType.ROOM.raw,
    )
    val roomSession = session.copy(
        id = Uuid.random(),
        publicKey = roomContact.publicKey,
        name = roomContact.name,
        role = RemoteNodeRole.ROOM_SERVER,
        permissionLevel = RoomPermissionLevel.ADMIN,
    )
    val commandReply = CompletableDeferred<String>()
    val cliReply = CompletableDeferred<String>()
    var commandCalls = 0
        private set
    var cliCalls = 0
        private set
    val reading = CompletableDeferred<OwnerInfoResponse>()
    var readingCancelled = false
        private set
    var readingCalls = 0
        private set
    private val nodeSession = object : NodeSession {
        override val events = MutableSharedFlow<MeshEvent>()
        override suspend fun sendLogin(destination: ByteArray, password: String): Nothing = unavailable()
        override suspend fun sendLogout(destination: ByteArray): Nothing = unavailable()
        override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): Nothing = unavailable()
        override suspend fun sendCommand(destination: ByteArray, command: String): Nothing = unavailable()
        override suspend fun requestStatus(publicKey: ByteArray): Nothing = unavailable()
        override suspend fun requestTelemetry(publicKey: ByteArray): Nothing = unavailable()
        override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse {
            readingCalls++
            return try {
                reading.await()
            } catch (cancelled: CancellationException) {
                readingCancelled = true
                throw cancelled
            }
        }
        override suspend fun requestNeighbours(
            publicKey: ByteArray,
            count: Int,
            offset: Int,
            orderBy: Int,
            pubkeyPrefixLength: Int,
        ): Nothing = unavailable()
    }
    private val store = RoomNodeStore(
        contact.radioID,
        database.remoteNodeSessionDao(),
        database.roomMessageDao(),
        database.contactDao(),
        now = { 0.0 },
    )
    private val passwords = InMemoryNodePasswordStore()
    private val cli = NodeCliCoordinator(nodeSession, store, passwords, scope)
    val admin = RepeaterAdminService(
        RemoteNodeService(nodeSession, store, passwords, scope),
        cli,
        nodeSession,
        store,
        passwords,
    )

    val roomAdmin = RoomAdminService(
        object : RoomAdminSession {
            override suspend fun requestStatus(publicKey: ByteArray, timeout: Duration): Nothing = unavailable()
            override suspend fun requestTelemetry(publicKey: ByteArray, timeout: Duration): Nothing = unavailable()
            override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
                when (command) {
                    "get owner.info" -> "Original owner"
                    "set owner.info Submitted owner" -> {
                        commandCalls++
                        commandReply.await()
                    }
                    else -> "OK"
                }
            override suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
                when (command) {
                    "retained cli" -> {
                        cliCalls++
                        cliReply.await()
                    }
                    "ver" -> "1.15.0"
                    "clock" -> "2026-10-09 12:00:00 UTC"
                    else -> "OK"
                }
        },
        store,
    )

    fun workspaces(scope: CoroutineScope) = RemoteAdminWorkspaces(
        scope,
        RemoteAdminWorkspaces.Dependencies(
            repeaterAdminService = { admin },
            roomAdminService = { roomAdmin },
            contactService = { null },
            nodeSnapshotService = { null },
            radioId = { session.radioID },
            isRadioConnected = { true },
        ),
    )

    init {
        runBlocking {
            val contacts = RoomContactStore(database)
            contacts.saveContact(contact)
            store.saveSession(session)
            contacts.saveContact(roomContact)
            store.saveSession(roomSession)
        }
    }

    fun close() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun unavailable(): Nothing = throw MeshError.NotConnected
}
