package one.meshcore.shared.map.ui

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import one.meshcore.shared.map.AppliedMapContent
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapClustering
import one.meshcore.shared.map.MapContent
import one.meshcore.shared.map.MapLine
import one.meshcore.shared.map.MapPinPalette
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.basemapStyleUrl
import one.meshcore.shared.map.isAuthorized
import one.meshcore.shared.map.locationAuthorizationStatus
import one.meshcore.shared.map.normalizedWorldLongitude
import one.meshcore.shared.map.resolveMapIsDark
import one.meshcore.shared.theme.AppColorSchemePreference
import one.meshcore.shared.theme.LocalUseDarkTheme
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.PropertyValue
import org.maplibre.android.style.layers.SymbolLayer
import android.graphics.Color as AndroidColor
import one.meshcore.shared.map.LatLng as MeshLatLng

internal const val SOURCE_ID = "mc1-contacts"
internal const val LAYER_ID = "mc1-contacts-circles"
internal const val ICON_LAYER_ID = "mc1-contacts-icons"
internal const val LABEL_LAYER_ID = "mc1-contacts-labels"
internal const val BADGE_LAYER_ID = "mc1-contacts-badges"
internal const val CLUSTER_CIRCLE_LAYER_ID = "mc1-contacts-cluster-circles"
internal const val CLUSTER_LABEL_LAYER_ID = "mc1-contacts-cluster-labels"
internal const val FIXED_SOURCE_ID = "mc1-contacts-fixed"
internal const val FIXED_LAYER_ID = "mc1-contacts-fixed-circles"
internal const val FIXED_ICON_LAYER_ID = "mc1-contacts-fixed-icons"
internal const val FIXED_LABEL_LAYER_ID = "mc1-contacts-fixed-labels"
internal const val FIXED_BADGE_LAYER_ID = "mc1-contacts-fixed-badges"
internal const val LINE_SOURCE_ID = "mc1-lines"
internal const val LINE_LAYER_PREFIX = "mc1-lines"

internal const val PROP_ID = "id"
internal const val PROP_PIN_STYLE = "pinStyle"
internal const val PROP_LINE_STYLE = "lineStyle"
internal const val PROP_ICON = "icon"
internal const val PROP_ANCHOR = "anchor"
internal const val PROP_LABEL = "label"

// Attribute names pinned to the iOS feature attributes so both renderers read the same wire shape.
internal const val PROP_HOP_INDEX = "hopIndex"
internal const val PROP_BADGE_TEXT = "badgeText"
internal const val PROP_SEGMENT_OPACITY = "segmentOpacity"

/** The count attribute MapLibre stamps on a cluster feature; its presence is what marks one. */
internal const val PROP_POINT_COUNT = "point_count"
internal const val CIRCLE_RADIUS = 8f
internal const val CIRCLE_STROKE_WIDTH = 2f
internal const val CLUSTER_CIRCLE_OPACITY = 1f
internal const val CLUSTER_STROKE_WIDTH = 2f
private const val COLOR_CHANNEL_MAX = 255

/** 0.8 opacity, matching the iOS cluster-circle stroke. */
internal const val CLUSTER_STROKE_ALPHA = 204
internal const val CLUSTER_LABEL_TEXT_SIZE = 14f
internal const val DENSITY_EPSILON = 0.01f
internal const val PILL_CORNER_FRACTION = 0.4f

/**
 * The font stack the OpenFreeMap glyph server serves. MapLibre's default ("Open Sans Regular") 404s
 * there, and a symbol layer whose glyphs fail to load draws nothing at all.
 */
internal const val MAP_FONT_NAME = "Noto Sans Regular"

/** Line-of-sight pin letters, matching iOS `PinSpriteRenderer` (`A`/`B` endpoints). */
internal const val LOS_ENDPOINT_A = "A"
internal const val LOS_ENDPOINT_B = "B"

// Pill background for the SNR/distance badge, stretched around its text by icon-text-fit.
internal const val ICON_PILL = "mc1-pill-bg"
internal const val LABEL_TEXT_SIZE = 12f
internal const val LABEL_HALO_WIDTH = 1.4f
internal const val LABEL_OFFSET_EM = 1.3f

