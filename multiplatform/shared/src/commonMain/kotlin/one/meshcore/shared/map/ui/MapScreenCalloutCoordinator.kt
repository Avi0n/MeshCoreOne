package one.meshcore.shared.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.contact.DEFAULT_HASH_SIZE
import one.meshcore.shared.contact.mapsGeoUri
import one.meshcore.shared.map.formatCoordinatePair

@Composable
internal fun MapScreenCallouts(
    state: MapScreenState,
    scope: CoroutineScope,
    inputs: MapScreenInputs,
    contactErrorText: (Throwable) -> String,
    addInputs: MapDiscoveredAddInputs,
) {
    val droppedMapPin = inputs.droppedMapPin
    val hashSize = inputs.hashSize
    val copyToClipboard = inputs.copyToClipboard
    val openExternalUrl = inputs.openExternalUrl
    val onClearDroppedMapPin = inputs.onClearDroppedMapPin
    val onMessageContact = inputs.onMessageContact
    val onShowTelemetry = inputs.onShowTelemetry
    val onManagement = inputs.onManagement
    val onJoinRoom = inputs.onJoinRoom
    val onZeroHopPing = inputs.onZeroHopPing
    val selfPublicKey = inputs.selfPublicKey
    val nowEpochSeconds = inputs.nowEpochSeconds
    val isRadioReady = inputs.isRadioReady
    val contactService = inputs.contactService
    val calloutHashSize = hashSize() ?: DEFAULT_HASH_SIZE
    val addDiscovered = state.addDiscovered(scope, addInputs)
    val callout = state.selectedContact
    if (callout != null) {
        ContactCalloutSheet(
            contact = callout,
            hashSize = calloutHashSize,
            onDismiss = { state.selectedContact = null },
            onDetails = {
                state.selectedContact = null
                state.contactDetail = callout
            },
            onMessage = {
                state.selectedContact = null
                onMessageContact(callout)
            },
        )
    }
    val droppedPinRequest by droppedMapPin.collectAsStateWithLifecycle()
    val pin = focusedMapPin(droppedPinRequest)
    if (state.showDroppedPinCallout && pin != null) {
        DroppedPinCalloutSheet(
            coordinate = pin.coordinate,
            onDismiss = { state.showDroppedPinCallout = false },
            onCopy = {
                copyToClipboard(formatCoordinatePair(pin.coordinate.latitude, pin.coordinate.longitude))
            },
            onOpenInMaps = {
                openExternalUrl(
                    mapsGeoUri(
                        latitude = pin.coordinate.latitude,
                        longitude = pin.coordinate.longitude,
                        label = null,
                    ),
                )
            },
            onClear = {
                onClearDroppedMapPin()
                state.showDroppedPinCallout = false
            },
        )
    }
    val discoveredCallout = state.selectedDiscovered
    if (discoveredCallout != null) {
        DiscoveredNodeCalloutSheet(
            node = discoveredCallout,
            isAdding = state.addingDiscoveredId == discoveredCallout.id,
            hashSize = calloutHashSize,
            onDismiss = { state.selectedDiscovered = null },
            onDetails = {
                state.selectedDiscovered = null
                state.discoveredDetail = discoveredCallout
            },
            onAdd = { addDiscovered(discoveredCallout) },
        )
    }
    val detail = state.discoveredDetail
    if (detail != null) {
        DiscoveredNodeDetailSheet(
            node = detail,
            isAdding = state.addingDiscoveredId == detail.id,
            nowEpochSeconds = nowEpochSeconds(),
            onDismiss = { state.discoveredDetail = null },
            onAdd = { addDiscovered(detail) },
        )
    }
    val savedDetail = state.contactDetail
    if (savedDetail != null) {
        MapContactDetailSheet(
            contact = savedDetail,
            nowEpochSeconds = nowEpochSeconds(),
            isRadioReady = isRadioReady,
            isVContact = mapContactIsVContact(savedDetail, selfPublicKey()),
            contactService = contactService,
            errorText = contactErrorText,
            onDismiss = { state.contactDetail = null },
            onMessage = {
                state.contactDetail = null
                onMessageContact(savedDetail)
            },
            onShowTelemetry = {
                state.contactDetail = null
                onShowTelemetry(savedDetail)
            },
            onManagement = {
                state.contactDetail = null
                onManagement(savedDetail)
            },
            onJoinRoom = {
                state.contactDetail = null
                onJoinRoom(savedDetail)
            },
            onZeroHopPing = onZeroHopPing,
        )
    }
}
