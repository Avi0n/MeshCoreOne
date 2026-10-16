package one.meshcore.shared.map.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.map.OfflineMapPackRegistry
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.MapFocusRequest
import one.meshcore.shared.prefs.PreferencesStore

/** Values the map tab reads. The shell builds one instance per composition. */
internal data class MapScreenInputs(
    val contactService: () -> ContactService?,
    val radioId: () -> Uuid?,
    val preferences: PreferencesStore,
    val pendingMapFocus: StateFlow<MapFocusRequest?>,
    val onClearMapFocus: () -> Unit,
    val droppedMapPin: StateFlow<MapFocusRequest?>,
    val onSetDroppedMapPin: (latitude: Double, longitude: Double) -> Unit,
    val onClearDroppedMapPin: () -> Unit,
    val onMessageContact: (ContactDto) -> Unit,
    val onShowTelemetry: (ContactDto) -> Unit = {},
    val onManagement: (ContactDto) -> Unit = {},
    val onJoinRoom: (ContactDto) -> Unit = {},
    val onZeroHopPing: (suspend (ContactDto) -> ContactPingResult)? = null,
    /** The connected radio's own public key, used to hide delete on the ZephCore V-contact. */
    val selfPublicKey: () -> ByteArray? = { null },
    /** Passively-discovered nodes, unioned onto the map when [mapDisplayPreferences] enables it. */
    val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
    /** Show-discovered toggle plus basemap appearance, and the on-map labels toggle; null uses defaults. */
    val mapDisplayPreferences: MapDisplayPreferencesStore? = null,
    /** App-wide location permission + one-shot fix; null hides the locate control and user dot. */
    val locationService: LocationService? = null,
    /** The connected radio's own coordinates, the locate fallback when the phone has no fix. */
    val nodeLocation: () -> LatLng? = { null },
    /** The device's contact-table cap, for the "node list full" copy; `null` when unknown. */
    val maxContacts: () -> Int? = { null },
    /** Completed offline packs, gating styles that need coverage while offline; null means no coverage. */
    val offlineMapPackRegistry: OfflineMapPackRegistry? = null,
    /** Epoch seconds for the "last advert" label and the added contact's lastModified stamp. */
    val nowEpochSeconds: () -> Long = { 0L },
    /** When true, show the offline badge (device has no usable network route). */
    val isOffline: () -> Boolean = { false },
    /**
     * Whether a radio session is live (Connected/Syncing/Ready). Offline [ContactService] still
     * exists for pin reloads; Add from a discovered pin must not run against that stand-in.
     */
    val isRadioConnected: () -> Boolean = { false },
    /**
     * Whether the radio is connected and synced. Gates map contact-detail actions the way iOS
     * `.radioDisabled(for:)` gates `ContactDetailSheet`.
     */
    val isRadioReady: Boolean = true,
    /** Connected radio key-prefix width for callout ids; null uses the protocol default. */
    val hashSize: () -> Int? = { null },
    /** Copy lat/lon text for the dropped-pin callout; no-op when unset. */
    val copyToClipboard: (String) -> Unit = {},
    /** Opens a `geo:` (or other) URI in the system handler; used for dropped-pin Open in Maps. */
    val openExternalUrl: (String) -> Unit = {},
    /**
     * App-side contact mutations (add, QR scan, Discover add, node-config import, rename, delete).
     * Without it a contact changed on another tab is missing from the map until the tab is re-entered.
     */
    val listInvalidations: Flow<Unit> = emptyFlow(),
)
