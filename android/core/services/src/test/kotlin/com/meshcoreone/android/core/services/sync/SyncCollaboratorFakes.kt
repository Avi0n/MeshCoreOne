// AndroidOnly: WP-214 Recording fakes for the sync collaborator ports (stand-ins for ServiceContainer.forTesting).
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.ReactionPersisting
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.notifications.NotificationService
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

private fun <T> recorder(): MutableList<T> = Collections.synchronizedList(ArrayList())

/** Only suppression is exercised here; App integration separately covers the production adapter. */
internal fun NotificationService.syncSuppressionPort(): SyncNotificationServicing =
    object : SyncNotificationServicing by FakeNotificationService() {
        override suspend fun isSuppressingNotifications(): Boolean = this@syncSuppressionPort.isSuppressingNotifications
        override suspend fun setSuppressingNotifications(suppressing: Boolean) {
            this@syncSuppressionPort.isSuppressingNotifications = suppressing
        }
    }

/** Records every NotificationService call sync makes. */
internal class FakeNotificationService : SyncNotificationServicing {
    data class Post(val kind: String, val title: String, val id: UUID?, val text: String)

    @Volatile var isSuppressing = false
    @Volatile var activeContact: UUID? = null
    @Volatile var activeChannel: UByte? = null
    @Volatile var activeChannelRadio: RadioId? = null
    @Volatile var badgeUpdates = 0
    val posts: MutableList<Post> = recorder()
    val suppressionWrites: MutableList<Boolean> = recorder()

    override suspend fun isSuppressingNotifications(): Boolean = isSuppressing
    override suspend fun setSuppressingNotifications(suppressing: Boolean) {
        suppressionWrites += suppressing
        isSuppressing = suppressing
    }
    override suspend fun activeContactID(): UUID? = activeContact
    override suspend fun activeChannelIndex(): UByte? = activeChannel
    override suspend fun activeChannelRadioId(): RadioId? = activeChannelRadio

    override suspend fun postDirectMessageNotification(from: String, contactID: UUID, messageText: String, messageID: UUID, isMuted: Boolean) {
        if (!isMuted) posts += Post("dm", from, contactID, messageText)
    }

    override suspend fun postChannelMessageNotification(
        channelName: String, channelIndex: UByte, radioId: RadioId, senderName: String?, messageText: String,
        messageID: UUID, notificationLevel: NotificationLevel, hasSelfMention: Boolean,
    ) {
        posts += Post("channel", channelName, messageID, messageText)
    }

    override suspend fun postRoomMessageNotification(
        roomName: String, sessionID: UUID, senderName: String?, messageText: String, messageID: UUID,
        notificationLevel: NotificationLevel,
    ) {
        posts += Post("room", roomName, sessionID, messageText)
    }

    override suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType) {
        posts += Post("contact", contactName, contactID, "")
    }

    override suspend fun updateBadgeCount() {
        badgeUpdates += 1
    }
}

/** Reaction service fake: index/queue calls are recorded; persistence writes through the store. */
internal class FakeReactionService : SyncReactionServicing {
    val indexed: MutableList<Pair<UUID, String>> = recorder()
    val queued: MutableList<String> = recorder()
    val pendingForNextIndex: MutableList<SyncPendingReaction> = recorder()
    var cachedTargets: MutableMap<String, UUID> = java.util.concurrent.ConcurrentHashMap()
    @Volatile var parseChannelReaction: (String) -> ParsedReaction? = { null }

    override suspend fun indexDMMessage(id: UUID, contactID: UUID, text: String, timestamp: UInt): List<SyncPendingDMReaction> {
        indexed += id to text
        return emptyList()
    }

    override suspend fun indexMessage(id: UUID, channelIndex: UByte, senderName: String, text: String, timestamp: UInt): List<SyncPendingReaction> {
        indexed += id to text
        return synchronized(pendingForNextIndex) { pendingForNextIndex.toList().also { pendingForNextIndex.clear() } }
    }

    override suspend fun persistReactionAndUpdateSummary(reaction: ReactionDTO, dataStore: ReactionPersisting): SyncReactionPersistResult? {
        dataStore.saveReaction(reaction)
        val summary = dataStore.fetchReactions(EntityKey(reaction.radioId, reaction.messageID)).joinToString(",") { it.emoji }
        return SyncReactionPersistResult(reaction.messageID, summary)
    }

    override suspend fun findDMTargetMessage(messageHash: String, contactID: UUID): UUID? = cachedTargets[messageHash]

    override suspend fun queuePendingDMReaction(parsed: SyncParsedDMReaction, contactID: UUID, senderName: String, rawText: String, radioId: RadioId) {
        queued += rawText
    }

