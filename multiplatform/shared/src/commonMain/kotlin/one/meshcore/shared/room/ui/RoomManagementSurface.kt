package one.meshcore.shared.room.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.ui.LocalMapColorSchemePreference
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.NodeSnapshotService
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.isAdmin
import one.meshcore.shared.remotenode.ui.AdminManagementTab
import one.meshcore.shared.remotenode.ui.AdminManagementTabRow
import one.meshcore.shared.remotenode.ui.NodeCliScreen
import one.meshcore.shared.remotenode.ui.NodeCliViewModel
import one.meshcore.shared.remotenode.ui.RemoteAdminWorkspaces
import one.meshcore.shared.remotenode.ui.rememberNodeCliMessages
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomSettingsController
import one.meshcore.shared.theme.AppColorSchemePreference
import kotlin.time.Duration

/**
 * The room admin surface: settings, CLI, and telemetry, in the iOS `RoomSettingsView` segment order.
 * Tabs show only when [session] is an admin login; a guest sees the settings form alone.
 */
@Composable
fun RoomManagementSurface(
    session: RemoteNodeSessionDto,
    roomAdminService: () -> RoomAdminService?,
    modifier: Modifier = Modifier,
    locationService: LocationService? = null,
    isRadioReady: Boolean = true,
    isRadioConnected: () -> Boolean = { isRadioReady },
    nodeSnapshotService: () -> NodeSnapshotService? = { null },
    contactService: () -> ContactService? = { null },
    radioId: () -> Uuid? = { null },
    mapColorSchemePreference: AppColorSchemePreference = LocalMapColorSchemePreference.current,
    isOffline: Boolean = LocalMapOffline.current,
    userLocation: UserLocation? = null,
    mapDisplayPreferences: one.meshcore.shared.map.MapDisplayPreferencesStore? = null,
    workspaces: RemoteAdminWorkspaces? = null,
) {
    val scope = rememberCoroutineScope()
    val admin = roomAdminService()
    val latestIsRadioConnected = rememberUpdatedState(isRadioConnected)
    val currentRoomAdminService by rememberUpdatedState(roomAdminService)
    val currentNodeSnapshotService by rememberUpdatedState(nodeSnapshotService)
    val currentContactService by rememberUpdatedState(contactService)
    val currentRadioId by rememberUpdatedState(radioId)
    val sessionId = session.id
    val send: suspend (String, Duration) -> String = { command, timeout ->
        (currentRoomAdminService() ?: throw RemoteNodeError.NotConnected).sendCommand(sessionId, command, timeout)
    }
    val sendRaw: suspend (String, Duration) -> String = { command, timeout ->
        (currentRoomAdminService() ?: throw RemoteNodeError.NotConnected).sendRawCommand(sessionId, command, timeout)
    }
    val family = workspaces?.family(session)
    LaunchedEffect(workspaces, admin) { workspaces?.reattach() }
    var tab by remember(sessionId, session.radioID) { mutableStateOf(AdminManagementTab.SETTINGS) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (session.isAdmin) {
            AdminManagementTabRow(selected = tab, onSelect = { tab = it })
        }
        when (tab) {
            AdminManagementTab.SETTINGS -> {
                val settingsViewModel = family?.roomSettings() ?: remember(sessionId, session.radioID, admin) {
                    RoomSettingsViewModel(
                        scope = scope,
                        controller = RoomSettingsController(
                            helper = NodeSettingsController(send, sendRaw),
                            sendCommand = send,
                            sendRawCommand = sendRaw,
                        ),
                        roomAdminService = roomAdminService,
                    )
                }
                DisposableEffect(settingsViewModel) {
                    val mount = settingsViewModel.settingsShown()
                    onDispose {
                        settingsViewModel.settingsHidden(mount)
                        if (workspaces == null) settingsViewModel.cleanup()
                    }
                }
                RoomSettingsScreen(
                    viewModel = settingsViewModel,
                    locationService = locationService,
                    isRadioReady = isRadioReady,
                )
            }
            AdminManagementTab.CLI -> {
                val cliMessages = rememberNodeCliMessages()
                val currentCliMessages by rememberUpdatedState(cliMessages)
                val cliViewModel =
                    family?.cli(cliMessages) ?: remember(sessionId, session.radioID, session.name, admin) {
                        NodeCliViewModel(
                            scope = scope,
                            sessionName = session.name,
                            messages = { currentCliMessages },
                            dependencies = NodeCliViewModel.Dependencies(
                                repeaterAdminService = { null },
                                sessionId = { sessionId },
                                sendRawCommand = sendRaw,
                            ),
                        )
                    }
                DisposableEffect(cliViewModel) {
                    onDispose { if (workspaces == null) cliViewModel.close() }
                }
                LaunchedEffect(cliViewModel, cliMessages) { cliViewModel.refreshLocalizedCopy() }
                NodeCliScreen(viewModel = cliViewModel)
            }
            AdminManagementTab.TELEMETRY -> {
                val statusViewModel =
                    family?.roomStatus() ?: remember(session.id, session.radioID, session.publicKey.contentHashCode()) {
                        RoomStatusViewModel(
                            scope = scope,
                            session = session,
                            dependencies = RoomStatusViewModel.Dependencies(
                                roomAdminService = { currentRoomAdminService() },
                                nodeSnapshotService = { currentNodeSnapshotService() },
                                contactService = { currentContactService() },
                                radioId = { currentRadioId() },
                                isRadioConnected = { latestIsRadioConnected.value() },
                            ),
                        )
                    }
                DisposableEffect(statusViewModel) {
                    onDispose { if (workspaces == null) statusViewModel.close() }
                }
                RoomStatusScreen(
                    viewModel = statusViewModel,
                    session = session,
                    isRadioReady = isRadioReady,
                    mapColorSchemePreference = mapColorSchemePreference,
                    isOffline = isOffline,
                    locationService = locationService,
                    userLocation = userLocation,
                    mapDisplayPreferences = mapDisplayPreferences,
                )
            }
        }
    }
}
