package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.firmwareCode
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_ble_characteristic_not_found
import one.meshcore.shared.resources.localizable_error_ble_connection_failed
import one.meshcore.shared.resources.localizable_error_ble_device_not_found
import one.meshcore.shared.resources.localizable_error_ble_invalid_response
import one.meshcore.shared.resources.localizable_error_ble_write_error
import one.meshcore.shared.resources.localizable_error_device_file_system
import one.meshcore.shared.resources.localizable_error_device_invalid_parameter
import one.meshcore.shared.resources.localizable_error_device_invalid_state
import one.meshcore.shared.resources.localizable_error_device_not_found
import one.meshcore.shared.resources.localizable_error_device_storage_full
import one.meshcore.shared.resources.localizable_error_device_unknown
import one.meshcore.shared.resources.localizable_error_device_unsupported_command
import one.meshcore.shared.resources.localizable_error_mesh_core_bluetooth_powered_off
import one.meshcore.shared.resources.localizable_error_mesh_core_bluetooth_unauthorized
import one.meshcore.shared.resources.localizable_error_mesh_core_bluetooth_unavailable
import one.meshcore.shared.resources.localizable_error_mesh_core_command_failed
import one.meshcore.shared.resources.localizable_error_mesh_core_connection_lost
import one.meshcore.shared.resources.localizable_error_mesh_core_connection_lost_no_detail
import one.meshcore.shared.resources.localizable_error_mesh_core_contact_not_found
import one.meshcore.shared.resources.localizable_error_mesh_core_data_too_large
import one.meshcore.shared.resources.localizable_error_mesh_core_feature_disabled
import one.meshcore.shared.resources.localizable_error_mesh_core_invalid_input
import one.meshcore.shared.resources.localizable_error_mesh_core_not_connected
import one.meshcore.shared.resources.localizable_error_mesh_core_parse_error
import one.meshcore.shared.resources.localizable_error_mesh_core_session_not_started
import one.meshcore.shared.resources.localizable_error_mesh_core_signing_failed
import one.meshcore.shared.resources.localizable_error_mesh_core_timeout
import one.meshcore.shared.resources.localizable_error_mesh_core_unknown
import org.jetbrains.compose.resources.StringResource

/**
 * The user-facing strings for a failure that came back from the bridge, the Kotlin analogue of the Swift
 * `MeshCoreError.userFacingMessage` and the `ProtocolError` sub-code table it shares with every other
 * rendering of the same firmware codes. Shared across verticals so those renderings cannot drift apart.
 *
 * Pure, so a vertical can assert its key mapping without a composition, and so a caller that has to
 * resolve strings before entering a coroutine can do so at composition time.
 */
object MeshBridgeErrorMessage {
    /**
     * The translated string for [error], or null when nothing shipped describes it. A null answer means
     * the exception's own text is the best surface there is — never a wrong string in its place.
     *
     * [BridgeErrorCode.DATA_TOO_LARGE] fills actual and maximum from the frozen bridge detail
     * `"data too large: <actual> > <max>"`. Any other wording stays unmapped so numbers are never
     * invented. [BridgeErrorCode.INVALID_RESPONSE] used to answer null because its mesh_core string
     * wants expected/got, but the transport family ships a complete sentence that needs no substitutions.
     */
    fun resource(error: MeshBridgeException): StringResource? = when (error.code) {
        BridgeErrorCode.TIMEOUT -> Res.string.localizable_error_mesh_core_timeout
        BridgeErrorCode.DEVICE_ERROR -> deviceResource(error.firmwareCode())
        BridgeErrorCode.PARSE_ERROR -> Res.string.localizable_error_mesh_core_parse_error
        BridgeErrorCode.NOT_CONNECTED -> Res.string.localizable_error_mesh_core_not_connected
        BridgeErrorCode.COMMAND_FAILED -> Res.string.localizable_error_mesh_core_command_failed
        BridgeErrorCode.CONTACT_NOT_FOUND -> Res.string.localizable_error_mesh_core_contact_not_found
        BridgeErrorCode.SIGNING_FAILED -> Res.string.localizable_error_mesh_core_signing_failed
        BridgeErrorCode.INVALID_INPUT -> Res.string.localizable_error_mesh_core_invalid_input
        BridgeErrorCode.BLUETOOTH_UNAVAILABLE -> Res.string.localizable_error_mesh_core_bluetooth_unavailable
        BridgeErrorCode.BLUETOOTH_UNAUTHORIZED -> Res.string.localizable_error_mesh_core_bluetooth_unauthorized
        BridgeErrorCode.BLUETOOTH_POWERED_OFF -> Res.string.localizable_error_mesh_core_bluetooth_powered_off
        else -> transportResource(error)
    }

