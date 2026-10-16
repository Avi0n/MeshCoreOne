package one.meshcore.shared.tools.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.contact.relativeAgeLabel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_overflow_menu
import one.meshcore.shared.resources.contacts_common_delete
import one.meshcore.shared.resources.contacts_saved_paths_empty_description
import one.meshcore.shared.resources.contacts_saved_paths_empty_title
import one.meshcore.shared.resources.contacts_saved_paths_last_run
import one.meshcore.shared.resources.contacts_saved_paths_rename
import one.meshcore.shared.resources.contacts_saved_paths_runs_plural
import one.meshcore.shared.resources.contacts_saved_paths_runs_singular
import one.meshcore.shared.resources.contacts_saved_paths_title
import one.meshcore.shared.trace.lastRunDate
import one.meshcore.shared.trace.recentRTTs
import one.meshcore.shared.trace.runCount
import one.meshcore.shared.trace.savedPathHealthLabel
import one.meshcore.shared.trace.sparklineDescription
import one.meshcore.shared.trace.successRate
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuRole
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SavedPathsListSheet(
    paths: List<SavedTracePathDto>,
    nowSeconds: Long,
    onDismiss: () -> Unit,
    onSelect: (SavedTracePathDto) -> Unit,
    onRename: (SavedTracePathDto) -> Unit,
    onDelete: (SavedTracePathDto) -> Unit,
) {
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.contacts_saved_paths_title),
    ) { padding ->
        if (paths.isEmpty()) {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(horizontal = PreferenceHubHorizontalPadding)
                    .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Icon(
                    imageVector = AppIcons.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.contacts_saved_paths_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(Res.string.contacts_saved_paths_empty_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = PreferenceHubHorizontalPadding)
                    .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
            ) {
                paths.forEach { path ->
                    SavedPathListRow(
                        path = path,
                        nowSeconds = nowSeconds,
                        onSelect = { onSelect(path) },
                        onRename = { onRename(path) },
                        onDelete = { onDelete(path) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedPathListRow(
    path: SavedTracePathDto,
    nowSeconds: Long,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val runsLabel = if (path.runCount == 1) {
        stringResource(Res.string.contacts_saved_paths_runs_singular)
    } else {
        formatTemplate(
            stringResource(Res.string.contacts_saved_paths_runs_plural),
            listOf(path.runCount),
        )
    }
    val lastRun = path.lastRunDate?.let {
        formatTemplate(
            stringResource(Res.string.contacts_saved_paths_last_run),
            listOf(relativeAgeLabel(it.toLong(), nowSeconds)),
        )
    }
    val subtitle = savedPathListSubtitle(runsLabel, lastRun, path.successRate)
    val healthLabel = savedPathHealthLabel(path)
    val recent = path.recentRTTs
    val trendLabel = if (recent.isEmpty()) null else sparklineDescription(recent)
    var menuOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(path.name) },
            supportingContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SAVED_PATH_ROW_SPARKLINE_GAP.dp),
                ) {
                    Text(text = subtitle, modifier = Modifier.weight(1f))
                    if (recent.isNotEmpty()) {
                        MiniSparkline(
                            values = recent,
                            modifier = Modifier
                                .width(SAVED_PATH_ROW_SPARKLINE_WIDTH.dp)
                                .height(SAVED_PATH_ROW_SPARKLINE_HEIGHT.dp),
                        )
                    }
                }
            },
            trailingContent = {
                AppIconButton(
                    imageVector = AppIcons.MoreVert,
                    contentDescription = stringResource(Res.string.android_overflow_menu),
                    onClick = { menuOpen = true },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = { menuOpen = true },
                )
                .semantics(mergeDescendants = true) {
                    contentDescription = listOfNotNull(path.name, subtitle, healthLabel, trendLabel)
                        .joinToString(SAVED_PATH_SUBTITLE_SEPARATOR)
                },
        )
        McDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            McMenuItem(
                text = stringResource(Res.string.contacts_saved_paths_rename),
                leadingIcon = AppIcons.Edit,
                onClick = {
                    menuOpen = false
                    onRename()
                },
            )
            McMenuItem(
                text = stringResource(Res.string.contacts_common_delete),
                leadingIcon = AppIcons.Delete,
                role = McMenuRole.Destructive,
                onClick = {
                    menuOpen = false
                    onDelete()
                },
            )
        }
    }
}

/** Joins the facts on a saved-path row: run count, recency, success percent. */
private const val SAVED_PATH_SUBTITLE_SEPARATOR = ", "

/** iOS `SavedPathRow` draws the recent-round-trip sparkline at 50 by 16 points. */
internal const val SAVED_PATH_ROW_SPARKLINE_WIDTH = 50
internal const val SAVED_PATH_ROW_SPARKLINE_HEIGHT = 16
private const val SAVED_PATH_ROW_SPARKLINE_GAP = 8

/** Visible saved-path facts: runs, recency, and success percent — not a colour-only health dot. */
internal fun savedPathListSubtitle(runsLabel: String, lastRun: String?, successRate: Int): String =
    listOfNotNull(runsLabel, lastRun, "$successRate%").joinToString(SAVED_PATH_SUBTITLE_SEPARATOR)
