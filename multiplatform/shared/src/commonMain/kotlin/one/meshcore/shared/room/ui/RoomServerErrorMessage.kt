package one.meshcore.shared.room.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.remotenode.ui.MESH_ERROR_RESOURCES
import one.meshcore.shared.remotenode.ui.messageFormatArgs
import one.meshcore.shared.remotenode.ui.messageResource
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_room_server_invalid_response
import one.meshcore.shared.resources.localizable_error_room_server_not_connected
import one.meshcore.shared.resources.localizable_error_room_server_permission_denied
import one.meshcore.shared.resources.localizable_error_room_server_send_failed
import one.meshcore.shared.resources.localizable_error_room_server_session_not_found
import one.meshcore.shared.room.RoomServerError
import one.meshcore.shared.ui.rememberMappedErrorText
import org.jetbrains.compose.resources.StringResource

/**
 * The translated string for a room-server failure, the Kotlin analogue of the Swift
 * `RoomServerError.userFacingMessage`. The exception's own text is developer English, so an unmapped case
 * reaches the screen untranslated whatever locale the device runs in.
 *
 * Exhaustive on purpose: a case added later is a compile error here rather than a silent fallback.
 */
fun RoomServerError.messageResource(): StringResource = when (this) {
    is RoomServerError.NotConnected -> Res.string.localizable_error_room_server_not_connected
    is RoomServerError.SessionNotFound -> Res.string.localizable_error_room_server_session_not_found
    is RoomServerError.SendFailed -> Res.string.localizable_error_room_server_send_failed
    is RoomServerError.PermissionDenied -> Res.string.localizable_error_room_server_permission_denied
    is RoomServerError.InvalidResponse -> Res.string.localizable_error_room_server_invalid_response
    is RoomServerError.SessionError -> error.messageResource()
}

/** The substitutions [messageResource] expects, left to right; empty for a fixed string. */
fun RoomServerError.messageFormatArgs(): List<Any> = when (this) {
    is RoomServerError.SessionError -> error.messageFormatArgs()
    else -> emptyList()
}

/** Every string [messageResource] can return, so a caller can resolve them before the failure happens. */
private val ROOM_SERVER_ERROR_RESOURCES: Set<StringResource> = setOf(
    Res.string.localizable_error_room_server_not_connected,
    Res.string.localizable_error_room_server_session_not_found,
    Res.string.localizable_error_room_server_send_failed,
    Res.string.localizable_error_room_server_permission_denied,
    Res.string.localizable_error_room_server_invalid_response,
) + MESH_ERROR_RESOURCES

/**
 * Turns a room-server failure into text for a view model, which cannot read string resources itself.
 * Every candidate string is resolved during composition and handed over as a plain function. A failure
 * that is not a [RoomServerError] answers null, so the caller keeps whatever text it already had.
 */
@Composable
fun rememberRoomServerErrorText(): (Throwable) -> String? = rememberMappedErrorText(
    ROOM_SERVER_ERROR_RESOURCES,
    { it as? RoomServerError },
    RoomServerError::messageResource,
    RoomServerError::messageFormatArgs,
)
