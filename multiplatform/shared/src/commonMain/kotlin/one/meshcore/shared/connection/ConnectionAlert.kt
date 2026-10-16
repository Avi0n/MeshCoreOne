package one.meshcore.shared.connection

import one.meshcore.shared.transport.BondLostException

/**
 * The connection alert currently presented, or `null` for none. A single source of truth replaces the
 * iOS `ConnectionUIState`'s separate `showingConnectionFailedAlert` / `otherAppWarningDeviceID`
 * bindings; the UI derives titles, messages, and buttons from the case and the [PairingFailureKind].
 */
sealed interface ConnectionAlert {
    data object BluetoothUnauthorized : ConnectionAlert

    /** A generic, OK-only failure carrying an already-resolved message. */
    data class Generic(val message: String?) : ConnectionAlert

    /** A pairing failure whose recovery UI is driven by [kind]. */
    data class Pairing(val deviceId: String, val kind: PairingFailureKind) : ConnectionAlert

    /** The radio is connected to another app; the separate "Could Not Connect" alert. */
    data class OtherApp(val deviceId: String) : ConnectionAlert
}

/**
 * Classifies a saved-radio connect failure the way iOS `presentSavedDeviceConnectFailure` does.
 * A dead bond earns guided re-pair. A GATT link held by another app is [SavedDeviceConnectError.OtherApp].
 * Other transport exceptions drop their message because untranslated developer English must not be
 * the copy a responder reads.
 */
fun classifySavedDeviceConnectError(error: Throwable): SavedDeviceConnectError = when {
    isBluetoothUnauthorized(error) -> SavedDeviceConnectError.BluetoothUnauthorized
    error is DeviceConnectedToOtherAppException -> SavedDeviceConnectError.OtherApp
    error is BondLostException -> SavedDeviceConnectError.AuthenticationFailed
    else -> SavedDeviceConnectError.Other(null)
}
