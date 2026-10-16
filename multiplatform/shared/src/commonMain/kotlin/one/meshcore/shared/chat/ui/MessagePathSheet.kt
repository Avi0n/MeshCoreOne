package one.meshcore.shared.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.chat.pathdetail.MessagePathMapBuilder
import one.meshcore.shared.chat.pathdetail.PathHopRow
import one.meshcore.shared.chat.pathdetail.PathHopType
import one.meshcore.shared.map.CENTER_ON_USER_SPAN_DEG
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.SnrQuality
import one.meshcore.shared.map.snrQuality
import one.meshcore.shared.map.ui.LocalMapOffline
import one.meshcore.shared.map.ui.MapLibreHost
import one.meshcore.shared.map.ui.MapLocateButton
import one.meshcore.shared.map.ui.MapOptionsMenu
import one.meshcore.shared.map.ui.PathDistanceBanner
import one.meshcore.shared.map.ui.rememberMapDisplayChrome
import one.meshcore.shared.map.ui.rememberNetworkOnlyStyleGate
import one.meshcore.shared.map.ui.rememberSnrBadgeFormatter
import one.meshcore.shared.remotenode.ui.PossibleMatchMarker
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_path_center_on_path
import one.meshcore.shared.resources.chats_path_copy_accessibility
import one.meshcore.shared.resources.chats_path_copy_hint
import one.meshcore.shared.resources.chats_path_hop_node_id
import one.meshcore.shared.resources.chats_path_hop_number
import one.meshcore.shared.resources.chats_path_hop_sender
import one.meshcore.shared.resources.chats_path_hop_signal_quality
import one.meshcore.shared.resources.chats_path_hop_signal_unknown
import one.meshcore.shared.resources.chats_path_hop_unknown
import one.meshcore.shared.resources.chats_path_map
import one.meshcore.shared.resources.chats_path_receiver_label
import one.meshcore.shared.resources.chats_path_unavailable_description
import one.meshcore.shared.resources.chats_path_unavailable_title
import one.meshcore.shared.resources.chats_signal_excellent
import one.meshcore.shared.resources.chats_signal_fair
import one.meshcore.shared.resources.chats_signal_good
import one.meshcore.shared.resources.chats_signal_poor
import one.meshcore.shared.resources.remotenodes_remote_nodes_history_snr
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.SignalBarMeter
import one.meshcore.shared.ui.barTint
import one.meshcore.shared.ui.filledBarCount
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.pathHopChipLabels
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen path map (iOS `MessagePathMapView`). Name resolution is already folded into the
 * display; unresolved nodes show the hop hex or unknown label. Nested destination with Up.
 */
