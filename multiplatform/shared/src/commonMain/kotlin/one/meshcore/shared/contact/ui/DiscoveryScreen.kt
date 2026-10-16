package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.DiscoveryListPhase
import one.meshcore.shared.contact.DiscoveryViewModel
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.discoveryListPhase
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_common_cancel
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_common_error_occurred
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.resources.contacts_discovery_clear_confirm
import one.meshcore.shared.resources.contacts_discovery_clear_message
import one.meshcore.shared.resources.contacts_discovery_clear_title
import one.meshcore.shared.resources.contacts_discovery_cleared_all_nodes
import one.meshcore.shared.resources.contacts_discovery_empty_description
import one.meshcore.shared.resources.contacts_discovery_empty_search_description
import one.meshcore.shared.resources.contacts_discovery_empty_search_title
import one.meshcore.shared.resources.contacts_discovery_empty_title
import one.meshcore.shared.resources.contacts_discovery_search_prompt
import one.meshcore.shared.resources.contacts_discovery_searching_all_types
import one.meshcore.shared.resources.contacts_discovery_title
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.AppSearchField
import one.meshcore.shared.ui.ConfirmAlertDialog
import one.meshcore.shared.ui.expressiveListItem
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.rememberAccessibilityAnnouncer
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Nodes → Discover: volatile discovered-node list with segment filter, clear, and Add (device contact
 * table). Live NewContact/Advertisement upserts keep the list current. Tools → Node Discovery uses
 * active scan ([one.meshcore.shared.tools.ui.NodeDiscoveryScreen]) instead.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalTime::class)
