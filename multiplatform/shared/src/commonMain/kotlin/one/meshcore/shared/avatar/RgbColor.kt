package one.meshcore.shared.avatar

import kotlin.math.roundToInt

/**
 * Platform-neutral sRGB color: three 0..1 components, framework-free so both Compose and any renderer
 * consume it ([toArgb] maps to `androidx.compose.ui.graphics.Color`). Mirrors the sRGB components
 * the Swift solver builds `Color(.sRGB, red:green:blue:)` from, so an [argb] value is byte-comparable
 * against the iOS avatar output.
 */
data class RgbColor(val red: Double, val green: Double, val blue: Double) {
    /** WCAG relative luminance of this fill, used to pick a legible glyph and to assert AA in tests. */
    val luminance: Double get() = WcagContrast.relativeLuminance(red, green, blue)

    /**
     * 32-bit `0xAARRGGBB` (opaque). Each component is clamped and rounded to 8 bits the same way an
     * sRGB color resolves to a device pixel; the round matches Swift's `(component * 255).rounded()`.
     */
    val argb: Int
        get() {
            val a = ALPHA_OPAQUE shl ALPHA_SHIFT
            return a or (channel(red) shl RED_SHIFT) or (channel(green) shl GREEN_SHIFT) or channel(blue)
        }

    private fun channel(component: Double): Int = (component.coerceIn(0.0, 1.0) * MAX_CHANNEL).roundToInt()

    companion object {
        private const val MAX_CHANNEL = 255.0
        private const val ALPHA_OPAQUE = 0xFF
        private const val ALPHA_SHIFT = 24
        private const val RED_SHIFT = 16
        private const val GREEN_SHIFT = 8
        private const val CHANNEL_MASK = 0xFF

        /** Reconstructs a color from an `0xAARRGGBB` int (alpha ignored), for fixed-swatch constants. */
        fun fromArgb(argb: Int): RgbColor = RgbColor(
            red = ((argb shr RED_SHIFT) and CHANNEL_MASK) / MAX_CHANNEL,
            green = ((argb shr GREEN_SHIFT) and CHANNEL_MASK) / MAX_CHANNEL,
            blue = (argb and CHANNEL_MASK) / MAX_CHANNEL,
        )
    }
}
