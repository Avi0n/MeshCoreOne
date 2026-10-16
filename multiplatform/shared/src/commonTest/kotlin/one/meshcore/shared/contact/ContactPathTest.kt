package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactPathTest {
    @Test
    fun floodRoutedHasEmptyPathHops() {
        val contact = sampleContact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL, outPath = byteArrayOf(0xA1.toByte()))
        assertTrue(contact.isFloodRouted)
        assertEquals(0, contact.pathHopCount)
        assertTrue(contact.pathHops.isEmpty())
        assertEquals("", contact.pathString)
    }

    @Test
    fun directZeroHopPath() {
        val contact = sampleContact(outPathLength = PathEncoding.encodePathLen(1, 0), outPath = ByteArray(0))
        assertEquals(0, contact.pathHopCount)
        assertTrue(contact.pathHops.isEmpty())
    }

    @Test
    fun multiHopPathString() {
        val hops = byteArrayOf(0x1A, 0x2B)
        val contact = sampleContact(
            outPathLength = PathEncoding.encodePathLen(1, 2),
            outPath = hops,
        )
        assertEquals(2, contact.pathHopCount)
        assertEquals(listOf("1A", "2B"), contact.pathHops.map { it.hex })
        assertEquals("1A → 2B", contact.pathString)
    }

    @Test
    fun idPrefixWidensWithTheRadiosPathHashSize() {
        val contact = sampleContact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL, outPath = ByteArray(0))
            .copy(publicKey = byteArrayOf(0xA3.toByte(), 0x7F, 0x42, 0x11))

        assertEquals("A3", contact.idPrefixHex(1))
        assertEquals("A37F", contact.idPrefixHex(2))
        assertEquals("A37F42", contact.idPrefixHex(3))
    }

    @Test
    fun resolvedPathStringNamesEveryHopItCanAndFallsBackToHex() {
        val contact = sampleContact(
            outPathLength = PathEncoding.encodePathLen(1, 3),
            outPath = byteArrayOf(0x1A, 0x2B, 0x3C),
        )

        val resolved = contact.pathString { hash ->
            when (hash.single()) {
                0x1A.toByte() -> "Hilltop"
                0x3C.toByte() -> "Ridge"
                else -> null
            }
        }

        assertEquals("Hilltop → 2B → Ridge", resolved)
    }

    @Test
    fun resolvedPathStringIsEmptyForAFloodRoutedContact() {
        val contact = sampleContact(outPathLength = CONTACT_FLOOD_PATH_SENTINEL, outPath = byteArrayOf(0x1A))

        assertEquals("", contact.pathString { "Hilltop" })
    }

    private fun sampleContact(outPathLength: Int, outPath: ByteArray): ContactDto = ContactDto(
        id = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(32) { 1 },
        name = "Node",
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = outPathLength,
        outPath = outPath,
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}
