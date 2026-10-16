package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_results_avg_round_trip
import one.meshcore.shared.resources.contacts_results_avg_rtt_label
import one.meshcore.shared.resources.contacts_results_batch_complete_label
import one.meshcore.shared.resources.contacts_results_batch_progress
import one.meshcore.shared.resources.contacts_results_batch_progress_label
import one.meshcore.shared.resources.contacts_results_batch_success
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.trace.averageRtt
import one.meshcore.shared.trace.batchSuccessPercent
import one.meshcore.shared.trace.maxRtt
import one.meshcore.shared.trace.minRtt
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.durationMsLabel
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun BatchSummarySection(
    results: List<TraceResult>,
    batchProgress: Pair<Int, Int>?,
    isBatchInProgress: Boolean,
) {
    val progress = batchProgress
    val isCompleteBatch = !isBatchInProgress && results.size >= MIN_RESULTS_FOR_HOP_STATS
    if (progress == null && !isCompleteBatch) return
    PreferenceHubSection(applyHorizontalPadding = false) {
        if (progress != null && isBatchInProgress) {
            val (current, total) = progress
            val caption = formatTemplate(
                stringResource(Res.string.contacts_results_batch_progress),
                listOf(current, total),
            )
            val spoken = formatTemplate(
                stringResource(Res.string.contacts_results_batch_progress_label),
                listOf(current, total),
            )
            ListItem(
                headlineContent = {
                    Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                leadingContent = {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
            )
        } else if (isCompleteBatch) {
            val okCount = results.count { it.success }
            val percent = batchSuccessPercent(okCount, results.size)
            val caption = formatTemplate(
                stringResource(Res.string.contacts_results_batch_success),
                listOf(okCount, results.size, percent),
            )
            val spoken = formatTemplate(
                stringResource(Res.string.contacts_results_batch_complete_label),
                listOf(okCount, results.size, percent),
            )
            ListItem(
                headlineContent = {
                    Text(caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                leadingContent = {
                    Icon(
                        imageVector = AppIcons.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken },
            )
        }
        val avg = averageRtt(results) ?: return@PreferenceHubSection
        val min = minRtt(results) ?: return@PreferenceHubSection
        val max = maxRtt(results) ?: return@PreferenceHubSection
        val rttSpoken = formatTemplate(
            stringResource(Res.string.contacts_results_avg_rtt_label),
            listOf(avg, min, max),
        )
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.contacts_results_avg_round_trip))
            },
            supportingContent = {
                Column {
                    Text(durationMsLabel(avg), fontFamily = FontFamily.Monospace)
                    Text(
                        "($min – $max)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = rttSpoken },
        )
    }
}
