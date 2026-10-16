package one.meshcore.shared.connection

import kotlin.math.abs

/**
 * The tolerance for comparing a written LoRa frequency or bandwidth against the value the radio reads
 * back. Both travel as scaled integers, so a match is exact in practice; the window only absorbs the
 * double conversion on either side.
 */
private const val RADIO_PARAM_TOLERANCE = 0.001

/** Thrown when the radio reads back LoRa parameters other than the ones just written. */
class RadioParamsVerificationFailed(val expected: String, val actual: String) :
    Exception("Radio parameter verification failed: expected $expected, got $actual")

/** A LoRa parameter set, in the wire units the radio reports: frequency in MHz, bandwidth in kHz. */
internal data class LoRaParams(
    val frequencyMHz: Double,
    val bandwidthKHz: Double,
    val spreadingFactor: Int,
    val codingRate: Int,
) {
    /**
     * Whether the radio's reported parameters match what was written. A radio that rejects a write
     * answers with its unchanged settings rather than an error, so without this comparison a refused
     * write reports success and the UI claims a preset the radio never adopted.
     */
    fun matches(other: LoRaParams): Boolean = abs(other.frequencyMHz - frequencyMHz) < RADIO_PARAM_TOLERANCE &&
        abs(other.bandwidthKHz - bandwidthKHz) < RADIO_PARAM_TOLERANCE &&
        other.spreadingFactor == spreadingFactor &&
        other.codingRate == codingRate

    /** Renders the set for the verification-failure message. */
    fun describe(): String = "freq=$frequencyMHz, bw=$bandwidthKHz, sf=$spreadingFactor, cr=$codingRate"
}

/** iOS `SettingsService.setRadioParamsVerified` dump when the radio kept different LoRa params. */
internal fun radioParamsVerificationDump(expected: LoRaParams, reported: LoRaParams): String =
    "[Radio] Verification failed - expected: freq=${expected.frequencyMHz}MHz, " +
        "bw=${expected.bandwidthKHz}kHz, sf=${expected.spreadingFactor}, " +
        "cr=${expected.codingRate}; device reports: freq=${reported.frequencyMHz}MHz, " +
        "bw=${reported.bandwidthKHz}kHz, sf=${reported.spreadingFactor}, " +
        "cr=${reported.codingRate}"

/** iOS `SettingsService.setTxPowerVerified` dump. */
internal fun radioTxPowerVerificationDump(expectedDbm: Int, reportedDbm: Int): String =
    "[Radio] TX power verification failed - expected: ${expectedDbm}dBm, device reports: ${reportedDbm}dBm"

/** iOS `SettingsService.setRadioParamsVerified` dump when client-repeat read-back disagrees. */
internal fun radioClientRepeatVerificationDump(expected: Boolean, reported: Boolean): String =
    "[Radio] Client repeat verification failed - expected: $expected, device reports: $reported"
