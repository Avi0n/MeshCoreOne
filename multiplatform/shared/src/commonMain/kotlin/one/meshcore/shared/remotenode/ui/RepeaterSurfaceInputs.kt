package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.DiscoveredNodeStore
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LocationService
import one.meshcore.shared.map.MapDisplayPreferencesStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.theme.AppColorSchemePreference

/**
 * Contact names, location, and map appearance shared by repeater settings and telemetry.
 * Session identity and admin services stay with the screen that owns them.
 */
internal data class RepeaterSurfaceInputs(
    val contactService: () -> ContactService? = { null },
    val radioId: () -> Uuid? = { null },
    val discoveredNodeStore: () -> DiscoveredNodeStore? = { null },
    /** The responder's position, which breaks a key-prefix collision by proximity. */
    val userLocation: () -> UserLocation? = { null },
    val locationService: LocationService? = null,
    val isOffline: Boolean = false,
    /** Basemap appearance for the neighbours and location-history maps, from Settings → Maps. */
    val mapColorSchemePreference: AppColorSchemePreference = AppColorSchemePreference.SYSTEM,
    val isRadioReady: Boolean = true,
    /** Neighbor-SNR map filter JSON; null keeps the host seed and does not persist. */
    val preferences: PreferencesStore? = null,
    val mapDisplayPreferences: MapDisplayPreferencesStore? = null,
    /** Radio hop hash size; clamped to 2–3 bytes for neighbour identity hex. */
    val deviceHashSize: () -> Int? = { null },
)