    override fun tryProcessAsReaction(text: String): ParsedReaction? = parseChannelReaction(text)

    override suspend fun findTargetMessage(parsed: ParsedReaction, channelIndex: UByte): UUID? = cachedTargets[parsed.messageHash]

    override suspend fun queuePendingReaction(parsed: ParsedReaction, channelIndex: UByte, senderNodeName: String, rawText: String, radioId: RadioId) {
        queued += rawText
    }
}

/**
 * Heard-repeats fake with WP-216's observable contract: harvest records later distinct correlated paths
 * as extras; recording skips a path equal to the canonical one or already stored.
 */
internal class FakeHeardRepeatsService(
    private val store: PersistenceStoreProtocol,
    private val correlation: SyncChannelRXCorrelating,
) : SyncHeardRepeatsServicing {
    val recordCalls: MutableList<Bytes> = recorder()
    val harvestCalls: MutableList<UUID> = recorder()

    override suspend fun harvestIncomingPaths(message: MessageDTO, decodedCandidates: List<RxLogEntryDTO>) {
        harvestCalls += message.id
        for (entry in correlation.matching(decodedCandidates, message.deduplicationKey)) {
            recordDistinctPathIfNeeded(message, entry.pathNodes, entry.pathLength, entry.snr, entry.rssi, entry.receivedAt, entry.id)
        }
    }

    override suspend fun recordDistinctPathIfNeeded(
        message: MessageDTO, pathNodes: Bytes, pathLength: UByte, snr: Double?, rssi: Long?, receivedAt: Instant, rxLogEntryID: UUID?,
    ): Long? {
        recordCalls += pathNodes
        if (message.pathNodes == null || pathNodes == message.pathNodes) return null
        val key = EntityKey(message.radioId, message.id)
        if (store.fetchMessageRepeats(key).any { it.pathNodes == pathNodes }) return null
        store.saveMessageRepeat(message.radioId, MessageRepeatDTO(messageID = message.id, receivedAt = receivedAt, pathNodes = pathNodes, pathLength = pathLength))
        return store.incrementMessageHeardRepeats(key)
    }
}

/** Advertisement fake: records syncing toggles, captures the delta handler, multicasts discovery events. */
internal class FakeAdvertisementService : SyncAdvertisementServicing {
    val syncingToggles: MutableList<Boolean> = recorder()
    @Volatile var deltaSyncHandler: (suspend (Boolean) -> SyncAdvertContactSyncOutcome)? = null
    @Volatile var materialized: ContactDTO? = null
    private val lock = Any()
    private val subscribers = ArrayList<Channel<SyncDiscoveryEvent>>()
    val subscriberCount: Int get() = synchronized(lock) { subscribers.size }

    override suspend fun setSyncingContacts(isSyncing: Boolean) {
        syncingToggles += isSyncing
    }

    override suspend fun setDeltaSyncHandler(handler: (suspend (Boolean) -> SyncAdvertContactSyncOutcome)?) {
        deltaSyncHandler = handler
    }

    override suspend fun materializeContactForPendingAdvert(prefix: Bytes, radioId: RadioId): ContactDTO? = materialized

    override fun events(): Flow<SyncDiscoveryEvent> {
        val channel = Channel<SyncDiscoveryEvent>(Channel.UNLIMITED)
        synchronized(lock) { subscribers += channel }
        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                synchronized(lock) { subscribers -= channel }
            }
        }
    }

    fun emit(event: SyncDiscoveryEvent) = synchronized(lock) { subscribers.forEach { it.trySend(event) } }
}

/** RX log fake: records cache updates; [decoder] stands in for re-decryption (identity by default). */
internal class FakeRxLogService : SyncRxLogServicing {
    @Volatile var privateKey: Bytes? = null
    @Volatile var contactKeyUpdates = 0
    @Volatile var channelUpdates = 0
    @Volatile var decoder: (RxLogEntryDTO) -> RxLogEntryDTO = { it }

    override suspend fun updatePrivateKey(key: Bytes?) {
        privateKey = key
    }
    override suspend fun updateContactPublicKeys(keys: Map<UByte, List<Bytes>>) {
        contactKeyUpdates += 1
    }
    override suspend fun updateChannels(secrets: Map<UByte, Bytes>, names: Map<UByte, String>) {
        channelUpdates += 1
    }
    override suspend fun decodedEntries(entries: List<RxLogEntryDTO>): List<RxLogEntryDTO> = entries.map(decoder)
}

internal class FakeRoomServerService : SyncRoomServerServicing {
    @Volatile var result: RoomMessageDTO? = null
    val calls: MutableList<Bytes> = recorder()
    override suspend fun handleIncomingMessage(senderPublicKeyPrefix: Bytes, timestamp: UInt, authorPrefix: Bytes, text: String): RoomMessageDTO? {
        calls += authorPrefix
        return result
    }
}

