package one.meshcore.shared.ui

import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.map.ui.MapScreenInputs
import one.meshcore.shared.navigation.clearDroppedMapPin
import one.meshcore.shared.navigation.clearPendingMapFocus
import one.meshcore.shared.navigation.navigateToChat
import one.meshcore.shared.navigation.setDroppedMapPin
import one.meshcore.shared.remotenode.NodeAuthPurpose

/** Maps the shell graph onto the map tab. */
internal fun mapScreenInputs(
    deps: ShellDependencies,
    connectionState: DeviceConnectionState,
    networkOffline: Boolean,
    onSheet: (ShellSheet) -> Unit,
): MapScreenInputs = MapScreenInputs(
    contactService = deps.contactService,
    radioId = deps.currentRadioId,
    nodeLocation = { shellNodeFix(deps.locationSettingsSnapshot()) },
    preferences = deps.pathEditPreferences,
    pendingMapFocus = deps.coordinator.pendingMapFocus,
    onClearMapFocus = { deps.coordinator.clearPendingMapFocus() },
    droppedMapPin = deps.coordinator.droppedMapPin,
    onSetDroppedMapPin = { lat, lon -> deps.coordinator.setDroppedMapPin(lat, lon) },
    onClearDroppedMapPin = { deps.coordinator.clearDroppedMapPin() },
    onMessageContact = { contact ->
        deps.coordinator.navigateToChat(contact.id.value)
    },
    onShowTelemetry = { contact -> contactTelemetrySheet(contact)?.let(onSheet) },
    onManagement = { contact ->
        contactNodeAuthSheet(contact, NodeAuthPurpose.MANAGE)?.let(onSheet)
    },
    onJoinRoom = { contact ->
        contactNodeAuthSheet(contact, NodeAuthPurpose.JOIN)?.let(onSheet)
    },
    onZeroHopPing = deps.zeroHopPing,
    selfPublicKey = deps.selfPublicKey,
    isRadioReady = connectionState == DeviceConnectionState.READY,
    discoveredNodeStore = deps.discoveredNodeStore,
    mapDisplayPreferences = deps.mapDisplayPreferences,
    locationService = deps.locationService,
    maxContacts = deps.maxContacts,
    offlineMapPackRegistry = deps.offlineMapPackRegistry,
    nowEpochSeconds = deps.nowSeconds,
    isOffline = { networkOffline },
    isRadioConnected = { connectionState.isConnected },
    hashSize = deps.deviceHashSize,
    copyToClipboard = deps.copyToClipboard,
    openExternalUrl = deps.openExternalUrl,
    listInvalidations = deps.contactListInvalidations,
)
