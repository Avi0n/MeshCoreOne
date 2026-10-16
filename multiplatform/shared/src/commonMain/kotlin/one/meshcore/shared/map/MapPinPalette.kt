package one.meshcore.shared.map

import one.meshcore.shared.map.ui.MapLibreChrome

/**
 * Fill colors for map pin sprites. Material baseline roles in [MapLibreChrome] are for UI chrome
 * and wash out on a basemap; pins use the same saturated fills iOS `PinSpriteRenderer` bakes so
 * hop numbers, A/B letters, and node kinds stay readable on light and dark tiles.
 */
object MapPinPalette {
    /** iOS `pin-chat` terracotta. */
    val CHAT = MapLibreChrome.PinRgb(204, 122, 92)

    /** iOS `systemCyan` repeater fill. */
    val REPEATER = MapLibreChrome.PinRgb(50, 173, 230)

    /** iOS `pin-room` orange. */
    val ROOM = MapLibreChrome.PinRgb(255, 136, 0)

    /** iOS `systemPink` dropped pin. */
    val DROPPED = MapLibreChrome.PinRgb(255, 45, 85)

    /** iOS `systemBlue` — hop badge, point A, cluster bubble. */
    val BLUE = MapLibreChrome.PinRgb(0, 122, 255)

    /** iOS `systemGreen` point B. */
    val GREEN = MapLibreChrome.PinRgb(52, 199, 89)

    /** iOS `systemPurple` line-of-sight radio. */
    val PURPLE = MapLibreChrome.PinRgb(175, 82, 222)
}
