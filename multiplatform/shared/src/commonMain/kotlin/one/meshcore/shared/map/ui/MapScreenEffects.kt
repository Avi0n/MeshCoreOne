package one.meshcore.shared.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.events
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraStore
import one.meshcore.shared.map.MapFilterPreferences
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.mapContactLoadError
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.MapFocusRequest
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore

@Composable
internal fun MapScreenEffects(
    state: MapScreenState,
    inputs: MapScreenInputs,
    contactErrorText: (Throwable) -> String,
) {
    val preferences = inputs.preferences
    val contactService = inputs.contactService
    val radioId = inputs.radioId
    val pendingMapFocus = inputs.pendingMapFocus
    val onSetDroppedMapPin = inputs.onSetDroppedMapPin
    val onClearMapFocus = inputs.onClearMapFocus
    val locationService = inputs.locationService
    val listInvalidations = inputs.listInvalidations
    val focus by pendingMapFocus.collectAsStateWithLifecycle()
    MapPrefsEffect(state, preferences)
    MapContactsReloadEffect(state, contactService, radioId, contactErrorText)
    MapFilterSelectionEffect(state)
    MapFocusEffect(state, focus, onSetDroppedMapPin, onClearMapFocus)
    MapLocationEffect(locationService)
    MapContactEventsEffect(state, contactService, radioId)
    MapInvalidationEffect(state, preferences, listInvalidations)
}

@Composable
private fun MapPrefsEffect(state: MapScreenState, preferences: PreferencesStore) {
    LaunchedEffect(preferences) {
        val savedStyle = preferences.getString(PersistenceKeys.MAP_STYLE_SELECTION)
        MapStyleSelection.fromRaw(savedStyle.orEmpty())?.let { state.mapStyle = it }
        state.initialCamera = preferences.getString(PersistenceKeys.MAP_CAMERA_REGION)?.let(MapCameraStore::decode)
        state.viewportRegion = state.initialCamera
        state.mapFilter = MapFilterPreferences.applyMainMap(preferences)
        state.prefsLoaded = true
    }
}

@Composable
private fun MapContactsReloadEffect(
    state: MapScreenState,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
    contactErrorText: (Throwable) -> String,
) {
    val service = contactService()
    val radio = radioId()
    LaunchedEffect(service, radio, state.contactReloadKey) {
        if (service == null || radio == null) {
            state.contactsById = emptyMap()
            state.contactsLoadChrome = false
            return@LaunchedEffect
        }
        val showChrome = mapContactsLoadShowsChrome(
            userInitiated = state.userInitiatedContactReload,
            hasCompletedInitialLoad = state.hasCompletedInitialContactLoad,
        )
        state.userInitiatedContactReload = false
        state.contactsLoadChrome = showChrome
        loadContacts(service, radio)
            .onSuccess { contacts ->
                state.contactsById = contacts.associateBy { it.id.value }
            }
            // A store failure must not leave a blank map with no explanation; iOS surfaces it too.
            .onFailure { state.loadError = mapContactLoadError(it, contactErrorText) }
        state.contactsLoadChrome = false
        state.hasCompletedInitialContactLoad = true
    }
}

@Composable
private fun MapFilterSelectionEffect(state: MapScreenState) {
    LaunchedEffect(state.mapFilter) {
        state.dismissMapCallouts()
    }
}

@Composable
private fun MapFocusEffect(
    state: MapScreenState,
    focus: MapFocusRequest?,
    onSetDroppedMapPin: (Double, Double) -> Unit,
    onClearMapFocus: () -> Unit,
) {
    LaunchedEffect(focus, state.prefsLoaded) {
        val request = focus ?: return@LaunchedEffect
        if (!state.prefsLoaded) return@LaunchedEffect
        onSetDroppedMapPin(request.latitude, request.longitude)
        state.cameraTarget = state.cameraTarget.requesting(
            focusCameraRegion(LatLng(request.latitude, request.longitude)),
        )
        onClearMapFocus()
    }
}

@Composable
private fun MapLocationEffect(locationService: LocationService?) {
    // Request permission and a one-shot fix on entry so the user dot and locate button work, mirroring iOS.
    LaunchedEffect(locationService) {
        locationService?.requestPermissionIfNeeded()
        locationService?.requestLocation()
    }
}

@Composable
private fun MapContactEventsEffect(
    state: MapScreenState,
    contactService: () -> ContactService?,
    radioId: () -> Uuid?,
) {
    // Live-refresh contact pins when an advert moves or renames a node, or a node is deleted; iOS reloads
    // off appState.contactsVersion. Re-subscribes when the connection (service/radio) changes.
    val service = contactService()
    val radio = radioId()
    LaunchedEffect(service, radio) {
        service ?: return@LaunchedEffect
        radio ?: return@LaunchedEffect
        service.events().consumeEach { event ->
            if (contactEventReloadsMap(event, radio)) state.contactReloadKey++
        }
    }
}

@Composable
private fun MapInvalidationEffect(
    state: MapScreenState,
    preferences: PreferencesStore,
    listInvalidations: Flow<Unit>,
) {
    // Mutations the app itself makes never reach the radio's event stream, so the map reloads off the
    // same invalidation signal the Nodes list uses; iOS reloads off appState.contactsVersion.
    // Restore also bumps that signal; remigrate so a legacy discovered toggle can still land while
    // this tab is mounted (iOS `ensureMigrated` on contactsVersion).
    LaunchedEffect(listInvalidations) {
        listInvalidations.collect {
            state.mapFilter = MapFilterPreferences.applyMainMap(preferences)
            state.contactReloadKey++
        }
    }
}

/** Reads the located nodes, turning a store failure into a value while letting cancellation propagate. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun loadContacts(service: ContactService, radio: Uuid): Result<List<ContactDto>> = try {
    Result.success(service.getContacts(radio))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
