package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.addressingPrefix
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.getConnectedSession
import one.meshcore.shared.room.ui.loadConnectedSession

internal sealed interface ConnectedSessionLoad {
    data object Loading : ConnectedSessionLoad
    data object Missing : ConnectedSessionLoad
    data class Ready(val session: RemoteNodeSessionDto) : ConnectedSessionLoad
}

/**
 * Loads the connected session for [contact]. A persist fault becomes [ConnectedSessionLoad.Missing]
 * so the sheet shows not-connected instead of staying on the spinner.
 */
@Composable
private fun <S : Any> rememberConnectedSession(
    contact: ContactDto,
    service: S?,
    fetch: suspend (S, ByteArray) -> RemoteNodeSessionDto?,
): ConnectedSessionLoad {
    val publicKeyHash = contact.publicKey.contentHashCode()
    var load by remember(contact.id, publicKeyHash, service) {
        mutableStateOf<ConnectedSessionLoad>(ConnectedSessionLoad.Loading)
    }
    LaunchedEffect(contact.id, publicKeyHash, service) {
        load = ConnectedSessionLoad.Loading
        val session = if (service == null) {
            null
        } else {
            loadConnectedSession { fetch(service, contact.publicKey.addressingPrefix()) }
        }
        load = if (session != null) ConnectedSessionLoad.Ready(session) else ConnectedSessionLoad.Missing
    }
    return load
}

/** The connected repeater session for [contact], or missing when the admin service has none. */
@Composable
internal fun rememberRepeaterSession(contact: ContactDto, service: () -> RepeaterAdminService?): ConnectedSessionLoad {
    val currentService = service()
    return rememberConnectedSession(contact, currentService) { owner, prefix ->
        owner.getConnectedSession(prefix)
    }
}

/** The connected room session for [contact], or missing when the room service has none. */
@Composable
internal fun rememberRoomSession(contact: ContactDto, service: () -> RoomServerService?): ConnectedSessionLoad {
    val currentService = service()
    return rememberConnectedSession(contact, currentService) { owner, prefix ->
        owner.getConnectedSession(prefix)
    }
}
