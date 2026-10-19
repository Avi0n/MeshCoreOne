// PortedFrom: MC1/Views/Tools/LineOfSight/LineOfSightView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/PointsSummarySectionView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/RFSettingsSectionView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/LineOfSight/Components/ResultsCardView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.los

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapLibreMapSurface
import com.meshcoreone.android.core.maps.MapLibreRuntime
import com.meshcoreone.android.core.model.Coordinate
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.flow.collectLatest

@Composable
fun LineOfSightEntry(
    dependencies: LineOfSightFeatureDependencies,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies) {
        LineOfSightStateHolder(
            scope = scope,
            elevationSource = dependencies.elevationSource,
            pathAnalyzer = dependencies.pathAnalyzer,
            analysisContext = dependencies.analysisContext,
            failureReporter = dependencies.failureReporter,
        ).also {
            it.configure(dependencies.contactSource, dependencies.radioId, dependencies.deviceFrequencyKHz())
        }
    }
    val state by holder.state.collectAsStateWithLifecycleCompat()
    val mapPresentation = remember { LineOfSightMapPresentation() }
    var encodedCamera by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = encodedCamera?.let(MapCamera::decode) ?: LineOfSightMapPresentation.WORLD_CAMERA

    DisposableEffect(holder) {
        onDispose(holder::dispose)
    }
    LaunchedEffect(holder) {
        holder.loadRepeaters()
        if (encodedCamera == null) {
            LineOfSightMapPresentation.fit(holder.state.value.repeaterCoordinates)?.let {
                encodedCamera = it.encode()
            }
        }
    }
    LaunchedEffect(holder) {
        holder.state.collectLatest {
            holder.onAnalysisStatusChanged()?.let(LineOfSightMapPresentation::fit)?.let { fitted ->
                encodedCamera = fitted.encode()
            }
        }
    }

    LineOfSightScreen(
        state = state,
        holder = holder,
        mapPresentation = mapPresentation,
        camera = camera,
        onCameraChanged = { encodedCamera = it.encode() },
        modifier = modifier,
    )
}

