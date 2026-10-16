package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import one.meshcore.shared.chat.ChatCoordinatorRegistry
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSession
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomConversationListStore
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.chatDaos
import one.meshcore.shared.persistence.conversationDaos
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.room.RoomMessageRetry
import one.meshcore.shared.room.RoomServerEdges
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.RoomServerSession

/** Offline-only firmware code for contact mutations that never reach a radio. */
private const val OFFLINE_NOT_CONNECTED_FIRMWARE_CODE: Int = 0xFF

/**
 * Room-backed list/thread/contact/room reads for one `radioID` with no live BLE/WiFi link. Every session
 * below it is inert: reads resolve from the store, writes that would need a radio throw. It owns a child
 * scope so discarding it stops the collectors its services start.
 */
class OfflineDataGraph(
    val radioId: Uuid,
    private val database: MeshDatabase,
    parentScope: CoroutineScope,
    private val nowEpochSeconds: () -> Double,
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )

    val chatStore: RoomChatStore = RoomChatStore(
        radioId,
        database.chatDaos(),
        RxLogCorrelator(database.rxLogEntryDao()),
    )

    val conversationListStore: ConversationListStore = RoomConversationListStore(
        radioID = radioId,
        daos = database.conversationDaos(),
    )

    val coordinatorRegistry: ChatCoordinatorRegistry = ChatCoordinatorRegistry(chatStore, scope)

    private val contactStore = RoomContactStore(database)

    val contactService: ContactService = ContactService(
        session = DisconnectedContactSession,
        store = contactStore,
        now = { nowEpochSeconds().toLong() },
    )

    private val nodeStore = RoomNodeStore(
        radioId = radioId,
        sessionDao = database.remoteNodeSessionDao(),
        roomMessageDao = database.roomMessageDao(),
        contactDao = database.contactDao(),
        now = nowEpochSeconds,
    )

    val roomServerService: RoomServerService = RoomServerService(
        remoteNodeService = RemoteNodeService(
            nodeSession = DisconnectedNodeSession,
            store = nodeStore,
            passwordStore = InMemoryNodePasswordStore(),
            scope = scope,
        ),
        store = nodeStore,
        roomSession = DisconnectedRoomServerSession,
        passwordStore = InMemoryNodePasswordStore(),
        scope = scope,
        edges = RoomServerEdges(now = { nowEpochSeconds().toLong() }),
    )

    /** Stops this graph's collectors. Called when a live connection supersedes it or the partition changes. */
    fun close() {
        scope.cancel()
    }
}

/** Contact session that never reaches a radio; reads go through the store only. */
private object DisconnectedContactSession : ContactSession {
    override suspend fun getContacts(since: Long?): List<MeshContact> = emptyList()

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = null

    override suspend fun addContact(contact: MeshContact) =
        throw ContactDeviceException(OFFLINE_NOT_CONNECTED_FIRMWARE_CODE)

    override suspend fun removeContact(publicKey: ByteArray) =
        throw ContactDeviceException(OFFLINE_NOT_CONNECTED_FIRMWARE_CODE)

    override suspend fun resetPath(publicKey: ByteArray) =
        throw ContactDeviceException(OFFLINE_NOT_CONNECTED_FIRMWARE_CODE)

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo =
        throw ContactDeviceException(OFFLINE_NOT_CONNECTED_FIRMWARE_CODE)

    override suspend fun shareContact(publicKey: ByteArray) =
        throw ContactDeviceException(OFFLINE_NOT_CONNECTED_FIRMWARE_CODE)

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) =
        throw ContactDeviceException(OFFLINE_NOT_CONNECTED_FIRMWARE_CODE)
}

private object DisconnectedNodeSession : NodeSession {
    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 1)
    override val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo =
        throw MeshError.NotConnected

    override suspend fun sendLogout(destination: ByteArray) = throw MeshError.NotConnected

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo =
        throw MeshError.NotConnected

    override suspend fun requestStatus(publicKey: ByteArray): StatusResponse = throw MeshError.NotConnected

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse = throw MeshError.NotConnected

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse = throw MeshError.NotConnected

    override suspend fun sendCommand(destination: ByteArray, command: String): MessageSentInfo =
        throw MeshError.NotConnected

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse = throw MeshError.NotConnected
}

private object DisconnectedRoomServerSession : RoomServerSession {
    override suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        retry: RoomMessageRetry,
    ): MessageSentInfo? = throw MeshError.NotConnected

    override suspend fun sendPathDiscovery(destination: ByteArray) = throw MeshError.NotConnected

    override suspend fun requestHistorySync(destination: ByteArray) = throw MeshError.NotConnected
}
