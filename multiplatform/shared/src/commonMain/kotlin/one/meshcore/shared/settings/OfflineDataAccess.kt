package one.meshcore.shared.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.chat.ChatCoordinatorRegistry
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.chatDaos
import one.meshcore.shared.room.RoomServerService

/**
 * The list/map/thread data providers, resolved live-first: the per-connection [ServiceContainer] while a
 * radio is up, else a standalone [OfflineDataGraph] scoped to the last-connected `radioID`, so a
 * responder whose radio dies keeps reading the rows already on disk. Mirrors the iOS
 * `AppState.currentRadioID` / `AppState.offlineDataStore` pair.
 *
 * The offline partition is the persisted `radioID` [refresh] reads; it is never minted here, because a
 * fresh one would orphan every per-radio row. The standalone graph is dropped the moment services
 * reappear, so a graph built against the old partition cannot outlive a reconnect.
 */
class OfflineDataAccess(
    private val database: MeshDatabase,
    private val scope: CoroutineScope,
    private val lastConnectionStore: LastConnectionStore,
    private val liveServices: () -> ServiceContainer?,
    private val nowEpochSeconds: () -> Double,
    /** Demo/screenshot partition, which wins over the persisted one while fixtures are seeded. */
    private val fixtureRadioId: () -> Uuid? = { null },
) {
    private var lastKnownRadioId: Uuid? = null
    private var cachedGraph: OfflineDataGraph? = null
    private val partitionMutex = Mutex()
    private val _dataRestoredGeneration = MutableStateFlow(0)

    /**
     * Process-lifetime thread registry, independent of [graph] retirement when a live
     * `ServiceContainer` appears. Mirrors iOS `AppState.chatCoordinatorRegistry`.
     */
    private var threadRegistryRadioId: Uuid? = null
    private var threadRegistry: ChatCoordinatorRegistry? = null
    private var threadStore: RoomChatStore? = null
    private val partitionDiscard = PartitionDiscard()

    /**
     * Bumped by [resetAfterDataRestore] so an open thread rebakes its coordinator, matching iOS
     * `servicesVersion` after `notifyDataRestored`.
     */
    val dataRestoredGeneration: StateFlow<Int> = _dataRestoredGeneration.asStateFlow()

    /**
     * The data partition to read: the live radio's, else the demo partition, else the last-connected one.
     * `null` only before any radio has ever paired.
     */
    val currentRadioId: () -> Uuid? = {
        liveServices()?.radioId ?: offlineRadioId()
    }

    /** Contacts for the Nodes list and the map's pins; store reads work offline, device writes throw. */
    val contactService: () -> ContactService? = { resolve({ it.contactService }, { it.contactService }) }

    /** The chats list's conversation source. */
    val conversationListStore: () -> ConversationListStore? =
        { resolve({ it.conversationListStore }, { it.conversationListStore }) }

    /** Per-conversation coordinators, so a chat detail screen renders stored messages while disconnected. */
    val coordinatorRegistry: () -> ChatCoordinatorRegistry? =
        { resolve({ it.coordinatorRegistry }, { it.coordinatorRegistry }) }

    /** The thread-level message store behind [coordinatorRegistry]. */
    val chatStore: () -> RoomChatStore? = { resolve({ it.chatStore }, { it.chatStore }) }

    /** Room-server conversations; offline reads render history, every send throws. */
    val roomServerService: () -> RoomServerService? = { resolve({ it.roomServerService }, { it.roomServerService }) }

    /** Re-reads the persisted last-connected `radioID`. Call at startup and on every connection change. */
    suspend fun refresh() {
        partitionMutex.withLock {
            lastKnownRadioId = lastConnectionStore.radioId()
            if (lastKnownRadioId == null) {
                partitionDiscard.dropGraph()
                partitionDiscard.dropThreads()
            }
        }
    }

    /**
     * Drops the standalone graph after a backup restore so the next chat open mints a fresh
     * coordinator instead of serving the pre-restore page. The last-radio partition is reread;
     * the graph is rebuilt lazily. Mirrors iOS `notifyDataRestored` clearing `ChatCoordinatorRegistry`.
     */
    suspend fun resetAfterDataRestore() {
        partitionMutex.withLock {
            lastKnownRadioId = lastConnectionStore.radioId()
            partitionDiscard.dropGraph()
            partitionDiscard.dropThreads()
            _dataRestoredGeneration.value += 1
        }
    }

    /**
     * Forgets the cached last-radio partition without rereading prefs. Call after
     * [LastConnectionStore.clear] so a late [refresh] cannot restore the forgotten radio.
     */
    suspend fun dropOfflinePartition() {
        partitionMutex.withLock {
            lastKnownRadioId = null
            partitionDiscard.dropGraph()
            partitionDiscard.dropThreads()
        }
    }

    /**
     * The process-lifetime coordinator registry for [radioId]. Survives disconnect and reconnect;
     * remints only when the partition changes or after restore, matching iOS
     * `ensureChatCoordinatorRegistry`.
     */
    fun threadCoordinatorRegistry(radioId: Uuid): ChatCoordinatorRegistry = ensureThreadPartition(radioId).registry

    /** The Room chat store bound to [threadCoordinatorRegistry] for [radioId]. */
    fun threadChatStore(radioId: Uuid): RoomChatStore = ensureThreadPartition(radioId).store

    private fun ensureThreadPartition(radioId: Uuid): ThreadPartition {
        val existingRegistry = threadRegistry
        val existingStore = threadStore
        if (threadRegistryRadioId == radioId && existingRegistry != null && existingStore != null) {
            return ThreadPartition(existingRegistry, existingStore)
        }
        partitionDiscard.dropThreads()
        val store = RoomChatStore(
            radioId,
            database.chatDaos(),
            RxLogCorrelator(database.rxLogEntryDao()),
        )
        val registry = ChatCoordinatorRegistry(store, scope)
        threadStore = store
        threadRegistry = registry
        threadRegistryRadioId = radioId
        return ThreadPartition(registry, store)
    }

    /**
     * Room-backed graph for [radioId], even when the last-connection store is empty. A notification tap
     * after last-radio forget still has the stored row and must read that partition. Does not change
     * [currentRadioId], so the Chats list stays empty.
     */
    fun graphFor(radioId: Uuid): OfflineDataGraph = cachedGraph?.takeIf { it.radioId == radioId }
        ?: OfflineDataGraph(radioId, database, scope, nowEpochSeconds).also {
            partitionDiscard.dropGraph()
            cachedGraph = it
        }

    private fun offlineRadioId(): Uuid? = fixtureRadioId() ?: lastKnownRadioId

    /**
     * Live-first resolution. Consulting the standalone graph on every read is what retires one the live
     * services have superseded, the same way the iOS `offlineDataStore` getter nils its cache.
     */
    private fun <T> resolve(live: (ServiceContainer) -> T, offline: (OfflineDataGraph) -> T): T? {
        val standalone = graph()
        return liveServices()?.let(live) ?: standalone?.let(offline)
    }

    /**
     * The standalone graph, or `null` when services are live or no radio is known. Rebuilt when the
     * partition changes, so switching radios cannot serve the previous one's rows.
     */
    private fun graph(): OfflineDataGraph? {
        val radio = offlineRadioId().takeIf { liveServices() == null }
        if (radio == null) {
            partitionDiscard.dropGraph()
            return null
        }
        return cachedGraph?.takeIf { it.radioId == radio }
            ?: OfflineDataGraph(radio, database, scope, nowEpochSeconds).also {
                partitionDiscard.dropGraph()
                cachedGraph = it
            }
    }

    private class ThreadPartition(val registry: ChatCoordinatorRegistry, val store: RoomChatStore)

    private inner class PartitionDiscard {
        fun dropGraph() {
            cachedGraph?.close()
            cachedGraph = null
        }

        fun dropThreads() {
            threadRegistry?.tearDown()
            threadRegistry = null
            threadStore = null
            threadRegistryRadioId = null
        }
    }
}
