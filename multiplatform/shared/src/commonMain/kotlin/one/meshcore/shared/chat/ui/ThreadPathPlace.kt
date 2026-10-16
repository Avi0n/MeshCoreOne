package one.meshcore.shared.chat.ui

import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LatLng

/** Locations and labels used to name the sender, hops, and receiver on a path-detail load. */
internal data class ThreadPathPlace(
    val userLocation: UserLocation?,
    val nodeCoordinate: LatLng?,
    val currentLocation: LatLng?,
    val localNodeName: String?,
    val youLabel: String,
    val unknownHopLabel: String,
)