/** Draw order for a label with no hop ordinal, so an unnumbered pin's name sorts ahead of hop 1. */
internal const val LABEL_SORT_KEY_DEFAULT = 0f

// Badge pill: white at 85% over a 1 px transparent margin, matching the iOS pill sprite.
internal const val BADGE_TEXT_SIZE = 11f
internal const val BADGE_FIT_PADDING_VERTICAL = 2f
internal const val BADGE_FIT_PADDING_HORIZONTAL = 8f

/** White halo behind every map line, matching iOS `setupLineLayers` casing opacity. */
internal const val LINE_CASING_OPACITY = 0.8f

/**
 * Recency ramp for sampled location-fix dots, oldest to newest: a warm family that harmonizes with the
 * hero teardrop while stopping short of its hue, so the newest dot is never mistaken for it. Tuned per
 * basemap because one ramp cannot stay legible on both grounds. Pinned to the iOS palettes.
 */
internal val LOCATION_DOT_PALETTE_LIGHT = intArrayOf(0xA19687, 0xB68F68, 0xD17A47, 0xE76740, 0xF55E47)
internal val LOCATION_DOT_PALETTE_DARK = intArrayOf(0xB1A695, 0xC59E77, 0xD8885A, 0xED7C5A, 0xF87662)

// The trail is deliberately faint, grey and dashed: it threads sampled fixes, and a solid stroke would
// read as the proven node-to-node route the message-path style owns. Dash lengths live on [mapLinePaint].
internal const val BOUNDS_PADDING_PX = 80
internal const val SINGLE_POINT_ZOOM = 12.0
internal const val SATELLITE_SOURCE_ID = "mc1-satellite-tiles"
internal const val SATELLITE_LAYER_ID = "mc1-satellite-layer"
internal const val TOPO_SOURCE_ID = "mc1-topo-tiles"
internal const val TOPO_LAYER_ID = "mc1-topo-layer"
internal const val RASTER_TILE_SIZE = 256
internal const val TILEJSON_VERSION = "2.1.0"

// Provider credit shown in MapLibre's attribution sheet; both providers require it by their terms.
internal const val ESRI_ATTRIBUTION = "<a href=\"https://www.esri.com\">Esri</a>"
internal const val OPEN_TOPO_MAP_ATTRIBUTION = "<a href=\"https://opentopomap.org\">OpenTopoMap</a>"

/**
 * Android MapLibre host: OpenFreeMap style with contact pins as circle features, pin click, and
 * camera restore/persist.
 */
object AndroidMapLibreHostRenderer : MapLibreHostRenderer {
    @Composable
    override fun Host(
        points: List<MapPoint>,
        modifier: Modifier,
        style: MapStyleSelection,
        initialCamera: MapCameraRegion?,
        cameraTarget: MapCameraRegion?,
        cameraTargetVersion: Int,
        lines: List<MapLine>,
        colorSchemePreference: AppColorSchemePreference,
        isOffline: Boolean,
        interactive: Boolean,
        showLabels: Boolean,
        northLocked: Boolean,
        showsUserLocation: Boolean,
        onCameraIdle: ((MapCameraRegion) -> Unit)?,
        onPointClick: ((String) -> Unit)?,
        onMapClick: ((MeshLatLng) -> Unit)?,
        onMapLongPress: ((MeshLatLng) -> Unit)?,
        onStyleLoadingChange: ((Boolean) -> Unit)?,
    ) = AndroidMapLibreHost(
        points = points,
        modifier = modifier,
        style = style,
        initialCamera = initialCamera,
        cameraTarget = cameraTarget,
        cameraTargetVersion = cameraTargetVersion,
        lines = lines,
        colorSchemePreference = colorSchemePreference,
        isOffline = isOffline,
        interactive = interactive,
        showLabels = showLabels,
        northLocked = northLocked,
        showsUserLocation = showsUserLocation,
        onCameraIdle = onCameraIdle,
        onPointClick = onPointClick,
        onMapClick = onMapClick,
        onMapLongPress = onMapLongPress,
        onStyleLoadingChange = onStyleLoadingChange,
    )
}

