package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.channel.ChannelListSink
import one.meshcore.shared.channel.ChannelSlotOccupantChanged
import one.meshcore.shared.channel.NoOpChannelListSink
import one.meshcore.shared.channel.NoOpChannelSlotOccupantChanged
import one.meshcore.shared.chat.ActiveConversation
import one.meshcore.shared.chat.ChatCoordinatorRegistry
import one.meshcore.shared.chat.ChatSendQueueConfig
import one.meshcore.shared.chat.ChatThreadStore
import one.meshcore.shared.chat.Conversation
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.chat.IngestNotifier
import one.meshcore.shared.chat.MessageServiceConfig
import one.meshcore.shared.chat.reactions.ReactionNotifier
import one.meshcore.shared.contact.ContactDiscoveryNotifier
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.settings.DebugLogStore
import one.meshcore.shared.tools.RxLogStore

/** Connect-time radio partition, self identity, and table caps. */
data class ServiceContainerIdentity(
    val radioId: Uuid,
    val selfPublicKey: ByteArray,
    val selfNodeName: String,
    /** The connected radio's contact-table cap, surfaced to the add-contact and channel flows. */
    val maxContacts: Int = 0,
    /** The connected radio's channel-slot cap, gating the channel create/join options. */
    val maxChannels: Int = 0,
)

/** Scope, clocks, and id factories that live with one connection. */
data class ServiceContainerRuntime(
    val scope: CoroutineScope,
    /** App-lifetime encrypted credential store, shared across connections (the iOS keychain analogue). */
    val nodePasswordStore: NodePasswordStore,
    /** Last-connection persist so a ghost adopt updates the offline partition key. */
    val lastConnectionStore: LastConnectionStore? = null,
    val nowEpochSeconds: () -> Double,
    val newMessageId: () -> Uuid = Uuid::random,
    val newPendingSendId: () -> Uuid = Uuid::random,
    val newContactId: () -> Uuid = Uuid::random,
)

/** Banners and the conversation the user is looking at. Defaults keep isolated tests notifying. */
data class ServiceContainerNotifiers(
    val notifier: IngestNotifier = IngestNotifier.NoOp,
    val reactionNotifier: ReactionNotifier = ReactionNotifier.NoOp,
    val contactDiscoveryNotifier: ContactDiscoveryNotifier = ContactDiscoveryNotifier.NoOp,
    val activeConversation: ActiveConversation = ActiveConversation.None,
    /**
     * False during the connect-sync window so a first-seen 0x8A does not spam banners (iOS
     * `isSuppressingNotifications`). Defaults on so isolated tests still notify.
     */
    val shouldNotifyDiscovery: () -> Boolean = { true },
)

/** Message-service knobs and the process-lifetime thread registry a closed chat prewarms into. */
data class ServiceContainerChat(
    val messageServiceConfig: MessageServiceConfig = MessageServiceConfig(),
    val sendQueueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    /**
     * Process-lifetime thread registry/store so closed-chat prewarm writes the same coordinators
     * an open thread reads after reconnect. Null in tests that do not exercise prewarm.
     */
    val threadCoordinatorRegistry: ChatCoordinatorRegistry? = null,
    val threadChatStore: ChatThreadStore? = null,
    /** Arrival-time prime preview warm. Defaults no-op for tests that do not exercise previews. */
    val warmConversationPreviews: suspend (Conversation) -> Unit = {},
    val chatEnvInputs: suspend () -> EnvInputs = { EnvInputs.DEFAULT },
    val unknownSenderLabel: suspend () -> String = { "" },
    val roomNameFallback: suspend () -> String = { "" },
)

/** List invalidation, scene phase, and the process-lifetime stores [ServiceContainer.start] subscribes. */
data class ServiceContainerSync(
    /**
     * App-level list invalidation after contact/conversation mutations (iOS SyncCoordinator version
     * bumps). Defaults no-op for unit tests; AppContainer wires SharedFlow emitters.
     */
    val onContactsChanged: suspend () -> Unit = {},
    val onConversationsChanged: suspend () -> Unit = {},
    /**
     * Scene-phase gate for advert delta work. Defaults to foreground so isolated tests match iOS
     * when `AppStateProvider` is nil. Production passes [ConnectionManager] app-active.
     */
    val isInForeground: suspend () -> Boolean = { true },
    /**
     * RX-log decrypt cache. [ServiceContainer.start] exports the radio private key into it (iOS
     * SyncCoordinator after `startEventMonitoring`, before `runFullSync`). [NoOpChannelListSink] in
     * tests that do not exercise decrypt.
     */
    val rxChannelCache: ChannelListSink = NoOpChannelListSink,
    /**
     * Slot-keyed occupant-changed handler. iOS AppState installs this on the new container before
     * `runFullSync`; constructing with it here avoids a host rebind after Connected.
     */
    val slotOccupantChanged: ChannelSlotOccupantChanged = NoOpChannelSlotOccupantChanged,
    /** App-lifetime Discover store so on-air lastHeard dual-writes the same snapshot the UI observes. */
    val discoveredNodes: DiscoveredNodeStore? = null,
    /**
     * Process-lifetime RX-log store. When set, [ServiceContainer.start] subscribes to `MeshEvent.RxLogData`
     * on this session immediately (iOS `rxLogService.startEventMonitoring`), instead of waiting for a host
     * rebind of a replay-0 event flow.
     */
    val rxLogStore: RxLogStore? = null,
    /**
     * Process-lifetime debug log. When set, [ServiceContainer.start] runs the age-then-ceiling prune iOS
     * `startEventMonitoring` runs on `dataStore` before `.active`.
     */
    val debugLogStore: DebugLogStore? = null,
)

/** Optional notifiers, chat prewarm, and sync stores. Defaults leave every seam a no-op. */
data class ServiceContainerEdges(
    val notifiers: ServiceContainerNotifiers = ServiceContainerNotifiers(),
    val chat: ServiceContainerChat = ServiceContainerChat(),
    val sync: ServiceContainerSync = ServiceContainerSync(),
)
