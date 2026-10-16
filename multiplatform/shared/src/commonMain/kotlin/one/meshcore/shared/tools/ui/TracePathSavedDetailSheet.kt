package one.meshcore.shared.tools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.chat.ui.LocalTimestampFormatter
import one.meshcore.shared.chat.ui.formatMessageDetailDateTime
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_delete
import one.meshcore.shared.resources.contacts_path_detail_avg
import one.meshcore.shared.resources.contacts_path_detail_best
import one.meshcore.shared.resources.contacts_path_detail_date
import one.meshcore.shared.resources.contacts_path_detail_failed
import one.meshcore.shared.resources.contacts_path_detail_history
import one.meshcore.shared.resources.contacts_path_detail_hop
import one.meshcore.shared.resources.contacts_path_detail_overview
import one.meshcore.shared.resources.contacts_path_detail_path
import one.meshcore.shared.resources.contacts_path_detail_per_hop_snr
import one.meshcore.shared.resources.contacts_path_detail_performance
import one.meshcore.shared.resources.contacts_path_detail_round_trip
import one.meshcore.shared.resources.contacts_path_detail_round_trip_ms
import one.meshcore.shared.resources.contacts_path_detail_run_details
import one.meshcore.shared.resources.contacts_path_detail_status
import one.meshcore.shared.resources.contacts_path_detail_success
import one.meshcore.shared.resources.contacts_saved_paths_rename
import one.meshcore.shared.resources.contacts_trace_list_run_trace
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.trace.averageRoundTripMs
import one.meshcore.shared.trace.bestRoundTripMs
import one.meshcore.shared.trace.runsNewestFirst
import one.meshcore.shared.trace.successRate
import one.meshcore.shared.trace.successfulRunsOldestFirst
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PathHopChipRow
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.durationMsLabel
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.preferenceHubInsetScroll
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SavedPathDetailSheet(
    path: SavedTracePathDto,
    onDismiss: () -> Unit,
    onLoad: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var selectedRun by remember { mutableStateOf<TracePathRunDto?>(null) }
    FullScreenOverlay(onDismiss = onDismiss, title = path.name) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PreferenceHubHorizontalPadding, vertical = PreferenceHubTopPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PreferenceHubSection(
                title = stringResource(Res.string.contacts_path_detail_path),
                applyHorizontalPadding = false,
            ) {
                Column(Modifier.padding(PreferenceHubCardPadding)) {
                    PathChipsRow(pathBytes = path.pathBytes, hashSize = path.hashSize)
                }
            }
            PreferenceHubSection(
                title = stringResource(Res.string.contacts_path_detail_performance),
                applyHorizontalPadding = false,
            ) {
                Column(
                    Modifier.padding(PreferenceHubCardPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val chartRuns = path.successfulRunsOldestFirst
                    if (chartRuns.size >= 2) {
                        Text(
                            text = stringResource(Res.string.contacts_path_detail_round_trip_ms),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SavedPathRunChart(
                            runs = chartRuns,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        StatCell(
                            label = stringResource(Res.string.contacts_path_detail_avg),
                            value = path.averageRoundTripMs?.let { durationMsLabel(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                        StatCell(
                            label = stringResource(Res.string.contacts_path_detail_best),
                            value = path.bestRoundTripMs?.let { durationMsLabel(it) } ?: "—",
                            modifier = Modifier.weight(1f),
                        )
                        StatCell(
                            label = stringResource(Res.string.contacts_path_detail_success),
                            value = "${path.successRate}%",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            PreferenceHubSection(
                title = stringResource(Res.string.contacts_path_detail_history),
                applyHorizontalPadding = false,
            ) {
                path.runsNewestFirst.forEach { run ->
                    ListItem(
                        headlineContent = {
                            Text(
                                text = formatMessageDetailDateTime(
                                    run.date,
                                    formatter = LocalTimestampFormatter.current,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = {
                            Column {
                                if (run.success) {
                                    Text(durationMsLabel(run.roundTripMs), fontFamily = FontFamily.Monospace)
                                    if (run.hopsSNR.isNotEmpty()) {
                                        val snrUnit = stringResource(
                                            Res.string.remotenodes_remote_nodes_status_snr_badge_unit,
                                        )
                                        Text(
                                            text = run.hopsSNR.joinToString(", ") {
                                                formatSnr(it, snrUnit)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = stringResource(Res.string.contacts_path_detail_failed),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.error.copy(
                                                    alpha = FAILED_RUN_CAPSULE_FILL_ALPHA,
                                                ),
                                                CircleShape,
                                            )
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().clickable { selectedRun = run },
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onLoad, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.contacts_trace_list_run_trace))
                }
                OutlinedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.contacts_saved_paths_rename))
                }
                OutlinedButton(
                    onClick = onDelete,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(stringResource(Res.string.contacts_common_delete))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    selectedRun?.let { run ->
        TracePathRunDetailDialog(run = run, onDismiss = { selectedRun = null })
    }
}

/** One saved-path run: date, round trip, success/failed, and per-hop SNR. Mirrors iOS `RunDetailView`. */
@Composable
private fun TracePathRunDetailDialog(run: TracePathRunDto, onDismiss: () -> Unit) {
    val status = if (run.success) {
        stringResource(Res.string.contacts_path_detail_success)
    } else {
        stringResource(Res.string.contacts_path_detail_failed)
    }
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.contacts_path_detail_run_details),
    ) { innerPadding ->
        Column(modifier = Modifier.preferenceHubInsetScroll(innerPadding)) {
            PreferenceHubSection(
                title = stringResource(Res.string.contacts_path_detail_overview),
            ) {
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.contacts_path_detail_date)) },
                    supportingContent = {
                        Text(
                            formatMessageDetailDateTime(run.date, formatter = LocalTimestampFormatter.current),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.contacts_path_detail_round_trip)) },
                    supportingContent = {
                        Text(
                            durationMsLabel(run.roundTripMs),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text(stringResource(Res.string.contacts_path_detail_status)) },
                    supportingContent = {
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            if (run.success && run.hopsSNR.isNotEmpty()) {
                PreferenceHubSection(
                    title = stringResource(Res.string.contacts_path_detail_per_hop_snr),
                ) {
                    run.hopsSNR.forEachIndexed { index, snr ->
                        val hopLabel = formatTemplate(
                            stringResource(Res.string.contacts_path_detail_hop),
                            listOf(index + 1),
                        )
                        ListItem(
                            headlineContent = { Text(hopLabel) },
                            supportingContent = {
                                Text(
                                    formatSnrLabel(snr),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Horizontal hop capsules with arrows, matching iOS `PathChipsView`. Includes a trailing partial
 * hop when [pathBytes] is not a multiple of [hashSize].
 */
@Composable
private fun PathChipsRow(pathBytes: ByteArray, hashSize: Int, modifier: Modifier = Modifier) {
    PathHopChipRow(
        hops = remember(pathBytes, hashSize) { hopHexStrings(pathBytes, hashSize) },
        modifier = modifier,
    )
}

/**
 * Splits [pathBytes] into uppercase hex hops of [hashSize] bytes, keeping a shorter last hop when
 * the length is not a multiple — the iOS `PathChipsView` stride.
 */
internal fun hopHexStrings(pathBytes: ByteArray, hashSize: Int): List<String> {
    if (pathBytes.isEmpty() || hashSize <= 0) return emptyList()
    val hops = ArrayList<String>((pathBytes.size + hashSize - 1) / hashSize)
    var start = 0
    while (start < pathBytes.size) {
        val end = minOf(start + hashSize, pathBytes.size)
        hops += pathBytes.copyOfRange(start, end).toUppercaseHex()
        start = end
    }
    return hops
}

@Composable
private fun formatSnrLabel(snr: Double): String =
    formatSnr(snr, stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit))
private const val FAILED_RUN_CAPSULE_FILL_ALPHA = 0.2f
