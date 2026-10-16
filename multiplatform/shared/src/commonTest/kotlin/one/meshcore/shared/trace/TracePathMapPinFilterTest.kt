package one.meshcore.shared.trace

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapFilterHost
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.pathedit.DiscoveredNode
import one.meshcore.shared.pathedit.PathHop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TracePathMapPinFilterTest {
    @Test
    fun defaultSeedTurnsDiscoveredOn() {
        assertTrue(MapFilterState.seed(MapFilterHost.TRACE_PATH).showDiscovered)
        assertTrue(MapFilterState.seed(MapFilterHost.TRACE_PATH).effectiveShowDiscovered)
    }

    @Test
    fun favoritesHidesNonFavoriteCandidatesButKeepsPathMembers() {
        val favorite = contact(seed = 0x11, isFavorite = true)
        val pathMember = contact(seed = 0x12, isFavorite = false)
        val other = contact(seed = 0x13, isFavorite = false)
        val filter = MapFilterState.seed(MapFilterHost.TRACE_PATH).setFavoritesOnly(true)

        val visible = TracePathMapPinFilter.visibleContacts(
            candidates = listOf(favorite, pathMember, other),
            pathMemberIds = setOf(pathMember.id),
            filter = filter,
        )
        assertEquals(setOf(favorite.id, pathMember.id), visible.map { it.id }.toSet())
    }

    @Test
    fun discoveredOnAddsLocatedRepeaterPinsDedupedAgainstContacts() {
        val contactKey = ByteArray(32) { 0x44 }
        val contact = contact(seed = 0x44, publicKey = contactKey)
        val discoveredDup = discovered(seed = 0x44, publicKey = contactKey)
        val discoveredNew = discovered(seed = 0x55)
        val chatDiscovered = discovered(seed = 0x66, isRepeater = false)
        val filter = MapFilterState.seed(MapFilterHost.TRACE_PATH)

        val visible = TracePathMapPinFilter.visibleDiscovered(
            discovered = listOf(discoveredDup, discoveredNew, chatDiscovered),
            contactKeys = setOf(contact.publicKey.toUppercaseHex()),
            pathMemberIds = emptySet(),
            filter = filter,
        )
        assertEquals(setOf(discoveredNew.id), visible.map { it.id }.toSet())
    }

    @Test
    fun discoveredOffHidesCandidatesButKeepsPathMembers() {
        val pathMember = discovered(seed = 0x21)
        val other = discovered(seed = 0x77)
        val filter = MapFilterState.seed(MapFilterHost.TRACE_PATH).setShowDiscovered(false)

        val visible = TracePathMapPinFilter.visibleDiscovered(
            discovered = listOf(pathMember, other),
            contactKeys = emptySet(),
            pathMemberIds = setOf(pathMember.id),
            filter = filter,
        )
        assertEquals(listOf(pathMember.id), visible.map { it.id })
    }

    @Test
    fun favoritesKeepsDiscoveredPathMembersOnly() {
        val filter = MapFilterState(showDiscovered = true).setFavoritesOnly(true)
        assertTrue(filter.showDiscovered)
        assertFalse(filter.effectiveShowDiscovered)
        val pathMember = discovered(seed = 0x31)
        val other = discovered(seed = 0x88)

        val visible = TracePathMapPinFilter.visibleDiscovered(
            discovered = listOf(pathMember, other),
            contactKeys = emptySet(),
            pathMemberIds = setOf(pathMember.id),
            filter = filter,
        )
        assertEquals(listOf(pathMember.id), visible.map { it.id })
    }

    @Test
    fun invalidFixDiscoveredIsNotPlotted() {
        val bad = discovered(seed = 0x41, latitude = 999.0, longitude = -122.0)
        val visible = TracePathMapPinFilter.visibleDiscovered(
            discovered = listOf(bad),
            contactKeys = emptySet(),
            pathMemberIds = emptySet(),
            filter = MapFilterState.seed(MapFilterHost.TRACE_PATH),
        )
        assertTrue(visible.isEmpty())
    }

    @Test
    fun pathMemberIdsMatchExactPublicKeys() {
        val contact = contact(seed = 0x51)
        val node = discovered(seed = 0x52)
        val hops = listOf(
            PathHop(
                hashBytes = contact.publicKey.copyOf(1),
                publicKey = contact.publicKey,
                resolvedName = contact.name,
            ),
            PathHop(hashBytes = node.publicKey.copyOf(1), publicKey = node.publicKey, resolvedName = node.name),
        )
        val ids = TracePathMapPinFilter.pathMemberIds(hops, listOf(contact), listOf(node))
        assertEquals(setOf(contact.id, node.id), ids)
    }

    private fun contact(
        seed: Int,
        isFavorite: Boolean = false,
        publicKey: ByteArray = ByteArray(32) { seed.toByte() },
    ): ContactDto = ContactDto(
        id = Uuid.normalized("A0000000-0000-0000-0000-0000000000${byteArrayOf(seed.toByte()).toLowercaseHex()}"),
        radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
        publicKey = publicKey,
        name = "Node",
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 37.0,
        longitude = -122.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = isFavorite,
        unreadCount = 0,
    )

    private fun discovered(
        seed: Int,
        publicKey: ByteArray = ByteArray(32) { seed.toByte() },
        isRepeater: Boolean = true,
        latitude: Double = 38.0,
        longitude: Double = -122.0,
    ): DiscoveredNode = DiscoveredNode(
        id = Uuid.normalized("C0000000-0000-0000-0000-0000000000${byteArrayOf(seed.toByte()).toLowercaseHex()}"),
        publicKey = publicKey,
        name = "Discovered",
        isRepeater = isRepeater,
        location = LatLng(latitude, longitude),
    )
}
