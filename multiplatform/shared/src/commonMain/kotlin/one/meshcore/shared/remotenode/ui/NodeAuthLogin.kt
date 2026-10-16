package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RepeaterAdminLogin
import one.meshcore.shared.room.RoomServerLogin
import one.meshcore.shared.room.joinRoom

/** Repeater and room-server login sends for the authentication sheet, including the flood retry. */
internal class NodeAuthLogin(
    private val contact: ContactDto,
    private val role: RemoteNodeRole,
    private val dependencies: NodeAuthViewModel.Dependencies,
    private val state: MutableStateFlow<NodeAuthState>,
    private val path: NodeAuthPath,
    private val countdown: NodeAuthCountdown,
) {
    internal suspend fun connect(): RemoteNodeSessionDto {
        val radioID = when {
            dependencies.isRadioConnected() -> dependencies.radioId()
            else -> null
        } ?: throw RemoteNodeError.NotConnected
        val pathLength = path.resolveLength(radioID)
        return try {
            performLogin(radioID, pathLength)
        } catch (e: RemoteNodeError.Timeout) {
            // The stored route produced no reply; clear it and let the network find a path, mirroring what
            // the flood toggle would have done. A flood attempt has no route left to reset, so it is terminal.
            if (pathLength == NODE_AUTH_FLOOD_SENTINEL) throw e
            state.update { it.copy(isRetryingViaFlood = true) }
            countdown.cancel()
            path.resetStoredRoute(radioID)
            performLogin(radioID, NODE_AUTH_FLOOD_SENTINEL)
        }
    }

    private suspend fun performLogin(radioID: Uuid, pathLength: Int): RemoteNodeSessionDto {
        val password = state.value.password.take(NodeAuthViewModel.MAX_PASSWORD_LENGTH)
        val remember = state.value.rememberPassword
        val onTimeoutKnown: suspend (Int) -> Unit = { seconds -> countdown.start(seconds) }
        val login: (suspend () -> RemoteNodeSessionDto)? = if (role == RemoteNodeRole.ROOM_SERVER) {
            dependencies.roomServerService()?.let { service ->
                {
                    service.joinRoom(
                        radioID,
                        contact,
                        password,
                        RoomServerLogin(
                            rememberPassword = remember,
                            pathLength = pathLength,
                            onTimeoutKnown = onTimeoutKnown,
                        ),
                    )
                }
            }
        } else {
            dependencies.repeaterAdminService()?.let { service ->
                {
                    service.connectAsAdmin(
                        radioID,
                        contact,
                        password,
                        RepeaterAdminLogin(
                            rememberPassword = remember,
                            pathLength = pathLength,
                            onTimeoutKnown = onTimeoutKnown,
                        ),
                    )
                }
            }
        }
        return (login ?: throw RemoteNodeError.NotConnected)()
    }
}
