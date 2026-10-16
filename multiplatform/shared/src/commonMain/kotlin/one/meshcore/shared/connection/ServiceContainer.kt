package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.channel.ChannelService
import one.meshcore.shared.chat.BlockedSendersCache
import one.meshcore.shared.chat.ChannelReactionIndexer
import one.meshcore.shared.chat.ChannelResolver
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatCoordinatorRegistry
import one.meshcore.shared.chat.ChatPrewarmRefresher
import one.meshcore.shared.chat.ChatSendQueueCollaborators
import one.meshcore.shared.chat.ChatSendQueueEdges
import one.meshcore.shared.chat.ChatSendQueueService
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.DefaultSendQueueStore
import one.meshcore.shared.chat.IncomingMessageBroadcaster
import one.meshcore.shared.chat.MessageFailedConversationCollector
import one.meshcore.shared.chat.MessageIngestCollaborators
import one.meshcore.shared.chat.MessageIngestEdges
import one.meshcore.shared.chat.MessageIngestPipeline
import one.meshcore.shared.chat.MessagePoller
import one.meshcore.shared.chat.MessagePollerEdges
import one.meshcore.shared.chat.MessageService
import one.meshcore.shared.chat.MessageServiceIdentity
import one.meshcore.shared.chat.ResolvedContact
import one.meshcore.shared.chat.RoomMessageNotice
import one.meshcore.shared.chat.liveMessageStatusEvents
import one.meshcore.shared.chat.notifyHeardRepeatRecorded
import one.meshcore.shared.chat.notifyMessagesRegionUpdated
import one.meshcore.shared.chat.pathdetail.ChannelMessageDeleter
import one.meshcore.shared.chat.pathdetail.HeardRepeatEcho
import one.meshcore.shared.chat.pathdetail.HeardRepeatsService
import one.meshcore.shared.chat.reactions.MessageServiceReactionSender
import one.meshcore.shared.chat.reactions.ReactionHandler
import one.meshcore.shared.chat.reactions.ReactionHandlerEdges
import one.meshcore.shared.chat.reactions.ReactionService
import one.meshcore.shared.chat.reactions.ReactionStore
import one.meshcore.shared.chat.reactions.ReactionSummarySink
import one.meshcore.shared.chat.reactions.indexMessages
import one.meshcore.shared.chat.reactions.reactionIndexScopeFor
import one.meshcore.shared.contact.AdoptedDmNotificationCollector
import one.meshcore.shared.contact.AdvertContactSyncClaim
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.BlockedNamesCache
import one.meshcore.shared.contact.CallbackContactSyncCoordinator
import one.meshcore.shared.contact.ContactBlockedNamesRefresher
import one.meshcore.shared.contact.ContactCleanupCoordinator
import one.meshcore.shared.contact.ContactDiscoveryNotifier
import one.meshcore.shared.contact.ContactPathEventCollector
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactServiceAdvert
import one.meshcore.shared.contact.ContactServiceCollaborators
import one.meshcore.shared.contact.ContactServiceEdges
import one.meshcore.shared.contact.ContactSyncCoordinator
import one.meshcore.shared.contact.DiscoveredNodeCollectorInputs
import one.meshcore.shared.contact.DiscoveredNodeEventCollector
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.NodeStorageEventCollector
import one.meshcore.shared.contact.NotifierContactNotificationCleanup
import one.meshcore.shared.contact.RemoteNodeServiceSessionCleanup
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.events
import one.meshcore.shared.contact.getContact
import one.meshcore.shared.contact.getContactByID
import one.meshcore.shared.contact.materializeContactForPendingAdvert
import one.meshcore.shared.contact.removeContact
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.MessageRegionBackfill
import one.meshcore.shared.persistence.RoomBlockedChannelSenderStore
import one.meshcore.shared.persistence.RoomChannelMessageDeletionStore
import one.meshcore.shared.persistence.RoomChannelStore
import one.meshcore.shared.persistence.RoomChatStore
import one.meshcore.shared.persistence.RoomContactStore
import one.meshcore.shared.persistence.RoomConversationListStore
import one.meshcore.shared.persistence.RoomHeardRepeatStore
import one.meshcore.shared.persistence.RoomNodeStore
import one.meshcore.shared.persistence.RoomOrphanDmAdopter
import one.meshcore.shared.persistence.RoomPendingSendStore
import one.meshcore.shared.persistence.RoomReactionStore
import one.meshcore.shared.persistence.RxLogCorrelator
import one.meshcore.shared.persistence.chatDaos
import one.meshcore.shared.persistence.conversationDaos
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminEdges
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.handleIncomingCliMessage
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomLeavingConversationListStore
import one.meshcore.shared.room.RoomServerEdges
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.RoomSessionConversationCollector
import one.meshcore.shared.room.fetchSession
import one.meshcore.shared.room.handleIncomingMessage
import one.meshcore.shared.room.ingestSignedRoomMessage
import one.meshcore.shared.settings.NodeConfigService
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.tools.RxLogCollectorInputs
import one.meshcore.shared.tools.RxLogDecodeCache
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogEventCollector

