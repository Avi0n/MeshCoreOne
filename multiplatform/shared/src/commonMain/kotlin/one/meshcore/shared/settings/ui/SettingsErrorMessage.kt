package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.channel.DefaultFloodScopeVerificationFailed
import one.meshcore.shared.connection.AutoAddConfigVerificationFailed
import one.meshcore.shared.connection.NodeNameVerificationFailed
import one.meshcore.shared.connection.OtherParamsVerificationFailed
import one.meshcore.shared.connection.PathHashModeVerificationFailed
import one.meshcore.shared.connection.RadioParamsVerificationFailed
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_ble_bluetooth_unauthorized
import one.meshcore.shared.resources.localizable_error_settings_gps_not_saved_expected_off
import one.meshcore.shared.resources.localizable_error_settings_gps_not_saved_expected_on
import one.meshcore.shared.resources.localizable_error_settings_not_connected
import one.meshcore.shared.resources.localizable_error_settings_verification_failed
import one.meshcore.shared.resources.settings_alert_retry_unable_to_save
import one.meshcore.shared.settings.DeviceGpsVerificationFailed
import one.meshcore.shared.settings.DeviceWriteVerificationFailed
import one.meshcore.shared.transport.BluetoothUnauthorizedException
import one.meshcore.shared.ui.MeshBridgeErrorMessage
import one.meshcore.shared.ui.rememberFallbackErrorText
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The user-facing message for a failed settings write, the Kotlin analogue of the Swift
 * `SettingsServiceError.userFacingMessage`. A bridge failure resolves through
 * [MeshBridgeErrorMessage], the way Swift resolves the `MeshCoreError` its `.sessionError` carries; a
 * refused write resolves to the shared expected/actual string. An unmapped fault uses the
 * unable-to-save copy rather than interpolating JNI or persist exception text.
 */
fun Throwable.settingsMessageResource(): StringResource? = when (this) {
    is BluetoothUnauthorizedException -> Res.string.localizable_error_ble_bluetooth_unauthorized
    is MeshBridgeException -> MeshBridgeErrorMessage.resource(this)
    // The connection layer refuses a write with no radio by throwing IllegalStateException.
    is IllegalStateException -> Res.string.localizable_error_settings_not_connected
    is NodeNameVerificationFailed,
    is OtherParamsVerificationFailed,
    is AutoAddConfigVerificationFailed,
    is PathHashModeVerificationFailed,
    is RadioParamsVerificationFailed,
    is DefaultFloodScopeVerificationFailed,
    is DeviceWriteVerificationFailed,
    -> Res.string.localizable_error_settings_verification_failed
    is DeviceGpsVerificationFailed ->
        if (expectedEnabled) {
            Res.string.localizable_error_settings_gps_not_saved_expected_on
        } else {
            Res.string.localizable_error_settings_gps_not_saved_expected_off
        }
    else -> null
}

/** The substitutions [settingsMessageResource] expects, left to right; empty for a fixed string. */
fun Throwable.settingsMessageFormatArgs(): List<Any> = when (this) {
    is MeshBridgeException -> MeshBridgeErrorMessage.formatArgs(this)
    is NodeNameVerificationFailed -> listOf(expected, actual)
    is OtherParamsVerificationFailed -> listOf(expected, actual)
    is AutoAddConfigVerificationFailed -> listOf(expected, actual)
    is PathHashModeVerificationFailed -> listOf(expected, actual)
    is RadioParamsVerificationFailed -> listOf(expected, actual)
    is DefaultFloodScopeVerificationFailed -> listOf(expected.orEmpty(), actual.orEmpty())
    is DeviceWriteVerificationFailed -> listOf(expected, actual)
    else -> emptyList()
}

/** Every string [settingsMessageResource] can return, for a caller reading them during composition. */
private val settingsErrorResources: Set<StringResource> = MeshBridgeErrorMessage.allResources +
    setOf(
        Res.string.localizable_error_ble_bluetooth_unauthorized,
        Res.string.localizable_error_settings_not_connected,
        Res.string.localizable_error_settings_verification_failed,
        Res.string.localizable_error_settings_gps_not_saved_expected_on,
        Res.string.localizable_error_settings_gps_not_saved_expected_off,
    )

/**
 * Turns a settings failure into text, for a `catch` inside a coroutine a composable launched. Every
 * candidate string is read during composition and the returned function only substitutes, because
 * `stringResource` cannot be called from the coroutine where the failure is caught.
 */
@Composable
fun rememberSettingsErrorText(): (Throwable) -> String {
    val fallback = stringResource(Res.string.settings_alert_retry_unable_to_save)
    return rememberFallbackErrorText(
        settingsErrorResources,
        { it },
        Throwable::settingsMessageResource,
        Throwable::settingsMessageFormatArgs,
        fallback,
    )
}
