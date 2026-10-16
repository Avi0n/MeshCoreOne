package one.meshcore.shared.map

/**
 * Stroke and casing a [LineStyle] draws with. MapLibre cannot data-drive `line-dasharray`, so the host
 * builds one layer pair per style from these values. Pinned to iOS `MC1MapView.setupLineLayers`.
 */
data class MapLinePaint(val width: Float, val dash: List<Float>?, val casingWidth: Float, val casingDash: List<Float>?)

/**
 * The paint a [LineStyle] uses. Dashed styles keep a shorter casing dash so the white halo follows
 * the gaps; solid good hops and message paths stay undashed so a measured route cannot be read as a
 * hypothetical sight line.
 */
fun LineStyle.mapLinePaint(): MapLinePaint = when (this) {
    LineStyle.LOS -> MapLinePaint(
        width = LINE_WIDTH_STANDARD,
        dash = DASH_LOS_AND_WEAK,
        casingWidth = CASING_WIDTH_STANDARD,
        casingDash = DASH_CASING,
    )
    LineStyle.TRACE_UNTRACED -> MapLinePaint(
        width = LINE_WIDTH_UNTRACED,
        dash = DASH_UNTRACED,
        casingWidth = CASING_WIDTH_UNTRACED,
        casingDash = DASH_CASING,
    )
    LineStyle.TRACE_WEAK, LineStyle.TRACE_MEDIUM -> MapLinePaint(
        width = LINE_WIDTH_STANDARD,
        dash = DASH_LOS_AND_WEAK,
        casingWidth = CASING_WIDTH_STANDARD,
        casingDash = DASH_CASING,
    )
    LineStyle.TRACE_GOOD -> MapLinePaint(
        width = LINE_WIDTH_GOOD,
        dash = null,
        casingWidth = CASING_WIDTH_GOOD,
        casingDash = null,
    )
    LineStyle.MESSAGE_PATH -> MapLinePaint(
        width = LINE_WIDTH_STANDARD,
        dash = null,
        casingWidth = CASING_WIDTH_STANDARD,
        casingDash = null,
    )
    LineStyle.LOCATION_TRAIL -> MapLinePaint(
        width = LINE_WIDTH_TRAIL,
        dash = DASH_TRAIL,
        casingWidth = CASING_WIDTH_UNTRACED,
        casingDash = DASH_TRAIL_CASING,
    )
}

private const val LINE_WIDTH_STANDARD = 3f
private const val LINE_WIDTH_UNTRACED = 2f
private const val LINE_WIDTH_GOOD = 4f
private const val LINE_WIDTH_TRAIL = 2.5f
private const val CASING_WIDTH_STANDARD = 6f
private const val CASING_WIDTH_UNTRACED = 5f
private const val CASING_WIDTH_GOOD = 7f

private val DASH_CASING = listOf(0.7f, 1.3f)
private val DASH_LOS_AND_WEAK = listOf(1.4f, 2.6f)
private val DASH_UNTRACED = listOf(1.75f, 3.25f)
private val DASH_TRAIL = listOf(1.6f, 2.8f)
private val DASH_TRAIL_CASING = listOf(0.8f, 1.4f)
