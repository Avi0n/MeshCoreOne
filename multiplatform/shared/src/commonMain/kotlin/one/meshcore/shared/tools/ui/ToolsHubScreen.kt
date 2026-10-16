package one.meshcore.shared.tools.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.tools_select_tool
import one.meshcore.shared.resources.tools_title
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.ui.LocalShellContentRole
import one.meshcore.shared.ui.PlaceholderPane
import one.meshcore.shared.ui.ShellContentRole
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/** Tools → Trace Path runner: path bytes, hash size, flags → [TraceResult]. */
typealias TracePathRunner = suspend (pathBytes: ByteArray, hashSize: Int, flags: Int) -> TraceResult

/** Tools → Node Discovery: issues active discover request; returns correlation tag. */
typealias NodeDiscoverRunner = suspend (filter: Int, prefixOnly: Boolean) -> Long

/**
 * Tools hub: Material list of [ToolSelection] entries. Selecting a tool records it on the coordinator.
 * Trace Path, Line of Sight, RX Log, Noise Floor, Node Discovery, and CLI are live screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolsHubScreen(
    selectedTool: StateFlow<ToolSelection?>,
    onSelectTool: (ToolSelection?) -> Unit,
    inputs: ToolsHubInputs,
    modifier: Modifier = Modifier,
) {
    val tool by selectedTool.collectAsStateWithLifecycle()
    val memoryFallback = remember { InMemorySavedTracePathStore() }
    val savedTraceStore = inputs.savedTracePathStore ?: memoryFallback
    val contentRole = LocalShellContentRole.current
    // Hosted here (not on the list) so compact list→detail→back keeps the hub scroll offset.
    val hubListState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
    val showList = contentRole == ShellContentRole.WideList ||
        (contentRole == ShellContentRole.CompactStacked && tool == null)
    val showDetail = contentRole == ShellContentRole.WideDetail ||
        (contentRole == ShellContentRole.CompactStacked && tool != null)

    if (showList) {
        ToolsHubList(
            isRadioReady = inputs.isRadioReady,
            selectedTool = if (contentRole == ShellContentRole.WideList) tool else null,
            onSelectTool = onSelectTool,
            listState = hubListState,
            modifier = modifier,
        )
        return
    }

    val openTool = tool
    if (showDetail && openTool != null) {
        ToolDetailPane(
            tool = openTool,
            inputs = inputs,
            savedTraceStore = savedTraceStore,
            onBack = { onSelectTool(null) },
            modifier = modifier,
        )
        return
    }

    PlaceholderPane(
        title = stringResource(Res.string.tools_select_tool),
        subtitle = stringResource(Res.string.tools_title),
        icon = AppIcons.ToolsSelected,
        modifier = modifier,
    )
}