@Composable
fun AndroidMapLibreHost(
    points: List<MapPoint>,
    modifier: Modifier,
    style: MapStyleSelection,
    initialCamera: MapCameraRegion?,
    cameraTarget: MapCameraRegion?,
    cameraTargetVersion: Int,
    lines: List<MapLine>,
    colorSchemePreference: AppColorSchemePreference,
    isOffline: Boolean,
    interactive: Boolean,
    showLabels: Boolean,
    northLocked: Boolean,
    showsUserLocation: Boolean,
    onCameraIdle: ((MapCameraRegion) -> Unit)?,
    onPointClick: ((String) -> Unit)?,
    onMapClick: ((MeshLatLng) -> Unit)?,
    onMapLongPress: ((MeshLatLng) -> Unit)?,
    onStyleLoadingChange: ((Boolean) -> Unit)?,
) {
    val context = LocalContext.current
    val latestOnCameraIdle by rememberUpdatedState(onCameraIdle)
    val latestOnPointClick by rememberUpdatedState(onPointClick)
    val latestOnMapClick by rememberUpdatedState(onMapClick)
    val latestOnMapLongPress by rememberUpdatedState(onMapLongPress)
    val latestOnStyleLoadingChange by rememberUpdatedState(onStyleLoadingChange)
    val hostState = remember {
        MapLibreHostState(
            onCameraIdle = { latestOnCameraIdle?.invoke(it) },
            onPointClick = { latestOnPointClick?.invoke(it) },
            onMapClick = { latestOnMapClick?.invoke(it) },
            onMapLongPress = { latestOnMapLongPress?.invoke(it) },
        )
    }

    val mapView = remember(context) {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(mapView) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }
    // iOS `resolvedMapIsDark` reads `@Environment(\.colorScheme)` after the Appearance override.
    // Device `isSystemInDarkTheme()` would ignore Settings → Appearance Light/Dark.
    val chromeDark = LocalUseDarkTheme.current
    val styleUrl = basemapStyleUrl(
        style = style,
        preference = colorSchemePreference,
        systemDark = chromeDark,
        isOffline = isOffline,
    )
    // The recency dots are tinted for the ground they sit on. A basemap appearance flip changes the
    // style URL, so the reload re-registers them; reading the flag at registration needs no other hook.
    hostState.isDarkBasemap = resolveMapIsDark(colorSchemePreference, chromeDark)
    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize(),
        update = { view ->
            view.getMapAsync { map ->
                hostState.ensureMap(map)
                hostState.applyGestures(map, interactive)
                hostState.applyChrome(map, interactive, view.resources.displayMetrics.density)
                hostState.applyNorthLock(map, interactive, northLocked)
                hostState.applyStyleAndPoints(
                    map = map,
                    content = MapContent(styleUrl = styleUrl, points = points, lines = lines),
                    pass = StyleLoadPass(
                        initialCamera = initialCamera,
                        showLabels = showLabels,
                        showsUserLocation = showsUserLocation,
                        context = context,
                        onStyleLoadingChange = { latestOnStyleLoadingChange?.invoke(it) },
                    ),
                )
                hostState.applyCameraTarget(map, cameraTarget, cameraTargetVersion)
                hostState.applyShowsUserLocation(map, context, showsUserLocation)
                hostState.updateLabelVisibility(map, showLabels)
                if (hostState.mapStyleSelection != style) {
                    hostState.mapStyleSelection = style
                    hostState.updateRasterVisibility(map)
                }
            }
        },
    )
}

/** The four layers one pin source feeds: markers, sprites, name labels and midpoint badges. */
internal data class PinLayerIds(
    val sourceId: String,
    val circleLayerId: String,
    val iconLayerId: String,
    val labelLayerId: String,
    val badgeLayerId: String,
)

/** Label, location, camera, and loading callback the style-load completion reapplies. */
internal data class StyleLoadPass(
    val initialCamera: MapCameraRegion?,
    val showLabels: Boolean,
    val showsUserLocation: Boolean,
    val context: Context,
    val onStyleLoadingChange: (Boolean) -> Unit,
)

