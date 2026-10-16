package one.meshcore.shared.contact.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.contact.ContactListViewModel
import one.meshcore.shared.contact.DEFAULT_HASH_SIZE
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_list_offline_announcement
import one.meshcore.shared.ui.AnnounceOnAppear
import one.meshcore.shared.ui.rememberMinuteTickSeconds
import one.meshcore.shared.ui.shouldAnnounceCachedData
import org.jetbrains.compose.resources.stringResource
import kotlin.time.ExperimentalTime

/**
 * The composition root wiring the Nodes tab to a [ContactListViewModel]. A `null` contact service or
 * radio id renders the disconnected state.
 *
 * Discovery and Blocked are owned by the navigation coordinator, not local composable state, so system
 * Back is consumed by the shell instead of finishing the activity.
 */
@OptIn(ExperimentalTime::class)
@Composable
internal fun ContactListRoot(inputs: ContactListInputs, modifier: Modifier = Modifier) {
    val currentInputs by rememberUpdatedState(inputs)
    val contactService = inputs.contactService
    val radioId = inputs.radioId
    val connectionState = inputs.connectionState
    val deviceHashSize = inputs.deviceHashSize
    val onContactTap = inputs.onContactTap
    val selectedContactId = inputs.selectedContactId
    val onSendMessage = inputs.onSendMessage
    val onAddContact = inputs.onAddContact
    val onShareMyContact = inputs.onShareMyContact
    val discoveredNodeStore = inputs.discoveredNodeStore
    val userLocation = inputs.userLocation
    val selfPublicKey = inputs.selfPublicKey
    val sortOrderStore = inputs.sortOrderStore
    val locationService = inputs.locationService
    val onOpenAppSettings = inputs.onOpenAppSettings
    val showingDiscovery = inputs.showingDiscovery
    val showingBlocked = inputs.showingBlocked
    val onDiscover = inputs.onDiscover
    val onBlockedContacts = inputs.onBlockedContacts
    val onBackFromDiscovery = inputs.onBackFromDiscovery
    val onBackFromBlocked = inputs.onBackFromBlocked
    val listInvalidations = inputs.listInvalidations
    val maxContacts = inputs.maxContacts
    val scope = rememberCoroutineScope()
    val state by connectionState.collectAsStateWithLifecycle()
    // Resolved here because a view model cannot read string resources; the service errors reach the list
    // as localized text instead of the exception's built-in English.
    val errorText = rememberContactServiceErrorText()
    val currentErrorText by rememberUpdatedState(errorText)
    val currentContactService = contactService()
    val currentRadioId = radioId()

    val viewModel = remember(scope) {
        ContactListViewModel(
            scope = scope,
            dependencies = ContactListViewModel.Dependencies(
                contactService = { currentInputs.contactService() },
                radioId = { currentInputs.radioId() },
                isConnected = { currentInputs.connectionState.value.isConnected },
                isReady = { currentInputs.connectionState.value == DeviceConnectionState.READY },
                userLocation = { currentInputs.userLocation() },
                // Best-effort passively-heard inbound hops from the volatile discovered-node table, keyed by
                // uppercase-hex public key to match the contact rows (iOS `inboundHopByKey`); an empty map
                // when the store is unavailable leaves flood-routed contacts on their "Flood" fallback.
                inboundHops = { radio ->
                    currentInputs.discoveredNodeStore()?.list(radio).orEmpty().mapNotNull { record ->
                        record.inboundHopCount?.let { record.publicKey.toUppercaseHex() to it }
                    }.toMap()
                },
                sortOrderStore = sortOrderStore,
                listInvalidations = listInvalidations,
                errorText = { error -> currentErrorText(error) },
                onContactRemoved = { contact -> currentInputs.onContactRemoved(contact) },
            ),
        )
    }
    DisposableEffect(viewModel) {
        onDispose(viewModel::close)
    }

    // Add Contact, QR scan, Discover "Add" and node-config import mutate the table without a device
    // push, so the list reloads off the app-wide invalidation as iOS reloads off `contactsVersion`.
    LaunchedEffect(viewModel, listInvalidations) { viewModel.bindListInvalidations(listInvalidations) }

    // Rebind to the service instance, including null on disconnect, so a retired connection cannot
    // keep delivering device contact events while the Nodes tab remains composed.
    LaunchedEffect(viewModel, currentContactService) { viewModel.bindServiceEvents() }

    val locationStatus = locationService?.authorizationStatus?.collectAsStateWithLifecycle()?.value
    // Collect the phone fix so Distance sort re-runs when GPS arrives (iOS onChange of locationSample).
    val phoneFix = locationService?.currentLocation?.collectAsStateWithLifecycle()?.value
    val resolvedUserLocation: () -> UserLocation? = {
        phoneFix?.let { UserLocation(it.latitude, it.longitude) } ?: userLocation()
    }
    val hashSize = deviceHashSize() ?: DEFAULT_HASH_SIZE

    // A single timestamp anchor for the rows' relative-age labels; live ticking is not needed here.
    val nowSeconds by rememberMinuteTickSeconds()

    // Reload whenever the connection owner or state changes so persisted Room rows render as soon as
    // the live graph appears, rather than waiting for contact sync to emit device pushes.
    LaunchedEffect(viewModel, state, currentContactService, currentRadioId) { viewModel.load() }

    if (showingBlocked) {
        BlockedContactsScreen(
            contactService = contactService,
            radioId = radioId,
            onBack = onBackFromBlocked,
            onContactTap = { contact -> onContactTap(contact.id.value) },
            nowSeconds = nowSeconds,
            hashSize = hashSize,
            listInvalidations = listInvalidations,
            modifier = modifier,
        )
        return
    }

    if (showingDiscovery) {
        DiscoveryScreen(
            store = discoveredNodeStore,
            contactService = contactService,
            radioId = radioId,
            isRadioConnected = { connectionState.value.isConnected },
            onBack = onBackFromDiscovery,
            // The same location the Nodes list sorts by, so Distance sort and the row "X away" label
            // work on both screens (iOS `bestAvailableLocation`). Collecting the phone fix above
            // makes this lambda refresh when GPS arrives.
            userLocation = resolvedUserLocation,
            modifier = modifier,
            // One app-wide preferences store, two namespaced keys: Discover remembers its own order.
            sortOrderStore = sortOrderStore,
            listInvalidations = listInvalidations,
            maxContacts = maxContacts,
        )
        return
    }

    // Nothing on the list marks its rows as stale, so an arriving screen has to say so, as iOS does from
    // ContactListActions.announceOfflineStateIfNeeded.
    AnnounceOnAppear(
        announce = shouldAnnounceCachedData(state, currentRadioId != null),
        message = stringResource(Res.string.contacts_list_offline_announcement),
    )

    ContactListScreen(
        viewModel = viewModel,
        screenState = ContactListScreenState(
            isReady = state == DeviceConnectionState.READY,
            nowSeconds = nowSeconds,
            hashSize = hashSize,
            selectedContactId = selectedContactId,
            userLocation = resolvedUserLocation(),
            selfPublicKey = selfPublicKey(),
            locationStatus = locationStatus,
        ),
        actions = ContactListScreenActions(
            onContactTap = { contact -> onContactTap(contact.id.value) },
            onSendMessage = { contact -> onSendMessage(contact.id.value) },
            onAddContact = onAddContact,
            onShareMyContact = onShareMyContact,
            onBlockedContacts = onBlockedContacts,
            onDiscover = onDiscover,
            onRequestLocation = { locationService?.requestLocation() },
            onRequestLocationPermission = { locationService?.requestPermissionIfNeeded() },
            onOpenAppSettings = onOpenAppSettings,
        ),
        modifier = modifier,
    )
}
