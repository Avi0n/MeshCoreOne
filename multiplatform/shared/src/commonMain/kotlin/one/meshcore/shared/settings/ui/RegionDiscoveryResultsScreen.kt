package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import one.meshcore.shared.remotenode.isPrivateRegion
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_region_add_selected
import one.meshcore.shared.resources.chats_channel_info_region_discover
import one.meshcore.shared.resources.chats_channel_info_region_private
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.expressiveListItem
import org.jetbrains.compose.resources.stringResource

/**
 * Multi-select of newly discovered regions before they are added to the device's known regions.
 * Every discovered name starts selected; the user deselects any to skip, then adds the rest. Mirrors
 * iOS `RegionDiscoveryResultsView` — discovery never auto-adds; the user picks.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionDiscoveryResultsScreen(
    discoveredRegions: List<String>,
    onAdd: (List<String>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sortedRegions = remember(discoveredRegions) { discoveredRegions.sorted() }
    var selected by remember(discoveredRegions) { mutableStateOf(discoveredRegions.toSet()) }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.chats_channel_info_region_discover),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(Modifier.weight(1f)) {
                items(sortedRegions, key = { it }) { region ->
                    ListItem(
                        headlineContent = { Text(region) },
                        supportingContent = if (region.isPrivateRegion()) {
                            {
                                Text(stringResource(Res.string.chats_channel_info_region_private))
                            }
                        } else {
                            null
                        },
                        leadingContent = {
                            Checkbox(
                                checked = region in selected,
                                onCheckedChange = null,
                            )
                        },
                        modifier = expressiveListItem(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = region in selected,
                                    role = Role.Checkbox,
                                    onValueChange = {
                                        selected = if (region in selected) selected - region else selected + region
                                    },
                                ),
                        ),
                    )
                }
            }
            Button(
                onClick = {
                    onAdd(selected.toList())
                    onBack()
                },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(Res.string.chats_channel_info_region_add_selected))
            }
        }
    }
}
