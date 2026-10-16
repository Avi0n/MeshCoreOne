package one.meshcore.shared.tools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_tools_line_of_sight_repeater_position
import one.meshcore.shared.resources.tools_line_of_sight_drag_to_adjust
import one.meshcore.shared.resources.tools_line_of_sight_elevation_attribution
import one.meshcore.shared.resources.tools_line_of_sight_no_data
import one.meshcore.shared.resources.tools_line_of_sight_select_two_points
import one.meshcore.shared.rf.FresnelBand
import one.meshcore.shared.rf.ProfileSample
import one.meshcore.shared.rf.fresnelBandOutlines
import one.meshcore.shared.tools.interpolateProfileAtFraction
import one.meshcore.shared.tools.pathFractionFromChartX
import one.meshcore.shared.tools.terrainDistanceTickLabel
import one.meshcore.shared.tools.terrainElevationTickLabel
import one.meshcore.shared.tools.terrainNiceStep
import one.meshcore.shared.tools.terrainProfileEndpointSamples
import one.meshcore.shared.tools.terrainTickValues
import one.meshcore.shared.ui.SignalChromeRole
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.onTint
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun FresnelTerrainChart(
    samples: List<ProfileSample>,
    terrainColor: Color,
    losColor: Color,
    fresnelColor: Color,
    obstructedColor: Color,
    repeaterPathFraction: Double? = null,
    junctionDistanceMeters: Double? = null,
    onRepeaterDrag: ((Double) -> Unit)? = null,
    showDragHint: Boolean = false,
) {
    if (samples.size < 2) {
        LosEmptyState()
        return
    }
    val maxDist = (samples.last().x - samples.first().x).coerceAtLeast(1.0)
    val originX = samples.first().x
    val minE = samples.minOf { minOf(it.yTerrain, it.yBottom) } - ELEV_PAD_M
    val maxE = samples.maxOf { maxOf(it.yTerrain, it.yTop) } + ELEV_PAD_M
    val elevSpan = (maxE - minE).coerceAtLeast(1.0)
    val dragHint = stringResource(Res.string.tools_line_of_sight_drag_to_adjust)
    val junctionColor = MaterialTheme.colorScheme.onSurface.copy(alpha = JUNCTION_SEPARATOR_ALPHA)
    val endpointAColor = LOS_ENDPOINT_A_ROLE.tint()
    val endpointBColor = LOS_ENDPOINT_B_ROLE.tint()
    val repeaterColor = LOS_REPEATER_ROLE.tint()
    val endpointAOn = LOS_ENDPOINT_A_ROLE.onTint()
    val endpointBOn = LOS_ENDPOINT_B_ROLE.onTint()
    val repeaterOn = LOS_REPEATER_ROLE.onTint()
    val axisLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val terrainFillColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = GRID_LINE_ALPHA)
    val yStep = terrainNiceStep(elevSpan, TERRAIN_Y_TARGET_DIVISIONS)
    val xStep = terrainNiceStep(maxDist, TERRAIN_X_TARGET_DIVISIONS)
    val yTicks = terrainTickValues(minE, maxE, yStep)
    val xTicks = terrainTickValues(originX, originX + maxDist, xStep)
    val textMeasurer = rememberTextMeasurer()
    val endpointLabelStyle = TextStyle(
        color = endpointAOn,
        fontSize = ENDPOINT_LABEL_SP.sp,
        fontWeight = FontWeight.Bold,
    )
    val endpointBLabelStyle = TextStyle(
        color = endpointBOn,
        fontSize = ENDPOINT_LABEL_SP.sp,
        fontWeight = FontWeight.Bold,
    )
    val repeaterLabelStyle = TextStyle(
        color = repeaterOn,
        fontSize = REPEATER_LABEL_SP.sp,
        fontWeight = FontWeight.Bold,
    )
    val axisLabelStyle = TextStyle(
        color = axisLabelColor,
        fontSize = AXIS_LABEL_SP.sp,
    )
    val labelA = textMeasurer.measure(TERRAIN_ENDPOINT_A, endpointLabelStyle)
    val labelB = textMeasurer.measure(TERRAIN_ENDPOINT_B, endpointBLabelStyle)
    val labelR = textMeasurer.measure(TERRAIN_REPEATER_LABEL, repeaterLabelStyle)
    val yTickLayouts = yTicks.mapIndexed { index, y ->
        textMeasurer.measure(terrainElevationTickLabel(y, index == yTicks.lastIndex), axisLabelStyle)
    }
    val xTickLayouts = xTicks.mapIndexed { index, x ->
        textMeasurer.measure(terrainDistanceTickLabel(x, xStep, index == xTicks.lastIndex), axisLabelStyle)
    }
    val dragModifier = if (onRepeaterDrag != null) {
        Modifier
            .pointerInput(onRepeaterDrag, samples) {
                detectDragGestures { change, _ ->
                    val padL = CHART_PAD_LEADING_DP.dp.toPx()
                    val plotW = size.width - padL - CHART_PAD_TRAILING_DP.dp.toPx()
                    change.consume()
                    onRepeaterDrag(pathFractionFromChartX(change.position.x - padL, plotW))
                }
            }
            .semantics { contentDescription = dragHint }
    } else {
        Modifier
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(PROFILE_HEIGHT_DP.dp),
    ) {
        Canvas(Modifier.fillMaxSize().then(dragModifier)) {
            val padL = CHART_PAD_LEADING_DP.dp.toPx()
            val padT = CHART_PAD_TOP_DP.dp.toPx()
            val padR = CHART_PAD_TRAILING_DP.dp.toPx()
            val padB = CHART_PAD_BOTTOM_DP.dp.toPx()
            val plotW = (size.width - padL - padR).coerceAtLeast(1f)
            val plotH = (size.height - padT - padB).coerceAtLeast(1f)
            val xAt = { x: Double -> padL + ((x - originX) / maxDist * plotW).toFloat() }
            val yAt = { e: Double -> padT + plotH - ((e - minE) / elevSpan * plotH).toFloat() }

            yTicks.forEachIndexed { index, y ->
                val gy = yAt(y)
                drawLine(
                    color = gridLineColor,
                    start = Offset(padL, gy),
                    end = Offset(padL + plotW, gy),
                    strokeWidth = GRID_STROKE_PX,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(GRID_DASH_ON, GRID_DASH_OFF)),
                )
                val layout = yTickLayouts[index]
                drawText(
                    layout,
                    topLeft = Offset(
                        padL - AXIS_Y_LABEL_GAP_PX - layout.size.width,
                        gy - layout.size.height / 2f,
                    ),
                )
            }
            val plotBottom = padT + plotH
            xTicks.forEachIndexed { index, x ->
                val layout = xTickLayouts[index]
                drawText(
                    layout,
                    topLeft = Offset(
                        xAt(x) - layout.size.width / 2f,
                        plotBottom + AXIS_X_LABEL_GAP_PX,
                    ),
                )
            }

            // The full envelope, then the inner 60% zone over it: the clearance verdict is defined against
            // that inner edge, so both bands are peers of one outline set rather than one hand-drawn fill.
            fresnelBandOutlines(samples).forEach { outline ->
                val band = Path()
                band.traceChart(outline.vertices.map { vertex -> xAt(vertex.x) to yAt(vertex.elevation) })
                band.close()
                drawPath(band, color = fresnelColor.copy(alpha = outline.band.fillAlpha))
            }

            obstructedProfileSamples(samples).forEach { sample ->
                val x = xAt(sample.x)
                drawLine(
                    color = obstructedColor,
                    start = Offset(x, yAt(sample.yTerrain)),
                    end = Offset(x, yAt(sample.yBottom60)),
                    strokeWidth = 2f,
                )
            }

            val terrain = Path()
            terrain.traceChart(samples.map { sample -> xAt(sample.x) to yAt(sample.yTerrain) })
            val terrainFill = Path().apply {
                addPath(terrain)
                lineTo(xAt(samples.last().x), plotBottom)
                lineTo(xAt(samples.first().x), plotBottom)
                close()
            }
            drawPath(terrainFill, color = terrainFillColor)
            drawPath(terrain, color = terrainColor, style = Stroke(width = 3f))

            val los = Path()
            los.traceChart(samples.map { sample -> xAt(sample.x) to yAt(sample.yLos) })
            drawPath(los, color = losColor, style = Stroke(width = 3f))

            terrainProfileEndpointSamples(samples)?.let { (sampleA, sampleB) ->
                val endpointRadius = ENDPOINT_MARKER_RADIUS_DP.dp.toPx()
                val centerA = Offset(xAt(sampleA.x), yAt(sampleA.yLos))
                val centerB = Offset(xAt(sampleB.x), yAt(sampleB.yLos))
                drawCircle(endpointAColor, radius = endpointRadius, center = centerA)
                drawCircle(endpointBColor, radius = endpointRadius, center = centerB)
                drawText(labelA, topLeft = centeredTextTopLeft(centerA, labelA.size.width, labelA.size.height))
                drawText(labelB, topLeft = centeredTextTopLeft(centerB, labelB.size.width, labelB.size.height))
            }

            if (junctionDistanceMeters != null) {
                val jx = xAt(junctionDistanceMeters)
                drawLine(
                    color = junctionColor,
                    start = Offset(jx, padT),
                    end = Offset(jx, plotBottom),
                    strokeWidth = JUNCTION_SEPARATOR_STROKE,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(JUNCTION_DASH_ON, JUNCTION_DASH_OFF),
                    ),
                )
            }

            if (repeaterPathFraction != null) {
                val sample = interpolateProfileAtFraction(samples, repeaterPathFraction) ?: return@Canvas
                val mx = xAt(sample.x)
                drawLine(
                    color = repeaterColor,
                    start = Offset(mx, yAt(sample.yTerrain)),
                    end = Offset(mx, yAt(sample.yLos)),
                    strokeWidth = 2f,
                )
                drawCircle(
                    color = repeaterColor,
                    radius = REPEATER_MARKER_RADIUS_DP.dp.toPx(),
                    center = Offset(mx, yAt(sample.yLos)),
                )
                val repeaterCenter = Offset(mx, yAt(sample.yLos))
                drawText(
                    labelR,
                    topLeft = centeredTextTopLeft(repeaterCenter, labelR.size.width, labelR.size.height),
                )
            }
        }
    }
    fresnelRepeaterSlider(onRepeaterDrag, repeaterPathFraction)?.let { slider ->
        val repeaterPosition = stringResource(Res.string.android_tools_line_of_sight_repeater_position)
        Slider(
            value = slider.fraction.toFloat(),
            onValueChange = { slider.onDrag(it.toDouble()) },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = repeaterPosition },
        )
        if (showDragHint) {
            Text(
                text = dragHint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    Text(
        text = stringResource(Res.string.tools_line_of_sight_elevation_attribution),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Fill opacity per Fresnel band, matching the two teal fills on iOS. */
private val FresnelBand.fillAlpha: Float
    get() = when (this) {
        FresnelBand.OUTER -> 0.25f
        FresnelBand.INNER -> 0.5f
    }

private fun centeredTextTopLeft(center: Offset, width: Int, height: Int): Offset = Offset(
    center.x - width / 2f,
    center.y - height / 2f,
)

/** iOS `TerrainProfileCanvas.chartHeight`. */
private const val PROFILE_HEIGHT_DP = 200

/** iOS `TerrainProfileCanvas` EdgeInsets(top: 24, leading: 45, bottom: 28, trailing: 16). */
private const val CHART_PAD_TOP_DP = 24
private const val CHART_PAD_LEADING_DP = 45
private const val CHART_PAD_BOTTOM_DP = 28
private const val CHART_PAD_TRAILING_DP = 16
private const val TERRAIN_Y_TARGET_DIVISIONS = 4
private const val TERRAIN_X_TARGET_DIVISIONS = 5
private const val AXIS_LABEL_SP = 9
private const val AXIS_Y_LABEL_GAP_PX = 8f
private const val AXIS_X_LABEL_GAP_PX = 10f
internal const val FRESNEL_OBSTRUCTED_ALPHA = 0.7f
private const val GRID_STROKE_PX = 0.5f
private const val GRID_DASH_ON = 4f
private const val GRID_DASH_OFF = 4f
private const val GRID_LINE_ALPHA = 0.45f
private const val REPEATER_MARKER_RADIUS_DP = 16
private const val ENDPOINT_MARKER_RADIUS_DP = 6
private const val ENDPOINT_LABEL_SP = 8
private const val REPEATER_LABEL_SP = 11
internal const val TERRAIN_ENDPOINT_A = "A"
internal const val TERRAIN_ENDPOINT_B = "B"
internal const val TERRAIN_REPEATER_LABEL = "R"
internal val LOS_ENDPOINT_A_ROLE = SignalChromeRole.PRIMARY
internal val LOS_ENDPOINT_B_ROLE = SignalChromeRole.TERTIARY
internal val LOS_REPEATER_ROLE = SignalChromeRole.SECONDARY
internal val LOS_FRESNEL_CLEAR_ROLE = SignalChromeRole.TERTIARY
internal val LOS_FRESNEL_OBSTRUCTED_ROLE = SignalChromeRole.ERROR
private const val JUNCTION_SEPARATOR_ALPHA = 0.35f
private const val JUNCTION_SEPARATOR_STROKE = 1.5f
private const val JUNCTION_DASH_ON = 6f
private const val JUNCTION_DASH_OFF = 4f
private const val ELEV_PAD_M = 5.0

/**
 * iOS `TerrainProfileCanvas` empty state: shown only when the chart is on screen with no samples,
 * not on the idle points sheet.
 */
@Composable
private fun LosEmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(LOS_EMPTY_STATE_GAP, Alignment.CenterVertically),
        modifier = Modifier
            .fillMaxWidth()
            .height(PROFILE_HEIGHT_DP.dp)
            .padding(LOS_EMPTY_STATE_PADDING),
    ) {
        Icon(
            imageVector = LOS_EMPTY_CHART_ICON,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LOS_EMPTY_CHART_ICON_DP.dp),
        )
        Text(
            text = stringResource(Res.string.tools_line_of_sight_no_data),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(Res.string.tools_line_of_sight_select_two_points),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private val LOS_EMPTY_STATE_GAP = 8.dp
private val LOS_EMPTY_STATE_PADDING = 24.dp
internal val LOS_SECTION_ICON_GAP = 6.dp
internal const val LOS_SECTION_ICON_DP = 18
private const val LOS_EMPTY_CHART_ICON_DP = 48

/** iOS `ContentUnavailableView` `chart.xyaxis.line`. */
internal val LOS_EMPTY_CHART_ICON get() = AppIcons.ShowChart
