package one.meshcore.shared.connection

/** A pairing failure to route, mirroring the iOS `PairingError` cases the alert routing distinguishes. */
sealed interface PairingFailure {
    /** A connect attempt failed; [isAuthenticationFailure] separates a dead bond from a transient error. */
    data class ConnectionFailed(val deviceId: String, val isAuthenticationFailure: Boolean) : PairingFailure

    /** The radio is held by another app. */
    data class DeviceConnectedToOtherApp(val deviceId: String) : PairingFailure
}
