package one.meshcore.shared.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_segment_contacts
import one.meshcore.shared.resources.contacts_segment_favorites
import one.meshcore.shared.resources.contacts_segment_repeaters
import one.meshcore.shared.resources.contacts_segment_rooms
import one.meshcore.shared.resources.map_callout_discovered
import one.meshcore.shared.resources.map_controls_filter
import one.meshcore.shared.resources.map_controls_filter_active
import one.meshcore.shared.resources.map_filters_title
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.menu.McMenuSectionHeader
import org.jetbrains.compose.resources.stringResource

/**
 * Filter FAB + menu for a [MapFilterHost]. Neighbor SNR and Trace Path expose favorites and
 * discovered only; Main Map also toggles contact types. Mirrors iOS `MapControlsToolbar` filter menu.
 */
@Composable
fun MapFilterButton(
    host: MapFilterHost,
    filter: MapFilterState,
    onFilterChange: (MapFilterState) -> Unit,
    modifier: Modifier = Modifier,
    onAdvancedFilters: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val filterActive = !filter.seedEquals(host)
    val filterActiveLabel = stringResource(Res.string.map_controls_filter_active)
    Box(modifier) {
        SmallFloatingActionButton(
            onClick = { menuOpen = true },
            containerColor = if (filterActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                FloatingActionButtonDefaults.containerColor
            },
        ) {
            Icon(
                imageVector = AppIcons.FilterList,
                contentDescription = stringResource(Res.string.map_controls_filter),
                modifier = if (filterActive) {
                    Modifier.semantics { stateDescription = filterActiveLabel }
                } else {
                    Modifier
                },
            )
        }
        McDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            McMenuSectionHeader(text = stringResource(Res.string.map_controls_filter))
            McMenuItem(
                text = stringResource(Res.string.contacts_segment_favorites),
                leadingIcon = AppIcons.Star,
                selected = filter.favoritesOnly,
                onClick = { onFilterChange(filter.setFavoritesOnly(!filter.favoritesOnly)) },
            )
            McMenuItem(
                text = stringResource(Res.string.map_callout_discovered),
                leadingIcon = AppIcons.Nodes,
                selected = filter.showDiscovered,
                enabled = !filter.favoritesOnly,
                onClick = { onFilterChange(filter.setShowDiscovered(!filter.showDiscovered)) },
            )
            if (host.includesTypes) {
                HorizontalDivider()
                McMenuItem(
                    text = stringResource(Res.string.contacts_segment_contacts),
                    selected = filter.showChat,
                    enabled = !filter.favoritesOnly,
                    onClick = {
                        onFilterChange(filter.setShowChat(!filter.showChat, host))
                    },
                )
                McMenuItem(
                    text = stringResource(Res.string.contacts_segment_repeaters),
                    selected = filter.showRepeater,
                    enabled = !filter.favoritesOnly,
                    onClick = {
                        onFilterChange(filter.setShowRepeater(!filter.showRepeater, host))
                    },
                )
                McMenuItem(
                    text = stringResource(Res.string.contacts_segment_rooms),
                    selected = filter.showRoom,
                    enabled = !filter.favoritesOnly,
                    onClick = {
                        onFilterChange(filter.setShowRoom(!filter.showRoom, host))
                    },
                )
            }
            if (host.includesAdvanced && onAdvancedFilters != null) {
                HorizontalDivider()
                McMenuItem(
                    text = stringResource(Res.string.map_filters_title),
                    leadingIcon = AppIcons.FilterList,
                    onClick = {
                        menuOpen = false
                        onAdvancedFilters()
                    },
                )
            }
        }
    }
}
