package one.meshcore.shared.tools.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_trace_mode_list
import one.meshcore.shared.resources.contacts_trace_mode_map
import one.meshcore.shared.resources.contacts_trace_saved
import one.meshcore.shared.resources.contacts_trace_title
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.ui.AppHaptics
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TracePathChrome(
    modifier: Modifier,
    onBack: () -> Unit,
    savedPathStore: SavedTracePathStore?,
    radioId: () -> Uuid?,
    mapMode: Boolean,
    onMapMode: (Boolean) -> Unit,
    onShowSavedPaths: () -> Unit,
    showJumpToPath: Boolean,
    onJumpToPath: () -> Unit,
    haptics: AppHaptics,
    list: @Composable () -> Unit,
    map: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_trace_title),
                onBack = onBack,
                actions = {
                    if (savedPathStore != null && radioId() != null) {
                        val savedLabel = stringResource(Res.string.contacts_trace_saved)
                        AppIconButton(
                            imageVector = AppIcons.Bookmark,
                            contentDescription = savedLabel,
                            onClick = onShowSavedPaths,
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = !mapMode,
                    onClick = { onMapMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text(stringResource(Res.string.contacts_trace_mode_list)) },
                )
                SegmentedButton(
                    selected = mapMode,
                    onClick = { onMapMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text(stringResource(Res.string.contacts_trace_mode_map)) },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (!mapMode) {
                    list()
                } else {
                    map()
                }
                if (!mapMode && showJumpToPath) {
                    JumpToPathButton(
                        onClick = {
                            haptics.selection()
                            onJumpToPath()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = JUMP_FAB_EDGE.dp, bottom = JUMP_FAB_EDGE.dp),
                    )
                }
            }
        }
    }
}
