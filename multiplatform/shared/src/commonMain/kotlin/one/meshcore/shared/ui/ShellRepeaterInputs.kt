package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.remotenode.ui.RepeaterSurfaceInputs

/** Map, location, and radio-readiness values both repeater sheets pass through. */
@Composable
internal fun repeaterSurfaceInputs(deps: ShellDependencies): RepeaterSurfaceInputs {
    val connection = deps.connectionState.collectAsStateWithLifecycle().value
    return RepeaterSurfaceInputs(
        contactService = deps.contactService,
        radioId = deps.currentRadioId,
        discoveredNodeStore = deps.discoveredNodeStore,
        userLocation = { deps.bestAvailableUserLocation() },
        locationService = deps.locationService,
        isOffline = deps.networkOfflineNow(),
        mapColorSchemePreference = deps.resolvedMapColorScheme(),
        isRadioReady = connection == DeviceConnectionState.READY,
        preferences = deps.pathEditPreferences,
        mapDisplayPreferences = deps.mapDisplayPreferences,
        deviceHashSize = deps.deviceHashSize,
    )
}
