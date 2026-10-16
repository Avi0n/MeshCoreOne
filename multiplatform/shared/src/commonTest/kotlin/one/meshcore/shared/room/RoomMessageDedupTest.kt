package one.meshcore.shared.room

import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Mirrors the `RoomMessage.generateDeduplicationKey` cases of the iOS `RemoteNodeModelTests`
 * (consistency, and difference on content/timestamp/author) plus frozen exact-string vectors computed
 * independently with `hashlib` (recorded in the slice report). A drift means a local post and its
 * server echo would no longer collapse.
 */
class RoomMessageDedupTest {
    private companion object {
        val AUTHOR = author("AABBCCDD")
        const val TS: UInt = 1_702_500_000u

        fun author(hex: String): ByteArray = assertNotNull(decodeHex(hex))

        fun key(ts: UInt, author: ByteArray, text: String): String =
            RoomMessageDedup.generateDeduplicationKey(ts, author, text)
    }

    @Test
    fun producesConsistentKeys() {
        assertEquals(key(TS, AUTHOR, "Hello world"), key(TS, AUTHOR, "Hello world"))
    }

    @Test
    fun differsForDifferentContent() {
        assertNotEquals(key(TS, AUTHOR, "Hello"), key(TS, AUTHOR, "World"))
    }

    @Test
    fun differsForDifferentTimestamps() {
        assertNotEquals(key(1_702_500_000u, AUTHOR, "Same text"), key(1_702_500_001u, AUTHOR, "Same text"))
    }

    @Test
    fun differsForDifferentAuthors() {
        assertNotEquals(key(TS, author("AABBCCDD"), "Same text"), key(TS, author("11223344"), "Same text"))
    }

    @Test
    fun matchesFrozenVectors() {
        assertEquals("1702500000-AABBCCDD-64EC88CA", key(1_702_500_000u, author("AABBCCDD"), "Hello world"))
        assertEquals("1720000000-A1B2C3D4-8C27BB5A", key(1_720_000_000u, author("A1B2C3D4"), "gm all"))
    }

    @Test
    fun emptyTextAndZeroInputsProduceTheWellKnownHashPrefix() {
        // SHA-256("") begins E3B0C442; timestamp 0 renders unpadded.
        assertEquals("0-00000000-E3B0C442", key(0u, author("00000000"), ""))
    }

    @Test
    fun maxTimestampRendersUnsignedWithoutOverflow() {
        assertEquals("4294967295-FFFFFFFF-BA44E9D4", key(4_294_967_295u, author("FFFFFFFF"), "🔐 secure 密码"))
    }
}
