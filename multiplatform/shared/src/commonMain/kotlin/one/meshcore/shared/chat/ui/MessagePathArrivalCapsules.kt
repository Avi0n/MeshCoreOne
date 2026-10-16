package one.meshcore.shared.chat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_path_arrival_first
import one.meshcore.shared.resources.chats_path_arrival_offset
import one.meshcore.shared.resources.chats_repeats_hop_plural
import one.meshcore.shared.resources.chats_repeats_hop_singular
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_snr_badge_unit
import one.meshcore.shared.ui.formattedStringResource
import org.jetbrains.compose.resources.stringResource

/** Selects one route in the incoming-arrival list without leaving the path destination. */
@Composable
internal fun MessagePathArrivalCapsules(
    arrivals: List<ArrivalPathDisplay>,
    selectedID: Uuid?,
    onSelect: (Uuid) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (arrivals.size < 2) return
    val firstReceivedAt = arrivals.first().arrival.receivedAt
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        arrivals.forEach { item ->
            val arrival = item.arrival
            val hops = if (arrival.hopCount == 1) {
                stringResource(Res.string.chats_repeats_hop_singular)
            } else {
                formattedStringResource(Res.string.chats_repeats_hop_plural, arrival.hopCount)
            }
            val snr = arrival.snr?.let {
                " · ${formatSnrDb(it)} ${stringResource(Res.string.remotenodes_remote_nodes_status_snr_badge_unit)}"
            }.orEmpty()
            val subtitle = if (arrival.id == arrivals.first().arrival.id) {
                stringResource(Res.string.chats_path_arrival_first)
            } else {
                val seconds = maxOf(0, (arrival.receivedAt - firstReceivedAt).toInt())
                formattedStringResource(Res.string.chats_path_arrival_offset, "${seconds}s")
            }
            FilterChip(
                selected = arrival.id == selectedID,
                onClick = { onSelect(arrival.id) },
                label = {
                    Column {
                        Text(hops + snr)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall)
                    }
                },
            )
        }
    }
}
