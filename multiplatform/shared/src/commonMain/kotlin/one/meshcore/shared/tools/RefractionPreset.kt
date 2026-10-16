package one.meshcore.shared.tools

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The atmospheric refraction k-factors the line-of-sight tool offers, matching the three the iOS
 * `RFSettingsSectionView` picker exposes. The k-factor scales the effective earth radius used for the
 * bulge and Fresnel-zone maths, so a wrong one silently changes whether a path reads as clear.
 *
 * A fixed set rather than free entry: knowing that 1.33 is standard atmosphere is exactly the domain
 * knowledge the picker exists to supply.
 */
enum class RefractionPreset(val kFactor: Double) {
    /** No refraction — true geometric line of sight. */
    NONE(1.0),

    /** Standard atmosphere, the usual planning assumption. */
    STANDARD(4.0 / 3.0),

    /** Ducting, where the atmosphere bends signals well beyond the horizon. */
    DUCTING(4.0),
    ;

    companion object {
        /**
         * The preset [kFactor] represents, or [STANDARD] when a stored value matches none of them — a
         * value from an older build or a hand-edited preference should land on the planning default
         * rather than leave the picker blank.
         */
        fun forKFactor(kFactor: Double): RefractionPreset =
            entries.firstOrNull { abs(it.kFactor - kFactor) < TOLERANCE } ?: STANDARD

        /** Standard's k is a recurring third, so matching is by proximity rather than equality. */
        private const val TOLERANCE = 0.001
    }
}

/**
 * The k-factor as the terrain-profile caption states it, e.g. `k=1.33` — two fraction digits, matching
 * the iOS `LOSFormatters.formatKFactor`. Kept beside the presets so the caption and the picker can never
 * disagree about the same number.
 */
fun formatKFactor(kFactor: Double): String {
    val scaled = (kFactor * K_FACTOR_SCALE).roundToLong()
    val whole = scaled / K_FACTOR_SCALE
    val fraction = abs(scaled % K_FACTOR_SCALE).toString().padStart(K_FACTOR_DIGITS, '0')
    return "k=$whole.$fraction"
}

/**
 * The operating frequency as the assumptions footnote states it, e.g. `906 MHz` or `868.5 MHz` — a
 * fraction digit only when there is one to show, matching the iOS `LOSFormatters.formatFrequency`.
 */
fun formatFrequencyMHz(megahertz: Double): String {
    val scaled = megahertz * TENTHS
    if (abs(scaled) >= Long.MAX_VALUE.toDouble()) return "$megahertz MHz"
    val rounded = scaled.roundToLong()
    val whole = rounded / TENTHS
    val tenth = abs(rounded % TENTHS)
    return if (tenth == 0L) "$whole MHz" else "$whole.$tenth MHz"
}

private const val TENTHS = 10L
private const val K_FACTOR_SCALE = 100L
private const val K_FACTOR_DIGITS = 2
