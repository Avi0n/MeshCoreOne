package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.battery.BatteryOcv
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_accessibility_reload_status
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_airtime
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_airtime_percent
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_battery
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_duplicates
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_last_rssi
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_last_snr
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_noise_floor
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_packets
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_packets_received
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_packets_sent
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_receive_errors
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_received_direct
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_received_flood
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sent_direct
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_sent_flood
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_status_section
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_uptime
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_uptime1_day
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_uptime_days
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_uptime_hours
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_uptime_minutes
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun StatusCountersSection(
    viewModel: NodeStatusViewModel,
    state: NodeStatusState,
    nowEpochSeconds: Double,
    isRadioReady: Boolean,
    onOpenHistory: () -> Unit,
) {
    StatusSection(
        expanded = state.statusExpanded,
        onExpandedChange = viewModel::setStatusExpanded,
        title = stringResource(Res.string.remotenodes_remote_nodes_status_status_section),
        isLoading = state.isLoadingStatus,
        onReload = { viewModel.requestStatus() },
        reloadAccessibilityLabel = stringResource(
            Res.string.remotenodes_remote_nodes_status_accessibility_reload_status,
        ),
        isRadioReady = isRadioReady,
    ) {
        val status = state.status
        when {
            state.statusSectionError != null && status == null ->
                SectionErrorText(state.statusSectionError)
            status != null -> {
                StatusRows(status = status, deltas = state.statusDeltas, ocvValues = state.ocvValues)
                state.previousStatusSnapshot?.let { previous ->
                    PreferenceHubStatusRow(previousVisitLabel(previous.timestamp, nowEpochSeconds))
                }
                StatusHistoryLink(onClick = onOpenHistory)
            }
        }
    }
}

@Composable
internal fun StatusRows(
    status: StatusResponse,
    deltas: StatusMetricDeltas,
    ocvValues: List<Int>,
    batteryLeadsWithVoltage: Boolean = false,
) {
    val percent = BatteryOcv.percentage(status.battery, ocvValues)
    val voltage = "${NodeStatusFormat.voltage(status.battery)}$VOLT_UNIT"
    val percentText = "$percent$PERCENT_UNIT"
    MetricRow(
        label = stringResource(Res.string.remotenodes_remote_nodes_status_battery),
        value = if (batteryLeadsWithVoltage) "$voltage ($percentText)" else "$percentText ($voltage)",
        delta = deltas.batteryVolts,
        higherIsBetter = true,
        unit = VOLT_UNIT,
        fractionDigits = 3,
    )
    PreferenceHubValueRow(stringResource(Res.string.remotenodes_remote_nodes_status_uptime), uptimeText(status.uptime))
    PreferenceHubValueRow(stringResource(Res.string.remotenodes_remote_nodes_status_airtime), airtimeText(status))
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_airtime_percent),
        airtimePercentText(status),
    )
    MetricRow(
        label = stringResource(Res.string.remotenodes_remote_nodes_status_last_rssi),
        value = "${status.lastRSSI}$DBM_UNIT",
        delta = deltas.rssi,
        higherIsBetter = true,
        unit = DBM_UNIT,
        fractionDigits = 0,
    )
    MetricRow(
        label = stringResource(Res.string.remotenodes_remote_nodes_status_last_snr),
        value = "${NodeStatusFormat.oneDecimal(status.lastSNR)}$DB_UNIT",
        delta = deltas.snr,
        higherIsBetter = true,
        unit = DB_UNIT,
        fractionDigits = 1,
    )
    MetricRow(
        label = stringResource(Res.string.remotenodes_remote_nodes_status_noise_floor),
        value = "${status.noiseFloor}$DBM_UNIT",
        delta = deltas.noiseFloor,
        higherIsBetter = false,
        unit = DBM_UNIT,
        fractionDigits = 0,
    )

    Text(
        text = stringResource(Res.string.remotenodes_remote_nodes_status_packets),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = PreferenceHubCardPadding,
            end = PreferenceHubCardPadding,
            top = LABEL_SPACING,
        ),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_packets_sent),
        NodeStatusFormat.count(status.packetsSent),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_packets_received),
        NodeStatusFormat.count(status.packetsReceived),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_sent_direct),
        NodeStatusFormat.count(status.sentDirect),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_sent_flood),
        NodeStatusFormat.count(status.sentFlood),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_received_direct),
        NodeStatusFormat.count(status.receivedDirect),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_received_flood),
        NodeStatusFormat.count(status.receivedFlood),
    )
    PreferenceHubValueRow(
        stringResource(Res.string.remotenodes_remote_nodes_status_duplicates),
        NodeStatusFormat.count(status.directDuplicates.toLong() + status.floodDuplicates.toLong()),
    )
    if (status.receiveErrors > 0) {
        PreferenceHubValueRow(
            stringResource(Res.string.remotenodes_remote_nodes_status_receive_errors),
            NodeStatusFormat.count(status.receiveErrors.toLong()),
        )
    }
}

@Composable
internal fun MetricRow(
    label: String,
    value: String,
    delta: Double?,
    higherIsBetter: Boolean,
    unit: String,
    fractionDigits: Int,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Column {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (delta != null) {
                    StatusDeltaView(
                        delta = delta,
                        higherIsBetter = higherIsBetter,
                        unit = unit,
                        fractionDigits = fractionDigits,
                    )
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun uptimeText(seconds: Long): String {
    val parts = NodeStatusFormat.uptimeComponents(seconds)
    return when {
        parts.days > 1 -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_status_uptime_days,
            parts.days,
            parts.hours,
            parts.minutes,
        )
        parts.days == 1 -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_status_uptime1_day,
            parts.hours,
            parts.minutes,
        )
        parts.hours > 0 -> formattedStringResource(
            Res.string.remotenodes_remote_nodes_status_uptime_hours,
            parts.hours,
            parts.minutes,
        )
        else -> formattedStringResource(Res.string.remotenodes_remote_nodes_status_uptime_minutes, parts.minutes)
    }
}

@Composable
private fun airtimeText(status: StatusResponse): String =
    "$TX_PREFIX ${uptimeText(status.airtime)} / $RX_PREFIX ${uptimeText(status.rxAirtime)}"

@Composable
private fun airtimePercentText(status: StatusResponse): String {
    val percents = NodeStatusFormat.airtimePercents(status) ?: return EM_DASH
    return "$TX_PREFIX ${NodeStatusFormat.oneDecimal(percents.tx)}$PERCENT_UNIT / " +
        "$RX_PREFIX ${NodeStatusFormat.oneDecimal(percents.rx)}$PERCENT_UNIT"
}

// Unit symbols and section markers are non-localized, matching the iOS status rows which inline them.
private const val VOLT_UNIT = " V"
private const val DBM_UNIT = " dBm"
private const val DB_UNIT = " dB"
private const val PERCENT_UNIT = "%"
private const val TX_PREFIX = "TX"
private const val RX_PREFIX = "RX"
private const val EM_DASH = "—"

private val LABEL_SPACING = 6.dp