@Composable
fun MessagePathSheet(
    presentation: MessagePathPresentation,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isOffline: Boolean = LocalMapOffline.current,
    locationService: LocationService? = null,
    mapDisplayPreferences: one.meshcore.shared.map.MapDisplayPreferencesStore? = null,
    knownLocation: () -> LatLng? = { null },
) {
    var selectedArrivalID by remember(presentation) {
        mutableStateOf(
            presentation.arrivals.firstOrNull { it.arrival.id == presentation.initialSelectionID }?.arrival?.id
                ?: presentation.arrivals.firstOrNull()?.arrival?.id,
        )
    }
    val display = presentation.displayFor(selectedArrivalID)
    val mapChrome = rememberMapDisplayChrome(mapDisplayPreferences)
    val (styleDisabled, styleDisabledReason) = rememberNetworkOnlyStyleGate(isNetworkAvailable = !isOffline)
    FullScreenOverlay(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(Res.string.chats_path_map),
    ) { innerPadding ->
        val snrFormatter = rememberSnrBadgeFormatter()
        val plot = remember(display, snrFormatter) {
            MessagePathMapBuilder.build(display, formatter = snrFormatter)
        }
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            MessagePathArrivalCapsules(
                arrivals = presentation.arrivals,
                selectedID = selectedArrivalID,
                onSelect = { selectedArrivalID = it },
            )
            if (shouldShowPathMapUnavailable(plot.points.size) || !display.available) {
                ChatDetailEmptyPane(
                    title = stringResource(Res.string.chats_path_unavailable_title),
                    description = stringResource(Res.string.chats_path_unavailable_description),
                    icon = AppIcons.Map,
                    modifier = Modifier.weight(1f),
                )
            } else {
                var cameraTarget by remember(plot) { mutableStateOf(plot.region) }
                var cameraTargetVersion by remember { mutableIntStateOf(0) }
                fun moveCamera(region: MapCameraRegion) {
                    cameraTarget = region
                    cameraTargetVersion += 1
                }
                MessagePathMapOverlay(
                    plot = plot,
                    hopCount = display.hopCount,
                    cameraTarget = cameraTarget,
                    cameraTargetVersion = cameraTargetVersion,
                    isOffline = isOffline,
                    locationService = locationService,
                    knownLocation = knownLocation,
                    mapChrome = mapChrome,
                    styleDisabled = styleDisabled,
                    styleDisabledReason = styleDisabledReason,
                    onCamera = ::moveCamera,
                    onCenterOnPath = plot.region?.let { region -> { moveCamera(region) } },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun MessagePathHopList(display: MessagePathDisplay, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(applyHorizontalPadding = false) {
            display.rows.forEach { hop ->
                PathHopRowContent(hop = hop)
            }
        }
        if (display.hasIntermediateHops) {
            val copyLabel = stringResource(Res.string.chats_path_copy_accessibility)
            val copyHint = stringResource(Res.string.chats_path_copy_hint)
            ListItem(
                headlineContent = {
                    PathHopChipRow(hops = pathHopChipLabels(display.pathString))
                },
                trailingContent = {
                    IconButton(onClick = {
                        scope.launch {
                            clipboard.setClipEntry(plainTextClipEntry(display.pathStringForClipboard))
                            haptics.success()
                        }
                    }) {
                        Icon(
                            imageVector = PATH_COPY_ICON,
                            contentDescription = "$copyLabel. $copyHint",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PathHopRowContent(hop: PathHopRow) {
    val unknown = stringResource(Res.string.chats_path_hop_unknown)
    val role = when (val type = hop.type) {
        PathHopType.Sender -> stringResource(Res.string.chats_path_hop_sender)
        is PathHopType.Intermediate -> formattedStringResource(Res.string.chats_path_hop_number, type.number)
        PathHopType.Receiver -> stringResource(Res.string.chats_path_receiver_label)
    }
    val name = hop.nodeName?.takeIf { it.isNotEmpty() } ?: hop.nodeID ?: unknown
    val showTrailing = pathHopShowsTrailingSignal(
        isReceiver = hop.type == PathHopType.Receiver,
        hasSnr = hop.snr != null,
    )
    val snrSpoken = hop.snr?.takeIf { showTrailing }?.let { snr ->
        formattedStringResource(
            Res.string.chats_path_hop_signal_quality,
            snrQualityLabel(snrQuality(snr)),
            formatSnrDb(snr),
        )
    }
    val idSpoken = hop.nodeID?.let { formattedStringResource(Res.string.chats_path_hop_node_id, it) }
    val spokenLabel = "$role: $name"
    val spokenValue = listOfNotNull(snrSpoken, idSpoken).joinToString(", ")
    val trailingSnr = hop.snr?.takeIf { showTrailing }
    ListItem(
        headlineContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                hop.nodeID?.takeIf { it != name }?.let { id ->
                    Text(
                        text = id,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(name)
                if (hop.matchKind == NodeNameMatchKind.FALLBACK) {
                    PossibleMatchMarker()
                }
            }
        },
        supportingContent = { Text(role) },
        trailingContent = trailingSnr?.let { snr ->
            {
                val quality = snrQuality(snr)
                Column(horizontalAlignment = Alignment.End) {
                    SignalBarMeter(
                        filledBars = quality.filledBarCount,
                        tint = quality.barTint(),
                    )
                    Text(
                        text = pathHopCompactSnrVisibleText(
                            snrFormatted = formatSnrDb(snr),
                            snrWord = stringResource(Res.string.remotenodes_remote_nodes_history_snr),
                            unit = stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = spokenLabel
                if (spokenValue.isNotEmpty()) {
                    stateDescription = spokenValue
                }
            },
    )
}

@Composable
private fun MessagePathMapOverlay(
    plot: MessagePathMapBuilder.Plot,
    hopCount: Int,
    cameraTarget: MapCameraRegion?,
    cameraTargetVersion: Int,
    isOffline: Boolean,
    locationService: LocationService?,
    knownLocation: () -> LatLng?,
    mapChrome: one.meshcore.shared.map.ui.MapDisplayChrome,
    styleDisabled: (one.meshcore.shared.map.MapStyleSelection) -> Boolean,
    styleDisabledReason: (one.meshcore.shared.map.MapStyleSelection) -> String?,
    onCamera: (MapCameraRegion) -> Unit,
    modifier: Modifier = Modifier,
    onOpenFullMap: (() -> Unit)? = null,
    onCenterOnPath: (() -> Unit)? = null,
) {
    Box(
        modifier.then(
            if (onOpenFullMap != null) Modifier.clickable(onClick = onOpenFullMap) else Modifier,
        ),
    ) {
        MapLibreHost(
            points = plot.points,
            lines = plot.lines,
            initialCamera = plot.region,
            cameraTarget = cameraTarget,
            cameraTargetVersion = cameraTargetVersion,
            isOffline = isOffline,
            style = mapChrome.style,
            colorSchemePreference = mapChrome.colorScheme,
            showLabels = mapChrome.showLabels,
            clusteringEnabled = mapChrome.clusteringEnabled,
            northLocked = mapChrome.northLocked,
            showsScale = true,
            modifier = Modifier.fillMaxSize(),
        )
        PathDistanceBanner(
            hopCount = hopCount,
            totalPathDistanceMeters = plot.totalPathDistanceMeters,
            isDistanceIncomplete = plot.isDistanceIncomplete,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            if (onCenterOnPath != null) {
                SmallFloatingActionButton(
                    onClick = onCenterOnPath,
                    containerColor = FloatingActionButtonDefaults.containerColor,
                ) {
                    Icon(
                        imageVector = AppIcons.Timeline,
                        contentDescription = stringResource(Res.string.chats_path_center_on_path),
                    )
                }
            }
            MapLocateButton(
                locationService = locationService,
                knownLocation = knownLocation,
                onFrame = onCamera,
                spanDeg = CENTER_ON_USER_SPAN_DEG,
            )
            MapOptionsMenu(
                chrome = mapChrome,
                styleDisabled = styleDisabled,
                styleDisabledReason = styleDisabledReason,
            )
        }
    }
}

/** Localized signal-quality word for an SNR bucket (iOS `SNRQuality.localizedLabel`); shared with the
 * message-details "Details" section so both surfaces read the same label. */
@Composable
internal fun snrQualityLabel(quality: SnrQuality): String = when (quality) {
    SnrQuality.EXCELLENT -> stringResource(Res.string.chats_signal_excellent)
    SnrQuality.GOOD -> stringResource(Res.string.chats_signal_good)
    SnrQuality.FAIR -> stringResource(Res.string.chats_signal_fair)
    SnrQuality.POOR -> stringResource(Res.string.chats_signal_poor)
    SnrQuality.UNKNOWN -> stringResource(Res.string.chats_path_hop_signal_unknown)
}

internal fun formatSnrDb(snr: Double): String {
    val rounded = ((snr * SNR_DECIMAL_FACTOR).toInt()) / SNR_DECIMAL_FACTOR
    return "$rounded"
}

internal fun pathHopShowsTrailingSignal(isReceiver: Boolean, hasSnr: Boolean): Boolean = isReceiver && hasSnr

/** Compact trailing SNR: localized word, value, unit (iOS `PathHopRowView` hardcodes English). */
internal fun pathHopCompactSnrVisibleText(snrFormatted: String, snrWord: String, unit: String): String =
    "$snrWord $snrFormatted $unit"

private const val SNR_DECIMAL_FACTOR = 10.0

/** iOS `MessagePathContent` copy control: `doc.on.doc` with `.labelStyle(.iconOnly)`. */
internal val PATH_COPY_ICON get() = AppIcons.ContentCopy
