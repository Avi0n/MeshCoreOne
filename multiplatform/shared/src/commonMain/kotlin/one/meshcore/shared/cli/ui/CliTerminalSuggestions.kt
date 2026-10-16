package one.meshcore.shared.cli.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily

/** Tappable tab-completion suggestion. FilterChip selected state follows hardware Tab cycling. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CliTabSuggestionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        modifier = modifier.semantics { this.selected = selected },
    )
}

internal fun cliTabSuggestionIsSelected(index: Int, selectedIndex: Int?): Boolean =
    selectedIndex != null && index == selectedIndex
