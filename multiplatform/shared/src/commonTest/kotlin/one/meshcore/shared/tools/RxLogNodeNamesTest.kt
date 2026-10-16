package one.meshcore.shared.tools

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RxLogNodeNamesTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun distinctFirstByteMapsAtOneBytePrefix() {
        val map = buildRxLogNodeNames(
            listOf(
                contact("Alice", byteArrayOf(0x0A, 0x11)),
                contact("Bob", byteArrayOf(0x0B, 0x22)),
            ),
        )
        assertEquals("Alice", map["0A"])
        assertEquals("Bob", map["0B"])
    }

    @Test
    fun sharedFirstByteFallsToUniqueLongerPrefix() {
        val map = buildRxLogNodeNames(
            listOf(
                contact("Alice", byteArrayOf(0x01, 0x02, 0x03)),
                contact("Bob", byteArrayOf(0x01, 0x99.toByte(), 0x03)),
            ),
        )
        // 1-byte prefix "01" is ambiguous, so it is excluded (resolveHashLabel then falls back to hex).
        assertFalse(map.containsKey("01"))
        assertEquals("Alice", map["0102"])
        assertEquals("Bob", map["0199"])
    }

    private fun contact(name: String, publicKey: ByteArray): ContactDto = ContactDto(
        id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
        radioID = radio,
        publicKey = publicKey,
        name = name,
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = 0xFF,
        outPath = ByteArray(0),
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
