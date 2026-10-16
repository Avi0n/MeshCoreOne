package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_save
import one.meshcore.shared.resources.contacts_trace_list_auto_return
import one.meshcore.shared.resources.contacts_trace_list_auto_return_description
import one.meshcore.shared.resources.contacts_trace_list_batch_hint
import one.meshcore.shared.resources.contacts_trace_list_batch_trace
import one.meshcore.shared.resources.contacts_trace_list_batch_trace_description
import one.meshcore.shared.resources.contacts_trace_list_hash_size
import one.meshcore.shared.resources.contacts_trace_list_hash_size_footer
import one.meshcore.shared.resources.contacts_trace_list_hash_size_four_bytes
import one.meshcore.shared.resources.contacts_trace_list_hash_size_one_byte
import one.meshcore.shared.resources.contacts_trace_list_hash_size_two_bytes
import one.meshcore.shared.resources.contacts_trace_list_run_trace
import one.meshcore.shared.resources.contacts_trace_list_run_trace_label
import one.meshcore.shared.resources.contacts_trace_list_running_batch
import one.meshcore.shared.resources.contacts_trace_list_running_batch_label
import one.meshcore.shared.resources.contacts_trace_list_running_hint
import one.meshcore.shared.resources.contacts_trace_list_running_label
import one.meshcore.shared.resources.contacts_trace_list_running_trace
import one.meshcore.shared.resources.contacts_trace_list_single_hint
import one.meshcore.shared.resources.contacts_trace_list_traces
import one.meshcore.shared.resources.contacts_trace_map_path_name
import one.meshcore.shared.resources.contacts_trace_map_save_message
import one.meshcore.shared.resources.contacts_trace_map_save_title
import one.meshcore.shared.settings.ui.SettingsToggleRow
import one.meshcore.shared.trace.VALID_TRACE_HASH_SIZES
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.preferenceHubInset
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

