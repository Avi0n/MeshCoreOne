package one.meshcore.shared.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch
import one.meshcore.shared.channel.RegionDiscoverUiResult
import one.meshcore.shared.chat.ui.chatImePadding
import one.meshcore.shared.remotenode.MAX_REGION_NAME_BYTES
import one.meshcore.shared.remotenode.RegionNameValidator
import one.meshcore.shared.remotenode.isPrivateRegion
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_action_delete
import one.meshcore.shared.resources.chats_channel_info_region_add_manually
import one.meshcore.shared.resources.chats_channel_info_region_add_region_placeholder
import one.meshcore.shared.resources.chats_channel_info_region_add_region_title
import one.meshcore.shared.resources.chats_channel_info_region_add_selected
import one.meshcore.shared.resources.chats_channel_info_region_discover
import one.meshcore.shared.resources.chats_channel_info_region_duplicate
import one.meshcore.shared.resources.chats_channel_info_region_invalid_name
import one.meshcore.shared.resources.chats_channel_info_region_manage
import one.meshcore.shared.resources.chats_channel_info_region_name_too_long
import one.meshcore.shared.resources.chats_channel_info_region_no_regions
import one.meshcore.shared.resources.chats_channel_info_region_no_regions_description
import one.meshcore.shared.resources.chats_channel_info_region_private
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.EMPTY_STATE_ICON_SIZE
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.OptionalNestedScaffold
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.PreferenceHubTopPadding
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

/**
 * Settings → Advanced → Manage Regions: manual add/delete of the device's known regions, plus a
 * discover shortcut. Mirrors iOS `RegionManagementView`.
 */
@Composable
fun RegionManagementScreen(
    knownRegions: List<String>,
    onBack: () -> Unit,
    onRemoveRegion: suspend (String) -> Unit,
    onAddRegion: suspend (String) -> Unit,
    onDiscover: suspend () -> RegionDiscoverUiResult,
    discoverMessage: suspend (RegionDiscoverUiResult) -> String?,
    modifier: Modifier = Modifier,
    /** False when a parent [Scaffold] already titled this destination (Settings hub). */
    showAppBar: Boolean = true,
) {
    val scope = rememberCoroutineScope()
    var isDiscovering by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var discoveredForSelection by remember { mutableStateOf<List<String>?>(null) }
    val invalidNameMessage = stringResource(Res.string.chats_channel_info_region_invalid_name)
    val tooLongMessage = formattedStringResource(
        Res.string.chats_channel_info_region_name_too_long,
        MAX_REGION_NAME_BYTES,
    )
    val duplicateMessage = stringResource(Res.string.chats_channel_info_region_duplicate)
    var showAddDialog by remember { mutableStateOf(false) }
    var newRegionName by remember { mutableStateOf("") }
    var validationMessage by remember { mutableStateOf<String?>(null) }
    var displayedRegions by remember(knownRegions) { mutableStateOf(knownRegions) }
    val sortedRegions = remember(displayedRegions) { displayedRegions.sorted() }
    var searchText by remember { mutableStateOf("") }
    val visibleRegions = remember(sortedRegions, searchText) {
        filteredKnownRegions(sortedRegions, searchText)
    }
    val showSearch = regionManagementSearchEnabled(sortedRegions.size)

    suspend fun persistAndDisplayRegion(region: String) {
        onAddRegion(region)
        displayedRegions = regionManagementRegionsAfterAdd(displayedRegions, region)
    }

    suspend fun removeAndHideRegion(region: String) {
        onRemoveRegion(region)
        displayedRegions = regionManagementRegionsAfterRemove(displayedRegions, region)
    }

    fun submitNewRegion() {
        val error = RegionNameValidator.validate(newRegionName, existingRegions = sortedRegions)
        if (error != null) {
            validationMessage = regionValidationMessage(
                error = error,
                invalidNameMessage = invalidNameMessage,
                tooLongMessage = tooLongMessage,
                duplicateMessage = duplicateMessage,
            )
            return
        }
        val trimmed = newRegionName.trim()
        showAddDialog = false
        scope.launch { persistAndDisplayRegion(trimmed) }
    }

    discoveredForSelection?.let { discovered ->
        val dismissDiscovery = { discoveredForSelection = null }
        val nestedBackState = rememberNavigationEventState(NavigationEventInfo.None)
        NavigationBackHandler(
            state = nestedBackState,
            isBackEnabled = true,
            onBackCompleted = dismissDiscovery,
        )
        RegionDiscoveryHost(
            discovered = discovered,
            showAppBar = showAppBar,
            modifier = modifier,
            onAdd = { selectedRegions ->
                scope.launch { selectedRegions.forEach { persistAndDisplayRegion(it) } }
            },
            onDismiss = dismissDiscovery,
        )
        return
    }

    val actions: @Composable () -> Unit = {
        Column {
            PreferenceHubSection(
                modifier = Modifier.padding(top = PreferenceHubSectionGap),
            ) {
                RegionDiscoverStatusRow(
                    isDiscovering = isDiscovering,
                    statusMessage = statusMessage,
                )
                ListItem(
                    headlineContent = {
                        Text(stringResource(Res.string.chats_channel_info_region_discover))
                    },
                    leadingContent = { Icon(AppIcons.Sensors, contentDescription = null) },
                    colors = regionManagementRowColors(),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !isDiscovering) {
                        isDiscovering = true
                        statusMessage = null
                        scope.launch {
                            try {
                                val result = onDiscover()
                                val newRegions =
                                    (result as? RegionDiscoverUiResult.Completed)?.newRegions.orEmpty()
                                val outcome = regionDiscoverOutcome(newRegions) { discoverMessage(result) }
                                discoveredForSelection = outcome.regionsToSelect
                                statusMessage = outcome.statusMessage
                            } finally {
                                isDiscovering = false
                            }
                        }
                    },
                )
                ListItem(
                    headlineContent = {
                        Text(stringResource(Res.string.chats_channel_info_region_add_manually))
                    },
                    leadingContent = { Icon(AppIcons.Add, contentDescription = null) },
                    colors = regionManagementRowColors(),
                    modifier = Modifier.fillMaxWidth().clickable {
                        newRegionName = ""
                        validationMessage = null
                        showAddDialog = true
                    },
                )
            }
            PreferenceHubFooter(invalidNameMessage)
        }
    }
    val body: @Composable (PaddingValues) -> Unit = { innerPadding ->
        RegionManagementBody(
            sortedRegions = sortedRegions,
            visibleRegions = visibleRegions,
            showSearch = showSearch,
            searchText = searchText,
            onSearchTextChange = { searchText = it },
            onRemoveRegion = { region -> scope.launch { removeAndHideRegion(region) } },
            actions = actions,
            innerPadding = innerPadding,
        )
    }
    OptionalNestedScaffold(
        showAppBar = showAppBar,
        title = stringResource(Res.string.chats_channel_info_region_manage),
        onBack = onBack,
        modifier = modifier,
        content = body,
    )

    if (showAddDialog) {
        FullScreenOverlay(
            onDismiss = {
                showAddDialog = false
                validationMessage = null
            },
            title = stringResource(Res.string.chats_channel_info_region_add_region_title),
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .chatImePadding()
                    .padding(top = PreferenceHubTopPadding, bottom = PreferenceHubSectionGap),
            ) {
                PreferenceHubSection {
                    Column(Modifier.padding(PreferenceHubCardPadding)) {
                        AppOutlinedTextField(
                            value = newRegionName,
                            onValueChange = {
                                newRegionName = it
                                validationMessage = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(stringResource(Res.string.chats_channel_info_region_add_region_placeholder))
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { submitNewRegion() }),
                            isError = validationMessage != null,
                            supportingText = validationMessage?.let { message ->
                                {
                                    Text(message)
                                }
                            },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = ::submitNewRegion,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PreferenceHubHorizontalPadding),
                ) {
                    Text(stringResource(Res.string.chats_channel_info_region_add_selected))
                }
            }
        }
    }
}

