package one.meshcore.shared.map

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.hasLocation
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import kotlin.math.abs

/**
 * Builds [MapPoint] pins from contact rows that carry a valid coordinate. Pure helper for the Map
 * tab until MapLibre mounts; same pin-style mapping the full map host will use.
 */
object MapNodesBuilder {
    private const val ZERO_EPSILON = 1e-9

    fun pointsFromContacts(contacts: List<ContactDto>): List<MapPoint> = contacts.mapNotNull { contact ->
        val coordinate = LatLng(contact.latitude, contact.longitude)
        if (!coordinate.isValid) return@mapNotNull null
        if (abs(coordinate.latitude) < ZERO_EPSILON && abs(coordinate.longitude) < ZERO_EPSILON) {
            return@mapNotNull null
        }
        MapPoint(
            id = contact.id.value,
            coordinate = coordinate,
            pinStyle = pinStyleFor(contact),
            label = contact.nickname?.takeIf { it.isNotEmpty() } ?: contact.name,
            isClusterable = true,
        )
    }

    /**
     * Builds pins for passively-discovered nodes that carry a valid coordinate and are not already a
     * contact (matched by public key). Mirrors iOS `MapViewModel.loadMapData(includeDiscovered:)`, which
     * keeps only `coordinate.isValidFix && !contactKeys.contains(publicKey)`. Discovered pins share the
     * contact type-based [PinStyle]; the callout, not the sprite, marks them as discovered.
     */
    fun pointsFromDiscovered(nodes: List<DiscoveredNodeRecord>, existingContactKeyHexes: Set<String>): List<MapPoint> {
        return nodes.mapNotNull { node ->
            if (!node.hasLocation) return@mapNotNull null
            if (node.publicKey.toUppercaseHex() in existingContactKeyHexes) return@mapNotNull null
            MapPoint(
                id = node.id.value,
                coordinate = LatLng(node.latitude, node.longitude),
                pinStyle = pinStyleFor(node.contactType),
                label = node.name,
                isClusterable = true,
            )
        }
    }

    private fun pinStyleFor(contact: ContactDto): PinStyle = pinStyleFor(ContactType.fromRaw(contact.typeRawValue))

    private fun pinStyleFor(type: ContactType?): PinStyle = when (type) {
        ContactType.REPEATER -> PinStyle.CONTACT_REPEATER
        ContactType.ROOM -> PinStyle.CONTACT_ROOM
        else -> PinStyle.CONTACT_CHAT
    }
}