internal class MapLibreHostState(
    val onCameraIdle: (MapCameraRegion) -> Unit,
    val onPointClick: (String) -> Unit,
    val onMapClick: (MeshLatLng) -> Unit,
    val onMapLongPress: (MeshLatLng) -> Unit,
) {
    internal var mapBound = false

    /** The latest content a render pass staged; the style-load completion reads this, not a captured copy. */
    internal var currentContent = MapContent(styleUrl = "", points = emptyList(), lines = emptyList())
    internal var appliedContent = AppliedMapContent()
    internal var lastCameraTarget: MapCameraRegion? = null
    internal var lastCameraTargetVersion = 0
    internal var iconsRegisteredForStyle: Style? = null

    /** Hop sprites already registered against [iconsRegisteredForStyle]; cleared when that style changes. */
    internal val registeredHopIcons = mutableSetOf<String>()
    internal var pinSprites = MapPinSprites(density = 1f)
    internal var locationComponentStyle: Style? = null

    /** Resolved basemap darkness; picks the recency palette the location-fix dots register with. */
    var isDarkBasemap = false
    internal var hasFramed = false
    internal var userGestureMove = false
    internal var programmaticMove = false
    var mapStyleSelection: MapStyleSelection = MapStyleSelection.STANDARD
}

/** Either precision authorizes the location indicator, matching [locationAuthorizationStatus]. */
internal fun hasLocationPermission(context: Context): Boolean = context.locationAuthorizationStatus().isAuthorized

internal fun regionFromVisibleBounds(map: MapLibreMap): MapCameraRegion? {
    val bounds = map.projection.visibleRegion.latLngBounds
    val center = bounds.center
    val spanLat = bounds.latitudeSpan
    val spanLon = bounds.longitudeSpan
    if (!isPositiveFiniteSpan(spanLat, spanLon)) return null
    return MapCameraRegion(
        center = MeshLatLng(center.latitude, center.longitude).normalizedWorldLongitude(),
        spanLatDeg = spanLat,
        spanLonDeg = spanLon,
    )
}

private fun isPositiveFiniteSpan(spanLat: Double, spanLon: Double): Boolean {
    val latOk = spanLat.isFinite() && spanLat > 0.0
    val lonOk = spanLon.isFinite() && spanLon > 0.0
    return latOk && lonOk
}

internal fun dpToPx(dp: Int, density: Float): Int = (dp * density).toInt()

/**
 * Style-layer construction for the pin and line sources: the layers themselves plus the data-driven
 * expressions they read. Stateless, so it sits beside the host rather than inside it.
 */
internal object MapPinLayers {
    fun visibility(visible: Boolean): PropertyValue<String> = PropertyFactory.visibility(
        if (visible) Property.VISIBLE else Property.NONE,
    )