@Composable
fun DiscoveryScreen(
    store: () -> DiscoveredNodeStore?,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    isRadioConnected: () -> Boolean = { false },
    onBack: () -> Unit,
    /**
     * The user's location for Distance sort and the row "X away" label; `null` when unavailable. Has no
     * default: a caller that omits it silently loses both, which is invisible until a field test.
     */
    userLocation: () -> UserLocation?,
    modifier: Modifier = Modifier,
    /** Persists Discover's sort order (iOS `@AppStorage(discoverySortOrder)`); `null` disables it. */
    sortOrderStore: PreferencesStore? = null,
    /** Emits when an app-side contact mutation changed the table (iOS `contactsVersion`). */
    listInvalidations: Flow<Unit> = emptyFlow(),
    /** The device's contact-table cap, interpolated into the "node list is full" error; null when unknown. */
    maxContacts: () -> Int? = { null },
) {
    val scope = rememberCoroutineScope()
    val loadFailedMessage = stringResource(Res.string.contacts_common_error_occurred)
    val contactErrorText = rememberContactServiceErrorText()
    val currentLoadFailedMessage by rememberUpdatedState(loadFailedMessage)
    val currentContactErrorText by rememberUpdatedState(contactErrorText)
    val currentStoreProvider by rememberUpdatedState(store)
    val currentContactServiceProvider by rememberUpdatedState(contactService)
    val currentRadioIdProvider by rememberUpdatedState(radioId)
    val currentIsRadioConnected by rememberUpdatedState(isRadioConnected)
    val currentUserLocation by rememberUpdatedState(userLocation)
    val currentMaxContacts by rememberUpdatedState(maxContacts)
    val currentStore = store()
    val currentContactService = contactService()
    val currentRadioId = radioId()
    val viewModel = remember(scope) {
        DiscoveryViewModel(
            scope = scope,
            dependencies = DiscoveryViewModel.Dependencies(
                store = { currentStoreProvider() },
                contactService = { currentContactServiceProvider() },
                radioId = { currentRadioIdProvider() },
                isRadioConnected = { currentIsRadioConnected() },
                userLocation = { currentUserLocation() },
                sortOrderStore = sortOrderStore,
                listInvalidations = listInvalidations,
                maxContacts = { currentMaxContacts() },
                discoverTrace = PersistentLogger(scope, category = "discover-trace"),
                loadFailedMessage = { currentLoadFailedMessage },
                errorText = { error -> currentContactErrorText(error) },
            ),
            nowEpochSeconds = { Clock.System.now().epochSeconds },
        )
    }
    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showClearConfirm by remember { mutableStateOf(false) }
    val announcer = rememberAccessibilityAnnouncer()
    val searchingAllTypes = stringResource(Res.string.contacts_discovery_searching_all_types)
    val clearedAllNodes = stringResource(Res.string.contacts_discovery_cleared_all_nodes)
    // A single timestamp anchor for the rows' relative-age labels; live ticking is not needed here.
    val nowSeconds = remember { Clock.System.now().epochSeconds }
    val filtered = viewModel.filteredNodes(state)

    LaunchedEffect(viewModel, currentStore, currentContactService, currentRadioId, listInvalidations) {
        viewModel.load()
        viewModel.startObserving()
        viewModel.bindListInvalidations(listInvalidations)
        viewModel.bindServiceEvents()
    }

    val visibleError = state.addError?.message() ?: state.errorMessage
    if (visibleError != null) {
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.contacts_common_error),
            message = visibleError,
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = viewModel::clearError,
        )
    }

    if (showClearConfirm) {
        ConfirmAlertDialog(
            title = stringResource(Res.string.contacts_discovery_clear_title),
            message = stringResource(Res.string.contacts_discovery_clear_message),
            confirmLabel = stringResource(Res.string.contacts_discovery_clear_confirm),
            dismissLabel = stringResource(Res.string.contacts_common_cancel),
            onConfirm = {
                showClearConfirm = false
                scope.launch {
                    viewModel.clearAll()
                    announcer.announce(clearedAllNodes)
                }
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = stringResource(Res.string.contacts_discovery_title),
                onBack = onBack,
                actions = {
                    DiscoverySortMenu(sortOrder = state.sortOrder, onSortSelect = viewModel::setSortOrder)
                    DiscoveryMoreMenu(
                        isEmpty = state.nodes.isEmpty(),
                        onClear = { showClearConfirm = true },
                    )
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            AppSearchField(
                value = state.searchText,
                onValueChange = { next ->
                    val previous = state.searchText
                    viewModel.setSearchText(next)
                    if (previous.isEmpty() && next.isNotEmpty()) {
                        announcer.announce(searchingAllTypes)
                    }
                },
                placeholder = stringResource(Res.string.contacts_discovery_search_prompt),
            )
            SegmentRow(
                selected = state.segment,
                isSearching = state.isSearching,
                onSelect = viewModel::setSegment,
            )
            LazyColumn(modifier = Modifier.weight(1f).fillMaxSize()) {
                when (discoveryListPhase(state, filtered.size)) {
                    DiscoveryListPhase.LOADING -> item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    DiscoveryListPhase.EMPTY -> item {
                        EmptyBlock(
                            title = stringResource(Res.string.contacts_discovery_empty_title),
                            description = stringResource(Res.string.contacts_discovery_empty_description),
                            icon = discoveryEmptyIcon(isSearch = false),
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                    DiscoveryListPhase.SEARCH_EMPTY -> item {
                        EmptyBlock(
                            title = stringResource(Res.string.contacts_discovery_empty_search_title),
                            description = formattedStringResource(
                                Res.string.contacts_discovery_empty_search_description,
                                state.searchText,
                            ),
                            icon = discoveryEmptyIcon(isSearch = true),
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                    DiscoveryListPhase.ROWS -> items(filtered, key = { it.id.value }) { node ->
                        DiscoveryRow(
                            node = node,
                            isAdded = state.isAdded(node),
                            isAdding = state.addingNodeId == node.id,
                            nowSeconds = nowSeconds,
                            userLocation = userLocation(),
                            onAdd = { scope.launch { viewModel.addNode(node) } },
                            onDelete = { scope.launch { viewModel.deleteNode(node) } },
                            modifier = expressiveListItem(),
                        )
                    }
                }
            }
        }
    }
}
