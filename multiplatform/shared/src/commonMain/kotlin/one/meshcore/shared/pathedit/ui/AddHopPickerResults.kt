package one.meshcore.shared.pathedit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.pathedit.AddHopFilter
import one.meshcore.shared.pathedit.PickerNode
import one.meshcore.shared.pathedit.PickerSections
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_node_kind_discovered
import one.meshcore.shared.resources.contacts_node_kind_room
import one.meshcore.shared.resources.contacts_path_edit_add_to_path_as_hop
import one.meshcore.shared.resources.contacts_path_edit_max_hops_description
import one.meshcore.shared.resources.contacts_path_edit_max_hops_reached
import one.meshcore.shared.resources.contacts_path_edit_no_favorites_description
import one.meshcore.shared.resources.contacts_path_edit_no_favorites_title
import one.meshcore.shared.resources.contacts_path_edit_no_recent_description
import one.meshcore.shared.resources.contacts_path_edit_no_recent_title
import one.meshcore.shared.resources.contacts_path_edit_no_repeaters_description
import one.meshcore.shared.resources.contacts_path_edit_no_repeaters_title
import one.meshcore.shared.resources.contacts_path_edit_search_no_matches_description
import one.meshcore.shared.resources.contacts_path_edit_search_no_matches_description_with_rooms_hint
import one.meshcore.shared.resources.contacts_path_edit_sections_contacts
import one.meshcore.shared.resources.contacts_path_edit_sections_discovered
import one.meshcore.shared.resources.contacts_path_edit_sections_favorites
import one.meshcore.shared.resources.contacts_path_edit_sections_recent
import one.meshcore.shared.resources.contacts_path_edit_sections_rooms
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.FAVORITE_STAR_ROLE
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberAppHaptics
import one.meshcore.shared.ui.tint
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ResultsContent(
    sections: PickerSections,
    filter: AddHopFilter,
    query: String,
    nextHopNumber: Int,
    roomsWouldMatch: Boolean,
    onTap: (PickerNode) -> Unit,
) {
    if (sections.isEmpty) {
        EmptyResults(filter = filter, query = query, roomsWouldMatch = roomsWouldMatch)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        section(Res.string.contacts_path_edit_sections_recent, sections.recent, nextHopNumber, onTap)
        section(Res.string.contacts_path_edit_sections_favorites, sections.favorites, nextHopNumber, onTap)
        section(Res.string.contacts_path_edit_sections_contacts, sections.contacts, nextHopNumber, onTap)
        section(Res.string.contacts_path_edit_sections_discovered, sections.discovered, nextHopNumber, onTap)
        section(Res.string.contacts_path_edit_sections_rooms, sections.rooms, nextHopNumber, onTap)
    }
}

private fun LazyListScope.section(
    title: StringResource,
    nodes: List<PickerNode>,
    nextHopNumber: Int,
    onTap: (PickerNode) -> Unit,
) {
    if (nodes.isEmpty()) return
    item {
        PreferenceHubSection(
            title = stringResource(title),
        ) {
            nodes.forEach { node ->
                PickerRow(node = node, nextHopNumber = nextHopNumber, onTap = { onTap(node) })
            }
        }
    }
}

