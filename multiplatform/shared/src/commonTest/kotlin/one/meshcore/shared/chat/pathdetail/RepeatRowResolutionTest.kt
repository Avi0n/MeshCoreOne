package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Heard-repeat last-hop naming, mirroring iOS `RepeatRowViewTests`. A unique short prefix is exact,
 * a colliding prefix is fallback (the "?" marker), and an unmatched hash is unresolved.
 */
class RepeatRowResolutionTest {
    @Test
    fun uniqueShortPrefixIsExact() {
        val result = repeatRowResolution(
            repeaterHash = byteArrayOf(PREFIX),
            contacts = listOf(repeater(PREFIX, 0x01, "Ridge")),
            unknownName = UNKNOWN,
        )
        assertEquals("Ridge", result.displayName)
        assertEquals(NodeNameMatchKind.EXACT, result.matchKind)
        assertFalse(result.isFallback)
    }

    @Test
    fun collidingShortPrefixIsFallback() {
        val result = repeatRowResolution(
            repeaterHash = byteArrayOf(PREFIX),
            contacts = listOf(
                repeater(PREFIX, 0x01, "Ridge"),
                repeater(PREFIX, 0x02, "Valley"),
            ),
            unknownName = UNKNOWN,
        )
        assertEquals(NodeNameMatchKind.FALLBACK, result.matchKind)
        assertTrue(result.isFallback)
    }

    @Test
    fun unmatchedTitleIsUnknownNotHex() {
        val unresolved = NodeNameResolution(displayName = "AA", matchKind = NodeNameMatchKind.UNRESOLVED)
        assertEquals(UNKNOWN, repeatRowTitle(unresolved, UNKNOWN))
        val named = NodeNameResolution(displayName = "Ridge", matchKind = NodeNameMatchKind.EXACT)
        assertEquals("Ridge", repeatRowTitle(named, UNKNOWN))
    }

    @Test
    fun unmatchedHashIsUnresolved() {
        val result = repeatRowResolution(
            repeaterHash = byteArrayOf(0xAA.toByte()),
            contacts = listOf(repeater(PREFIX, 0x01, "Ridge")),
            unknownName = UNKNOWN,
        )
        assertEquals(UNKNOWN, result.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, result.matchKind)
        assertFalse(result.isFallback)
    }

    @Test
    fun emptyHashIsUnresolved() {
        val result = repeatRowResolution(
            repeaterHash = ByteArray(0),
            contacts = listOf(repeater(PREFIX, 0x01, "Ridge")),
            unknownName = UNKNOWN,
        )
        assertEquals(UNKNOWN, result.displayName)
        assertEquals(NodeNameMatchKind.UNRESOLVED, result.matchKind)
    }

    private fun repeater(prefix: Byte, secondByte: Byte, name: String) = ChatFixtures.contact(name = name).copy(
        publicKey = ByteArray(PUBLIC_KEY_LENGTH) { index ->
            when (index) {
                0 -> prefix
                1 -> secondByte
                else -> 0
            }
        },
        typeRawValue = ContactType.REPEATER.raw,
        lastAdvertTimestamp = ADVERT_TIMESTAMP,
    )

    private companion object {
        const val PREFIX: Byte = 0x3F
        const val UNKNOWN = "Unknown repeater"
        const val PUBLIC_KEY_LENGTH = 32
        const val ADVERT_TIMESTAMP = 10L
    }
}