private fun regionValidationMessage(
    error: RegionNameValidator.ValidationError,
    invalidNameMessage: String,
    tooLongMessage: String,
    duplicateMessage: String,
): String? = when (error) {
    RegionNameValidator.ValidationError.Empty -> null
    RegionNameValidator.ValidationError.InvalidCharacters -> invalidNameMessage
    is RegionNameValidator.ValidationError.TooLong -> tooLongMessage
    RegionNameValidator.ValidationError.Duplicate -> duplicateMessage
}

@Composable
internal fun RegionManagementEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(
            imageVector = REGION_MANAGEMENT_EMPTY_ICON,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
        )
        Text(
            text = stringResource(Res.string.chats_channel_info_region_no_regions),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(Res.string.chats_channel_info_region_no_regions_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun KnownRegionRow(name: String, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(name) },
        supportingContent = if (name.isPrivateRegion()) {
            {
                Text(
                    text = stringResource(Res.string.chats_channel_info_region_private),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            null
        },
        trailingContent = {
            AppIconButton(
                imageVector = AppIcons.Delete,
                contentDescription = stringResource(Res.string.chats_action_delete),
                onClick = onRemove,
            )
        },
        colors = regionManagementRowColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun regionManagementRowColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** iOS `RegionManagementEmptyState` `map`. */
internal val REGION_MANAGEMENT_EMPTY_ICON get() = AppIcons.Map

/** Settings hub already titles Manage Regions; Discover must cover that chrome instead of stacking. */
internal fun regionDiscoveryCoversHubChrome(parentShowsAppBar: Boolean): Boolean = !parentShowsAppBar

/** Trailing spinner on the discovering ListItem, matching Channel Options loading. */
internal val REGION_DISCOVERING_PROGRESS_SIZE = 20.dp

/** iOS `SearchableModifier` only attaches `.searchable` once the list has this many names. */
internal const val REGION_MANAGEMENT_SEARCH_THRESHOLD = 15

internal fun regionManagementSearchEnabled(regionCount: Int): Boolean =
    regionCount >= REGION_MANAGEMENT_SEARCH_THRESHOLD

/** iOS `filteredRegions` — sorted list, then `localizedStandardContains` analog. */
internal fun filteredKnownRegions(regions: List<String>, searchText: String): List<String> {
    val query = searchText.trim()
    if (query.isEmpty()) return regions
    return regions.filter { it.contains(query, ignoreCase = true) }
}

internal fun regionManagementRegionsAfterAdd(regions: List<String>, added: String): List<String> =
    (regions + added).distinct().sorted()

internal fun regionManagementRegionsAfterRemove(regions: List<String>, removed: String): List<String> =
    regions.filterNot { it == removed }.sorted()
