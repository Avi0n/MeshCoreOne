package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.translation.MessageTranslationPhase
import one.meshcore.shared.chat.translation.seedIncomingTranslations
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.isAdmin
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_show_original
import one.meshcore.shared.resources.chats_message_action_translate
import one.meshcore.shared.resources.chats_message_action_translating
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_info_title
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.ui.FullScreenOverlay
import org.jetbrains.compose.resources.stringResource

/** What the room translate action says for the phase already on the message. */
internal enum class RoomTranslateActionLabel {
    TRANSLATING,
    SHOW_ORIGINAL,
    TRANSLATE,
}

/**
 * In-progress speech is the progress label. A visible translation offers the original.
 * Every other phase, including no phase, offers translate.
 */
internal fun roomTranslateActionLabel(phase: MessageTranslationPhase?): RoomTranslateActionLabel = when (phase) {
    MessageTranslationPhase.InProgress -> RoomTranslateActionLabel.TRANSLATING
    is MessageTranslationPhase.Showing -> RoomTranslateActionLabel.SHOW_ORIGINAL
    else -> RoomTranslateActionLabel.TRANSLATE
}

@Composable
internal fun roomTranslateActionText(phase: MessageTranslationPhase?): String = stringResource(
    when (roomTranslateActionLabel(phase)) {
        RoomTranslateActionLabel.TRANSLATING -> Res.string.chats_message_action_translating
        RoomTranslateActionLabel.SHOW_ORIGINAL -> Res.string.chats_message_action_show_original
        RoomTranslateActionLabel.TRANSLATE -> Res.string.chats_message_action_translate
    },
)

/**
 * Offers that are off seed nothing. Own posts and blank text are not translation sources.
 * Other people's text is seeded in list order.
 */
internal suspend fun seedRoomTranslations(
    offersEnabled: Boolean,
    messages: List<RoomMessageDto>,
    seed: suspend (Uuid, String) -> Unit,
) = seedIncomingTranslations(
    offersEnabled = offersEnabled,
    rows = messages,
    incoming = { message -> if (message.isFromSelf) null else message.id to message.text },
    seed = seed,
)

/** A connected room shows the permission. A disconnected room shows the disconnected line. */
internal fun roomDetailSubtitle(connected: Boolean, permissionLabel: String, disconnectedLabel: String): String =
    if (connected) permissionLabel else disconnectedLabel

/** A link activates only while no message actions sheet is covering the transcript. */
internal fun applyRoomLinkTap(messageSelected: Boolean, target: LinkTarget, open: (LinkTarget) -> Unit) {
    if (!messageSelected) open(target)
}

/**
 * Info stays until it is dismissed. Status and settings are the admin pages stacked on it, and settings
 * additionally requires an admin session.
 */
@Composable
internal fun RoomDetailInfoOverlays(
    infoHost: RoomInfoHostState,
    onInfoHostChange: (RoomInfoHostState) -> Unit,
    currentSession: () -> RemoteNodeSessionDto,
    conversationListStore: () -> ConversationListStore?,
    onConversationsChanged: suspend () -> Unit,
    updateBadge: suspend () -> Unit,
    connectionState: StateFlow<DeviceConnectionState>,
    roomAdminService: () -> RoomAdminService?,
    nodeSnapshotService: () -> NodeSnapshotService?,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    radioReady: Boolean,
    locationService: LocationService?,
    mapDisplayPreferences: MapDisplayPreferencesStore?,
    scope: CoroutineScope,
    workspaces: RemoteAdminWorkspaces? = null,
) {
    if (infoHost.showInfo) {
        val current = currentSession()
        val adminReady = current.isConnected && roomAdminService() != null
        FullScreenOverlay(
            onDismiss = { onInfoHostChange(infoHost.dismissInfo()) },
            title = stringResource(Res.string.remotenodes_remote_nodes_room_info_title),
        ) { padding ->
            RoomInfoSheet(
                session = current,
                onPinnedChange = { pin ->
                    checkNotNull(conversationListStore()).setSessionPinned(current.id, pin)
                    scope.launch { onConversationsChanged() }
                },
                onNotificationLevelChange = { level ->
                    conversationListStore()?.setSessionNotificationLevel(current.id, level)
                    scope.launch {
                        onConversationsChanged()
                        updateBadge()
                    }
                },
                isRadioReady = { connectionState.value == DeviceConnectionState.READY },
                onOpenStatus = {
                    onInfoHostChange(infoHost.openStatus())
                }.takeIf { adminReady },
                onOpenSettings = {
                    onInfoHostChange(infoHost.openSettings())
                }.takeIf { adminReady && current.isAdmin },
                modifier = Modifier.padding(padding),
            )
        }
    }
    if (infoHost.showStatus) {
        RoomStatusOverlay(
            workspaces = workspaces,
            session = currentSession(),
            roomAdminService = roomAdminService,
            nodeSnapshotService = nodeSnapshotService,
            contactService = contactService,
            radioId = radioId,
            isRadioReady = radioReady,
            locationService = locationService,
            mapDisplayPreferences = mapDisplayPreferences,
            onDismiss = { onInfoHostChange(infoHost.dismissStatus()) },
        )
    }
    if (infoHost.showSettings) {
        RoomSettingsOverlay(
            workspaces = workspaces,
            session = currentSession(),
            roomAdminService = roomAdminService,
            nodeSnapshotService = nodeSnapshotService,
            contactService = contactService,
            radioId = radioId,
            locationService = locationService,
            mapDisplayPreferences = mapDisplayPreferences,
            isRadioReady = radioReady,
            onDismiss = { onInfoHostChange(infoHost.dismissSettings()) },
        )
    }
}