/** Prompts for the saved path's name, seeded from the generated default. Mirrors iOS map `saveTitle` alert. */
@Composable
internal fun TracePathSaveNameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.contacts_trace_map_save_title),
    ) { innerPadding ->
        Column(modifier = Modifier.preferenceHubInset(innerPadding)) {
            PreferenceHubSection {
                Column(Modifier.padding(PreferenceHubCardPadding)) {
                    Text(
                        text = stringResource(Res.string.contacts_trace_map_save_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    AppOutlinedTextField(
                        value = name,
                        onValueChange = onNameChange,
                        singleLine = true,
                        label = { Text(stringResource(Res.string.contacts_trace_map_path_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onConfirm,
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PreferenceHubHorizontalPadding),
            ) {
                Text(stringResource(Res.string.contacts_common_save))
            }
        }
    }
}

@Composable
internal fun TraceHashSizeRow(hashSize: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    PreferenceHubSection(
        title = stringResource(Res.string.contacts_trace_list_hash_size),
        applyHorizontalPadding = false,
    ) {
        Column(Modifier.padding(PreferenceHubCardPadding)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                VALID_TRACE_HASH_SIZES.forEachIndexed { index, size ->
                    SegmentedButton(
                        selected = hashSize == size,
                        onClick = { onSelect(size) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = VALID_TRACE_HASH_SIZES.size,
                        ),
                        label = { Text(hashSizeLabel(size)) },
                    )
                }
            }
        }
    }
    if (traceHashSizeFooterVisible(hashSize)) {
        // Choosing more than one byte silently stops traces crossing older repeaters, and nothing else on
        // the screen says so; the trace simply never comes back.
        PreferenceHubFooter(
            text = stringResource(Res.string.contacts_trace_list_hash_size_footer),
            applyHorizontalPadding = false,
        )
    }
}

/** iOS `PathActionsSectionView`: footer only when `effectiveTraceMode > 0` (2- or 4-byte hops). */
internal fun traceHashSizeFooterVisible(hashSize: Int): Boolean = hashSize > HASH_SIZE_ONE

@Composable
private fun hashSizeLabel(size: Int): String = when (size) {
    HASH_SIZE_ONE -> stringResource(Res.string.contacts_trace_list_hash_size_one_byte)
    HASH_SIZE_TWO -> stringResource(Res.string.contacts_trace_list_hash_size_two_bytes)
    else -> stringResource(Res.string.contacts_trace_list_hash_size_four_bytes)
}

@Composable
internal fun TraceAutoReturnRow(autoReturn: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsToggleRow(
        label = stringResource(Res.string.contacts_trace_list_auto_return),
        supporting = stringResource(Res.string.contacts_trace_list_auto_return_description),
        checked = autoReturn,
        enabled = enabled,
        onCheckedChange = onChange,
    )
}

@Composable
internal fun TraceBatchRow(
    enabled: Boolean,
    batchEnabled: Boolean,
    batchCount: Int,
    onBatchEnabledChange: (Boolean) -> Unit,
    onBatchCountChange: (Int) -> Unit,
) {
    SettingsToggleRow(
        label = stringResource(Res.string.contacts_trace_list_batch_trace),
        supporting = stringResource(Res.string.contacts_trace_list_batch_trace_description),
        checked = batchEnabled,
        enabled = enabled,
        onCheckedChange = onBatchEnabledChange,
    )
    if (batchEnabled) {
        Column(
            Modifier.padding(PreferenceHubCardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.contacts_trace_list_traces),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TRACE_BATCH_SIZE_OPTIONS.forEachIndexed { index, count ->
                    SegmentedButton(
                        selected = batchCount == count,
                        onClick = { onBatchCountChange(count) },
                        enabled = enabled,
                        shape = SegmentedButtonDefaults.itemShape(index, TRACE_BATCH_SIZE_OPTIONS.size),
                        label = { Text(traceBatchSizeChipLabel(count)) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun TraceRunButton(
    isRunning: Boolean,
    batchProgress: Pair<Int, Int>?,
    enabled: Boolean,
    onClick: () -> Unit,
    isRadioReady: Boolean = true,
    batchEnabled: Boolean = false,
    batchSize: Int = 0,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    // The visible caption reads as a label, but nothing said what a tap would do or how many traces a
    // batch would run, so a batch commitment was invisible until it started.
    val spoken = traceRunButtonAccessibility(isRunning, batchEnabled, batchSize, batchProgress)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .radioConnectionHint(isRadioReady)
            .semantics { contentDescription = spoken },
    ) {
        if (isRunning) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
            )
            val label = batchProgress?.let { (current, total) ->
                formatTemplate(
                    stringResource(Res.string.contacts_trace_list_running_batch),
                    listOf(current, total),
                )
            } ?: stringResource(Res.string.contacts_trace_list_running_trace)
            Text(label, modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(stringResource(Res.string.contacts_trace_list_run_trace))
        }
    }
}

/**
 * What a screen reader says about the run button: what it is, then what acting on it does. A batch names
 * its size before it is started and its progress once it is running, which is the part the visible
 * caption alone leaves out.
 */
@Composable
private fun traceRunButtonAccessibility(
    isRunning: Boolean,
    batchEnabled: Boolean,
    batchSize: Int,
    batchProgress: Pair<Int, Int>?,
): String {
    val label: String
    val hint: String
    if (isRunning) {
        label = batchProgress?.let { (current, total) ->
            formatTemplate(
                stringResource(Res.string.contacts_trace_list_running_batch_label),
                listOf(current, total),
            )
        } ?: stringResource(Res.string.contacts_trace_list_running_label)
        hint = stringResource(Res.string.contacts_trace_list_running_hint)
    } else {
        label = stringResource(Res.string.contacts_trace_list_run_trace_label)
        // iOS `RunTraceSectionView`: idle batch speaks `batchHint(size)`, not the single-trace hint.
        hint = if (batchEnabled) {
            formatTemplate(stringResource(Res.string.contacts_trace_list_batch_hint), listOf(batchSize))
        } else {
            stringResource(Res.string.contacts_trace_list_single_hint)
        }
    }
    return "$label. $hint"
}

private const val HASH_SIZE_ONE = 1
private const val HASH_SIZE_TWO = 2
internal const val MIN_BATCH_COUNT = 3
internal const val MAX_BATCH_COUNT = 5

/** iOS `PathActionsSectionView` batch chips: 3× and 5×. */
internal val TRACE_BATCH_SIZE_OPTIONS = listOf(3, 5)

internal fun traceBatchSizeChipLabel(size: Int): String = "$size×"

/** Buffer between consecutive batch traces to avoid network flooding (iOS `interTraceBufferMs`). */
internal const val INTER_TRACE_BUFFER_MS = 500L
