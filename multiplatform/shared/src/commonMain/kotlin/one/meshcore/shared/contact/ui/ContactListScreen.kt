package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.ContactListViewModel
import one.meshcore.shared.contact.NodeSortOrder
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.map.isAuthorized
import one.meshcore.shared.map.isDenied
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.contacts_list_add_contact
import one.meshcore.shared.resources.contacts_list_blocked_contacts
import one.meshcore.shared.resources.contacts_list_discover
import one.meshcore.shared.resources.contacts_list_distance_requires_location
import one.meshcore.shared.resources.contacts_list_location_unavailable
import one.meshcore.shared.resources.contacts_list_open_settings
import one.meshcore.shared.resources.contacts_list_options
import one.meshcore.shared.resources.contacts_list_search_prompt
import one.meshcore.shared.resources.contacts_list_search_prompt_with_count
import one.meshcore.shared.resources.contacts_list_share_my_contact
import one.meshcore.shared.resources.contacts_list_sync_nodes
import one.meshcore.shared.resources.contacts_list_title
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.AppRootTopBar
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIconButton
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.menu.McDropdownMenu
import one.meshcore.shared.ui.menu.McFabMenu
import one.meshcore.shared.ui.menu.McFabMenuItem
import one.meshcore.shared.ui.menu.McMenuDivider
import one.meshcore.shared.ui.menu.McMenuItem
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Nodes (contacts) list screen: a search field, a segment picker (hidden while searching), and a
 * pull-to-refresh lazy list of [ContactRow]s with per-segment empty and search-empty states. Sort and
 * sync live in an overflow menu; create flows use a FAB menu. Kotlin port of the iOS `ContactsListView`
 * / `ContactsSidebarContent` for the compact layout; detail navigation is delegated through [actions].
 *
 * The screen is driven entirely by [viewModel]; [screenState] gates row actions and renders timestamps.
 * Sync stays tappable while disconnected and explains itself through the offline alert, as on iOS,
 * rather than greying out with no reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    viewModel: ContactListViewModel,
    screenState: ContactListScreenState,
    actions: ContactListScreenActions,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filtered = remember(state) { viewModel.filteredContacts(state) }
    // A contact sync ends with no visible change when nothing arrived, so the finish is confirmed by
    // touch. The offline path returns before syncing and gets no confirmation, matching iOS.
    val syncContacts: () -> Unit = {
        scope.launch {
            viewModel.refresh()
            if (screenState.isReady) haptics.success()
        }
    }
    // The V-contact key derived once from the self key; per-row Delete is hidden when a contact matches it.
    val vContactKey = remember(screenState.selfPublicKey) {
        screenState.selfPublicKey?.let { VContactIdentity.publicKey(it) }
    }
    var showLocationDeniedAlert by remember { mutableStateOf(false) }

    // Selecting Distance sort requests a fix when authorized, prompts when undecided, and otherwise
    // surfaces the location-unavailable alert, mirroring the iOS `.task(id: sortOrder)` + status onChange.
    LaunchedEffect(state.sortOrder, screenState.locationStatus) {
        if (state.sortOrder == NodeSortOrder.DISTANCE && screenState.locationStatus != null) {
            when {
                screenState.locationStatus.isAuthorized -> actions.onRequestLocation()
                screenState.locationStatus.isDenied -> showLocationDeniedAlert = true
                else -> actions.onRequestLocationPermission()
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppRootTopBar(
                title = stringResource(Res.string.contacts_list_title),
                actions = {
                    NodesOverflowMenu(
                        sortOrder = state.sortOrder,
                        onSortSelect = viewModel::setSortOrder,
                        syncEnabled = !state.isSyncing,
                        onSync = syncContacts,
                        onBlockedContacts = actions.onBlockedContacts,
                    )
                },
            )
        },
        floatingActionButton = {
            NodesFabMenu(
                enabled = screenState.isReady,
                onAddContact = actions.onAddContact,
                onShareMyContact = actions.onShareMyContact,
                onDiscover = actions.onDiscover,
            )
        },
    ) { innerPadding ->
        val searchPrompt = if (state.contacts.isNotEmpty()) {
            formattedStringResource(Res.string.contacts_list_search_prompt_with_count, state.contacts.size)
        } else {
            stringResource(Res.string.contacts_list_search_prompt)
        }
        PullToRefreshBox(
            isRefreshing = state.isSyncing,
            onRefresh = syncContacts,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            ContactListBody(
                state = ContactListBodyState(
                    list = state,
                    filtered = filtered,
                    searchPlaceholder = searchPrompt,
                    isReady = screenState.isReady,
                    nowSeconds = screenState.nowSeconds,
                    hashSize = screenState.hashSize,
                    userLocation = screenState.userLocation,
                    vContactKey = vContactKey,
                    selectedContactId = screenState.selectedContactId,
                ),
                actions = ContactListBodyActions(
                    onSearchChange = viewModel::setSearchText,
                    onContactTap = actions.onContactTap,
                    onSendMessage = actions.onSendMessage,
                    onToggleFavorite = { contact -> scope.launch { viewModel.toggleFavorite(contact) } },
                    onToggleBlocked = { contact -> scope.launch { viewModel.toggleBlocked(contact) } },
                    onDelete = { contact -> scope.launch { viewModel.deleteContact(contact) } },
                    onSelectSegment = viewModel::setSegment,
                ),
            )
        }
    }

    state.error?.let { error ->
        AcknowledgedAlertDialog(
            title = error.title(),
            message = error.message(),
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = viewModel::clearError,
        )
    }

    if (showLocationDeniedAlert) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.contacts_list_location_unavailable),
            message = stringResource(Res.string.contacts_list_distance_requires_location),
            confirmLabel = stringResource(Res.string.contacts_list_open_settings),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                showLocationDeniedAlert = false
                actions.onOpenAppSettings()
            },
            onDismiss = { showLocationDeniedAlert = false },
        )
    }
}

