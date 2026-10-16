package one.meshcore.shared.map

import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapNodesBuilderDiscoveredTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun keepsOnlyLocatedNonContactDiscoveredNodes() {
        val located = discovered("1", ContactType.CHAT.raw, lat = 37.77, lon = -122.42)
        val noLocation = discovered("2", ContactType.REPEATER.raw, lat = 0.0, lon = 0.0)
        val alreadyContact = discovered("3", ContactType.ROOM.raw, lat = 40.0, lon = -74.0)

        val points = MapNodesBuilder.pointsFromDiscovered(
            listOf(located, noLocation, alreadyContact),
            existingContactKeyHexes = setOf(alreadyContact.publicKey.toUppercaseHex()),
        )

        assertEquals(1, points.size)
        assertEquals(located.id.value, points.first().id)
        assertEquals(PinStyle.CONTACT_CHAT, points.first().pinStyle)
        assertTrue(points.first().coordinate.latitude in 37.0..38.0)
    }

    @Test
    fun mapsPinStyleByNodeType() {
        val chat = discovered("1", ContactType.CHAT.raw, lat = 1.0, lon = 1.0)
        val repeater = discovered("2", ContactType.REPEATER.raw, lat = 2.0, lon = 2.0)
        val room = discovered("3", ContactType.ROOM.raw, lat = 3.0, lon = 3.0)

        val styles = MapNodesBuilder.pointsFromDiscovered(listOf(chat, repeater, room), emptySet())
            .associate { it.id to it.pinStyle }

        assertEquals(PinStyle.CONTACT_CHAT, styles[chat.id.value])
        assertEquals(PinStyle.CONTACT_REPEATER, styles[repeater.id.value])
        assertEquals(PinStyle.CONTACT_ROOM, styles[room.id.value])
    }

    private fun discovered(idSuffix: String, typeRaw: Int, lat: Double, lon: Double): DiscoveredNodeRecord =
        DiscoveredNodeRecord(
            id = Uuid.normalized("D0000000-0000-0000-0000-00000000000$idSuffix"),
            radioID = radio,
            publicKey = ByteArray(32) { idSuffix.first().code.toByte() },
            name = "Node$idSuffix",
            typeRawValue = typeRaw,
            lastHeardEpochSeconds = 0,
            latitude = lat,
            longitude = lon,
        )
}
