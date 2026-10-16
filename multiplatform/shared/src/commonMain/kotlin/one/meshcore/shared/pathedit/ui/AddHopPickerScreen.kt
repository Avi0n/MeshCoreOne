package one.meshcore.shared.pathedit.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.pathedit.AddHopFilter
import one.meshcore.shared.pathedit.AddHopPickerSections
import one.meshcore.shared.pathedit.HopNodeMatching
import one.meshcore.shared.pathedit.HopPickerSource
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_path_edit_add_hop
import one.meshcore.shared.resources.contacts_path_edit_max_hops_reached
import one.meshcore.shared.resources.contacts_path_edit_paste
import one.meshcore.shared.resources.contacts_path_edit_position_append
import one.meshcore.shared.resources.contacts_path_edit_search_prompt
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.plainText
import one.meshcore.shared.ui.rememberAccessibilityAnnouncer
import org.jetbrains.compose.resources.stringResource

/**
 * The shared full-screen Add-Hop picker, ported from the iOS `AddHopPickerView`. Finds a node by name
 * substring or hex prefix; a comma in the search field switches to bulk code entry. Driven by
 * [HopPickerSource] so it serves both the contact editor and the trace builder.
 *
 * The recent-key snapshot is frozen at presentation so a just-tapped row keeps its section place instead
 * of jumping into Recent mid-interaction, matching the iOS `sessionRecentKeys`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddHopPickerScreen(source: HopPickerSource, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val state by source.state.collectAsStateWithLifecycle()
    var searchText by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AddHopFilter.ALL) }
    val sessionRecentKeys = remember { state.recentPublicKeys }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val announcer = rememberAccessibilityAnnouncer()
    val positionAppendTemplate = stringResource(Res.string.contacts_path_edit_position_append)
    val maxHopsReached = stringResource(Res.string.contacts_path_edit_max_hops_reached)
    fun announceBanner() {
        announcer.announce(
            addHopBannerAnnouncement(
                pathSize = source.currentHopCount(),
                hopLimit = source.hopLimit,
                positionAppendTemplate = positionAppendTemplate,
                maxHopsReached = maxHopsReached,
            ),
        )
    }

    val isSearching = searchText.isNotEmpty()
    val isBulkMode = searchText.contains(",")
    val hopLimit = source.hopLimit
    val isPathFull = hopLimit != null && state.editablePath.size >= hopLimit

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_path_edit_add_hop),
                subtitle = if (!isPathFull) {
                    formattedStringResource(
                        Res.string.contacts_path_edit_position_append,
                        state.editablePath.size + 1,
                    )
                } else {
                    null
                },
                onBack = onBack,
                actions = {
                    AppIconButton(
                        imageVector = AppIcons.ContentPaste,
                        contentDescription = stringResource(Res.string.contacts_path_edit_paste),
                        onClick = {
                            scope.launch {
                                val pasted = clipboard.getClipEntry()?.plainText()?.trim().orEmpty()
                                if (pasted.isEmpty()) return@launch
                                searchText = pasted
                                if (pasted.contains(",")) source.adoptHashSize(pasted)
                            }
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AppSearchField(
                value = searchText,
                onValueChange = {
                    searchText = it
                    if (it.contains(",")) source.adoptHashSize(it)
                },
                placeholder = stringResource(Res.string.contacts_path_edit_search_prompt),
            )
            AddHopSegmentPicker(
                selection = filter,
                isSearching = isSearching,
                onSelect = { filter = it },
                modifier = Modifier.padding(horizontal = PathEditMetrics.rowInset),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    isPathFull -> MaxHopsUnavailable(hopLimit ?: 0)
                    isBulkMode -> BulkAddContent(
                        source = source,
                        input = searchText,
                        onAdded = { searchText = "" },
                        onHopsAdded = { announceBanner() },
                    )
                    else -> ResultsContent(
                        sections = AddHopPickerSections.build(
                            state = state,
                            filter = filter,
                            query = searchText,
                            sessionRecentKeys = sessionRecentKeys,
                        ),
                        filter = filter,
                        query = searchText,
                        nextHopNumber = state.editablePath.size + 1,
                        roomsWouldMatch = state.availableRooms.any {
                            HopNodeMatching.matches(PickerNode.Contact(it), searchText)
                        },
                        onTap = { node ->
                            if (!isPathFull) {
                                source.appendHop(node)
                                announceBanner()
                            }
                        },
                    )
                }
            }
        }
    }
}