internal class FakeAdminService : SyncRoomAdminServicing, SyncRepeaterAdminServicing {
    val routed: MutableList<String> = recorder()
    override suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDTO) {
        routed += message.text
    }
}

internal class FakeRemoteNodeService : SyncRemoteNodeServicing {
    val reauthenticated: MutableList<Set<EntityKey>> = recorder()
    override suspend fun handleBLEReconnection(sessions: Set<EntityKey>) {
        reauthenticated += sessions
    }
}

/** Deterministic stand-in for WP-208's content key (sync only needs equality of equal inputs). */
internal val fakeDeduplicationKey = SyncDeduplicationKeying { contactID, channelIndex, sender, timestamp, content ->
    "${contactID ?: "-"}|${channelIndex ?: "-"}|${sender ?: "-"}|$timestamp|$content"
}

/** Mirrors WP-208's matcher: decrypted group-text rows whose "sender: body" rebuilds the key. */
internal val fakeChannelRXCorrelation = SyncChannelRXCorrelating { entries, key ->
    if (key == null) emptyList() else entries.filter { entry ->
        val text = entry.decodedText ?: return@filter false
        if (entry.payloadType != PayloadType.GROUP_TEXT || entry.decryptStatus != DecryptStatus.SUCCESS) return@filter false
        val parsed = parseChannelMessage(text)
        fakeDeduplicationKey.contentBased(null, entry.channelIndex, parsed.senderNodeName, entry.senderTimestamp ?: 0u, parsed.messageText) == key
    }.sortedBy { it.receivedAt }
}

internal val noReactionParsing = object : SyncReactionParsing {
    override fun parseDM(text: String): SyncParsedDMReaction? = null
    override fun isReactionText(text: String, isDM: Boolean): Boolean = false
}

internal val noMeshCoreOpenParsing = object : SyncMeshCoreOpenReactionParsing {
    override fun parse(text: String): SyncParsedMCOReaction? = null
    override fun parseV1(text: String): SyncParsedMCOReactionV1? = null
    override fun computeReactionHash(timestamp: UInt, senderName: String?, text: String): String = ""
    override fun dartStringHash(string: String): UInt = 0u
}

internal val fakeCodecs = SyncMessageCodecs(
    reactionParser = noReactionParsing,
    meshCoreOpenParser = noMeshCoreOpenParsing,
    deduplicationKey = fakeDeduplicationKey,
    channelRXCorrelation = fakeChannelRXCorrelation,
    mentions = SyncMentionDetecting { text, selfName -> text.contains("@[$selfName]") },
    cliEcho = SyncCLIEchoParsing { text -> if (text.startsWith("ECHO> ")) text.removePrefix("ECHO> ") else null },
)

/** Swift `ServiceContainer.forTesting(...).syncDependencies`, built from recording fakes. */
internal class SyncTestServices(
    val dataStore: SyncInMemoryStore = SyncInMemoryStore(),
    val contactService: MockContactService = MockContactService(),
    val channelService: MockChannelService = MockChannelService(),
    val polling: MockMessagePollingService = MockMessagePollingService(),
) {
    val notifications = FakeNotificationService()
    val reactions = FakeReactionService()
    val heardRepeats = FakeHeardRepeatsService(dataStore, fakeChannelRXCorrelation)
    val adverts = FakeAdvertisementService()
    val rxLog = FakeRxLogService()
    val roomServer = FakeRoomServerService()
    val roomAdmin = FakeAdminService()
    val repeaterAdmin = FakeAdminService()
    val remoteNode = FakeRemoteNodeService()
    val monitoringStarts: MutableList<Pair<RadioId, Boolean>> = recorder()

    fun dependencies(
        codecs: SyncMessageCodecs = fakeCodecs,
        appStateProvider: com.meshcoreone.android.core.contracts.domain.AppStateProvider? = null,
    ): SyncDependencies = SyncDependencies(
        dataStore = dataStore, contactService = contactService, channelService = channelService,
        messagePollingService = polling, notificationService = notifications, reactionService = reactions,
        advertisementService = adverts, rxLogService = rxLog, heardRepeatsService = heardRepeats,
        roomServerService = roomServer, roomAdminService = roomAdmin, repeaterAdminService = repeaterAdmin,
        codecs = codecs, appStateProvider = appStateProvider,
        startEventMonitoring = { radioId, autoFetch -> monitoringStarts += radioId to autoFetch },
        exportPrivateKey = { Bytes(ByteArray(64) { 7 }) },
    )
}
