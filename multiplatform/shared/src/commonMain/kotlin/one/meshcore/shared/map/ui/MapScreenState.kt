package one.meshcore.shared.map.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapCameraTarget
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapLoadError
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.model.Uuid

/**
 * Map tab state remembered by [MapScreen]. Effects, the map canvas, and the callout sheets read the
 * same instance, so a reload or a pin tap updates whichever part of the tab is showing it.
 */
internal class MapScreenState {
    var contactsById by mutableStateOf<Map<String, ContactDto>>(emptyMap())
    var contactReloadKey by mutableStateOf(0)
    var userInitiatedContactReload by mutableStateOf(false)
    var hasCompletedInitialContactLoad by mutableStateOf(false)
    var contactsLoadChrome by mutableStateOf(false)
    var selectedDiscovered by mutableStateOf<DiscoveredNodeRecord?>(null)
    var discoveredDetail by mutableStateOf<DiscoveredNodeRecord?>(null)
    var addingDiscoveredId by mutableStateOf<Uuid?>(null)
    var addError by mutableStateOf<AddContactError?>(null)
    var loadError by mutableStateOf<MapLoadError?>(null)
    var mapFilter by mutableStateOf(MapFilterState())
    var showAdvancedFilters by mutableStateOf(false)
    var isStyleLoading by mutableStateOf(false)
    var isCenteredOnUser by mutableStateOf(false)
    var mapStyle by mutableStateOf(MapStyleSelection.STANDARD)
    var initialCamera by mutableStateOf<MapCameraRegion?>(null)

    // What the map is showing, the analogue of the iOS view model's cameraRegion: the restored camera
    // first, then every user move. Null until prefs load, which asks the style gate its layer-wide
    // question, as iOS does with no viewport bounds yet.
    var viewportRegion by mutableStateOf<MapCameraRegion?>(null)

    // Versioned, not cleared: the same region requested twice must reframe twice, so "Center all" after
    // panning back still works. Mirrors iOS cameraRegion + cameraRegionVersion.
    var cameraTarget by mutableStateOf(MapCameraTarget())
    var selectedContact by mutableStateOf<ContactDto?>(null)
    var contactDetail by mutableStateOf<ContactDto?>(null)
    var showDroppedPinCallout by mutableStateOf(false)
    var prefsLoaded by mutableStateOf(false)
}

/** Phone fix, radio fix, and the location service the locate control reads at tap time. */
internal class MapLocateInputs(
    val service: LocationService?,
    val userLocation: () -> LatLng?,
    val nodeLocation: () -> LatLng?,
)

/** Live radio and copy the discovered-pin add path needs when the tap runs. */
internal class MapDiscoveredAddInputs(
    val contactService: () -> ContactService?,
    val isRadioConnected: () -> Boolean,
    val nowEpochSeconds: () -> Long,
    val maxContacts: () -> Int?,
    val contactErrorText: (Throwable) -> String,
)

/** Map-options toggles that share one preferences-store write. */
internal enum class MapDisplayToggle {
    LABELS,
    CLUSTERING,
    NORTH_LOCK,
}
