package one.meshcore.shared.map

/**
 * Formats the locale-dependent parts of an SNR badge label. The model composes the comma
 * separator and the space before the unit in code (so whitespace-trimming tooling can't strip them
 * out of a localized value); the platform UI supplies the measurement formatting, the number
 * formatting, and the localized `dB` unit (`L10n.RemoteNodes.Status.snrBadgeUnit`).
 */
interface SnrBadgeFormatter {
    /** A road-usage abbreviated distance, e.g. "1.2 km". */
    fun formatDistance(meters: Double): String

    /** The SNR value to one fraction digit, e.g. "-3.0". */
    fun formatSnr(snr: Double): String

    /** The localized unit appended after the SNR value, e.g. "dB". */
    val snrUnit: String
}
