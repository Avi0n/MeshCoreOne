package one.meshcore.shared.channel.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.channel.ChannelDeviceException
import one.meshcore.shared.channel.ChannelSendTimeoutException
import one.meshcore.shared.channel.ChannelServiceError
import one.meshcore.shared.channel.ChannelTimeoutException
import one.meshcore.shared.channel.ChannelTransportException
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.localizable_error_channel_service_channel_not_found
import one.meshcore.shared.resources.localizable_error_channel_service_circuit_breaker_open
import one.meshcore.shared.resources.localizable_error_channel_service_invalid_channel_index
import one.meshcore.shared.resources.localizable_error_channel_service_not_connected
import one.meshcore.shared.resources.localizable_error_channel_service_save_failed
import one.meshcore.shared.resources.localizable_error_channel_service_secret_hashing_failed
import one.meshcore.shared.resources.localizable_error_channel_service_send_failed
import one.meshcore.shared.resources.localizable_error_channel_service_sync_already_in_progress
import one.meshcore.shared.resources.localizable_error_device_unknown
import one.meshcore.shared.resources.localizable_error_mesh_core_connection_lost_no_detail
import one.meshcore.shared.resources.localizable_error_mesh_core_timeout
import one.meshcore.shared.ui.MeshBridgeErrorMessage
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedPluralStringResource
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The user-facing message for a channel-service failure, the Kotlin analogue of the Swift
 * `ChannelServiceError.userFacingMessage`. Unmapped session failures use
 * [Res.string.contacts_common_error_occurred] rather than the exception's English text.
 */
@Composable
fun ChannelServiceError.userFacingMessage(): String {
    if (this is ChannelServiceError.CircuitBreakerOpen) {
        return formattedPluralStringResource(
            Res.plurals.localizable_error_channel_service_circuit_breaker_open,
            consecutiveFailures,
            consecutiveFailures,
        )
    }
    val resource = messageResource() ?: return stringResource(Res.string.contacts_common_error_occurred)
    return formatTemplate(stringResource(resource), messageFormatArgs())
}

/**
 * The translated string for a failure, or null when only the exception's own text describes it. A
 * wrapped session failure resolves through its cause, the way Swift resolves the `MeshCoreError` it
 * carries. Pure so the key mapping is asserted without a composition.
 */
fun ChannelServiceError.messageResource(): StringResource? = when (this) {
    is ChannelServiceError.NotConnected -> Res.string.localizable_error_channel_service_not_connected
    is ChannelServiceError.ChannelNotFound -> Res.string.localizable_error_channel_service_channel_not_found
    is ChannelServiceError.InvalidChannelIndex -> Res.string.localizable_error_channel_service_invalid_channel_index
    is ChannelServiceError.SecretHashingFailed -> Res.string.localizable_error_channel_service_secret_hashing_failed
    is ChannelServiceError.SaveFailed -> Res.string.localizable_error_channel_service_save_failed
    is ChannelServiceError.SendFailed -> Res.string.localizable_error_channel_service_send_failed
    is ChannelServiceError.SyncAlreadyInProgress ->
        Res.string.localizable_error_channel_service_sync_already_in_progress
    is ChannelServiceError.CircuitBreakerOpen -> null
    is ChannelServiceError.SessionError -> sessionCauseResource(cause)
}

/**
 * Circuit-breaker copy is a `.stringsdict` plural (`one`/`other`, plus few/many where the locale
 * has them). Null for every other case; [userFacingMessage] prefers this over [messageResource].
 */
fun ChannelServiceError.messagePluralResource(): PluralStringResource? =
    if (this is ChannelServiceError.CircuitBreakerOpen) {
        Res.plurals.localizable_error_channel_service_circuit_breaker_open
    } else {
        null
    }

/** The substitutions [messageResource] expects, left to right; empty for a fixed string. */
fun ChannelServiceError.messageFormatArgs(): List<Any> = when (this) {
    is ChannelServiceError.SaveFailed -> listOf(reason)
    is ChannelServiceError.SendFailed -> listOf(reason)
    is ChannelServiceError.CircuitBreakerOpen -> listOf(consecutiveFailures)
    is ChannelServiceError.SessionError -> sessionCauseArgs(cause)
    else -> emptyList()
}

private fun sessionCauseResource(cause: Throwable?): StringResource? = when (cause) {
    is ChannelTimeoutException, is ChannelSendTimeoutException -> Res.string.localizable_error_mesh_core_timeout
    is ChannelDeviceException -> MeshBridgeErrorMessage.deviceResource(cause.firmwareCode)
    is ChannelTransportException -> Res.string.localizable_error_mesh_core_connection_lost_no_detail
    is MeshBridgeException -> MeshBridgeErrorMessage.resource(cause)
    else -> null
}

private fun sessionCauseArgs(cause: Throwable?): List<Any> = when (cause) {
    is MeshBridgeException -> MeshBridgeErrorMessage.formatArgs(cause)
    is ChannelDeviceException -> {
        val isUnknownCode =
            MeshBridgeErrorMessage.deviceResource(cause.firmwareCode) == Res.string.localizable_error_device_unknown
        if (isUnknownCode) listOf(cause.firmwareCode) else emptyList()
    }
    else -> emptyList()
}