    // Codes already mapped by resource. Listing them keeps this when exhaustive when a code is added.
    private fun transportResource(error: MeshBridgeException): StringResource? = when (error.code) {
        BridgeErrorCode.CONNECTION_LOST -> connectionLostResource(error.detail)
        BridgeErrorCode.SESSION_NOT_STARTED -> Res.string.localizable_error_mesh_core_session_not_started
        BridgeErrorCode.FEATURE_DISABLED -> Res.string.localizable_error_mesh_core_feature_disabled
        BridgeErrorCode.UNKNOWN -> Res.string.localizable_error_mesh_core_unknown
        BridgeErrorCode.TRANSPORT_CONNECTION_FAILED -> Res.string.localizable_error_ble_connection_failed
        BridgeErrorCode.TRANSPORT_SEND_FAILED -> Res.string.localizable_error_ble_write_error
        BridgeErrorCode.TRANSPORT_DEVICE_NOT_FOUND -> Res.string.localizable_error_ble_device_not_found
        BridgeErrorCode.TRANSPORT_SERVICE_NOT_FOUND,
        BridgeErrorCode.TRANSPORT_CHARACTERISTIC_NOT_FOUND,
        -> Res.string.localizable_error_ble_characteristic_not_found
        BridgeErrorCode.INVALID_RESPONSE -> Res.string.localizable_error_ble_invalid_response
        BridgeErrorCode.DATA_TOO_LARGE ->
            if (parseDataTooLargeSizes(error.detail) != null) {
                Res.string.localizable_error_mesh_core_data_too_large
            } else {
                null
            }
        BridgeErrorCode.INVALID_DESTINATION -> null
        BridgeErrorCode.TIMEOUT,
        BridgeErrorCode.DEVICE_ERROR,
        BridgeErrorCode.PARSE_ERROR,
        BridgeErrorCode.NOT_CONNECTED,
        BridgeErrorCode.COMMAND_FAILED,
        BridgeErrorCode.CONTACT_NOT_FOUND,
        BridgeErrorCode.SIGNING_FAILED,
        BridgeErrorCode.INVALID_INPUT,
        BridgeErrorCode.BLUETOOTH_UNAVAILABLE,
        BridgeErrorCode.BLUETOOTH_UNAUTHORIZED,
        BridgeErrorCode.BLUETOOTH_POWERED_OFF,
        -> null
    }

    /** The substitutions [resource] expects, left to right; empty for a fixed string. */
    fun formatArgs(error: MeshBridgeException): List<Any> = when (resource(error)) {
        Res.string.localizable_error_device_unknown -> listOf(error.firmwareCode())
        Res.string.localizable_error_mesh_core_parse_error,
        Res.string.localizable_error_mesh_core_command_failed,
        Res.string.localizable_error_mesh_core_signing_failed,
        Res.string.localizable_error_mesh_core_invalid_input,
        Res.string.localizable_error_mesh_core_unknown,
        Res.string.localizable_error_mesh_core_connection_lost,
        Res.string.localizable_error_ble_connection_failed,
        Res.string.localizable_error_ble_write_error,
        -> listOf(error.detail)
        Res.string.localizable_error_mesh_core_data_too_large ->
            parseDataTooLargeSizes(error.detail)?.let { listOf(it.first, it.second) } ?: emptyList()
        else -> emptyList()
    }

