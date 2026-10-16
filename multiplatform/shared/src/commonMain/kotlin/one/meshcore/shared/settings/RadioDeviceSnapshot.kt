package one.meshcore.shared.settings

/**
 * Live radio parameters for the connected device (Device-row units: frequency kHz, bandwidth Hz).
 */
data class RadioDeviceSnapshot(
    val frequencyKHz: Long,
    val bandwidthHz: Long,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int? = null,
    /**
     * The radio's own TX-power ceiling, from `getSelfInfo`. Defaults to the Device-row default so a row
     * written before the field existed still validates against a plausible ceiling rather than none.
     */
    val maxTxPower: Int = DEFAULT_MAX_TX_POWER,
    val firmwareVersion: Int = 0,
    val firmwareVersionString: String = "",
    /** The radio's hardware model string, as reported at connect. Empty until a device row exists. */
    val manufacturerName: String = "",
    /** The firmware build date the radio reports, free-form text. Empty until a device row exists. */
    val buildDate: String = "",
    /** The radio's contact-table capacity. */
    val maxContacts: Int = 0,
    /** The radio's channel-slot capacity. */
    val maxChannels: Int = 0,
    /** Whether the device is currently running in client-repeat mode. */
    val clientRepeat: Boolean = false,
    /** The frequency saved when repeat mode was entered, restored when it is switched off. */
    val preRepeatFrequencyKHz: Long? = null,
    /**
     * Last-applied catalog id when several names share this RF tuple. Null until a named preset is
     * applied, and dropped when live RF no longer matches (except in Repeat Mode).
     */
    val appliedRadioPresetID: String? = null,
) {
    /**
     * Whether the trace command honors a per-trace hash size in its flags byte, letting a trace use a
     * hash size different from the routing `pathHashMode`. Added in firmware v1.11.0; `firmwareVersion`
     * stayed 8 from v1.10.0 through v1.12.0, so the version string disambiguates the v1.11/v1.12 window
     * while `firmwareVersion >= 9` (v1.13+) still holds if a fork rewrites the string. Mirrors iOS
     * `Device.supportsTraceHashSizeOverride`.
     */
    val supportsTraceHashSizeOverride: Boolean
        get() = firmwareVersion >= TRACE_HASH_OVERRIDE_MIN_FIRMWARE_VERSION ||
            FirmwareVersion.isAtLeast(
                firmwareVersionString,
                major = TRACE_HASH_OVERRIDE_MAJOR,
                minor = TRACE_HASH_OVERRIDE_MINOR,
            )

    /** Whether this device supports client-repeat mode (firmware v9+). Mirrors iOS `Device.supportsClientRepeat`. */
    val supportsClientRepeat: Boolean
        get() = firmwareVersion >= CLIENT_REPEAT_MIN_FIRMWARE_VERSION

    /**
     * Whether the radio accepts a persisted default flood scope (firmware v11+). Mirrors iOS
     * `Device.supportsDefaultFloodScope`; an older radio rejects the opcode, so the section is hidden.
     */
    val supportsDefaultFloodScope: Boolean
        get() = firmwareVersion >= DEFAULT_FLOOD_SCOPE_MIN_FIRMWARE_VERSION

    /**
     * Whether the radio honors an anonymous request to a public key that is not one of its contacts,
     * auto-adding a temporary zero-hop entry so a nearby repeater can be queried without adding it
     * first. Region discovery needs it: an older radio rejects the request outright, so only responders
     * already saved as contacts can be asked. Added with firmware version code 13; the version string
     * covers a fork that leaves the code unbumped. Mirrors iOS `Device.supportsAdHocRepeaterRequest`.
     */
    val supportsAdHocRepeaterRequest: Boolean
        get() = firmwareVersion >= AD_HOC_REPEATER_MIN_FIRMWARE_VERSION ||
            FirmwareVersion.isAtLeast(
                firmwareVersionString,
                major = AD_HOC_REPEATER_MAJOR,
                minor = AD_HOC_REPEATER_MINOR,
            )

    companion object {
        /** The Device-row TX-power default, mirroring Swift `Device.Defaults.maxTxPower`. */
        const val DEFAULT_MAX_TX_POWER = 20

        private const val TRACE_HASH_OVERRIDE_MIN_FIRMWARE_VERSION = 9
        private const val TRACE_HASH_OVERRIDE_MAJOR = 1
        private const val TRACE_HASH_OVERRIDE_MINOR = 11
        private const val CLIENT_REPEAT_MIN_FIRMWARE_VERSION = 9
        private const val DEFAULT_FLOOD_SCOPE_MIN_FIRMWARE_VERSION = 11
        private const val AD_HOC_REPEATER_MIN_FIRMWARE_VERSION = 13
        private const val AD_HOC_REPEATER_MAJOR = 1
        private const val AD_HOC_REPEATER_MINOR = 16
    }
}
