package one.meshcore.shared.map.ui

/**
 * MapLibre compass and attribution placement, matching iOS `MC1MapView`.
 *
 * Compass is top-end with 8 dp margins and hidden on non-interactive previews.
 * Attribution sits bottom-start with a 30 dp bottom inset so it clears the scale bar.
 */
object MapLibreChrome {
    const val COMPASS_MARGIN_DP = 8
    const val ATTRIBUTION_MARGIN_START_DP = 4
    const val ATTRIBUTION_MARGIN_BOTTOM_DP = 30

    /** Material 3 baseline primary (`#6750A4`). Sprites bake at style load. */
    val PRIMARY = PinRgb(103, 80, 164)

    /** Material 3 baseline secondary (`#625B71`). */
    val SECONDARY = PinRgb(98, 91, 113)

    /** Material 3 baseline tertiary (`#7D5260`). */
    val TERTIARY = PinRgb(125, 82, 96)

    /** Material 3 baseline error (`#B3261E`). */
    val ERROR = PinRgb(179, 38, 30)

    /** Material 3 baseline outline (`#79747E`), for untraced / muted strokes. */
    val MUTED = PinRgb(121, 116, 126)

    data class PinRgb(val r: Int, val g: Int, val b: Int)
}
