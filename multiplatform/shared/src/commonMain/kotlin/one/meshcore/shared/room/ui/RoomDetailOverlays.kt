package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.ui.NodeAuthViewModel
import one.meshcore.shared.remotenode.ui.NodeAuthenticationSheet
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.remotenode.ui.rememberNodeAuthErrorText
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_room_auth_not_found_description
import one.meshcore.shared.resources.chats_room_auth_not_found_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_settings_title
import one.meshcore.shared.resources.remotenodes_remote_nodes_room_status_title
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomMessageAction
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.authorDisplayName
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PlaceholderPane
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RoomStatusOverlay(
    session: RemoteNodeSessionDto,
    roomAdminService: () -> RoomAdminService?,
    nodeSnapshotService: () -> NodeSnapshotService?,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    isRadioReady: Boolean,
    locationService: one.meshcore.shared.map.LocationService?,
    mapDisplayPreferences: one.meshcore.shared.map.MapDisplayPreferencesStore? = null,
    onDismiss: () -> Unit,
    workspaces: RemoteAdminWorkspaces? = null,
) {
    val scope = rememberCoroutineScope()
    val latestIsRadioReady = rememberUpdatedState(isRadioReady)
    val currentRoomAdminService by rememberUpdatedState(roomAdminService)
    val currentNodeSnapshotService by rememberUpdatedState(nodeSnapshotService)
    val currentContactService by rememberUpdatedState(contactService)
    val currentRadioId by rememberUpdatedState(radioId)
    val viewModel =
        workspaces?.family(session)?.roomStatus()
            ?: remember(session.id, session.radioID, session.publicKey.contentHashCode()) {
                RoomStatusViewModel(
                    scope = scope,
                    session = session,
                    dependencies = RoomStatusViewModel.Dependencies(
                        roomAdminService = { currentRoomAdminService() },
                        nodeSnapshotService = { currentNodeSnapshotService() },
                        contactService = { currentContactService() },
                        radioId = { currentRadioId() },
                        isRadioConnected = { latestIsRadioReady.value },
                    ),
                )
            }
    DisposableEffect(viewModel) {
        onDispose { if (workspaces == null) viewModel.close() }
    }
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.remotenodes_remote_nodes_room_status_title),
    ) { innerPadding ->
        RoomStatusScreen(
            viewModel = viewModel,
            session = session,
            isRadioReady = isRadioReady,
            locationService = locationService,
            mapDisplayPreferences = mapDisplayPreferences,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
internal fun RoomSettingsOverlay(
    session: RemoteNodeSessionDto,
    roomAdminService: () -> RoomAdminService?,
    nodeSnapshotService: () -> NodeSnapshotService?,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    locationService: one.meshcore.shared.map.LocationService?,
    isRadioReady: Boolean,
    mapDisplayPreferences: one.meshcore.shared.map.MapDisplayPreferencesStore? = null,
    onDismiss: () -> Unit,
    workspaces: RemoteAdminWorkspaces? = null,
) {
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.remotenodes_remote_nodes_room_settings_title),
    ) { innerPadding ->
        RoomManagementSurface(
            workspaces = workspaces,
            session = session,
            roomAdminService = roomAdminService,
            locationService = locationService,
            isRadioReady = isRadioReady,
            isRadioConnected = { isRadioReady },
            nodeSnapshotService = nodeSnapshotService,
            contactService = contactService,
            radioId = radioId,
            mapDisplayPreferences = mapDisplayPreferences,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
internal fun RoomMessageActionOverlay(
    message: RoomMessageDto,
    session: RemoteNodeSessionDto,
    viewModel: RoomConversationViewModel,
    onSendDm: (String) -> Unit,
    replyWithQuote: () -> Boolean,
    displayedText: String,
    copyText: (String) -> Unit,
    onTranslate: () -> Unit,
    translateLabel: String,
    onDismiss: () -> Unit,
) {
    RoomMessageActionsSheet(
        message = message,
        session = session,
        replyWithQuote = replyWithQuote(),
        translateLabel = translateLabel,
        onAction = { action ->
            dispatchRoomMessageAction(
                action,
                RoomMessageActionContext(
                    message = message,
                    viewModel = viewModel,
                    copyText = copyText,
                    onSendDm = onSendDm,
                    replyWithQuote = replyWithQuote(),
                    displayedText = displayedText,
                    onTranslate = onTranslate,
                ),
            )
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

private fun dispatchRoomMessageAction(action: RoomMessageAction, context: RoomMessageActionContext) {
    val message = context.message
    val viewModel = context.viewModel
    when (action) {
        RoomMessageAction.COPY -> context.copyText(context.displayedText)
        RoomMessageAction.TRANSLATE -> context.onTranslate()
        RoomMessageAction.REPLY -> {
            val name = message.authorDisplayName
            if (name.isNotEmpty()) {
                viewModel.setComposingText(
                    roomReplyText(name, message.text, viewModel.state.value.composingText, context.replyWithQuote),
                )
                viewModel.requestComposerFocus()
            }
        }
        RoomMessageAction.SEND_AGAIN -> viewModel.sendText(message.text)
        RoomMessageAction.SEND_DM -> message.authorName?.let(context.onSendDm)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RoomAuthOverlay(
    contact: ContactDto?,
    roomServerService: () -> RoomServerService?,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    isRadioConnected: () -> Boolean = { true },
    nodePasswordStore: () -> NodePasswordStore?,
    discoveredNodeStore: () -> DiscoveredNodeStore?,
    userLocation: () -> UserLocation?,
    onAuthenticated: (RemoteNodeSessionDto) -> Unit,
    onDismiss: () -> Unit,
) {
    if (roomConversationAuthShowsNotFound(contact)) {
        FullScreenOverlay(
            onDismiss = onDismiss,
            title = stringResource(Res.string.chats_room_auth_not_found_title),
        ) { innerPadding ->
            PlaceholderPane(
                title = stringResource(Res.string.chats_room_auth_not_found_title),
                subtitle = stringResource(Res.string.chats_room_auth_not_found_description),
                icon = ROOM_CONVERSATION_AUTH_MISSING_ICON,
                modifier = Modifier.padding(innerPadding),
            )
        }
        return
    }
    val authContact = contact ?: return
    val scope = rememberCoroutineScope()
    val nodeErrorText = rememberNodeAuthErrorText()
    val currentNodeErrorText = rememberUpdatedState(nodeErrorText)
    val authViewModel = remember(
        authContact.id,
        authContact.radioID,
        authContact.publicKey.contentHashCode(),
    ) {
        NodeAuthViewModel(
            scope = scope,
            contact = authContact,
            role = RemoteNodeRole.ROOM_SERVER,
            dependencies = roomConversationAuthDependencies(
                RoomAuthSources(
                    roomServerService = roomServerService,
                    contactService = contactService,
                    radioId = radioId,
                    isRadioConnected = isRadioConnected,
                    nodePasswordStore = nodePasswordStore,
                    errorText = { error -> currentNodeErrorText.value(error) },
                    discoveredNodeStore = discoveredNodeStore,
                    userLocation = userLocation,
                ),
            ),
        )
    }
    DisposableEffect(authViewModel) {
        onDispose(authViewModel::close)
    }
    FullScreenOverlay(onDismiss = onDismiss) {
        NodeAuthenticationSheet(
            viewModel = authViewModel,
            contact = authContact,
            role = RemoteNodeRole.ROOM_SERVER,
            hideNodeDetails = true,
            onSuccess = onAuthenticated,
            onDismiss = onDismiss,
        )
    }
}

internal data class RoomAuthSources(
    val roomServerService: () -> RoomServerService?,
    val contactService: () -> ContactService?,
    val radioId: () -> Uuid?,
    val isRadioConnected: () -> Boolean,
    val nodePasswordStore: () -> NodePasswordStore?,
    val errorText: (Throwable) -> String,
    val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
    val userLocation: () -> UserLocation? = { null },
)

internal fun roomConversationAuthDependencies(sources: RoomAuthSources): NodeAuthViewModel.Dependencies =
    NodeAuthViewModel.Dependencies(
        repeaterAdminService = { null },
        roomServerService = sources.roomServerService,
        passwordStore = sources.nodePasswordStore,
        contactService = sources.contactService,
        radioId = sources.radioId,
        isRadioConnected = sources.isRadioConnected,
        discoveredNodeStore = sources.discoveredNodeStore,
        errorText = sources.errorText,
        userLocation = sources.userLocation,
    )
