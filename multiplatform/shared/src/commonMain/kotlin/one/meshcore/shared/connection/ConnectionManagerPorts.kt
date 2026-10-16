package one.meshcore.shared.connection

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
import one.meshcore.shared.identity.DeviceIdentity
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.settings.AppliedRadioPresetStore
import one.meshcore.shared.tools.RxLogStore
import one.meshcore.shared.transport.MeshLink
import kotlin.time.Duration

/**
 * Optional [ConnectionManager] dependencies. The six lifecycle clocks stay on the manager
 * constructor; everything a host can leave at its default lives here so the constructor
 * stays a short parameter list. Defaults match the previous constructor defaults.
 */
data class ConnectionManagerPorts(
    val policy: ReconnectPolicy = ReconnectPolicy(),
    val wifiPolicy: WiFiReconnectPolicy = WiFiReconnectPolicy(),
    /** The post-connect resync schedule (interval + attempt budget) the sync-retry loop runs on. */
    val resyncPolicy: ResyncPolicy = ResyncPolicy(),
    /** The backoff and attempt budget the channel-only retry re-reads unread slots on. */
    val channelRetryPolicy: ChannelRetryPolicy = ChannelRetryPolicy(),
    /** Inner GATT/handshake retries for one BLE connect call (iOS `connectWithRetry`). */
    val connectRetryPolicy: ConnectRetryPolicy = ConnectRetryPolicy(),
    /**
     * BLE link factory for the auto-reconnect loop. iOS uses a 15s discovery timeout there
     * because a bonded reconnect does not wait on a pairing dialog. Null uses the manager's
     * [ConnectionManager.linkFactory].
     */
    val reconnectLinkFactory: ((deviceId: String) -> MeshLink)? = null,
    /**
     * Outer wall on session start / queryDevice, matching Swift `initializeSession`
     * `withTimeout(.seconds(10))`. Inner bridge commands are 5s; this fence covers a native
     * hang that never completes the Deferred.
     */
    val handshakeTimeout: Duration = HANDSHAKE_COMMAND_TIMEOUT,
    /** Live adapter power. Default on so JVM tests stay radio-free. */
    val bluetoothAdapterEnabled: () -> Boolean = { true },
    /** Live authorization for BLE connections; saved handles do not require scan permission. */
    val bluetoothConnectAuthorized: () -> Boolean = { true },
    /**
     * True when the platform has a system pairing registry. Hosts without one stay false so a
     * user tap uses the bounded unverified budget.
     */
    val hasSystemPairingRegistry: () -> Boolean = { false },
    /** Handles currently on this phone's GATT stack. Empty on desktop and in JVM tests. */
    val systemConnectedHandles: () -> Set<String> = { emptySet() },
    val wifiLinkFactory: (host: String, port: Int, heartbeat: suspend () -> Unit) -> MeshLink =
        { _, _, _ -> error("no wifiLinkFactory configured") },
    val lastConnectionStore: LastConnectionStore? = null,
    val appliedRadioPresetStore: AppliedRadioPresetStore? = null,
    val intentStore: ConnectionIntentStore? = null,
    val deriveWifiDeviceId: (ByteArray) -> Uuid = DeviceIdentity::deriveUuid,
    val newRadioId: () -> Uuid = ::randomRadioId,
    /** App-lifetime Discover store so on-air lastHeard dual-writes the UI snapshot. */
    val discoveredNodes: one.meshcore.shared.contact.DiscoveredNodeStore? = null,
    /** Process-lifetime RX-log store so connect-time ingest writes the Tools list snapshot. */
    val rxLogStore: RxLogStore? = null,
    /** Process-lifetime debug log pruned on connect, before sync reaches Ready. */
    val debugLogStore: one.meshcore.shared.settings.DebugLogStore? = null,
    /**
     * False during the connect-sync window so a first-seen discovery event does not spam banners.
     * Production passes the notification coordinator's suppression flag.
     */
    val shouldNotifyDiscovery: () -> Boolean = { true },
    /** Process-lifetime thread registry so closed-chat prewarm matches the open thread after reconnect. */
    val threadCoordinatorRegistry: (Uuid) -> ChatCoordinatorRegistry? = { null },
    val threadChatStore: (Uuid) -> ChatThreadStore? = { null },
    /** Arrival-time prime preview warm for a conversation that is not on screen. */
    val warmConversationPreviews: suspend (Conversation) -> Unit = {},
    /** Live chat bake environment so an arrival-time prime matches the first open frame. */
    val chatEnvInputs: suspend () -> EnvInputs = { EnvInputs.DEFAULT },
    val unknownSenderLabel: suspend () -> String = { "" },
    val roomNameFallback: suspend () -> String = { "" },
    val notifier: IngestNotifier = IngestNotifier.NoOp,
    val reactionNotifier: ReactionNotifier = ReactionNotifier.NoOp,
    val contactDiscoveryNotifier: ContactDiscoveryNotifier = ContactDiscoveryNotifier.NoOp,
    val activeConversation: ActiveConversation = ActiveConversation.None,
    val messageServiceConfig: MessageServiceConfig = MessageServiceConfig(),
    val sendQueueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    /** App-lifetime encrypted node-password store, shared across connections. */
    val nodePasswordStore: NodePasswordStore = InMemoryNodePasswordStore(),
    /** RX-log channel secret sink. */
    val rxChannelCache: ChannelListSink = NoOpChannelListSink,
    /**
     * Slot-keyed occupant-changed handler, installed on each service container at construction so a
     * connect-time prune cannot race a host rebind.
     */
    val slotOccupantChanged: ChannelSlotOccupantChanged = NoOpChannelSlotOccupantChanged,
    /**
     * Status-pill flag holder the resync loop reports sync failure and recovery to. Null only in
     * tests that do not observe the pill.
     */
    val connectionUi: ConnectionUiState? = null,
    /** App-level contact list invalidation. */
    val onContactsChanged: suspend () -> Unit = {},
    /** App-level chats list invalidation after block, delete, or ingest. */
    val onConversationsChanged: suspend () -> Unit = {},
)