@Composable
private fun NodesOverflowMenu(
    sortOrder: NodeSortOrder,
    onSortSelect: (NodeSortOrder) -> Unit,
    syncEnabled: Boolean,
    onSync: () -> Unit,
    onBlockedContacts: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(nodesOverflowMenuLabel())
    Box {
        AppIconButton(
            imageVector = AppIcons.MoreVert,
            contentDescription = label,
            onClick = { expanded = true },
        )
        McDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            McMenuItem(
                text = stringResource(Res.string.contacts_list_sync_nodes),
                leadingIcon = AppIcons.Sync,
                enabled = syncEnabled,
                onClick = {
                    expanded = false
                    onSync()
                },
            )
            McMenuItem(
                text = stringResource(Res.string.contacts_list_blocked_contacts),
                leadingIcon = AppIcons.FrontHand,
                onClick = {
                    expanded = false
                    onBlockedContacts()
                },
            )
            McMenuDivider()
            NodeSortMenuItems(
                sortOrder = sortOrder,
                onSortSelect = onSortSelect,
                onDismiss = { expanded = false },
            )
        }
    }
}

@Composable
private fun NodesFabMenu(
    enabled: Boolean,
    onAddContact: () -> Unit,
    onShareMyContact: () -> Unit,
    onDiscover: () -> Unit,
) {
    val label = stringResource(Res.string.contacts_list_add_contact)
    McFabMenu(
        expandedContentDescription = label,
        collapsedIcon = AppIcons.Add,
        items = listOf(
            McFabMenuItem(
                label = stringResource(Res.string.contacts_list_add_contact),
                icon = AppIcons.PersonAdd,
                onClick = onAddContact,
            ),
            McFabMenuItem(
                label = stringResource(Res.string.contacts_list_share_my_contact),
                icon = AppIcons.Share,
                enabled = enabled,
                onClick = onShareMyContact,
            ),
            // Discover reads the persisted discovered-node table, so it stays reachable while
            // disconnected, matching the iOS button that carries no disabled gate.
            McFabMenuItem(
                label = stringResource(Res.string.contacts_list_discover),
                icon = AppIcons.Sensors,
                onClick = onDiscover,
            ),
        ),
    )
}

/** Overflow (⋯) TalkBack name, matching iOS `Contacts.List.options` on the ellipsis. */
internal fun nodesOverflowMenuLabel(): StringResource = Res.string.contacts_list_options
