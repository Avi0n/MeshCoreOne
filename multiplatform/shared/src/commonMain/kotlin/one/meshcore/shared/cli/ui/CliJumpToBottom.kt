package one.meshcore.shared.cli.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_cli_jump_to_bottom
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * True when the last output line is visible (or the list is empty). iOS
 * `scrollPosition.isPositionedByUser` is the inverse: follow new lines only while this is true.
 */
internal fun LazyListState.cliOutputIsAtBottom(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()
    return last == null || last.index >= info.totalItemsCount - 1
}

/** iOS `CLIScrollToBottomButton` — same control Tools CLI and Node CLI share. */
@Composable
internal fun CliJumpToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    AppIconButton(
        imageVector = AppIcons.KeyboardArrowDown,
        contentDescription = stringResource(Res.string.tools_cli_jump_to_bottom),
        onClick = onClick,
        modifier = modifier,
        tint = MaterialTheme.colorScheme.primary,
    )
}
