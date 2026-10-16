package one.meshcore.shared.map

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayedHopCount
import one.meshcore.shared.contact.recencyTimestamp
import one.meshcore.shared.model.toUppercaseHex

/**
 * Main-map pin algebra from iOS `MapViewModel.rebuildDisplayPins`. Favorites keeps only favorited
 * contacts and drops discovered. Otherwise type gates apply to both layers; discovered also requires
 * [MapFilterState.effectiveShowDiscovered].
 */
object MapPinFilter {
    fun contacts(
        contacts: List<ContactDto>,
        filter: MapFilterState,
        now: Long,
        inboundHopByKey: Map<String, Int> = emptyMap(),
    ): List<ContactDto> = contacts.filter {
        val allowedByWho = if (filter.favoritesOnly) it.isFavorite else filter.allowsContactType(it.contactType)
        allowedByWho &&
            filter.allowsAdvanced(
                heardAt = it.recencyTimestamp.takeIf { timestamp -> timestamp > 0L },
                hops = it.displayedHopCount(inboundHopByKey[it.publicKey.toUppercaseHex()]),
                now = now,
            )
    }

    fun discovered(nodes: List<DiscoveredNodeRecord>, filter: MapFilterState, now: Long): List<DiscoveredNodeRecord> {
        if (!filter.effectiveShowDiscovered) return emptyList()
        return nodes.filter {
            filter.allowsContactType(it.contactType) &&
                filter.allowsAdvanced(
                    heardAt = it.lastHeardEpochSeconds,
                    hops = it.displayedHopCount,
                    now = now,
                )
        }
    }
}
