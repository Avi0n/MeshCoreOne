package one.meshcore.shared.remotenode

import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES

/**
 * Field validation for node settings, mirroring the Swift `NodeSettingsViewModel.validateIdentityFields`
 * and `validateBehaviorFields`. The Swift versions return localized error strings; this port returns
 * structured per-field flags so the shared layer stays free of `L10n` and the app target maps a flag to
 * its message. Out-of-range coordinates are rejected, not clamped, so a mistyped value surfaces to the
 * user instead of the firmware silently normalizing it.
 */
object NodeSettingsValidation {
    /** Firmware limit on the `set owner.info` value length, in characters. */
    const val OWNER_INFO_MAX_LENGTH: Int = 119

    /** Firmware-accepted ranges for the behavior fields; 0 means disabled for the two intervals. */
    val ADVERT_INTERVAL_MINUTES_RANGE = 60..240
    val FLOOD_INTERVAL_HOURS_RANGE = 3..168
    val FLOOD_MAX_HOPS_RANGE = 0..64

    private const val LATITUDE_LIMIT = 90.0
    private const val LONGITUDE_LIMIT = 180.0

    /** Which identity fields failed validation; each maps to its own localized message in the UI. */
    data class IdentityValidation(
        val nameTooLong: Boolean = false,
        val latitudeOutOfRange: Boolean = false,
        val longitudeOutOfRange: Boolean = false,
    ) {
        val hasErrors: Boolean get() = nameTooLong || latitudeOutOfRange || longitudeOutOfRange
    }

    /** Which behavior fields failed validation. */
    data class BehaviorValidation(
        val advertIntervalInvalid: Boolean = false,
        val floodIntervalInvalid: Boolean = false,
        val floodMaxHopsInvalid: Boolean = false,
    ) {
        val hasErrors: Boolean get() = advertIntervalInvalid || floodIntervalInvalid || floodMaxHopsInvalid
    }

    /** Validates identity fields against the binary write path's name byte cap and coordinate ranges. */
    fun validateIdentityFields(name: String?, latitude: Double?, longitude: Double?): IdentityValidation =
        IdentityValidation(
            nameTooLong = name != null && name.encodeToByteArray().size > MAX_USABLE_NAME_BYTES,
            latitudeOutOfRange = coordinateOutOfRange(latitude, LATITUDE_LIMIT),
            longitudeOutOfRange = coordinateOutOfRange(longitude, LONGITUDE_LIMIT),
        )

    private fun coordinateOutOfRange(value: Double?, limit: Double): Boolean =
        value != null && (!value.isFinite() || value !in -limit..limit)

    /** Validates behavior fields; the two intervals treat 0 as "disabled" and skip the range check. */
    fun validateBehaviorFields(advertInterval: Int?, floodInterval: Int?, floodMaxHops: Int?): BehaviorValidation =
        BehaviorValidation(
            advertIntervalInvalid =
            advertInterval != null && advertInterval != 0 && advertInterval !in ADVERT_INTERVAL_MINUTES_RANGE,
            floodIntervalInvalid =
            floodInterval != null && floodInterval != 0 && floodInterval !in FLOOD_INTERVAL_HOURS_RANGE,
            floodMaxHopsInvalid = floodMaxHops != null && floodMaxHops !in FLOOD_MAX_HOPS_RANGE,
        )
}
