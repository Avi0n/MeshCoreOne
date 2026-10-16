package one.meshcore.shared.map.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.addOrUpdateContact
import one.meshcore.shared.contact.toContactFrame
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapCameraStore
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.bestAvailableLocation
import one.meshcore.shared.map.centerAllRegion
import one.meshcore.shared.map.mapAddDiscoveredWriteService
import one.meshcore.shared.map.runDiscoveredNodeAdd
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.runExceptionFallback

internal fun CoroutineScope.persistMapCamera(preferences: PreferencesStore, region: MapCameraRegion) {
    launch {
        preferences.putString(PersistenceKeys.MAP_CAMERA_REGION, MapCameraStore.encode(region))
    }
}

/** Frames a known fix and remembers the camera. Locate uses this; focus and center-all do not. */
internal fun MapScreenState.frameOn(center: LatLng, scope: CoroutineScope, preferences: PreferencesStore) {
    val region = focusCameraRegion(center)
    cameraTarget = cameraTarget.requesting(region)
    viewportRegion = region
    isCenteredOnUser = true
    scope.persistMapCamera(preferences, region)
}

internal fun MapScreenState.locateOnUser(
    scope: CoroutineScope,
    preferences: PreferencesStore,
    inputs: MapLocateInputs,
): () -> Unit = locate@{
    // iOS `centerOnUserLocation` frames the last-known phone or radio position at once
    // and only requests a fresh fix when both are missing.
    val known = bestAvailableLocation(inputs.userLocation(), inputs.nodeLocation())
    if (known != null) {
        frameOn(known, scope, preferences)
        return@locate
    }
    val service = inputs.service ?: return@locate
    scope.launch {
        val fix = runExceptionFallback({ null }) {
            service.requestCurrentLocation()
        }
        val center = bestAvailableLocation(fix, inputs.nodeLocation()) ?: return@launch
        frameOn(center, scope, preferences)
    }
}

internal fun MapScreenState.addDiscovered(
    scope: CoroutineScope,
    inputs: MapDiscoveredAddInputs,
): (DiscoveredNodeRecord) -> Unit = add@{ node ->
    val service = mapAddDiscoveredWriteService(inputs.contactService(), inputs.isRadioConnected())
    if (addingDiscoveredId != null) return@add
    if (service == null) {
        addError = AddContactError.NotConnected
        return@add
    }
    addingDiscoveredId = node.id
    scope.launch {
        try {
            runDiscoveredNodeAdd(
                maxContacts = inputs.maxContacts,
                errorText = inputs.contactErrorText,
                onError = { addError = it },
            ) {
                service.addOrUpdateContact(node.radioID, node.toContactFrame(inputs.nowEpochSeconds()))
                selectedDiscovered = null
                discoveredDetail = null
                contactReloadKey++
            }
        } finally {
            addingDiscoveredId = null
        }
    }
}

internal fun MapScreenState.onMapPointClick(
    id: String,
    visibleContactsById: Map<String, ContactDto>,
    discoveredById: Map<String, DiscoveredNodeRecord>,
) {
    when {
        id == FOCUSED_PIN_ID -> {
            selectedContact = null
            contactDetail = null
            selectedDiscovered = null
            showDroppedPinCallout = true
        }
        visibleContactsById[id] != null -> {
            showDroppedPinCallout = false
            selectedDiscovered = null
            contactDetail = null
            selectedContact = visibleContactsById[id]
        }
        discoveredById[id] != null -> {
            showDroppedPinCallout = false
            selectedContact = null
            contactDetail = null
            selectedDiscovered = discoveredById[id]
        }
    }
}

/** A tap on empty map dismisses whatever callout is open, as iOS `onMapTap` does. */
internal fun MapScreenState.dismissMapCallouts() {
    selectedContact = null
    contactDetail = null
    selectedDiscovered = null
    discoveredDetail = null
    showDroppedPinCallout = false
}

internal fun MapScreenState.dropMapPin(coordinate: LatLng, onSetDroppedMapPin: (Double, Double) -> Unit) {
    onSetDroppedMapPin(coordinate.latitude, coordinate.longitude)
    selectedContact = null
    contactDetail = null
    showDroppedPinCallout = true
}

internal fun MapScreenState.onMapStyleSelected(
    style: MapStyleSelection,
    mapDisplayPreferences: MapDisplayPreferencesStore?,
    scope: CoroutineScope,
    preferences: PreferencesStore,
) {
    mapStyle = style
    mapDisplayPreferences?.setMapStyle(style)
    if (mapDisplayPreferences == null) {
        scope.launch {
            preferences.putString(PersistenceKeys.MAP_STYLE_SELECTION, style.rawValue)
        }
    }
}

internal fun toggleMapDisplay(preferences: MapDisplayPreferencesStore?, toggle: MapDisplayToggle, enabled: Boolean) {
    val store = preferences ?: return
    when (toggle) {
        MapDisplayToggle.LABELS -> store.setMapShowLabels(enabled)
        MapDisplayToggle.CLUSTERING -> store.setMapClusteringEnabled(enabled)
        MapDisplayToggle.NORTH_LOCK -> store.setMapNorthLocked(enabled)
    }
}

internal fun MapScreenState.centerAll(
    contactPoints: List<MapPoint>,
    discoveredPoints: List<MapPoint>,
    scope: CoroutineScope,
    preferences: PreferencesStore,
) {
    val region = centerAllRegion(contactPoints, discoveredPoints)
    if (region != null) {
        cameraTarget = cameraTarget.requesting(region)
        viewportRegion = region
        scope.persistMapCamera(preferences, region)
    }
}
