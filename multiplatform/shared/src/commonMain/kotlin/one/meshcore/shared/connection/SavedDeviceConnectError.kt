package one.meshcore.shared.connection

/**
 * A failure from a user-initiated connect to an already-paired radio, mirroring the classification the
 * iOS `presentSavedDeviceConnectFailure` performs on the caught error.
 */
sealed interface SavedDeviceConnectError {
    data object BluetoothUnauthorized : SavedDeviceConnectError

    /** The radio is held by another app. */
    data object OtherApp : SavedDeviceConnectError

    /** The saved bond is dead; surface guided re-pair immediately. */
    data object AuthenticationFailed : SavedDeviceConnectError

    /** Any other failure, with an already-resolved user-facing message. */
    data class Other(val message: String?) : SavedDeviceConnectError
}
