package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.mapsGeoUri
import one.meshcore.shared.contact.ui.ContactDetailInputs
import one.meshcore.shared.contact.ui.rememberContactServiceErrorText
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.pop
import one.meshcore.shared.pathedit.PATH_MANAGEMENT_LOG_CATEGORY
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.remotenode.NodeAuthPurpose
import one.meshcore.shared.settings.PersistentLogger

@Composable
internal fun contactDetailInputs(
    contactId: String,
    fromDirectChat: Boolean,
    deps: ShellDependencies,
    connectionState: DeviceConnectionState,
    networkOffline: Boolean,
    onSheet: (ShellSheet) -> Unit,
): ContactDetailInputs {
    val pathErrorText = rememberContactServiceErrorText()
    val currentPathErrorText by rememberUpdatedState(pathErrorText)
    val currentDeps by rememberUpdatedState(deps)
    val pathPreferences = deps.pathEditPreferences
    val recentHopsStore = remember(pathPreferences) { RecentHopsStore(pathPreferences) }
    val buildPathViewModel: (CoroutineScope) -> PathManagementViewModel =
        remember(recentHopsStore) {
            { pathScope ->
                val pathLogger = PersistentLogger(pathScope, category = PATH_MANAGEMENT_LOG_CATEGORY)
                PathManagementViewModel(
                    scope = pathScope,
                    recents = recentHopsStore,
                    dependencies = shellPathDependencies(
                        deps = { currentDeps },
                        isRadioConnected = { currentDeps.connectionState.value.isConnected },
                        errorText = { error -> currentPathErrorText(error) },
                        warning = pathLogger::warning,
                    ),
                )
            }
        }
    return ContactDetailInputs(
        contactId = contactId,
        contactService = deps.contactService,
        radioId = deps.currentRadioId,
        isRadioReady = connectionState == DeviceConnectionState.READY,
        isRadioConnected = connectionState.isConnected,
        isOffline = networkOffline,
        listInvalidations = deps.contactListInvalidations,
        showFromDirectChat = fromDirectChat,
        onBack = {
            if (fromDirectChat) {
                deps.coordinator.pop(AppTab.CHATS)
            } else {
                deps.coordinator.pop(AppTab.NODES)
            }
        },
        onSendMessage = { contact ->
            deps.coordinator.navigateToChat(contact.id.value)
        },
        onEditPath = { contact -> onSheet(ShellSheet.EditPath(contact)) },
        onShare = { contact -> onSheet(ShellSheet.ShareContact(contact)) },
        onZeroHopPing = deps.zeroHopPing,
        onShowTelemetry = { contact -> contactTelemetrySheet(contact)?.let(onSheet) },
        onShowSavedHistory = { contact -> onSheet(ShellSheet.TelemetryHistory(contact)) },
        onJoinRoom = { contact ->
            contactNodeAuthSheet(contact, NodeAuthPurpose.JOIN)?.let(onSheet)
        },
        onManagement = { contact ->
            contactNodeAuthSheet(contact, NodeAuthPurpose.MANAGE)?.let(onSheet)
        },
        onOpenInMaps = { contact ->
            deps.openExternalUrl(
                mapsGeoUri(
                    latitude = contact.latitude,
                    longitude = contact.longitude,
                    label = contact.displayName,
                ),
            )
        },
        buildPathViewModel = buildPathViewModel,
        selfPublicKey = deps.selfPublicKey,
    )
}

/**
 * Shared path-editor inputs. [isRadioConnected] stays a caller choice so each host can expose its live
 * connection owner without coupling the path view model to the shell.
 */
internal fun shellPathDependencies(
    deps: () -> ShellDependencies,
    isRadioConnected: () -> Boolean,
    errorText: (Throwable) -> String,
    warning: (String) -> Unit,
) = PathManagementViewModel.Dependencies(
    contactService = { deps().contactService() },
    radioId = { deps().currentRadioId() },
    isRadioConnected = isRadioConnected,
    hashSize = { deps().deviceHashSize() },
    discoveredNodes = { radioId -> deps().pathEditDiscoveredNodes(radioId) },
    errorText = errorText,
    warning = warning,
)
