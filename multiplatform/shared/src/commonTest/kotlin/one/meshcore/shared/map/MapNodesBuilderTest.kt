package one.meshcore.shared.map

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MapNodesBuilderTest {
    @Test
    fun skipsZeroAndInvalidCoordinates() {
        val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val points =
            MapNodesBuilder.pointsFromContacts(
                listOf(
                    sample(radio, "1", 0.0, 0.0),
                    sample(radio, "2", 91.0, 0.0),
                    sample(radio, "3", 37.77, -122.42),
                ),
            )
        assertEquals(1, points.size)
        assertEquals("C0000000-0000-0000-0000-000000000003", points.first().id)
        assertTrue(points.first().coordinate.latitude in 37.0..38.0)
    }

    private fun sample(radio: Uuid, idSuffix: String, lat: Double, lon: Double): ContactDto = ContactDto(
        id = Uuid.normalized("C0000000-0000-0000-0000-00000000000$idSuffix"),
        radioID = radio,
        publicKey = ByteArray(32) { idSuffix.first().code.toByte() },
        name = "N$idSuffix",
        typeRawValue = 1,
        flags = 0,
        outPathLength = 255,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = lat,
        longitude = lon,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}