@Composable
private fun PickerRow(node: PickerNode, nextHopNumber: Int, onTap: () -> Unit) {
    val addLabel = formattedStringResource(
        Res.string.contacts_path_edit_add_to_path_as_hop,
        node.displayName,
        nextHopNumber,
    )
    var showSuccess by remember(node.id) { mutableStateOf(false) }
    var successJob by remember(node.id) { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    ListItem(
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PathEditMetrics.badgeSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    node.displayName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (node.isFavorite) {
                    Icon(
                        imageVector = AppIcons.Star,
                        contentDescription = null,
                        modifier = Modifier.size(FAVORITE_STAR_DP.dp),
                        tint = FAVORITE_STAR_ROLE.tint(),
                    )
                }
                if (node.isDiscovered) {
                    NodeKindBadge(
                        stringResource(Res.string.contacts_node_kind_discovered),
                        MaterialTheme.colorScheme.primary,
                    )
                }
                if (node.isRoom) {
                    NodeKindBadge(
                        stringResource(Res.string.contacts_node_kind_room),
                        MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        },
        supportingContent = {
            Text(
                text = node.publicKeyHex,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Icon(
                imageVector = if (showSuccess) AppIcons.CheckCircle else AppIcons.AddCircle,
                contentDescription = null,
                modifier = Modifier.size(PathEditMetrics.ctaIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PathEditMetrics.tapTarget)
            .semantics(mergeDescendants = true) { contentDescription = addLabel }
            .clickable(onClick = {
                haptics.tap()
                onTap()
                showSuccess = true
                successJob?.cancel()
                successJob = scope.launch {
                    delay(PathEditMetrics.ADD_HOP_SUCCESS_MS)
                    showSuccess = false
                }
            }),
    )
}

@Composable
private fun EmptyResults(filter: AddHopFilter, query: String, roomsWouldMatch: Boolean) {
    val title: String
    val description: String
    when {
        query.isNotEmpty() -> {
            title = stringResource(Res.string.contacts_path_edit_no_repeaters_title)
            description = stringResource(
                if (filter != AddHopFilter.ALL && roomsWouldMatch) {
                    Res.string.contacts_path_edit_search_no_matches_description_with_rooms_hint
                } else {
                    Res.string.contacts_path_edit_search_no_matches_description
                },
            )
        }
        filter == AddHopFilter.FAVORITES -> {
            title = stringResource(Res.string.contacts_path_edit_no_favorites_title)
            description = stringResource(Res.string.contacts_path_edit_no_favorites_description)
        }
        filter == AddHopFilter.RECENT -> {
            title = stringResource(Res.string.contacts_path_edit_no_recent_title)
            description = stringResource(Res.string.contacts_path_edit_no_recent_description)
        }
        else -> {
            title = stringResource(Res.string.contacts_path_edit_no_repeaters_title)
            description = stringResource(Res.string.contacts_path_edit_no_repeaters_description)
        }
    }
    CenteredMessage(
        title = title,
        description = description,
        icon = addHopEmptyIcon(query),
    )
}

@Composable
internal fun MaxHopsUnavailable(hopLimit: Int) {
    CenteredMessage(
        title = stringResource(Res.string.contacts_path_edit_max_hops_reached),
        description = formattedStringResource(Res.string.contacts_path_edit_max_hops_description, hopLimit),
        icon = ADD_HOP_MAX_HOPS_ICON,
    )
}

@Composable
private fun CenteredMessage(title: String, description: String, icon: ImageVector) {
    Column(
        modifier = Modifier.fillMaxSize().padding(PathEditMetrics.rowInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
        )
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** iOS Add Hop empty: search vs `antenna.radiowaves.left.and.right.slash`. */
internal fun addHopEmptyIcon(query: String): ImageVector =
    if (query.isNotEmpty()) AppIcons.Search else PATH_EDIT_EMPTY_ICON

/** iOS Add Hop max hops: `checkmark.circle`. */
internal val ADD_HOP_MAX_HOPS_ICON get() = PATH_EDIT_PATH_FULL_ICON

/** iOS `AddHopPickerView.bannerText` after a hop lands — next slot, or the max-hops line. */
internal fun addHopBannerAnnouncement(
    pathSize: Int,
    hopLimit: Int?,
    positionAppendTemplate: String,
    maxHopsReached: String,
): String {
    val isFull = hopLimit != null && pathSize >= hopLimit
    return if (isFull) {
        maxHopsReached
    } else {
        formatTemplate(positionAppendTemplate, listOf(pathSize + 1))
    }
}

private const val FAVORITE_STAR_DP = 12
