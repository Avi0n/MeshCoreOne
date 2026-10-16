package one.meshcore.shared.map.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactServiceEvent
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.map.CoordinateBounds
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.MapPinFilter
import one.meshcore.shared.map.MapPoint
import one.meshcore.shared.map.MapStyleDisabledReason
import one.meshcore.shared.map.MapStyleGate
import one.meshcore.shared.map.MapStyleSelection
import one.meshcore.shared.map.OfflineMapPackRegistry
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.map.validatedBounds
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MapFocusRequest

/** Span for deep-link / dropped-pin framing (~1 km). Mirrors iOS MapViewModel.focusSpan. */
internal const val FOCUS_SPAN_DEG = 0.01

/** Stable id so a focus pin survives contact refresh without churn. Mirrors iOS focusedPinID. */
internal const val FOCUSED_PIN_ID = "map-focused-pin"

internal fun focusCameraRegion(center: LatLng): MapCameraRegion = MapCameraRegion(
    center = center,
    spanLatDeg = FOCUS_SPAN_DEG,
    spanLonDeg = FOCUS_SPAN_DEG,
)

internal fun focusedMapPin(request: MapFocusRequest?): MapPoint? {
    val focus = request ?: return null
    return MapPoint(
        id = FOCUSED_PIN_ID,
        coordinate = LatLng(focus.latitude, focus.longitude),
        pinStyle = PinStyle.DROPPED_PIN,
        label = null,
        isClusterable = false,
    )
}

/**
 * Located discovered rows for this radio that are not already contacts. Pins and the id lookup both
 * derive from this list so a tap cannot resolve to a stale row.
 */
internal fun discoveredRowsForRadio(
    snapshot: List<DiscoveredNodeRecord>,
    activeRadio: Uuid?,
    contacts: Collection<ContactDto>,
    mapFilter: MapFilterState,
    now: Long,
): List<DiscoveredNodeRecord> {
    if (!mapFilter.effectiveShowDiscovered || activeRadio == null) return emptyList()
    val contactKeyHexes = contacts.map { it.publicKey.toUppercaseHex() }.toSet()
    val located = snapshot.filter { node ->
        node.radioID == activeRadio &&
            node.hasLocation &&
            node.publicKey.toUppercaseHex() !in contactKeyHexes
    }
    return MapPinFilter.discovered(located, mapFilter, now)
}

/** Inbound routes remain available for contacts even when the discovered layer is hidden. */
internal fun mapInboundHops(snapshot: List<DiscoveredNodeRecord>, radio: Uuid?): Map<String, Int> =
    snapshot.filter { it.radioID == radio }.mapNotNull { node ->
        node.inboundHopCount?.let { node.publicKey.toUppercaseHex() to it }
    }.toMap()

internal fun viewportBoundsOrNull(region: MapCameraRegion?): CoordinateBounds? = region?.validatedBounds()

internal fun mapStyleDisabledReasons(
    isNetworkAvailable: Boolean,
    registry: OfflineMapPackRegistry?,
    viewportBounds: CoordinateBounds?,
): Map<MapStyleSelection, MapStyleDisabledReason?> = MapStyleSelection.entries.associateWith { style ->
    MapStyleGate.disabledReason(
        style = style,
        isNetworkAvailable = isNetworkAvailable,
        hasOfflineCoverage = registry?.hasCompletedPack(style.offlineMapLayer, viewportBounds) ?: false,
    )
}

internal fun mapStyleDisabledHint(
    reason: MapStyleDisabledReason?,
    requiresNetwork: String,
    noOfflineCoverage: String,
): String? = when (reason) {
    MapStyleDisabledReason.REQUIRES_NETWORK -> requiresNetwork
    MapStyleDisabledReason.NO_OFFLINE_COVERAGE -> noOfflineCoverage
    null -> null
}

internal fun mapContactIsVContact(contact: ContactDto, selfKey: ByteArray?): Boolean =
    selfKey != null && VContactIdentity.isVContact(contact.publicKey, selfKey)

/** Advert for this radio, or any node deletion, is the contact-pin reload signal. */
internal fun contactEventReloadsMap(event: ContactServiceEvent, radio: Uuid): Boolean = when (event) {
    is ContactServiceEvent.ContactAdvertisementApplied -> event.radioID == radio
    ContactServiceEvent.NodeDeleted -> true
    else -> false
}
