package one.meshcore.shared.map

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class MapAdvancedPinFilterTest {
    @Test
    fun restoredDirectHopRangeHidesIndirectAndUnknownContacts() {
        val filter = assertNotNull(MapFilterState.fromStorageString("""{"hops":{"min":0,"max":0}}"""))
        val direct = contact("Direct", 0)
        val indirect = contact("Indirect", 2)
        val unknown = contact("Unknown", 255)
        assertEquals(listOf(direct), MapPinFilter.contacts(listOf(direct, indirect, unknown), filter, now = 1_000L))
    }

    @Test
    fun recencyUsesTheNewestContactStampAndStillNarrowsFavorites() {
        val recent = contact("Recent", 0).copy(lastModified = 800L, lastHeardTimestamp = 940L, isFavorite = true)
        val old = contact("Old", 0).copy(lastModified = 939L, isFavorite = true)
        val unknown = contact("Unknown", 0).copy(isFavorite = true)
        val unfavorited = recent.copy(isFavorite = false)
        val filter = MapFilterState(
            favoritesOnly = true,
            showChat = false,
            lastHeard = MapLastHeardRange.within(MapDuration(1, MapDurationUnit.MINUTES)),
        )
        assertEquals(listOf(recent), MapPinFilter.contacts(listOf(recent, old, unknown, unfavorited), filter, 1_000L))
        assertEquals(emptyList(), MapPinFilter.contacts(listOf(recent), filter, 1_001L))
    }

    @Test
    fun floodContactUsesInboundHopsEvenWithDiscoveredHidden() {
        val contact = contact("Flood", 255)
        val filter = MapFilterState(hops = MapHopRange.DIRECT)
        assertEquals(emptyList(), MapPinFilter.contacts(listOf(contact), filter, 1_000L))
        assertEquals(
            listOf(contact),
            MapPinFilter.contacts(
                listOf(contact),
                filter,
                1_000L,
                mapOf(contact.publicKey.toUppercaseHex() to 0),
            ),
        )
    }

    @Test
    fun discoveredIntersectsRecencyAndDisplayedInboundHopRanges() {
        val contact = contact("Heard", 255)
        val node = DiscoveredNodeRecord(
            id = contact.id,
            radioID = contact.radioID,
            publicKey = contact.publicKey,
            name = contact.name,
            typeRawValue = contact.typeRawValue,
            lastHeardEpochSeconds = 940L,
            inboundHopCount = 2,
        )
        val filter = MapFilterState(
            showDiscovered = true,
            lastHeard = MapLastHeardRange.within(MapDuration(1, MapDurationUnit.MINUTES)),
            hops = MapHopRange(max = 2),
        )
        assertEquals(listOf(node), MapPinFilter.discovered(listOf(node), filter, 1_000L))
        assertEquals(emptyList(), MapPinFilter.discovered(listOf(node), filter, 1_001L))
        assertEquals(emptyList(), MapPinFilter.discovered(listOf(node.copy(inboundHopCount = null)), filter, 1_000L))
        assertEquals(emptyList(), MapPinFilter.discovered(listOf(node), filter.copy(favoritesOnly = true), 1_000L))
    }

    private fun contact(name: String, pathLength: Int) = ContactDto(
        id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(32) { name.first().code.toByte() },
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        flags = 0,
        outPathLength = pathLength,
        outPath = ByteArray(pathLength.takeIf { it < 255 } ?: 0),
        lastAdvertTimestamp = 0,
        latitude = 1.0,
        longitude = 2.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}
