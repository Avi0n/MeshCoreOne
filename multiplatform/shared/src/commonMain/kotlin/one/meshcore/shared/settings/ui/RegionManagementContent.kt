package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_region_discovering
import one.meshcore.shared.resources.contacts_list_empty_search_title
import one.meshcore.shared.resources.localizable_common_search
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RegionDiscoverStatusRow(isDiscovering: Boolean, statusMessage: String?) {
    if (isDiscovering) {
        ListItem(
            headlineContent = {
                Text(stringResource(Res.string.chats_channel_info_region_discovering))
            },
            trailingContent = {
                CircularProgressIndicator(
                    modifier = Modifier.size(REGION_DISCOVERING_PROGRESS_SIZE),
                    strokeWidth = 2.dp,
                )
            },
            colors = regionManagementRowColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        statusMessage?.let { message ->
            ListItem(
                headlineContent = {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = regionManagementRowColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun RegionDiscoveryHost(
    discovered: List<String>,
    showAppBar: Boolean,
    modifier: Modifier,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val results: @Composable () -> Unit = {
        RegionDiscoveryResultsScreen(
            discoveredRegions = discovered,
            onAdd = onAdd,
            onBack = onDismiss,
            modifier = if (showAppBar) modifier else Modifier.fillMaxSize(),
        )
    }
    if (regionDiscoveryCoversHubChrome(showAppBar)) {
        FullScreenOverlay(onDismiss = onDismiss) { _ ->
            results()
        }
    } else {
        results()
    }
}

@Composable
internal fun RegionManagementBody(
    sortedRegions: List<String>,
    visibleRegions: List<String>,
    showSearch: Boolean,
    searchText: String,
    onSearchTextChange: (String) -> Unit,
    onRemoveRegion: (String) -> Unit,
    actions: @Composable () -> Unit,
    innerPadding: PaddingValues,
) {
    if (sortedRegions.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
        ) {
            RegionManagementEmptyState(Modifier.weight(1f).fillMaxWidth())
            actions()
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(top = PreferenceHubTopPadding, bottom = 24.dp),
        ) {
            if (showSearch) {
                AppSearchField(
                    value = searchText,
                    onValueChange = onSearchTextChange,
                    placeholder = stringResource(Res.string.localizable_common_search),
                )
            }
            if (visibleRegions.isEmpty()) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                ) {
                    Icon(
                        imageVector = AppIcons.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
                    )
                    Text(
                        text = stringResource(Res.string.contacts_list_empty_search_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    item {
                        PreferenceHubSection {
                            for (region in visibleRegions) {
                                KnownRegionRow(
                                    name = region,
                                    onRemove = { onRemoveRegion(region) },
                                )
                            }
                        }
                    }
                }
            }
            actions()
        }
    }
}
