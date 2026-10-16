package one.meshcore.shared.map

/** The map content one render pass wants on screen: a basemap style plus the pins and lines over it. */
data class MapContent(val styleUrl: String, val points: List<MapPoint>, val lines: List<MapLine>)

/**
 * What the renderer has actually handed to MapLibre. The style URL is recorded when its load is
 * requested, so a slow load is not restarted; the points and lines are recorded only once a live style
 * existed to receive them. Recording content that was never applied is what leaves a map blank: the
 * GeoJSON sources are built empty and every later pass then reads as unchanged.
 */
data class AppliedMapContent(
    val styleUrl: String? = null,
    val points: List<MapPoint>? = null,
    val lines: List<MapLine>? = null,
)

/** The single action a render pass takes; see [mapContentApplyAction]. */
enum class MapContentApplyAction {
    /** Style and content are already on the live style and the camera has framed; leave MapLibre alone. */
    SKIP,

    /** A different basemap style is wanted; load it and let its completion apply the content. */
    LOAD_STYLE,

    /** Content changed and a live style can take it now. */
    APPLY_CONTENT,

    /** Content changed while the style is still loading; the load completion applies it, so record nothing. */
    AWAIT_STYLE,
}

/**
 * Decides what a render pass does with the current style, points and lines, mirroring the iOS
 * coordinator's deferred source creation.
 *
 * Contacts usually arrive from the database after the remote style load has started, so a pass can want
 * content with no style to put it on. That pass must resolve to [MapContentApplyAction.AWAIT_STYLE] and
 * leave [applied] untouched: claiming content as applied while the sources do not exist yet makes every
 * later pass compare equal and the pins never appear.
 */
fun mapContentApplyAction(
    requested: MapContent,
    applied: AppliedMapContent,
    hasLiveStyle: Boolean,
    hasFramed: Boolean,
): MapContentApplyAction {
    if (applied.styleUrl != requested.styleUrl) return MapContentApplyAction.LOAD_STYLE
    val contentChanged = applied.points != requested.points || applied.lines != requested.lines
    if (!contentChanged && hasFramed) return MapContentApplyAction.SKIP
    return if (hasLiveStyle) MapContentApplyAction.APPLY_CONTENT else MapContentApplyAction.AWAIT_STYLE
}

/**
 * Whether an initial framing pass has anything to move the camera to: a usable saved region, or at
 * least one pin to fit. A host must not mark itself framed on a pass that framed nothing — the pins
 * routinely arrive from the database after the style loads, and latching on the empty pass leaves the
 * map on the default world view with nothing left to trigger a re-frame. iOS avoids the race instead,
 * by awaiting the contact load before it applies the initial camera at all.
 */
fun canFrameInitialCamera(initialCamera: MapCameraRegion?, points: List<MapPoint>): Boolean =
    initialCamera?.validatedBounds() != null || points.isNotEmpty()
