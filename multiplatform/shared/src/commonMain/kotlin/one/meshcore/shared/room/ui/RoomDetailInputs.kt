package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomNotificationSink
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.settings.TranslationTargetPreferencesStore

private val idleConnection = MutableStateFlow(DeviceConnectionState.DISCONNECTED)

/** Host graph and actions for a pushed room conversation. */
internal data class RoomDetailInputs(
    val sessionId: String,
    val sessionSnapshot: RemoteNodeSessionDto? = null,
    val roomServerService: () -> RoomServerService?,
    val contactService: () -> ContactService?,
    val radioId: () -> Uuid?,
    val conversationListStore: () -> ConversationListStore?,
    val roomAdminService: () -> RoomAdminService?,
    val nodePasswordStore: () -> NodePasswordStore?,
    /**
     * Ticks whenever the per-connection service graph may have been replaced, so the conversation can
     * follow a reconnect onto the rebuilt container instead of collecting the dead one's event flow.
     */
    val servicesChanged: Flow<Any?>,
    /**
     * The app's foreground edge. Backgrounding stops every room keep-alive, and only the room on screen
     * restarts its own.
     */
    val appForeground: Flow<Unit>,
    val onBack: () -> Unit,
    val nodeSnapshotService: () -> NodeSnapshotService? = { null },
    /** Notification hooks for the room on screen; [RoomNotificationSink.NONE] leaves the tray untouched. */
    val roomNotifications: RoomNotificationSink = RoomNotificationSink.NONE,
    val onConversationsChanged: suspend () -> Unit = {},
    /** Refreshes the OS unread badge after room-info mute. */
    val updateBadge: suspend () -> Unit = {},
    val onOpenContactChat: (String) -> Unit = {},
    val onLinkTap: (LinkTarget) -> Unit = {},
    val localNodeName: String? = null,
    val locationService: LocationService? = null,
    /** Reply-with-quote chat preference; default false makes the default reply a bare @name mention. */
    val replyWithQuote: () -> Boolean = { false },
    val connectionState: StateFlow<DeviceConnectionState> = idleConnection,
    /** System Translation overlay when on-device translation fails. */
    val presentSystemTranslation: (String) -> Unit = {},
    val translationTargetPreferences: TranslationTargetPreferencesStore? = null,
    val mapDisplayPreferences: MapDisplayPreferencesStore? = null,
    val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
    val userLocation: () -> UserLocation? = { null },
    val remoteAdminWorkspaces: RemoteAdminWorkspaces? = null,
)
