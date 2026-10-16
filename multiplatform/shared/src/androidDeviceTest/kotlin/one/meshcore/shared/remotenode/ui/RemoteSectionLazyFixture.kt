package one.meshcore.shared.remotenode.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.NeighborSnapshotEntry
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.TelemetrySnapshotEntry
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.contact.ContactStore
import one.meshcore.shared.contact.ui.ContactClipboardFixtures
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomNodeSnapshotStore
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NeighborBaseline
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodeLocationFix
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.NodeSnapshotStore
import one.meshcore.shared.remotenode.NodeStatusMetrics
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.room.RoomAdminService

internal class RemoteSectionLazyFixture(private val context: Context, scope: CoroutineScope) {
    private val databaseName = "remote-section-lazy-${Uuid.random().value}.db"
    private val database = meshDatabase(context, Dispatchers.IO, databaseName)
    val radio = RemoteSectionLazySession()
    val contact = ContactClipboardFixtures.contact.copy(
        radioID = Uuid.random(),
        typeRawValue = ContactType.REPEATER.raw,
        name = "Lazy repeater",
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
        permissionLevel = RoomPermissionLevel.ADMIN,
        unreadCount = 0,
        notificationLevel = NotificationLevel.ALL,
        isPinned = false,
        neighborCount = 0,
        lastSyncTimestamp = 0,
    )
    val roomContact = contact.copy(
        id = Uuid.random(),
        publicKey = contact.publicKey.copyOf().also { it[it.lastIndex]++ },
        name = "Lazy room",
        typeRawValue = ContactType.ROOM.raw,
    )
    val roomSession = session.copy(
        id = Uuid.random(),
        publicKey = roomContact.publicKey.copyOf(),
        name = roomContact.name,
        role = RemoteNodeRole.ROOM_SERVER,
    )
    private val store = RoomNodeStore(
        session.radioID,
        database.remoteNodeSessionDao(),
        database.roomMessageDao(),
        database.contactDao(),
        now = { 0.0 },
    )
    private val passwords = InMemoryNodePasswordStore()
    private val cli = NodeCliCoordinator(radio, store, passwords, scope)
    val admin = RepeaterAdminService(
        RemoteNodeService(radio, store, passwords, scope),
        cli,
        radio,
        store,
        passwords,
    )
    val roomAdmin = RoomAdminService(radio.room, store)
    var contactReads = 0
        private set
    var snapshotReads = 0
        private set
    var persistenceWrites = 0
        private set
    var lastHistoryRows: List<NodeStatusSnapshotDto> = emptyList()
        private set
    private val contactsBacking = RoomContactStore(database)
    private val contactsStore = object : ContactStore by contactsBacking {
        override suspend fun fetchContacts(radioID: Uuid): List<ContactDto> {
            contactReads++
            return contactsBacking.fetchContacts(radioID)
        }

        override suspend fun fetchContact(id: Uuid): ContactDto? {
            contactReads++
            return contactsBacking.fetchContact(id)
        }

        override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
            contactReads++
            return contactsBacking.fetchContact(radioID, publicKey)
        }

        override suspend fun saveContact(dto: ContactDto) {
            persistenceWrites++
            contactsBacking.saveContact(dto)
        }
    }
    private val snapshotsBacking = RoomNodeSnapshotStore(
        database.nodeStatusSnapshotDao(),
        now = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    )
    val snapshots = NodeSnapshotService(object : NodeSnapshotStore by snapshotsBacking {
        override suspend fun fetchSnapshots(nodePublicKey: ByteArray, since: Double?): List<NodeStatusSnapshotDto> {
            snapshotReads++
            return snapshotsBacking.fetchSnapshots(nodePublicKey, since).also { lastHistoryRows = it }
        }

        override suspend fun previousStatusSnapshot(nodePublicKey: ByteArray, before: Double): NodeStatusSnapshotDto? {
            snapshotReads++
            return snapshotsBacking.previousStatusSnapshot(nodePublicKey, before)
        }

        override suspend fun neighborBaseline(nodePublicKey: ByteArray): NeighborBaseline {
            snapshotReads++
            return snapshotsBacking.neighborBaseline(nodePublicKey)
        }

        override suspend fun fetchLatestSnapshot(nodePublicKey: ByteArray): NodeStatusSnapshotDto? {
            snapshotReads++
            return snapshotsBacking.fetchLatestSnapshot(nodePublicKey)
        }

        override suspend fun recordSnapshot(
            nodePublicKey: ByteArray,
            status: NodeStatusMetrics?,
            telemetry: List<TelemetrySnapshotEntry>?,
            neighbors: List<NeighborSnapshotEntry>?,
            location: NodeLocationFix?,
        ): Uuid {
            persistenceWrites++
            return snapshotsBacking.recordSnapshot(nodePublicKey, status, telemetry, neighbors, location)
        }

        override suspend fun updateSnapshotNeighbors(id: Uuid, neighbors: List<NeighborSnapshotEntry>) {
            persistenceWrites++
            snapshotsBacking.updateSnapshotNeighbors(id, neighbors)
        }

        override suspend fun updateSnapshotTelemetry(id: Uuid, telemetry: List<TelemetrySnapshotEntry>) {
            persistenceWrites++
            snapshotsBacking.updateSnapshotTelemetry(id, telemetry)
        }

        override suspend fun deleteSnapshotsOlderThan(cutoff: Double) {
            persistenceWrites++
            snapshotsBacking.deleteSnapshotsOlderThan(cutoff)
        }
    })
    val contacts = ContactService(
        session = object : ContactSession {
            override suspend fun getContacts(since: Long?): Nothing = unexpectedContactOperation()
            override suspend fun getContact(publicKey: ByteArray): Nothing = unexpectedContactOperation()
            override suspend fun addContact(contact: MeshContact): Nothing = unexpectedContactOperation()
            override suspend fun removeContact(publicKey: ByteArray): Nothing = unexpectedContactOperation()
            override suspend fun resetPath(publicKey: ByteArray): Nothing = unexpectedContactOperation()
            override suspend fun sendPathDiscovery(destination: ByteArray): Nothing = unexpectedContactOperation()
            override suspend fun shareContact(publicKey: ByteArray): Nothing = unexpectedContactOperation()
            override suspend fun changeContactFlags(contact: MeshContact, flags: Int): Nothing =
                unexpectedContactOperation()
        },
        store = contactsStore,
        now = { 0L },
    )
    val workspaces = RemoteAdminWorkspaces(
        scope,
        RemoteAdminWorkspaces.Dependencies(
            repeaterAdminService = { admin },
            roomAdminService = { roomAdmin },
            contactService = { contacts },
            nodeSnapshotService = { snapshots },
            radioId = { session.radioID },
            isRadioConnected = { true },
        ),
    )
    val surface = RepeaterSurfaceInputs(
        contactService = { contacts },
        radioId = { session.radioID },
    )

    init {
        runBlocking {
            contactsBacking.saveContact(contact)
            contactsBacking.saveContact(roomContact)
            store.saveSession(session)
            store.saveSession(roomSession)
        }
    }

    fun close() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    private fun unexpectedContactOperation(): Nothing {
        radio.calls += "contact-operation"
        error("Unexpected fixture contact operation")
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