/**
 * Per-connection service graph, rebuilt on every connect and torn down on disconnect — the Kotlin analogue
 * of the Swift `ServiceContainer`. It wires the committed chat pipeline (message service, durable send
 * queue, incoming-ingest poller, coordinator registry) and the contact service against the live
 * [MeshSession] and the [database], all scoped to one [radioId] partition. Anything that must survive
 * reconnects (drafts, notification coordinator, active-conversation state) is owned above the container and
 * injected; a container instance is valid for exactly one live link.
 *
 * The single [RoomChatStore] backs the three chat persistence seams (message send, ingest, thread reads),
 * mirroring the Swift single `PersistenceStore`.
 */
class ServiceContainer(
    val session: MeshSession,
    val database: MeshDatabase,
    identity: ServiceContainerIdentity,
    runtime: ServiceContainerRuntime,
    edges: ServiceContainerEdges = ServiceContainerEdges(),
) {
    /**
     * Live data partition. Starts as the connect-time value; [reconcileAfterIdentityImport] adopts a
     * matching ghost `radioID` so lists and ingest follow iOS `connectedDevice.radioID`.
     */
    var radioId: Uuid = identity.radioId
        internal set

    /**
     * Live radio public key. Starts as the connect-time value; [reconcileAfterIdentityImport] rewrites
     * it after import or regenerate so Device Info, outgoing sends, and room author prefixes follow
     * iOS `connectedDevice.publicKey`.
     */
    var selfPublicKey: ByteArray = identity.selfPublicKey.copyOf()
        internal set

    private val selfNodeName = identity.selfNodeName

    /** The connected radio's contact-table cap, surfaced to the add-contact and channel flows. */
    val maxContacts = identity.maxContacts

    /** The connected radio's channel-slot cap, gating the channel create/join options. */
    val maxChannels = identity.maxChannels

    internal val scope = runtime.scope

    /** App-lifetime encrypted credential store, shared across connections (the iOS keychain analogue). */
    private val nodePasswordStore = runtime.nodePasswordStore

    /** Last-connection persist so a ghost adopt updates the offline partition key. */
    internal val lastConnectionStore = runtime.lastConnectionStore

    internal val nowEpochSeconds = runtime.nowEpochSeconds
    private val newMessageId = runtime.newMessageId
    private val newPendingSendId = runtime.newPendingSendId
    private val newContactId = runtime.newContactId
    private val notifier = edges.notifiers.notifier
    private val reactionNotifier = edges.notifiers.reactionNotifier
    private val contactDiscoveryNotifier = edges.notifiers.contactDiscoveryNotifier
    private val activeConversation = edges.notifiers.activeConversation
    private val shouldNotifyDiscovery = edges.notifiers.shouldNotifyDiscovery
    private val messageServiceConfig = edges.chat.messageServiceConfig
    private val sendQueueConfig = edges.chat.sendQueueConfig
    private val threadCoordinatorRegistry = edges.chat.threadCoordinatorRegistry
    private val threadChatStore = edges.chat.threadChatStore
    private val warmConversationPreviews = edges.chat.warmConversationPreviews
    private val chatEnvInputs = edges.chat.chatEnvInputs
    private val unknownSenderLabel = edges.chat.unknownSenderLabel
    private val roomNameFallback = edges.chat.roomNameFallback
    private val onContactsChanged = edges.sync.onContactsChanged
    private val onConversationsChanged = edges.sync.onConversationsChanged
    private val isInForeground = edges.sync.isInForeground
    internal val rxChannelCache = edges.sync.rxChannelCache
    private val slotOccupantChanged = edges.sync.slotOccupantChanged
    private val discoveredNodes = edges.sync.discoveredNodes
    internal val rxLogStore = edges.sync.rxLogStore
    internal val debugLogStore = edges.sync.debugLogStore

    internal val connectionLogger = PersistentLogger(scope, category = "ConnectionManager")
    internal val messagePollingLogger = PersistentLogger(scope, category = "MessagePolling")
    internal val syncLogger = PersistentLogger(scope, category = "SyncCoordinator")
    internal var autoFetchEnabled = false
    internal var eventMonitoringActive = false
    internal val liveSync = LiveSync(this)
    private val containerStartup = ContainerStartup(this)
    private val containerTeardown = ContainerTeardown(this)
    internal var rxLogCollectJob: Job? = null
    internal var rxLogCatchUpJob: Job? = null
    internal var discoveredNodeCollectJob: Job? = null
    internal var chatPrewarmCollectJob: Job? = null

    /** Whether service event listeners are active. Mirrors iOS `isEventMonitoringActive`. */
    val isEventMonitoringActive: Boolean get() = eventMonitoringActive

    /** Whether the session's `messagesWaiting` drain is enabled. Mirrors iOS `isAutoFetching`. */
    val isAutoFetching: Boolean get() = autoFetchEnabled

    private val syncCoordinator: ContactSyncCoordinator =
        CallbackContactSyncCoordinator(onContactsChanged, onConversationsChanged)

    // Session adapters bridge the live session to the narrow chat/contact/ingest/channel seams the services take.
    private val chatSession = ChatSessionAdapter(session) { this.selfPublicKey }
    private val ingestSession = IngestSessionAdapter(session)
    private val contactSession = ContactSessionAdapter(session)
    private val channelSession = ChannelSessionAdapter(session)

    /** The unified Room persistence adapter backing the message-send, ingest, and thread-read seams. */
    val chatStore: RoomChatStore =
        RoomChatStore(
            radioId,
            database.chatDaos(),
            RxLogCorrelator(
                database.rxLogEntryDao(),
                regionNames = {
                    database.deviceDao().listByRadio(radioId).firstOrNull()?.knownRegions.orEmpty()
                },
            ),
            newContactId,
            discoveredNodes = discoveredNodes,
        )

    internal val contactStore =
        RoomContactStore(database, newContactId)

    private val blockedChannelSenderStore = RoomBlockedChannelSenderStore(database.blockedChannelSenderDao())

    /** The name-based blocked-sender cache the ingest pipeline consults; refreshed on connect and block. */
    val blockedSenders: BlockedSendersCache = BlockedSendersCache()

    internal val blockedNamesRefresher = ContactBlockedNamesRefresher(
        contactStore = contactStore,
        blockedSenderStore = blockedChannelSenderStore,
        cache = BlockedNamesCache { names -> blockedSenders.refresh(names) },
        logger = syncLogger,
    )

    private val channelMessageDeleter = ChannelMessageDeleter(
        RoomChannelMessageDeletionStore(
            messageDao = database.messageDao(),
            pendingSendDao = database.pendingSendDao(),
            reactionDao = database.reactionDao(),
            messageRepeatDao = database.messageRepeatDao(),
        ),
    )

    // Remote-node graph starts early so contact delete/block cleanup can tear down sessions (iOS
    // ContactCleanupCoordinator). One Room-backed store backs node-login and room seams.
    private val nodeSession = NodeSessionAdapter(session) { nowEpochSeconds().toLong() }
    private val roomServerSession = RoomServerSessionAdapter(session)

    /** The shared Room-backed session/message store backing both the node-login and room seams. */
    val nodeStore: RoomNodeStore = RoomNodeStore(
        radioId = radioId,
        sessionDao = database.remoteNodeSessionDao(),
        roomMessageDao = database.roomMessageDao(),
        contactDao = database.contactDao(),
        now = nowEpochSeconds,
    )

    /** Remote node login/keep-alive; Tools CLI remote login binds here. */
    val remoteNodeService: RemoteNodeService =
        RemoteNodeService(nodeSession, nodeStore, nodePasswordStore, scope)

    /**
     * Block/unblock/delete side effects: channel-message purge, blocked-name cache, list invalidation,
     * delivered-notification clear, badge refresh, and remote-node session teardown on delete.
     */
    private val contactCleanupCoordinator = ContactCleanupCoordinator(
        contactStore = contactStore,
        channelMessageDeleter = channelMessageDeleter,
        blockedNamesRefresher = blockedNamesRefresher,
        syncCoordinator = syncCoordinator,
        notifications = NotifierContactNotificationCleanup(notifier),
        remoteNodes = RemoteNodeServiceSessionCleanup(nodeStore, remoteNodeService),
    )

    /**
     * Held by connect/resync for the contacts-channels-backlog window so an advert delta
     * returns [AdvertContactSyncOutcome.BUSY] instead of starting a second GET_CONTACTS.
     */
    val advertContactSyncClaim = AdvertContactSyncClaim()

    /** Manages mesh contacts (sync, add/remove, favorites) for this radio. */
    val contactService: ContactService =
        ContactService(
            contactSession,
            contactStore,
            now = { nowEpochSeconds().toLong() },
            collaborators = contactCollaborators(
                syncCoordinator,
                contactCleanupCoordinator,
                contactDiscoveryNotifier,
                database,
                discoveredNodes,
            ),
            advert = contactAdvert(scope, advertContactSyncClaim, isInForeground),
            edges = contactEdges(database, scope),
        )

    /**
     * Name-based channel-sender blocks (persist + delete that sender's channel messages + refresh cache).
     * The block-sender sheet and blocked-senders settings bind here.
     */
    val blockedChannelSenderService: BlockedChannelSenderService = BlockedChannelSenderService(
        store = blockedChannelSenderStore,
        channelMessageDeleter = channelMessageDeleter,
        blockedNamesRefresher = blockedNamesRefresher,
        syncCoordinator = syncCoordinator,
        now = { nowEpochSeconds().toLong() },
        newId = newMessageId,
    )

    /** Manages channels (sync, create/join, delete, info) for this radio; the channel flows bind to it. */
    val channelService: ChannelService =
        ChannelService(
            channelSession,
            RoomChannelStore(database),
            rxCache = rxChannelCache,
            slotOccupantChanged = slotOccupantChanged,
            logger = PersistentLogger(scope, category = "ChannelService"),
        )

    /** Admin CLI request/response; Tools CLI remote passthrough and Node CLI bind here. */
    val nodeCliCoordinator: NodeCliCoordinator =
        NodeCliCoordinator(
            nodeSession,
            nodeStore,
            nodePasswordStore,
            scope,
            logger = PersistentLogger(scope, category = "RemoteNode"),
        )

    /** Repeater admin (auth, status/telemetry/owner-info, CLI, neighbours); the node screens bind to it. */
    val repeaterAdminService: RepeaterAdminService =
        RepeaterAdminService(
            remoteNodeService,
            nodeCliCoordinator,
            nodeSession,
            nodeStore,
            nodePasswordStore,
            edges = RepeaterAdminEdges(logger = PersistentLogger(scope, category = "RepeaterAdmin")),
        )

    /** Throttled node status/telemetry/neighbour snapshot capture (Room-backed). */
    val nodeSnapshotService: one.meshcore.shared.remotenode.NodeSnapshotService =
        one.meshcore.shared.remotenode.NodeSnapshotService(
            one.meshcore.shared.persistence.RoomNodeSnapshotStore(database.nodeStatusSnapshotDao()),
        )

    /** Room-server interactions (join/leave, posting, history sync); the room auth + chat surfaces bind here. */
    val roomServerService: RoomServerService = RoomServerService(
        remoteNodeService,
        nodeStore,
        roomServerSession,
        nodePasswordStore,
        scope,
        edges = RoomServerEdges(config = messageServiceConfig),
    )

    /**
     * Room admin (status/telemetry/CLI over the shared coordinator); the room settings/status screens bind
     * here. The coordinator is passed twice on purpose: once to send, once to observe every inbound CLI
     * reply, so a reply that lands after its request timed out still fills the field it was asked for.
     */
    val roomAdminService: RoomAdminService =
        RoomAdminService(
            RoomAdminSessionAdapter(session, nodeCliCoordinator, nodeStore),
            nodeStore,
            nodeCliCoordinator,
            logger = PersistentLogger(scope, category = "RoomAdmin"),
        )

    /**
     * Local-radio config export/import (node settings backup). The session adapter folds device writes with
     * channel/contact persistence. [NodeConfigService.onPostIdentityImport] rewrites the Device public key
     * (and adopts a ghost radioID when present) so channel/contact rows stay on the correct partition.
     */
    val nodeConfigService: NodeConfigService = NodeConfigService(
        session = NodeConfigSessionAdapter(session, channelService, contactStore),
        onContactsChanged = onContactsChanged,
    ).also { service ->
        service.onPostIdentityImport = {
            reconcileAfterIdentityImport(currentDeviceId = null)
        }
    }

    private val identityReconcile = IdentityReconcile(this)

    /**
     * After a private-key import, read `selfInfo` and reconcile the local Device row so `publicKey`
     * matches the radio while [radioId] (or a restored ghost partition) remains the data key.
     *
     * @param currentDeviceId live BLE/WiFi row id when known; when null, matches by [radioId].
     */
    suspend fun reconcileAfterIdentityImport(currentDeviceId: Uuid?): Uuid {
        val info = readSelfInfoForReconcile(
            getSelfInfo = { session.getSelfInfo() },
            logger = connectionLogger,
        ) ?: return radioId
        val reconciled = identityReconcile.apply(info, currentDeviceId) ?: return radioId
        return reconciled
    }

    /**
     * The conversation-list read/write seam the list view model binds to. Leaving a room is wrapped so it
     * runs the room-server logout and the contact removal, not just the local row write.
     */
    internal val conversationListBase = RoomConversationListStore(
        radioID = radioId,
        daos = database.conversationDaos(),
    )

    val conversationListStore: ConversationListStore =
        RoomLeavingConversationListStore(
            base = conversationListBase,
            roomServerService = roomServerService,
            removeContact = { radioID, publicKey -> contactService.removeContact(radioID, publicKey) },
            refreshBadge = { notifier.updateBadge() },
        )

    /** The DM/channel send-and-ack service; the send queue drives it. */
    val messageService: MessageService =
        MessageService(
            chatSession,
            chatStore,
            scope,
            MessageServiceIdentity(nowEpochSeconds, newMessageId),
            messageServiceConfig,
        )

    /** Room-backed reaction rows and message-hash lookups for react send/receive. */
    val reactionStore: ReactionStore = RoomReactionStore(
        reactionDao = database.reactionDao(),
        messageDao = database.messageDao(),
        contactDao = database.contactDao(),
        channelDao = database.channelDao(),
    )

    /** Outgoing reaction send + optimistic persist; UI binds through chat environment. */
    val reactionService: ReactionService = ReactionService(
        newReactionId = newMessageId,
        now = nowEpochSeconds,
    )

    /** Routes reaction wire text through [messageService] as ordinary channel/DM sends. */
    val reactionSender: MessageServiceReactionSender = MessageServiceReactionSender { messageService }

    /**
     * Inbound reaction wire text is consumed by [MessageIngestPipeline] so reaction messages do not
     * become chat rows. Summary UI refresh is coordinator-driven; ingest only needs a sink no-op here.
     */
    private val reactionIngestHook: ReactionHandler =
        ReactionHandler(
            reactionService = reactionService,
            store = reactionStore,
            sink = ReactionSummarySink { _, _ -> },
            newReactionId = newMessageId,
            now = nowEpochSeconds,
            edges = ReactionHandlerEdges(
                unknownSenderLabel = unknownSenderLabel,
                notifier = reactionNotifier,
                localNodeName = { selfNodeName },
            ),
        )

    private val sendQueueStore =
        DefaultSendQueueStore(RoomPendingSendStore(database.pendingSendDao()), chatStore) { id ->
            contactStore.fetchContact(id)
        }

    // iOS `ChannelService.fetchChannel(index:)` reads the radio. A Room row that
    // outlived a deleted slot must not keep the drain parked as pool exhaustion.
    private val channelResolver = ChannelResolver { index ->
        channelService.fetchChannel(index) != null
    }

    /** The durable, restart-surviving DM/channel send queue for this connection. */
    val sendQueue: ChatSendQueueService =
        ChatSendQueueService(
            radioID = radioId,
            collaborators = ChatSendQueueCollaborators(
                store = sendQueueStore,
                messageService = messageService,
                channelResolver = channelResolver,
                scope = scope,
                now = nowEpochSeconds,
                newPendingSendId = newPendingSendId,
            ),
            edges = ChatSendQueueEdges(
                reactionIndexer = ChannelReactionIndexer { id, channelIndex, senderName, text, timestamp ->
                    reactionService.indexMessage(id, channelIndex, senderName, text, timestamp)
                },
                config = sendQueueConfig,
            ),
        )

    /** Multicasts received-message events to every open thread bound to this connection. */
    val incomingBroadcaster: IncomingMessageBroadcaster = IncomingMessageBroadcaster()

    private val ingestPipeline =
        MessageIngestPipeline(
            collaborators = MessageIngestCollaborators(
                radioID = radioId,
                store = chatStore,
                blockedSenders = blockedSenders,
                broadcaster = incomingBroadcaster,
                selfNodeName = { selfNodeName },
                now = nowEpochSeconds,
                newMessageId = newMessageId,
            ),
            edges = MessageIngestEdges(
                reactionHook = reactionIngestHook,
                notifier = notifier,
                activeConversation = activeConversation,
                logger = syncLogger,
                onDuplicateChannelPath = { dto ->
                    val pathNodes = dto.pathNodes
                    val deduplicationKey = dto.deduplicationKey
                    if (pathNodes != null && deduplicationKey != null) {
                        heardRepeatsService.recordIncomingPath(
                            deduplicationKey,
                            one.meshcore.shared.chat.pathdetail.IncomingPathObservation(
                                pathNodes = pathNodes,
                                pathLength = dto.pathLength,
                                snr = dto.snr,
                                rssi = null,
                                receivedAt = dto.createdAt,
                            ),
                        )
                    }
                },
                onIncomingChannelSaved = { dto ->
                    val candidates = rxLogStore?.list(radioId).orEmpty()
                        .map(HeardRepeatEcho::fromRxLogEntry)
                    heardRepeatsService.harvestIncomingPaths(dto, candidates)
                },
            ),
        )

    internal val messagePoller =
        MessagePoller(
            radioID = radioId,
            session = ingestSession,
            store = chatStore,
            pipeline = ingestPipeline,
            scope = scope,
            edges = MessagePollerEdges(
                now = nowEpochSeconds,
                // signedPlain room posts: author is the first 4 bytes of signature (iOS SyncCoordinator).
                signedMessageHandler = { message, _ ->
                    ingestSignedRoomMessage(
                        message = message,
                        logger = syncLogger,
                        handle = { authorPrefix ->
                            roomServerService.handleIncomingMessage(
                                senderPublicKeyPrefix = message.senderPublicKeyPrefix,
                                timestamp = message.senderTimestamp,
                                authorPrefix = authorPrefix,
                                text = message.text,
                            )
                        },
                        onSaved = { saved ->
                            val roomSession = roomServerService.fetchSession(saved.sessionID)
                            notifier.postRoomMessage(
                                RoomMessageNotice(
                                    roomName = roomSession?.name ?: roomNameFallback(),
                                    sessionID = saved.sessionID,
                                    senderName = saved.authorName,
                                    text = saved.text,
                                    messageID = saved.id,
                                    notificationLevel = roomSession?.notificationLevel ?: NotificationLevel.ALL,
                                ),
                            )
                            notifier.updateBadge()
                            notifier.notifyConversationsChanged()
                        },
                    )
                },
                // Backlog-only. Live CLI is collected by NodeCliCoordinator from MeshEvent; getMessage
                // never emits that event, so pollAllMessages must fan out here (iOS handleIncomingCLIMessage).
                cliMessageHandler = { message, resolved ->
                    handleIncomingCliMessage(
                        message = message,
                        contact = resolved?.let { contactStore.fetchContact(it.id) },
                        roomAdmin = roomAdminService,
                        repeaterAdmin = repeaterAdminService,
                        logger = syncLogger,
                    )
                },
                materializePendingContact = { prefix ->
                    contactService.materializeContactForPendingAdvert(
                        matchingPrefix = prefix,
                        radioID = radioId,
                    )?.let { dto ->
                        ResolvedContact(
                            id = dto.id,
                            displayName = dto.displayName,
                            isBlocked = dto.isBlocked,
                            isMuted = dto.isMuted,
                        )
                    }
                },
                logger = PersistentLogger(scope, category = "MessagePoller"),
            ),
        )

    internal val adoptedDmNotificationCollector = AdoptedDmNotificationCollector(
        events = { contactService.events() },
        contacts = contactStore,
        database = database,
        notifier = notifier,
        scope = scope,
    )
    internal val contactPathCollector = ContactPathEventCollector(radioId, contactService, scope)
    internal val nodeStorageCollector = NodeStorageEventCollector(radioId, contactService, scope)
    internal val roomSessionConversationCollector = RoomSessionConversationCollector(
        remoteNodeEvents = remoteNodeService.events,
        roomServerEvents = roomServerService.events,
        scope = scope,
        onConversationsChanged = onConversationsChanged,
    )
    internal val messageFailedConversationCollector = MessageFailedConversationCollector(
        statusEvents = liveMessageStatusEvents { messageService },
        scope = scope,
        onConversationsChanged = onConversationsChanged,
    )

    /**
     * Stamps a flood region and hop path resolved after the fact onto the messages they belong to. The
     * [RxLogEventCollector] calls this on live append and on the rows a late secret re-derived, which
     * is the only point at which an already-stored message can learn its region or hops.
     */
    val messageRegionBackfill = MessageRegionBackfill(database.messageDao())

    internal suspend fun backfillMessagesFromRxLog(entries: List<RxLogEntry>) {
        val touched = messageRegionBackfill.apply(entries)
        if (touched.isNotEmpty()) messageService.notifyMessagesRegionUpdated(touched)
    }

    /**
     * Correlates successfully decrypted group-text RX-log entries to sent echoes and distinct
     * incoming channel paths. [RxLogEventCollector] calls [processRxLogEntryForRepeats] after each append.
     */
    val heardRepeatsService: HeardRepeatsService = HeardRepeatsService(
        store = RoomHeardRepeatStore(database.messageDao(), database.messageRepeatDao()),
        newRepeatId = newMessageId,
        onRepeat = { event ->
            messageService.notifyHeardRepeatRecorded(event.messageID, event.count)
        },
        logger = PersistentLogger(scope, category = "HeardRepeatsService"),
    )

    internal val rxLogCollector: RxLogEventCollector? = rxLogStore?.let { store ->
        RxLogEventCollector(
            RxLogCollectorInputs(
                store = store,
                radioId = { radioId },
                nowEpochMs = { (nowEpochSeconds() * MILLIS_PER_SECOND).toLong() },
                decodeCache = rxChannelCache as? RxLogDecodeCache ?: RxLogDecodeCache(),
                onEntryAppended = { entry ->
                    liveSync.processRxLogEntryForRepeats(entry)
                    backfillMessagesFromRxLog(listOf(entry))
                },
                onRowsRederived = { entries ->
                    entries.forEach { liveSync.processRxLogEntryForRepeats(it) }
                    backfillMessagesFromRxLog(entries)
                },
                logger = PersistentLogger(scope, category = "RxLogService"),
            ),
        )
    }

    internal val discoveredNodeCollector: DiscoveredNodeEventCollector? = discoveredNodes?.let { store ->
        DiscoveredNodeEventCollector(
            DiscoveredNodeCollectorInputs(
                store = store,
                radioId = { radioId },
                lookupLocalContact = { radioID, publicKey ->
                    contactService.getContact(radioID, publicKey)
                },
                nowEpochSeconds = { nowEpochSeconds().toLong() },
                discoveryNotifier = contactDiscoveryNotifier,
                shouldNotifyDiscovery = shouldNotifyDiscovery,
                logger = PersistentLogger(scope, category = "RxLogService"),
                discoverTrace = PersistentLogger(scope, category = "discover-trace"),
            ),
        )
    }

    /** Resolves the one coordinator shared by every UI surface bound to a conversation on this connection. */
    val coordinatorRegistry: ChatCoordinatorRegistry = ChatCoordinatorRegistry(chatStore, scope)

    /**
     * Re-primes a closed conversation's warm coordinator when a message arrives, matching iOS
     * `ChatPrewarmRefresher`. Open threads already fold arrivals in place.
     */
    internal val chatPrewarmRefresher = ChatPrewarmRefresher(
        hooks = ChatPrewarmRefresher.Hooks(
            registry = { threadCoordinatorRegistry ?: coordinatorRegistry },
            store = { threadChatStore ?: chatStore },
            isConversationActive = { id ->
                when (val key = id.conversation) {
                    is ChatConversationID.ConversationKey.Dm ->
                        activeConversation.activeContactID() == key.contactID
                    is ChatConversationID.ConversationKey.Channel -> {
                        val open = activeConversation.activeChannel()
                        open != null && open.radioID == id.radioID && open.index == key.channelIndex
                    }
                }
            },
            conversation = { id -> conversationForPrime(id) },
            loadContacts = { radio ->
                one.meshcore.shared.chat.pathdetail.loadContacts(contactService, radio)
            },
            indexPage = { conversation, page -> indexPrimePage(conversation, page) },
            warmPreviews = warmConversationPreviews,
            envInputs = chatEnvInputs,
            unknownLabel = unknownSenderLabel,
        ),
        scope = scope,
    )

    /**
     * Indexes a primed page so queued reactions find their target before the next open.
     * Mirrors iOS `ChatTimeline.open` calling `indexMessagesForReactions` after populate.
     */
    suspend fun indexPrimePage(conversation: Conversation, page: List<MessageDto>) {
        val indexScope = reactionIndexScopeFor(conversation, selfNodeName) ?: return
        reactionIngestHook.indexMessages(page, indexScope)
    }

    private suspend fun conversationForPrime(id: ChatConversationID): Conversation? = when (val key = id.conversation) {
        is ChatConversationID.ConversationKey.Dm ->
            contactService.getContactByID(key.contactID)?.let { Conversation.Direct(it) }
        is ChatConversationID.ConversationKey.Channel ->
            channelService.getChannel(id.radioID, key.channelIndex)?.let { Conversation.Channel(it) }
    }

    /**
     * Starts the live pipeline: ACK monitor, expiry checker, live-message monitor, RX-log persist
     * and heard-repeats, 0x8A / inbound-advert hops, advertisement contact auto-add / path refresh,
     * node-storage full/delete pushes, one-year node-status snapshot prune, and the send queue's
     * connection-state observation plus warm-up/hydrate so a restart replays parked sends. Backlog drain
     * (`getMessage`) is driven by the caller after contact/channel sync (see [ConnectionManager]
     * finishConnection), and [startAutoMessageFetching] only after that drain returns.
     */
    suspend fun start(connectionStates: Flow<DeviceConnectionState>, initialState: DeviceConnectionState) =
        containerStartup.start(connectionStates, initialState)

    /**
     * Tears the container down: stops every per-connection collector and drain so nothing outlives the
     * link. The process-lifetime chat coordinator registry is not cleared here (iOS keeps it across
     * reconnect). The session itself is stopped/closed by [ConnectionManager], which owns its lifetime.
     *
     * In-flight DM ACK tracking is **not** failed here — only the expiry checker is stopped
     * ([MessageService.stopAckExpiryChecking]), matching iOS `ServiceContainer.stopEventMonitoring`.
     * Firmware re-emits delivery ACKs after reconnect; a routine BLE cycle must not mark those DMs
     * `.failed`. Use [MessageService.stopAndFailAllPending] only for an explicit terminal teardown.
     */
    suspend fun tearDown() = containerTeardown.tearDown()

    /**
     * Already-attributed RX-log rows do not re-fire [onRowsRederived], so a get_msg-first bubble
     * saved without hops stays on Flood across reconnect unless this pass stamps them from disk.
     */
    internal suspend fun startRxLogCatchUp() = containerStartup.startRxLogCatchUp()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}

