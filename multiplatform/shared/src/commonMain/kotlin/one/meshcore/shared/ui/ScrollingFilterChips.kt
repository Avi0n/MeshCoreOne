package one.meshcore.shared.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Horizontal inset for a scrolling filter-chip row under a list. */
internal val FilterChipRowHorizontalPadding = 16.dp

/** Gap between chips in a scrolling filter row. */
private val FilterChipGap = 8.dp

/**
 * A horizontally scrolling row of single-choice filter chips. Padding sits inside the scroll,
 * so the first and last chips can scroll out from under the inset. The row speaks [spokenLabel].
 */
@Composable
internal fun <T> ScrollingFilterChips(
    entries: List<T>,
    selected: T,
    spokenLabel: String,
    onSelect: (T) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = FilterChipRowHorizontalPadding),
    chipLabel: @Composable (T) -> String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding)
            .semantics { contentDescription = spokenLabel },
        horizontalArrangement = Arrangement.spacedBy(FilterChipGap),
    ) {
        entries.forEach { entry ->
            FilterChip(
                selected = entry == selected,
                onClick = { onSelect(entry) },
                label = { Text(chipLabel(entry)) },
            )
        }
    }
}
