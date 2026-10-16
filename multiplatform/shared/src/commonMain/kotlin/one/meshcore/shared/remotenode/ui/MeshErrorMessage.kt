package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_device_file_system
import one.meshcore.shared.resources.localizable_error_device_invalid_parameter
import one.meshcore.shared.resources.localizable_error_device_invalid_state
import one.meshcore.shared.resources.localizable_error_device_not_found
import one.meshcore.shared.resources.localizable_error_device_storage_full
import one.meshcore.shared.resources.localizable_error_device_unknown
import one.meshcore.shared.resources.localizable_error_device_unsupported_command
import one.meshcore.shared.resources.localizable_error_mesh_core_connection_lost_no_detail
import one.meshcore.shared.resources.localizable_error_mesh_core_not_connected
import one.meshcore.shared.resources.localizable_error_mesh_core_timeout
import one.meshcore.shared.ui.MeshBridgeErrorMessage
import org.jetbrains.compose.resources.StringResource

/**
 * The translated string for a lower-layer [MeshError], shared by the remote-node and room-server error
 * mappers because both wrap the same type in their `SessionError` case. A firmware failure resolves
 * through [MeshBridgeErrorMessage.deviceResource].
 */
fun MeshError.messageResource(): StringResource = when (this) {
    is MeshError.Timeout -> Res.string.localizable_error_mesh_core_timeout
    is MeshError.NotConnected -> Res.string.localizable_error_mesh_core_not_connected
    is MeshError.ConnectionLost -> Res.string.localizable_error_mesh_core_connection_lost_no_detail
    is MeshError.DeviceError -> MeshBridgeErrorMessage.deviceResource(code)
}

/** The substitutions [messageResource] expects; only an unrecognised firmware code carries one. */
fun MeshError.messageFormatArgs(): List<Any> = when {
    this is MeshError.DeviceError && messageResource() == Res.string.localizable_error_device_unknown ->
        listOf(code)
    else -> emptyList()
}

/** Every string [messageResource] can return, for a caller resolving them before the failure happens. */
val MESH_ERROR_RESOURCES: Set<StringResource> = setOf(
    Res.string.localizable_error_mesh_core_timeout,
    Res.string.localizable_error_mesh_core_not_connected,
    Res.string.localizable_error_mesh_core_connection_lost_no_detail,
    Res.string.localizable_error_device_unsupported_command,
    Res.string.localizable_error_device_not_found,
    Res.string.localizable_error_device_storage_full,
    Res.string.localizable_error_device_invalid_state,
    Res.string.localizable_error_device_file_system,
    Res.string.localizable_error_device_invalid_parameter,
    Res.string.localizable_error_device_unknown,
)