@Composable
private fun LineOfSightScreen(
    state: LineOfSightState,
    holder: LineOfSightStateHolder,
    mapPresentation: LineOfSightMapPresentation,
    camera: MapCamera,
    onCameraChanged: (MapCamera) -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (maxWidth >= 840.dp) {
            Row(Modifier.fillMaxSize()) {
                LineOfSightMap(state, holder, mapPresentation, camera, onCameraChanged, Modifier.weight(1.2f).fillMaxHeight())
                LineOfSightPanel(state, holder, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                LineOfSightMap(state, holder, mapPresentation, camera, onCameraChanged, Modifier.weight(0.82f).fillMaxWidth())
                LineOfSightPanel(state, holder, Modifier.weight(1.18f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun LineOfSightMap(
    state: LineOfSightState,
    holder: LineOfSightStateHolder,
    mapPresentation: LineOfSightMapPresentation,
    camera: MapCamera,
    onCameraChanged: (MapCamera) -> Unit,
    modifier: Modifier,
) {
    val accessibilityLabel = stringResource(AppToolsStrings.toolsLineOfSight)
    val resources = LocalContext.current.resources
    var size by remember { mutableStateOf(IntSize.Zero) }
    val viewConfiguration = LocalViewConfiguration.current
    val presentation = remember(state, camera) { mapPresentation.build(state, camera, labelsEnabled = true) }
    val gestureModifier = Modifier
        .onSizeChanged { size = it }
        .pointerInput(camera, size, viewConfiguration) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var moved = false
                var lastPosition = down.position
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    lastPosition = change.position
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                    if (!change.pressed) {
                        if (!moved) {
                            LineOfSightMapPresentation.coordinateAt(lastPosition, size, camera)?.let { coordinate ->
                                if (change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) {
                                    holder.handleMapLongPress(coordinate)
                                } else {
                                    holder.handleMapTap(coordinate)
                                }
                            }
                        }
                        break
                    }
                }
            }
        }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (MapLibreRuntime.isSupported) {
            MapLibreMapSurface(
                presentation = presentation,
                clusteringEnabled = true,
                labelsEnabled = true,
                accessibilityLabel = accessibilityLabel,
                onCameraChanged = onCameraChanged,
                onMarkerSelected = { marker ->
                    state.repeatersWithLocation.firstOrNull { it.id == marker.id }?.let(holder::toggleContact)
                },
                modifier = gestureModifier.fillMaxSize(),
            )
        } else {
            Text(
                stringResource(AppToolsStrings.toolsLineOfSightNoData),
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 4.dp,
        ) {
            Text(
                if (state.isRelocating) {
                    val point = pointName(state.relocatingPoint)
                    "${AppToolsStrings.toolsLineOfSightRelocating(resources, point)} " +
                        stringResource(AppToolsStrings.toolsLineOfSightTapMapInstruction)
                } else {
                    stringResource(AppToolsStrings.toolsLineOfSightLongPressPointsHint)
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun LineOfSightPanel(state: LineOfSightState, holder: LineOfSightStateHolder, modifier: Modifier) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(AppToolsStrings.toolsLineOfSight),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        PointsSection(state, holder)
        if (state.canAnalyze) {
            Button(
                onClick = holder::requestAnalysis,
                enabled = !state.isAnalyzing,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (state.isAnalyzing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(
                        if (state.isAnalyzing) AppToolsStrings.toolsLineOfSightAnalyzing
                        else AppToolsStrings.toolsLineOfSightAnalyze,
                    ),
                )
            }
            RfSettings(state, holder)
        }
        when (val status = state.analysisStatus) {
            is AnalysisStatus.Result -> ResultCard(status.result)
            is AnalysisStatus.RelayResult -> RelayResultCard(status.result)
            is AnalysisStatus.Error -> AnalysisError(status.message, holder::retryAnalysis)
            AnalysisStatus.Idle -> Unit
        }
        if (state.hasAnalysisResult) {
            LineOfSightTerrainProfile(state, holder::dragRepeater)
        }
    }
}

@Composable
private fun PointsSection(state: LineOfSightState, holder: LineOfSightStateHolder) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(AppToolsStrings.toolsLineOfSightPoints), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (state.isRelocating) {
                OutlinedButton(onClick = { holder.setRelocatingPoint(null) }) {
                    Text(stringResource(AppToolsStrings.toolsLineOfSightCancel))
                }
            }
        }
        if (!state.isRelocating) {
            PointCard("A", state.pointA, PointID.POINT_A, state, holder)
            if (state.repeaterPoint != null) {
                RepeaterCard(state, holder)
            } else if (state.shouldShowRepeaterPlaceholder) {
                OutlinedButton(onClick = holder::addRepeaterAndAnalyze, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("R  ${stringResource(AppToolsStrings.toolsLineOfSightAddRepeater)}")
                }
            }
            PointCard("B", state.pointB, PointID.POINT_B, state, holder)
            if (state.showsLongPressHint) {
                Text(
                    stringResource(AppToolsStrings.toolsLineOfSightLongPressPointsHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.elevationFetchFailed) {
                Text(
                    stringResource(AppToolsStrings.toolsLineOfSightElevationUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun PointCard(
    label: String,
    point: SelectedPoint?,
    id: PointID,
    state: LineOfSightState,
    holder: LineOfSightStateHolder,
) {
    val color = if (id == PointID.POINT_A) ColorBlue else ColorGreen
    val relocateLabel = stringResource(AppToolsStrings.toolsLineOfSightRelocate)
    val clearLabel = stringResource(AppToolsStrings.toolsLineOfSightClear)
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PointBadge(label, color, point != null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(point?.displayName(stringResource(AppToolsStrings.toolsLineOfSightDroppedPin))
                        ?: stringResource(AppToolsStrings.toolsLineOfSightNotSelected))
                    point?.let {
                        Text(
                            if (it.isLoadingElevation) stringResource(AppToolsStrings.toolsLineOfSightLoadingElevation)
                            else it.groundElevation?.let { elevation ->
                                formatMeters(elevation + it.additionalHeight)
                            }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (point != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    CoordinateActions(point.coordinate, id)
                    IconButton(
                        onClick = { holder.toggleRelocation(id) },
                        enabled = state.isRelocateEnabled(id),
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = relocateLabel
                        },
                    ) { Text("⌖", style = MaterialTheme.typography.titleLarge) }
                    IconButton(
                        onClick = { if (id == PointID.POINT_A) holder.clearPointA() else holder.clearPointB() },
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = clearLabel
                        },
                    ) { Text("×", style = MaterialTheme.typography.titleLarge) }
                }
            }
            point?.let {
                HeightEditor(
                    groundElevation = it.groundElevation,
                    additionalHeight = it.additionalHeight,
                    onChange = { value -> holder.updateAdditionalHeight(id, value) },
                )
            }
        }
    }
}

@Composable
private fun RepeaterCard(state: LineOfSightState, holder: LineOfSightStateHolder) {
    val repeater = state.repeaterPoint ?: return
    val relocateLabel = stringResource(AppToolsStrings.toolsLineOfSightRelocate)
    val clearLabel = stringResource(AppToolsStrings.toolsLineOfSightClear)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PointBadge("R", ColorPurple, true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(AppToolsStrings.toolsLineOfSightRepeater))
                    state.repeaterGroundElevation?.let {
                        Text(
                            formatMeters(it + repeater.additionalHeight),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                CoordinateActions(repeater.coordinate, PointID.REPEATER)
                IconButton(
                    onClick = { holder.toggleRelocation(PointID.REPEATER) },
                    enabled = state.isRelocateEnabled(PointID.REPEATER),
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = relocateLabel
                    },
                ) { Text("⌖", style = MaterialTheme.typography.titleLarge) }
                IconButton(
                    onClick = holder::clearRepeater,
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = clearLabel
                    },
                ) {
                    Text("×", style = MaterialTheme.typography.titleLarge)
                }
            }
            HeightEditor(state.repeaterGroundElevation, repeater.additionalHeight, holder::editRepeaterHeight)
        }
    }
}

@Composable
private fun HeightEditor(groundElevation: Double?, additionalHeight: Double, onChange: (Double) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "${stringResource(AppToolsStrings.toolsLineOfSightGroundElevation)}: " +
                    (groundElevation?.let(::formatMeters) ?: "…"),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                "${stringResource(AppToolsStrings.toolsLineOfSightAdditionalHeight)}: ${formatMeters(additionalHeight)}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        val step = if (usesMetric(Locale.getDefault())) 1.0 else 0.3048
        val additionalHeightLabel = stringResource(AppToolsStrings.toolsLineOfSightAdditionalHeight)
        IconButton(
            onClick = { onChange((additionalHeight - step).coerceAtLeast(0.0)) },
            modifier = Modifier.size(48.dp).semantics { contentDescription = "$additionalHeightLabel −" },
        ) {
            Text("−")
        }
        IconButton(
            onClick = { onChange((additionalHeight + step).coerceAtMost(200.0)) },
            modifier = Modifier.size(48.dp).semantics { contentDescription = "$additionalHeightLabel +" },
        ) {
            Text("+")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RfSettings(state: LineOfSightState, holder: LineOfSightStateHolder) {
    var frequency by remember(state.frequencyMHz) { mutableStateOf(holder.formatFrequencyForEditing(state.frequencyMHz)) }
    var hadFocus by remember { mutableStateOf(false) }
    fun commit() {
        holder.commitFrequencyText(frequency)?.let { frequency = it }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(AppToolsStrings.toolsLineOfSightRfSettings), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = frequency,
                onValueChange = { frequency = it.replace(',', '.') },
                label = { Text(stringResource(AppToolsStrings.toolsLineOfSightFrequency)) },
                suffix = { Text(stringResource(AppToolsStrings.toolsLineOfSightMhz)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (hadFocus && !it.isFocused) commit()
                    hadFocus = it.isFocused
                },
            )
            Text(stringResource(AppToolsStrings.toolsLineOfSightRefraction), style = MaterialTheme.typography.labelLarge)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RefractionPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = abs(state.refractionK - preset.refractionK) < 0.000001,
                        onClick = { holder.setRefractionK(preset.refractionK) },
                        label = {
                            Text(
                                stringResource(
                                    when (preset) {
                                        RefractionPreset.NONE -> AppToolsStrings.toolsLineOfSightRefractionNone
                                        RefractionPreset.STANDARD -> AppToolsStrings.toolsLineOfSightRefractionStandard
                                        RefractionPreset.DUCTING -> AppToolsStrings.toolsLineOfSightRefractionDucting
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: PathAnalysisResult) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val locale = Locale.getDefault()
    Card(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(AppToolsStrings.toolsLineOfSightResults), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "${stringResource(LosFormatters.statusLabel(result.clearanceStatus))} · " +
                        "${LosFormatters.formatClearancePercent(result.worstClearancePercent)}%",
                )
            }
            Text("${formatDistance(result.distanceMeters)} · ${LosFormatters.formatPathLoss(result.totalPathLoss, locale)}")
            if (result.clearanceStatus == ClearanceStatus.BLOCKED) {
                Text(stringResource(LosFormatters.blockedSubtitle), color = MaterialTheme.colorScheme.error)
            }
            if (expanded) {
                HorizontalDivider()
                ResultValue(stringResource(AppToolsStrings.toolsLineOfSightFreeSpaceLoss), LosFormatters.formatPathLoss(result.freeSpacePathLoss, locale))
                LosFormatters.formatDiffractionLoss(result.peakDiffractionLoss, locale)?.let {
                    ResultValue(stringResource(AppToolsStrings.toolsLineOfSightDiffractionLoss), it)
                }
                ResultValue(stringResource(AppToolsStrings.toolsLineOfSightTotal), LosFormatters.formatPathLoss(result.totalPathLoss, locale))
                ResultValue(stringResource(AppToolsStrings.toolsLineOfSightObstructionsFound), result.obstructionPoints.size.toString())
                Text(
                    "${formatFrequency(result.frequencyMHz)} · ${LosFormatters.formatKFactor(result.refractionK, locale)} · " +
                        stringResource(AppToolsStrings.toolsLineOfSightElevationAttribution),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RelayResultCard(result: RelayPathAnalysisResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(AppToolsStrings.toolsLineOfSightResults), style = MaterialTheme.typography.titleMedium)
            Text(
                "${stringResource(LosFormatters.statusLabel(result.overallStatus))} · ${formatDistance(result.totalDistanceMeters)}",
                style = MaterialTheme.typography.titleSmall,
            )
            SegmentRow(result.segmentAR)
            SegmentRow(result.segmentRB)
            Text(
                stringResource(AppToolsStrings.toolsLineOfSightElevationAttribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SegmentRow(segment: SegmentAnalysisResult) {
    ResultValue(
        "${segment.startLabel} → ${segment.endLabel}",
        "${stringResource(LosFormatters.statusLabel(segment.clearanceStatus))} · " +
            "${LosFormatters.formatClearancePercent(segment.worstClearancePercent)}% · ${formatDistance(segment.distanceMeters)}",
    )
}

@Composable
private fun ResultValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun AnalysisError(message: String, retry: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(AppToolsStrings.toolsLineOfSightAnalysisFailed), style = MaterialTheme.typography.titleMedium)
            Text(message, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedButton(onClick = retry) { Text(stringResource(AppToolsStrings.toolsLineOfSightRetry)) }
        }
    }
}

@Composable
private fun CoordinateActions(coordinate: Coordinate, point: PointID) {
    val context = LocalContext.current
    val pointLabel = pointName(point)
    val copyLabel = stringResource(AppToolsStrings.toolsLineOfSightCopyCoordinates)
    val openLabel = stringResource(AppToolsStrings.toolsLineOfSightOpenInMaps)
    val formatted = "${coordinate.latitude.formatCoordinate()}, ${coordinate.longitude.formatCoordinate()}"
    val label = Uri.encode(pointLabel)
    val uri = Uri.parse("geo:${coordinate.latitude},${coordinate.longitude}?q=${coordinate.latitude},${coordinate.longitude}($label)")
    val mapIntent = remember(uri) { Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    val canOpenMap = remember(mapIntent) { mapIntent.resolveActivity(context.packageManager) != null }
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Coordinate", formatted))
        },
        modifier = Modifier.size(48.dp).semantics { contentDescription = copyLabel },
    ) { Text("⧉") }
    IconButton(
        onClick = { context.startActivity(mapIntent) },
        enabled = canOpenMap,
        modifier = Modifier.size(48.dp).semantics { contentDescription = openLabel },
    ) { Text("↗") }
}

@Composable
private fun pointName(point: PointID?): String = when (point) {
    PointID.POINT_A -> stringResource(AppToolsStrings.toolsLineOfSightPointA)
    PointID.POINT_B -> stringResource(AppToolsStrings.toolsLineOfSightPointB)
    PointID.REPEATER -> stringResource(AppToolsStrings.toolsLineOfSightRepeater)
    null -> ""
}

@Composable
private fun PointBadge(label: String, color: androidx.compose.ui.graphics.Color, selected: Boolean) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (selected) color else MaterialTheme.colorScheme.outlineVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatMeters(value: Double): String = if (usesMetric(Locale.getDefault())) {
    "${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value)} m"
} else {
    "${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(value / 0.3048)} ft"
}

private fun formatDistance(meters: Double): String {
    val locale = Locale.getDefault()
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return if (usesMetric(locale)) "${number.format(meters / 1000.0)} km"
    else "${number.format(meters / 1609.344)} mi"
}

private fun formatFrequency(mhz: Double): String =
    "${NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(mhz)} MHz"

private fun Double.formatCoordinate(): String =
    NumberFormat.getNumberInstance(Locale.ROOT).apply {
        maximumFractionDigits = 6
        minimumFractionDigits = 6
        isGroupingUsed = false
    }.format(this)

private fun usesMetric(locale: Locale): Boolean = locale.country !in setOf("US", "LR", "MM")

private val ColorBlue = androidx.compose.ui.graphics.Color(0xFF1677FF)
private val ColorGreen = androidx.compose.ui.graphics.Color(0xFF1B8A5A)
private val ColorPurple = androidx.compose.ui.graphics.Color(0xFF8A3FFC)

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateWithLifecycleCompat() =
    collectAsState()
