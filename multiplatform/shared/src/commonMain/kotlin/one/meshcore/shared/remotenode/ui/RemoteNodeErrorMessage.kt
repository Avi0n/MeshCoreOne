package one.meshcore.shared.remotenode.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.localizable_error_remote_node_cancelled
import one.meshcore.shared.resources.localizable_error_remote_node_contact_not_found
import one.meshcore.shared.resources.localizable_error_remote_node_flood_routed
import one.meshcore.shared.resources.localizable_error_remote_node_invalid_response
import one.meshcore.shared.resources.localizable_error_remote_node_login_failed
import one.meshcore.shared.resources.localizable_error_remote_node_not_connected
import one.meshcore.shared.resources.localizable_error_remote_node_password_not_found
import one.meshcore.shared.resources.localizable_error_remote_node_path_discovery_failed
import one.meshcore.shared.resources.localizable_error_remote_node_permission_denied
import one.meshcore.shared.resources.localizable_error_remote_node_radio_contacts_full
import one.meshcore.shared.resources.localizable_error_remote_node_send_failed
import one.meshcore.shared.resources.localizable_error_remote_node_session_not_found
import one.meshcore.shared.resources.localizable_error_remote_node_timeout
import one.meshcore.shared.ui.rememberMappedErrorText
import one.meshcore.shared.ui.rememberMeshBridgeErrorText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The translated string for a remote-node failure, the Kotlin analogue of the Swift
 * `RemoteNodeError.userFacingMessage`. The exception's own text is developer English, so an unmapped case
 * reaches the screen untranslated whatever locale the device runs in.
 *
 * Exhaustive on purpose: a case added later is a compile error here rather than a silent fallback.
 */
fun RemoteNodeError.messageResource(): StringResource = when (this) {
    is RemoteNodeError.NotConnected -> Res.string.localizable_error_remote_node_not_connected
    is RemoteNodeError.LoginFailed -> Res.string.localizable_error_remote_node_login_failed
    is RemoteNodeError.SendFailed -> Res.string.localizable_error_remote_node_send_failed
    is RemoteNodeError.InvalidResponse -> Res.string.localizable_error_remote_node_invalid_response
    is RemoteNodeError.PermissionDenied -> Res.string.localizable_error_remote_node_permission_denied
    is RemoteNodeError.Timeout -> Res.string.localizable_error_remote_node_timeout
    is RemoteNodeError.SessionNotFound -> Res.string.localizable_error_remote_node_session_not_found
    is RemoteNodeError.PasswordNotFound -> Res.string.localizable_error_remote_node_password_not_found
    is RemoteNodeError.FloodRouted -> Res.string.localizable_error_remote_node_flood_routed
    is RemoteNodeError.PathDiscoveryFailed -> Res.string.localizable_error_remote_node_path_discovery_failed
    else -> contactMessageResource()
}

// Cases already returned above. Listed so a new remote-node error is still a compile error.
private fun RemoteNodeError.contactMessageResource(): StringResource = when (this) {
    is RemoteNodeError.ContactNotFound -> Res.string.localizable_error_remote_node_contact_not_found
    is RemoteNodeError.RadioContactsFull -> Res.string.localizable_error_remote_node_radio_contacts_full
    is RemoteNodeError.Cancelled -> Res.string.localizable_error_remote_node_cancelled
    is RemoteNodeError.SessionError -> error.messageResource()
    is RemoteNodeError.NotConnected,
    is RemoteNodeError.LoginFailed,
    is RemoteNodeError.SendFailed,
    is RemoteNodeError.InvalidResponse,
    is RemoteNodeError.PermissionDenied,
    is RemoteNodeError.Timeout,
    is RemoteNodeError.SessionNotFound,
    is RemoteNodeError.PasswordNotFound,
    is RemoteNodeError.FloodRouted,
    is RemoteNodeError.PathDiscoveryFailed,
    -> messageResource()
}

/** The substitutions [messageResource] expects, left to right; empty for a fixed string. */
fun RemoteNodeError.messageFormatArgs(): List<Any> = when (this) {
    is RemoteNodeError.SessionError -> error.messageFormatArgs()
    else -> emptyList()
}

/** Every string [messageResource] can return, so a caller can resolve them before the failure happens. */
private val REMOTE_NODE_ERROR_RESOURCES: Set<StringResource> = setOf(
    Res.string.localizable_error_remote_node_not_connected,
    Res.string.localizable_error_remote_node_login_failed,
    Res.string.localizable_error_remote_node_send_failed,
    Res.string.localizable_error_remote_node_invalid_response,
    Res.string.localizable_error_remote_node_permission_denied,
    Res.string.localizable_error_remote_node_timeout,
    Res.string.localizable_error_remote_node_session_not_found,
    Res.string.localizable_error_remote_node_password_not_found,
    Res.string.localizable_error_remote_node_flood_routed,
    Res.string.localizable_error_remote_node_path_discovery_failed,
    Res.string.localizable_error_remote_node_contact_not_found,
    Res.string.localizable_error_remote_node_radio_contacts_full,
    Res.string.localizable_error_remote_node_cancelled,
) + MESH_ERROR_RESOURCES

/**
 * Turns a remote-node failure into text for a view model, which cannot read string resources itself.
 * Every candidate string is resolved during composition and handed over as a plain function. A failure
 * that is not a [RemoteNodeError] answers null, so the caller keeps whatever text it already had.
 */
@Composable
fun rememberRemoteNodeErrorText(): (Throwable) -> String? = rememberMappedErrorText(
    REMOTE_NODE_ERROR_RESOURCES,
    { it as? RemoteNodeError },
    RemoteNodeError::messageResource,
    RemoteNodeError::messageFormatArgs,
)

/**
 * Node-auth surface copy, matching iOS `error.userFacingMessage`. Typed remote-node and bridge
 * failures keep their sentences; anything else uses errorOccurred instead of English
 * "Authentication failed".
 */
internal fun nodeAuthSurfaceText(
    remote: (Throwable) -> String?,
    bridge: (Throwable) -> String?,
    unmapped: String,
): (Throwable) -> String = { error -> remote(error) ?: bridge(error) ?: unmapped }

@Composable
fun rememberNodeAuthErrorText(): (Throwable) -> String {
    val remote = rememberRemoteNodeErrorText()
    val bridge = rememberMeshBridgeErrorText()
    val unmapped = stringResource(Res.string.contacts_common_error_occurred)
    return remember(remote, bridge, unmapped) { nodeAuthSurfaceText(remote, bridge, unmapped) }
}
