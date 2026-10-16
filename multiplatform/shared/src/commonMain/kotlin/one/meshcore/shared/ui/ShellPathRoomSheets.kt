package one.meshcore.shared.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import one.meshcore.shared.channel.ui.ChannelFlowProviders
import one.meshcore.shared.channel.ui.JoinHashtagFromMessageSheet
import one.meshcore.shared.contact.ui.rememberContactServiceErrorText
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.clearPendingHashtagJoin
import one.meshcore.shared.navigation.clearPendingRoomAuth
import one.meshcore.shared.navigation.navigateToChannel
import one.meshcore.shared.navigation.navigateToRoom
import one.meshcore.shared.pathedit.PATH_MANAGEMENT_LOG_CATEGORY
import one.meshcore.shared.pathedit.PathManagementViewModel
import one.meshcore.shared.pathedit.RecentHopsStore
import one.meshcore.shared.pathedit.ui.PathEditingSheet
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_join_from_message_title
import one.meshcore.shared.settings.PersistentLogger
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShellPathRoomSheets(
    sheet: ShellSheet,
    deps: ShellDependencies,
    dismiss: () -> Unit,
    coordinator: NavigationCoordinator,
    channelProviders: ChannelFlowProviders,
    onLinkTap: (one.meshcore.shared.chat.text.LinkTarget) -> Unit,
) {
    when (sheet) {
        is ShellSheet.JoinHashtagFromMessage -> {
            val onJoinDismiss = {
                dismiss()
                coordinator.clearPendingHashtagJoin()
            }
            FullScreenOverlay(
                onDismiss = onJoinDismiss,
                title = stringResource(Res.string.chats_join_from_message_title),
            ) { innerPadding ->
                JoinHashtagFromMessageSheet(
                    rawChannelName = sheet.rawChannelName,
                    providers = channelProviders,
                    onDismiss = onJoinDismiss,
                    onJoined = { channel ->
                        dismiss()
                        coordinator.clearPendingHashtagJoin()
                        coordinator.navigateToChannel(channel.id.value)
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
        }
        is ShellSheet.EditPath -> {
            val pathScope = rememberCoroutineScope()
            val pathErrorText = rememberContactServiceErrorText()
            val currentPathErrorText by rememberUpdatedState(pathErrorText)
            val currentDeps by rememberUpdatedState(deps)
            val pathLogger = remember(pathScope) {
                PersistentLogger(pathScope, category = PATH_MANAGEMENT_LOG_CATEGORY)
            }
            val pathPreferences = deps.pathEditPreferences
            val recentHopsStore = remember(pathPreferences) { RecentHopsStore(pathPreferences) }
            val pathViewModel = remember(
                sheet.contact.id,
                sheet.contact.radioID,
                sheet.contact.publicKey.contentHashCode(),
                pathLogger,
                recentHopsStore,
            ) {
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
            PathEditingSheet(viewModel = pathViewModel, contact = sheet.contact, onDismiss = dismiss)
        }
        ShellSheet.ConnectRadio -> ConnectRadioSheet(deps = deps, onDismiss = dismiss)
        is ShellSheet.RoomAuth -> RoomAuthSheetHost(
            sessionId = sheet.sessionId,
            deps = deps,
            onSuccess = { authenticated ->
                coordinator.clearPendingRoomAuth()
                dismiss()
                coordinator.navigateToRoom(authenticated.id.value)
            },
            onDismiss = {
                coordinator.clearPendingRoomAuth()
                dismiss()
            },
        )
        is ShellSheet.RoomConversation -> RoomConversationSheetHost(
            contact = sheet.contact,
            deps = deps,
            onDismiss = dismiss,
            onLinkTap = onLinkTap,
        )
        else -> Unit
    }
}
