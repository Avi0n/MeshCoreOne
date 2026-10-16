package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_results_hop_received
import one.meshcore.shared.resources.contacts_results_hop_repeated
import one.meshcore.shared.resources.contacts_results_hop_started
import one.meshcore.shared.trace.TraceHop
import org.jetbrains.compose.resources.stringResource

/** Where a trace hop sits. Start is checked first, so a hop flagged as both stays the origin. */
internal enum class TraceHopPlace {
    Started,
    Received,
    Repeated,
}

internal fun traceHopPlace(isStartNode: Boolean, isEndNode: Boolean): TraceHopPlace = when {
    isStartNode -> TraceHopPlace.Started
    isEndNode -> TraceHopPlace.Received
    else -> TraceHopPlace.Repeated
}

@Composable
internal fun traceHopRoleLabel(hop: TraceHop): String = when (traceHopPlace(hop.isStartNode, hop.isEndNode)) {
    TraceHopPlace.Started -> stringResource(Res.string.contacts_results_hop_started)
    TraceHopPlace.Received -> stringResource(Res.string.contacts_results_hop_received)
    TraceHopPlace.Repeated -> stringResource(Res.string.contacts_results_hop_repeated)
}

@Composable
internal fun TraceResultHopHeadline(hop: TraceHop, name: String) {
    when (traceHopPlace(hop.isStartNode, hop.isEndNode)) {
        TraceHopPlace.Started -> Text(name)
        TraceHopPlace.Received -> Text(name, color = MaterialTheme.colorScheme.primary)
        TraceHopPlace.Repeated -> {
            Row(horizontalArrangement = Arrangement.spacedBy(TRACE_HOP_HASH_NAME_GAP)) {
                hop.hashDisplayString?.let { hash ->
                    Text(
                        text = hash,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                hop.resolvedName?.let { Text(it) }
            }
        }
    }
}

private val TRACE_HOP_HASH_NAME_GAP = 8.dp
