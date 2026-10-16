package one.meshcore.shared.cli.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_cli_cancel_operation
import one.meshcore.shared.resources.tools_cli_clear
import one.meshcore.shared.resources.tools_cli_cursor_left
import one.meshcore.shared.resources.tools_cli_cursor_right
import one.meshcore.shared.resources.tools_cli_dismiss
import one.meshcore.shared.resources.tools_cli_history_down
import one.meshcore.shared.resources.tools_cli_history_up
import one.meshcore.shared.resources.tools_cli_paste
import one.meshcore.shared.resources.tools_cli_sessions
import one.meshcore.shared.resources.tools_cli_tab_complete
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** iOS `CLIInputAccessoryView.task`: cancel stays dim until wait lasts this long. */
internal const val CLI_CANCEL_REVEAL_MS = 150L

/** Whether the stop control is armed: waiting and past the iOS 150ms flash-guard. */
internal fun cliCancelArmed(isWaiting: Boolean, elapsedMs: Long, revealDelayMs: Long = CLI_CANCEL_REVEAL_MS): Boolean =
    isWaiting && elapsedMs >= revealDelayMs

/** iOS `CLIInputAccessoryView`: a divider precedes sessions when that control is shown. */
internal const val CLI_SESSIONS_DIVIDER_HEIGHT_DP = 20

/**
 * On-screen input controls mirroring the iOS `CLIInputAccessoryView`: tab-complete, history, cursor
 * movement, paste, optional session list, cancel, clear, and dismiss, so a touch-only user reaches
 * every action. Node/room CLI hides sessions (`showSessionsButton = false`) the same way iOS
 * `NodeCLIView` does.
 */
@Composable
fun CliInputAccessory(
    isWaiting: Boolean,
    onTab: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    showSessionsButton: Boolean = true,
    onSessions: () -> Unit = {},
) {
    var cancelElapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(isWaiting) {
        if (!isWaiting) {
            cancelElapsedMs = 0L
            return@LaunchedEffect
        }
        cancelElapsedMs = 0L
        delay(CLI_CANCEL_REVEAL_MS)
        cancelElapsedMs = CLI_CANCEL_REVEAL_MS
    }
    val cancelArmed = cliCancelArmed(isWaiting = isWaiting, elapsedMs = cancelElapsedMs)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccessoryButton(
                AppIcons.KeyboardTab,
                stringResource(Res.string.tools_cli_tab_complete),
                onClick = onTab,
            )
            AccessoryButton(
                AppIcons.KeyboardArrowUp,
                stringResource(Res.string.tools_cli_history_up),
                onClick = onHistoryUp,
            )
            AccessoryButton(
                AppIcons.KeyboardArrowDown,
                stringResource(Res.string.tools_cli_history_down),
                onClick = onHistoryDown,
            )
            AccessoryButton(
                AppIcons.KeyboardArrowLeft,
                stringResource(Res.string.tools_cli_cursor_left),
                onClick = onMoveLeft,
            )
            AccessoryButton(
                AppIcons.KeyboardArrowRight,
                stringResource(Res.string.tools_cli_cursor_right),
                onClick = onMoveRight,
            )
            AccessoryButton(AppIcons.ContentPaste, stringResource(Res.string.tools_cli_paste), onClick = onPaste)
            if (showSessionsButton) {
                VerticalDivider(modifier = Modifier.height(CLI_SESSIONS_DIVIDER_HEIGHT_DP.dp))
                AccessoryButton(AppIcons.Layers, stringResource(Res.string.tools_cli_sessions), onClick = onSessions)
            }
            AccessoryButton(
                icon = AppIcons.StopCircle,
                contentDescription = stringResource(Res.string.tools_cli_cancel_operation),
                enabled = cancelArmed,
                tint = if (cancelArmed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onCancel,
            )
        }
        AccessoryButton(AppIcons.Clear, stringResource(Res.string.tools_cli_clear), onClick = onClear)
        AccessoryButton(AppIcons.KeyboardHide, stringResource(Res.string.tools_cli_dismiss), onClick = onDismiss)
    }
}

@Composable
private fun AccessoryButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit,
) {
    AppIconButton(
        imageVector = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        enabled = enabled,
        tint = tint,
    )
}
