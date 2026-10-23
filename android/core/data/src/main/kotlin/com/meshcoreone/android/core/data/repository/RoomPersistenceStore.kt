// PortedFrom: MC1Services/Sources/MC1Services/Protocols/PersistenceStoreProtocol.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/MC1Services.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/** The supplied scope and database are process-owned, never a radio-connection generation. */
class RoomPersistenceStore private constructor(
    private val context: RoomRepositoryContext,
    private val discovered: DiscoveredNodeRepository = DiscoveredNodeRepository(context),
    private val devices: DeviceRepository = DeviceRepository(context),
    private val rooms: RoomRepository = RoomRepository(context),
    private val contacts: ContactRepository = ContactRepository(context, discovered),
    private val channels: ChannelRepository = ChannelRepository(context, rooms),
    private val pending: PendingSendRepository = PendingSendRepository(context),
    private val messages: MessageRepository = MessageRepository(context, pending),
    private val diagnostics: DiagnosticRepository = DiagnosticRepository(context),
    private val metadata: MetadataRepository = MetadataRepository(context),
) : PersistenceStoreProtocol,
    DevicePersisting by devices, ContactPersisting by contacts, ChannelPersisting by channels,
    MessagePersisting by messages, HeardRepeatPersisting by messages, ReactionPersisting by messages,
    FailedSendPersisting by messages, RoomPersisting by rooms, DiscoveredNodePersisting by discovered,
    TracePathPersisting by diagnostics, DebugLogPersisting by diagnostics, LinkPreviewPersisting by diagnostics,
    RxLogPersisting by diagnostics, NodeSnapshotPersisting by diagnostics, MetadataPersisting by metadata {
    constructor(
        database: MeshCoreDatabase,
        scope: CoroutineScope,
        clock: Clock = Clock.systemUTC(),
        reporter: RepositoryIssueReporter = AndroidRepositoryIssueReporter,
    ) : this(RoomRepositoryContext(database, clock, scope, reporter))

    override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? =
        messages.fetchMessage(deduplicationKey, radioId)
    override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> =
        contacts.fetchContactPublicKeys(radioId)
    override suspend fun setSessionNotificationLevel(key: EntityKey, level: NotificationLevel) =
        rooms.setSessionNotificationLevel(key, level)

    override suspend fun warmUp() {
        context.read("warmUp") { database.devices().count() }
        try {
            pending.purgeOrphanPendingSends()
        } catch (cause: PersistenceStoreException) {
            // The source reports each independent maintenance failure and still attempts the other purge.
        }
        try {
            pending.purgeLegacyAttemptCountRows()
        } catch (cause: PersistenceStoreException) {
            // The operation has already reported its typed cause without recreating the database.
        }
    }

    suspend fun close() { context.close() }

    internal suspend fun <T> withBackupSnapshot(block: suspend (MeshCoreDatabase, Clock) -> T): T =
        context.read("fetchBackupExportSnapshot") { block(database, clock) }

    internal suspend fun <T> withBackupRestore(
        afterCommit: () -> Unit = {},
        block: suspend (MeshCoreDatabase, Clock) -> T,
    ): T = context.restoreBackup(afterCommit) { block(database, clock) }

    fun observeContacts(radioId: RadioId): Flow<SnapshotList<ContactDTO>> =
        context.observe("observeContacts", context.database.contacts().observe(radioId.value)) {
            it.map { row -> row.toDTO() }.snapshot()
        }

    fun observeChannels(radioId: RadioId): Flow<SnapshotList<ChannelDTO>> =
        context.observe("observeChannels", context.database.channels().observe(radioId.value)) {
            it.map { row -> row.toDTO() }.snapshot()
        }

    fun observeContactMessages(contact: EntityKey): Flow<SnapshotList<MessageDTO>> =
        context.observe("observeContactMessages", context.database.messages().observeContact(contact.radioId.value, contact.id)) {
            MessageDTO.reorderSameSenderClusters(it.asReversed().map { row -> row.toDTO() })
        }

    internal val rxInitiatedSaveCount: Long get() = context.rxInitiatedSaveCount

    companion object {
        const val MAX_DISCOVERED_NODES = DiscoveredNodeRepository.MAX_DISCOVERED_NODES
        /** Closes a Room database when process-store construction failed before ownership transfer. */
        fun closeUntransferredDatabase(database: MeshCoreDatabase) {
            database.close()
        }

        fun clampedPhoneClockTimestamp(stamp: UInt, now: java.time.Instant): UInt =
            com.meshcoreone.android.core.data.repository.clampedPhoneClockTimestamp(stamp, now)
    }
}

typealias PersistenceStore = RoomPersistenceStore
typealias DataStore = RoomPersistenceStore
