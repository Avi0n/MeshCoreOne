package one.meshcore.shared.settings

/**
 * Read model for the Settings → Advanced telemetry-sharing, direct-message multi-ack, and path-hash
 * pickers (iOS `TelemetrySettingsSection` / `DirectMessagesSettingsSection` / `PathHashModeSection`).
 * Values come from the Device row; the writes go through [one.meshcore.shared.meshcore.MeshSession]
 * `setOtherParams` / `setPathHashMode`. `null` when disconnected or the Device row is missing.
 */
data class DeviceOtherParamsSnapshot(
    /** Firmware telemetry permission for the base flag (0 off, 1 trusted-only, 2 everyone). */
    val telemetryModeBase: Int,
    /** Firmware telemetry permission for shared location. */
    val telemetryModeLocation: Int,
    /** Firmware telemetry permission for shared environment metrics. */
    val telemetryModeEnvironment: Int,
    /** Acknowledgments sent per direct message on the wire (0 or 1); the picker shows this plus one. */
    val multiAcks: Int,
    /** Routing key-prefix hash size (0 = 1 byte, 1 = 2 bytes, 2 = 3 bytes). */
    val pathHashMode: Int,
    /** Device firmware version, gating the path-hash picker (v10+). */
    val firmwareVersion: Int,
) {
    /**
     * Whether the device exposes the path-hash-mode setting (firmware v10+). Mirrors iOS
     * `Device.supportsPathHashMode`.
     */
    val supportsPathHashMode: Boolean
        get() = firmwareVersion >= PATH_HASH_MODE_MIN_FIRMWARE_VERSION

    companion object {
        private const val PATH_HASH_MODE_MIN_FIRMWARE_VERSION = 10

        /** Telemetry permission levels shared by the base, location, and environment modes. */
        const val TELEMETRY_MODE_OFF = 0
        const val TELEMETRY_MODE_TRUSTED_ONLY = 1
        const val TELEMETRY_MODE_EVERYONE = 2
    }
}
