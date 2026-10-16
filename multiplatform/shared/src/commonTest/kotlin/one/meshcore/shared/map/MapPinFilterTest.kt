package one.meshcore.shared.map

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

/** Main-map pin algebra: favorites, type gates, discovered on/off. */
class MapPinFilterTest {
    @Test
    fun favoritesKeepsOnlyFavoritedContactsAndDropsDiscovered() {
        val filter = MapFilterState(favoritesOnly = true, showDiscovered = true)
        val visible = MapPinFilter.contacts(listOf(chat, favoriteRepeater), filter, now = 1_000L)
        assertEquals(listOf("Fav"), visible.map { it.name })
        assertEquals(emptyList(), MapPinFilter.discovered(listOf(discoveredChat), filter, now = 1_000L))
    }

    @Test
    fun typeGatesDropHiddenContactKinds() {
        val filter = MapFilterState(showChat = false, showRepeater = true, showRoom = true)
        val visible = MapPinFilter.contacts(listOf(chat, favoriteRepeater, room), filter, now = 1_000L)
        assertEquals(listOf("Fav", "Room"), visible.map { it.name })
    }

    @Test
    fun discoveredHonorsEffectiveShowDiscoveredAndTypeGates() {
        val hidden = MapFilterState(showDiscovered = false)
        assertEquals(emptyList(), MapPinFilter.discovered(listOf(discoveredChat), hidden, now = 1_000L))
        val types = MapFilterState(showDiscovered = true, showChat = false, showRepeater = true, showRoom = true)
        assertEquals(emptyList(), MapPinFilter.discovered(listOf(discoveredChat), types, now = 1_000L))
        val shown = MapFilterState(showDiscovered = true)
        assertEquals(
            listOf("Heard"),
            MapPinFilter.discovered(listOf(discoveredChat), shown, now = 1_000L).map {
                it.name
            },
        )
    }

    private companion object {
        val RADIO: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val chat = contact("Chat", ContactType.CHAT, favorite = false, idSuffix = "1")
        val favoriteRepeater = contact("Fav", ContactType.REPEATER, favorite = true, idSuffix = "2")
        val room = contact("Room", ContactType.ROOM, favorite = false, idSuffix = "3")
        val discoveredChat = DiscoveredNodeRecord(
            id = Uuid.normalized("D0000000-0000-0000-0000-000000000001"),
            radioID = RADIO,
            publicKey = ByteArray(32) { 0x11 },
            name = "Heard",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 1,
            lastAdvertTimestamp = 1,
            latitude = 1.0,
            longitude = 2.0,
        )

        fun contact(name: String, type: ContactType, favorite: Boolean, idSuffix: String): ContactDto = ContactDto(
            id = Uuid.normalized("C0000000-0000-0000-0000-00000000000$idSuffix"),
            radioID = RADIO,
            publicKey = ByteArray(32) { name.first().code.toByte() },
            name = name,
            typeRawValue = type.raw,
            flags = 0,
            outPathLength = 0,
            outPath = ByteArray(0),
            lastAdvertTimestamp = 0,
            latitude = 1.0,
            longitude = 2.0,
            lastModified = 0,
            isBlocked = false,
            isMuted = false,
            isFavorite = favorite,
            unreadCount = 0,
        )
    }
}
