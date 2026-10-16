package one.meshcore.shared.pathedit.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.pathedit.AddHopFilter
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_edit_filter_picker_label
import one.meshcore.shared.ui.filterBarVisible
import org.jetbrains.compose.resources.stringResource

/**
 * Filter chips for the Add-Hop picker. Four sections overflow a compact segmented row, so chips
 * scroll instead. Search hides the control; the selected filter still applies to results.
 */
@Composable
internal fun AddHopSegmentPicker(
    selection: AddHopFilter,
    isSearching: Boolean,
    onSelect: (AddHopFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!filterBarVisible(isSearching)) return
    val pickerLabel = stringResource(Res.string.contacts_path_edit_filter_picker_label)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .semantics { contentDescription = pickerLabel },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AddHopFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selection,
                onClick = { onSelect(filter) },
                label = { Text(filter.label()) },
            )
        }
    }
}