    /**
     * Every string [resource] can return, derived from the code table rather than listed beside it, so a
     * code mapped to a new string cannot be missed here. A caller that has to read its strings during
     * composition, before the coroutine that may fail, reads these.
     */
    val allResources: Set<StringResource> = (
        BridgeErrorCode.entries.mapNotNull { resource(MeshBridgeException(it, probeDetail(it))) } +
            BridgeErrorCode.entries.mapNotNull { resource(MeshBridgeException(it, "")) } +
            (0..DEVICE_ERROR_ILLEGAL_ARGUMENT).map(::deviceResource)
        ).toSet()

    /**
     * The firmware `ERR_CODE_*` sub-codes, sharing the `error.device.*` strings with every other
     * rendering of the same codes, as the Swift `ProtocolError.userFacingMessage` does.
     */
    fun deviceResource(code: Int): StringResource = when (code) {
        DEVICE_ERROR_UNSUPPORTED_COMMAND -> Res.string.localizable_error_device_unsupported_command
        DEVICE_ERROR_NOT_FOUND -> Res.string.localizable_error_device_not_found
        DEVICE_ERROR_TABLE_FULL -> Res.string.localizable_error_device_storage_full
        DEVICE_ERROR_BAD_STATE -> Res.string.localizable_error_device_invalid_state
        DEVICE_ERROR_FILE_IO -> Res.string.localizable_error_device_file_system
        DEVICE_ERROR_ILLEGAL_ARGUMENT -> Res.string.localizable_error_device_invalid_parameter
        else -> Res.string.localizable_error_device_unknown
    }

    private fun connectionLostResource(detail: String): StringResource = if (detail.isBlank()) {
        Res.string.localizable_error_mesh_core_connection_lost_no_detail
    } else {
        Res.string.localizable_error_mesh_core_connection_lost
    }

    // A non-empty detail, so [allResources] also picks up the with-detail branches.
    private const val DETAIL_PROBE = "detail"

    /** Frozen bridge wording, so [allResources] includes the sized sentence. */
    private const val DATA_TOO_LARGE_PROBE = "data too large: 1 > 2"

    private fun probeDetail(code: BridgeErrorCode): String = when (code) {
        BridgeErrorCode.DATA_TOO_LARGE -> DATA_TOO_LARGE_PROBE
        else -> DETAIL_PROBE
    }

    /**
     * Actual and maximum from `"data too large: <actual> > <max>"`. Null when the detail is not that
     * frozen shape, so a caller can keep the exception text rather than invent sizes.
     */
    private fun parseDataTooLargeSizes(detail: String): Pair<Int, Int>? {
        val match = Regex("""data too large:\s*(\d+)\s*>\s*(\d+)""").find(detail) ?: return null
        val actual = match.groupValues[1].toIntOrNull() ?: return null
        val max = match.groupValues[2].toIntOrNull() ?: return null
        return actual to max
    }
    private const val DEVICE_ERROR_UNSUPPORTED_COMMAND = 1
    private const val DEVICE_ERROR_NOT_FOUND = 2
    private const val DEVICE_ERROR_TABLE_FULL = 3
    private const val DEVICE_ERROR_BAD_STATE = 4
    private const val DEVICE_ERROR_FILE_IO = 5
    private const val DEVICE_ERROR_ILLEGAL_ARGUMENT = 6
}

/**
 * Turns a bridge failure into text for a `catch` inside a coroutine, resolving every candidate string
 * during composition because `stringResource` cannot be read from the coroutine that catches it.
 * Answers `null` when nothing shipped describes the failure, so the caller can fall back to the
 * exception's own text rather than show a wrong string.
 */
@Composable
fun rememberMeshBridgeErrorText(): (Throwable) -> String? = rememberMappedErrorText(
    MeshBridgeErrorMessage.allResources,
    { it as? MeshBridgeException },
    MeshBridgeErrorMessage::resource,
    MeshBridgeErrorMessage::formatArgs,
)
