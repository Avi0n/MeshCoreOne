package one.meshcore.shared.avatar

import kotlin.math.pow

/**
 * WCAG relative-luminance and contrast-ratio math, shared by the avatar color solver ([IdentityGamut])
 * and the contrast tests. One copy of the formula so a safety-critical app never ships two divergent
 * versions. Byte-for-byte port of Swift `WCAGContrast`; avatars must stay legible under outdoor glare.
 */
object WcagContrast {
    /** Standard WCAG AA floor for normal-size text. */
    const val AA_FLOOR = 4.5

    /** Floor applied when Increased Contrast is enabled; targets the AAA ratio. */
    const val INCREASED_CONTRAST_FLOOR = 7.0

    // sRGB linearization threshold and curve constants from the WCAG 2.x definition.
    private const val LINEAR_THRESHOLD = 0.03928
    private const val LINEAR_DIVISOR = 12.92
    private const val CURVE_OFFSET = 0.055
    private const val CURVE_DIVISOR = 1.055
    private const val CURVE_EXPONENT = 2.4

    // Rec. 709 luminance coefficients (a standard, fixed table — not tunable).
    private const val RED_COEFFICIENT = 0.2126
    private const val GREEN_COEFFICIENT = 0.7152
    private const val BLUE_COEFFICIENT = 0.0722

    // Additive term in the WCAG contrast ratio, modelling ambient flare.
    private const val CONTRAST_FLARE = 0.05

    /** Relative luminance of an sRGB color expressed as 0..1 components (components are clamped). */
    fun relativeLuminance(red: Double, green: Double, blue: Double): Double {
        fun linearize(component: Double): Double {
            val c = component.coerceIn(0.0, 1.0)
            if (c <= LINEAR_THRESHOLD) return c / LINEAR_DIVISOR
            return ((c + CURVE_OFFSET) / CURVE_DIVISOR).pow(CURVE_EXPONENT)
        }
        return RED_COEFFICIENT * linearize(red) +
            GREEN_COEFFICIENT * linearize(green) +
            BLUE_COEFFICIENT * linearize(blue)
    }

    /** Contrast ratio between two relative-luminance values (order-independent). */
    fun contrastRatio(lhs: Double, rhs: Double): Double {
        val lighter = maxOf(lhs, rhs)
        val darker = minOf(lhs, rhs)
        return (lighter + CONTRAST_FLARE) / (darker + CONTRAST_FLARE)
    }
}