    /** The count bubble and its label, drawn only for features MapLibre marked as clusters. */
    fun addClusterLayers(style: Style) {
        val isCluster = Expression.has(PROP_POINT_COUNT)
        style.addLayer(
            CircleLayer(CLUSTER_CIRCLE_LAYER_ID, SOURCE_ID).withFilter(isCluster).withProperties(
                PropertyFactory.circleRadius(clusterRadiusExpression()),
                PropertyFactory.circleColor(MapPinPalette.BLUE.toAndroidColor()),
                PropertyFactory.circleOpacity(CLUSTER_CIRCLE_OPACITY),
                PropertyFactory.circleStrokeWidth(CLUSTER_STROKE_WIDTH),
                PropertyFactory.circleStrokeColor(
                    AndroidColor.argb(
                        CLUSTER_STROKE_ALPHA,
                        COLOR_CHANNEL_MAX,
                        COLOR_CHANNEL_MAX,
                        COLOR_CHANNEL_MAX,
                    ),
                ),
            ),
        )
        style.addLayer(
            SymbolLayer(CLUSTER_LABEL_LAYER_ID, SOURCE_ID).withFilter(isCluster).withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get(PROP_POINT_COUNT))),
                PropertyFactory.textFont(arrayOf(MAP_FONT_NAME)),
                PropertyFactory.textSize(CLUSTER_LABEL_TEXT_SIZE),
                PropertyFactory.textColor(AndroidColor.WHITE),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textIgnorePlacement(true),
            ),
        )
    }

    @Suppress("ArrayPrimitive")
    fun addPinLayers(style: Style, ids: PinLayerIds, filter: Expression?) {
        // Circles remain as a fallback for empty-icon points; sprites zero their radius.

        val circles = CircleLayer(ids.circleLayerId, ids.sourceId).withProperties(
            PropertyFactory.circleRadius(circleRadiusExpression()),
            PropertyFactory.circleColor(pinStyleColorExpression()),
            PropertyFactory.circleStrokeWidth(CIRCLE_STROKE_WIDTH),
            PropertyFactory.circleStrokeColor(AndroidColor.WHITE),
        )
        val icons = SymbolLayer(ids.iconLayerId, ids.sourceId).withProperties(
            PropertyFactory.iconImage(Expression.get(PROP_ICON)),
            // Per feature: a teardrop centred on its coordinate points half an icon off the node.
            PropertyFactory.iconAnchor(Expression.get(PROP_ANCHOR)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        val labels = SymbolLayer(ids.labelLayerId, ids.sourceId).withProperties(
            PropertyFactory.textField(Expression.get(PROP_LABEL)),
            PropertyFactory.textFont(arrayOf(MAP_FONT_NAME)),
            PropertyFactory.textSize(LABEL_TEXT_SIZE),
            PropertyFactory.textColor(AndroidColor.WHITE),
            PropertyFactory.textHaloColor(AndroidColor.BLACK),
            PropertyFactory.textHaloWidth(LABEL_HALO_WIDTH),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textOffset(arrayOf(0f, LABEL_OFFSET_EM)),
            PropertyFactory.textOptional(true),
            PropertyFactory.textAllowOverlap(false),
            // Names resolve collisions in hop order, mirroring the iOS hopIndex symbol sort key.
            PropertyFactory.symbolSortKey(hopSortKeyExpression()),
        )
        val badges = badgeLayer(ids)
        if (filter != null) {
            circles.setFilter(filter)
            icons.setFilter(filter)
            labels.setFilter(filter)
            badges.setFilter(Expression.all(filter, Expression.has(PROP_BADGE_TEXT)))
        }
        style.addLayer(circles)
        style.addLayer(icons)
        style.addLayer(labels)
        style.addLayer(badges)
    }

    /** The SNR/distance pill at a link midpoint: text on a stretched pill sprite, drawn over the pins. */
    @Suppress("ArrayPrimitive")
    private fun badgeLayer(ids: PinLayerIds): SymbolLayer = SymbolLayer(ids.badgeLayerId, ids.sourceId)
        .withFilter(Expression.has(PROP_BADGE_TEXT))
        .withProperties(
            PropertyFactory.textField(Expression.get(PROP_BADGE_TEXT)),
            PropertyFactory.textFont(arrayOf(MAP_FONT_NAME)),
            PropertyFactory.textSize(BADGE_TEXT_SIZE),
            PropertyFactory.textColor(AndroidColor.BLACK),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.iconImage(ICON_PILL),
            PropertyFactory.iconTextFit(Property.ICON_TEXT_FIT_BOTH),
            PropertyFactory.iconTextFitPadding(
                arrayOf(
                    BADGE_FIT_PADDING_VERTICAL,
                    BADGE_FIT_PADDING_HORIZONTAL,
                    BADGE_FIT_PADDING_VERTICAL,
                    BADGE_FIT_PADDING_HORIZONTAL,
                ),
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )

    /** Hop ordinal as a draw-order key; a point without one sorts at [LABEL_SORT_KEY_DEFAULT]. */
    private fun hopSortKeyExpression(): Expression = Expression.switchCase(
        Expression.has(PROP_HOP_INDEX),
        Expression.toNumber(Expression.get(PROP_HOP_INDEX)),
        Expression.literal(LABEL_SORT_KEY_DEFAULT),
    )

    /** Cluster-bubble radius grows in steps with the point count, matching the iOS stepping expression. */
    private fun clusterRadiusExpression(): Expression {
        val steps = MapClustering.CIRCLE_RADIUS_STEPS
        check(steps.size == MapClustering.CIRCLE_RADIUS_STOP_COUNT)
        val base = steps.first()
        val grown = steps.drop(1)
        val low = grown[0]
        val mid = grown[1]
        val high = grown[2]
        return Expression.step(
            Expression.get(PROP_POINT_COUNT),
            Expression.literal(base.radiusPx),
            Expression.literal(low.pointCount),
            Expression.literal(low.radiusPx),
            Expression.literal(mid.pointCount),
            Expression.literal(mid.radiusPx),
            Expression.literal(high.pointCount),
            Expression.literal(high.radiusPx),
        )
    }

    /** Zero radius whenever a sprite is set, so a lettered pin is not also a coloured disc. */
    private fun circleRadiusExpression(): Expression = Expression.switchCase(
        Expression.neq(Expression.get(PROP_ICON), Expression.literal("")),
        Expression.literal(0f),
        Expression.literal(CIRCLE_RADIUS),
    )

    /** Data-driven pin colors so dropped pins and node kinds are distinguishable on the map. */
    private fun pinStyleColorExpression(): Expression = Expression.match(
        Expression.get(PROP_PIN_STYLE),
        Expression.literal(PinStyle.DROPPED_PIN.rawValue),
        Expression.color(MapLibreChrome.SECONDARY.toAndroidColor()),
        Expression.literal(PinStyle.CONTACT_REPEATER.rawValue),
        Expression.color(MapLibreChrome.TERTIARY.toAndroidColor()),
        Expression.literal(PinStyle.REPEATER.rawValue),
        Expression.color(MapLibreChrome.TERTIARY.toAndroidColor()),
        // Ring fill uses secondary so an assigned repeater is not the same colour as endpoint A/B.
        Expression.literal(PinStyle.REPEATER_RING_BLUE.rawValue),
        Expression.color(MapLibreChrome.SECONDARY.toAndroidColor()),
        Expression.literal(PinStyle.REPEATER_RING_GREEN.rawValue),
        Expression.color(MapLibreChrome.SECONDARY.toAndroidColor()),
        Expression.literal(PinStyle.POINT_A.rawValue),
        Expression.color(MapLibreChrome.PRIMARY.toAndroidColor()),
        Expression.literal(PinStyle.POINT_B.rawValue),
        Expression.color(MapLibreChrome.TERTIARY.toAndroidColor()),
        Expression.literal(PinStyle.CROSSHAIR.rawValue),
        Expression.color(MapLibreChrome.SECONDARY.toAndroidColor()),
        Expression.literal(PinStyle.OBSTRUCTION.rawValue),
        Expression.color(MapLibreChrome.ERROR.toAndroidColor()),
        Expression.literal(PinStyle.CONTACT_ROOM.rawValue),
        Expression.color(MapLibreChrome.SECONDARY.toAndroidColor()),
        Expression.literal(PinStyle.CONTACT_CHAT.rawValue),
        Expression.color(MapLibreChrome.PRIMARY.toAndroidColor()),
        Expression.color(MapLibreChrome.PRIMARY.toAndroidColor()),
    )

    /**
     * SNR-tier line paint (and LOS). When callers supply [MapLine] geometry with [LineStyle] from
     * [one.meshcore.shared.map.lineStyleForSnr], segments render in the matching quality color.
     */
    fun lineStyleColorExpression(): Expression = Expression.match(
        Expression.get(PROP_LINE_STYLE),
        Expression.literal(LineStyle.TRACE_GOOD.rawValue),
        Expression.color(MapLibreChrome.PRIMARY.toAndroidColor()),
        Expression.literal(LineStyle.TRACE_MEDIUM.rawValue),
        Expression.color(MapLibreChrome.TERTIARY.toAndroidColor()),
        Expression.literal(LineStyle.TRACE_WEAK.rawValue),
        Expression.color(MapLibreChrome.ERROR.toAndroidColor()),
        Expression.literal(LineStyle.TRACE_UNTRACED.rawValue),
        Expression.color(MapLibreChrome.MUTED.toAndroidColor()),
        Expression.literal(LineStyle.LOS.rawValue),
        Expression.color(MapLibreChrome.PRIMARY.toAndroidColor()),
        Expression.literal(LineStyle.MESSAGE_PATH.rawValue),
        Expression.color(MapLibreChrome.PRIMARY.toAndroidColor()),
        Expression.color(MapLibreChrome.PRIMARY.toAndroidColor()),
    )
}
