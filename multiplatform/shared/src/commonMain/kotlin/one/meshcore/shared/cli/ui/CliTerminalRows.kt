package one.meshcore.shared.cli.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import one.meshcore.shared.remotenode.CLIOutputLine
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_cli_command_input
import one.meshcore.shared.resources.tools_cli_command_prompt
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliOutputRow(line: CLIOutputLine, onClick: () -> Unit, onCopy: () -> Unit) {
    Text(
        text = line.text,
        color = line.type.cliTint(
            command = MaterialTheme.colorScheme.onSurfaceVariant,
            response = MaterialTheme.colorScheme.onSurface,
            success = MaterialTheme.colorScheme.primary,
            error = MaterialTheme.colorScheme.error,
        ),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClick = onCopy,
            ),
    )
}

@Composable
internal fun CliInlinePrompt(
    promptText: String,
    input: TextFieldValue,
    ghostText: String,
    isWaiting: Boolean,
    showCaret: Boolean,
) {
    val promptLabel = stringResource(Res.string.tools_cli_command_prompt)
    val inputLabel = stringResource(Res.string.tools_cli_command_input)
    val cursor = input.selection.start.coerceIn(0, input.text.length)
    val before = input.text.substring(0, cursor)
    val after = input.text.substring(cursor)
    val cursorAtEnd = cliCursorAtEnd(input.selection.collapsed, cursor, input.text.length)
    val ghost = if (cursorAtEnd) ghostText else ""
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isWaiting) {
            Box(
                modifier = Modifier
                    .padding(end = BUTTON_SPACING)
                    .width(WAITING_BLOCK_WIDTH)
                    .height(CARET_HEIGHT)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        } else {
            Text(
                text = promptText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    contentDescription = promptLabel
                    stateDescription = promptText
                },
            )
            Text(
                text = before,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.semantics { contentDescription = inputLabel },
            )
            if (showCaret) {
                Box(
                    modifier = Modifier
                        .width(CARET_WIDTH)
                        .height(CARET_HEIGHT)
                        .background(MaterialTheme.colorScheme.onSurface),
                )
            }
            if (after.isNotEmpty()) {
                Text(
                    text = after,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
            if (ghost.isNotEmpty()) {
                Text(
                    text = ghost,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun CliTabSuggestionStrip(suggestions: List<String>, selectedIndex: Int?, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = CLI_TERMINAL_LINE_SPACING),
        horizontalArrangement = Arrangement.spacedBy(BUTTON_SPACING),
    ) {
        suggestions.forEachIndexed { index, suggestion ->
            CliTabSuggestionChip(
                text = suggestion,
                selected = cliTabSuggestionIsSelected(index, selectedIndex),
                onClick = { onSelect(index) },
            )
        }
    }
}

internal val CLI_TERMINAL_LINE_SPACING = 2.dp
private val BUTTON_SPACING = 8.dp
private val CARET_WIDTH = 2.dp
private val CARET_HEIGHT = 14.dp
private val WAITING_BLOCK_WIDTH = 8.dp