/**
 * A connect-time history prune. A Room fault is ignored so ACK and RX-log monitors still start.
 */
internal suspend fun runConnectPrune(prune: suspend () -> Unit) {
    try {
        prune()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A Room prune fault must not block ACK / RX-log monitors.
    }
}

private fun contactCollaborators(
    syncCoordinator: ContactSyncCoordinator,
    cleanupCoordinator: ContactCleanupCoordinator,
    discoveryNotifier: ContactDiscoveryNotifier,
    database: MeshDatabase,
    discoveredNodes: DiscoveredNodeStore?,
) = ContactServiceCollaborators(
    syncCoordinator = syncCoordinator,
    cleanupCoordinator = cleanupCoordinator,
    discoveryNotifier = discoveryNotifier,
    orphanDmAdopter = RoomOrphanDmAdopter(database),
    discoveredNodes = discoveredNodes,
)

private fun contactAdvert(
    scope: CoroutineScope,
    advertSyncClaim: AdvertContactSyncClaim,
    isInForeground: suspend () -> Boolean,
) = ContactServiceAdvert(
    deltaScope = scope,
    advertSyncClaim = advertSyncClaim,
    isInForeground = isInForeground,
)

private fun contactEdges(database: MeshDatabase, scope: CoroutineScope) = ContactServiceEdges(
    selfPublicKey = { radioID -> database.deviceDao().listByRadio(radioID).firstOrNull()?.publicKey },
    logger = PersistentLogger(scope, category = "AdvertisementService"),
    discoverTrace = PersistentLogger(scope, category = "discover-trace"),
)
