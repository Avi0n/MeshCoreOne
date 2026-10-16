package one.meshcore.shared.map.ui

import androidx.compose.runtime.getValue
import one.meshcore.shared.map.MapPinIcon
import one.meshcore.shared.map.MapPinPalette
import one.meshcore.shared.map.MapPinShape
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.mapPinHopBadge
import one.meshcore.shared.map.mapPinIconName
import org.maplibre.android.maps.ImageContent
import org.maplibre.android.maps.ImageStretches
import org.maplibre.android.maps.Style

/** Sprites belong to a style, so a reloaded style needs them again; the same one needs them once. */
internal fun MapLibreHostState.ensurePinIcons(style: Style) {
    if (iconsRegisteredForStyle === style) return
    style.addImage(MapPinIcon.CHAT, pinSprites.shapeBitmap(MapPinShape.CHAT_TEARDROP))
    style.addImage(MapPinIcon.REPEATER, pinSprites.shapeBitmap(MapPinShape.REPEATER_DIAMOND))
    style.addImage(MapPinIcon.ROOM, pinSprites.shapeBitmap(MapPinShape.ROOM_HEXAGON))
    style.addImage(MapPinIcon.DROPPED, pinSprites.shapeBitmap(MapPinShape.DROPPED_PIN))
    style.addImage(
        MapPinIcon.REPEATER_RING_BLUE,
        pinSprites.ringedRepeaterBitmap(
            MapPinPalette.BLUE.toAndroidColor(),
        ),
    )
    style.addImage(
        MapPinIcon.REPEATER_RING_GREEN,
        pinSprites.ringedRepeaterBitmap(
            MapPinPalette.GREEN.toAndroidColor(),
        ),
    )
    style.addImage(MapPinIcon.REPEATER_RING_WHITE, pinSprites.hopPinBitmap(badge = null, ringed = true))
    style.addImage(
        MapPinIcon.POINT_A,
        pinSprites.letterPinBitmap(
            MapPinPalette.BLUE.toAndroidColor(),
            LOS_ENDPOINT_A,
        ),
    )
    style.addImage(
        MapPinIcon.POINT_B,
        pinSprites.letterPinBitmap(
            MapPinPalette.GREEN.toAndroidColor(),
            LOS_ENDPOINT_B,
        ),
    )
    style.addImage(MapPinIcon.CROSSHAIR, pinSprites.crosshairBitmap())
    style.addImage(MapPinIcon.OBSTRUCTION, pinSprites.obstructionBitmap())
    // A badge point must draw no marker of its own; a transparent sprite also zeroes its circle
    // radius, which is what keeps the midpoint pill from also dropping a phantom pin.
    style.addImage(MapPinIcon.BADGE, pinSprites.transparentBitmap())
    addPillImage(style)
    addLocationFixImages(style)
    registeredHopIcons.clear()
    iconsRegisteredForStyle = style
}

/** One dot sprite per recency grade plus the hero teardrop, tinted for the current basemap ground. */
private fun MapLibreHostState.addLocationFixImages(style: Style) {
    val palette = if (isDarkBasemap) LOCATION_DOT_PALETTE_DARK else LOCATION_DOT_PALETTE_LIGHT
    for (bucket in 0 until MapPinIcon.RECENCY_BUCKET_COUNT) {
        style.addImage(
            "${MapPinIcon.LOCATION_FIX_PREFIX}$bucket",
            pinSprites.locationDotBitmap(palette[bucket], isDarkBasemap),
        )
    }
    style.addImage(
        MapPinIcon.LOCATION_LATEST,
        pinSprites.heroPinBitmap(),
    )
}

/**
 * Registers the numbered hop sprites the current point set needs, the analogue of the iOS
 * on-demand hop renderer: one sprite per hop number actually plotted, not per possible hop.
 */
internal fun MapLibreHostState.ensureHopIcons(style: Style, points: List<MapPoint>) {
    for (point in points) {
        val badge = mapPinHopBadge(point.pinStyle, point.hopIndex) ?: continue
        registerHopIcon(style, point, badge)
    }
}

private fun MapLibreHostState.registerHopIcon(style: Style, point: MapPoint, badge: Int) {
    val name = mapPinIconName(point.pinStyle, point.hopIndex)
    if (!registeredHopIcons.add(name)) return
    val ringed = point.pinStyle == PinStyle.REPEATER_RING_WHITE
    style.addImage(name, pinSprites.hopPinBitmap(badge, ringed = ringed))
}

/** The stretchable pill behind badge text; the corners stay square under icon-text-fit. */
private fun MapLibreHostState.addPillImage(style: Style) {
    val bitmap = pinSprites.pillBitmap()
    val size = bitmap.width.toFloat()
    val corner = size * PILL_CORNER_FRACTION
    val stretch = listOf(ImageStretches(corner, size - corner))
    style.addImage(
        ICON_PILL,
        bitmap,
        stretch,
        stretch,
        ImageContent(corner, corner, size - corner, size - corner),
    )
}
