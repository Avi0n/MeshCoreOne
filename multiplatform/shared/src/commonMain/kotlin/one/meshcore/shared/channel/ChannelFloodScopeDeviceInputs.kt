package one.meshcore.shared.channel

/** Firmware version that first honors the un-scoped flood-send override (sub-command 1); iOS `>= 12`. */
const val UNSCOPED_FLOOD_SEND_MIN_FIRMWARE_VERSION: Int = 12

/**
 * App-owned flood-scope picker inputs taken from the connected Device row (iOS
 * `connectedDevice.knownRegions` / `defaultFloodScopeName` / `supportsUnscopedFloodSend`). Pure so connect
 * wiring and unit tests share one mapping.
 */
data class ChannelFloodScopeDeviceInputs(
    val knownRegions: List<String>,
    val defaultFloodScopeName: String?,
    val supportsUnscopedFloodSend: Boolean,
) {
    companion object {
        /** Maps a device row (or absence) into picker inputs. Null device → empty regions / null default. */
        fun fromDevice(
            knownRegions: List<String>?,
            defaultFloodScopeName: String?,
            firmwareVersion: Int?,
        ): ChannelFloodScopeDeviceInputs = ChannelFloodScopeDeviceInputs(
            knownRegions = knownRegions.orEmpty(),
            defaultFloodScopeName = defaultFloodScopeName,
            supportsUnscopedFloodSend = (firmwareVersion ?: 0) >= UNSCOPED_FLOOD_SEND_MIN_FIRMWARE_VERSION,
        )
    }
}
