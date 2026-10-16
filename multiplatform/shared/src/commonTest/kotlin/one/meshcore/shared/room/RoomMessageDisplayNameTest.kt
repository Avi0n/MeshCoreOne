package one.meshcore.shared.room

import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomMessageDisplayNameTest {
    @Test
    fun prefersResolvedAuthorName() {
        val message =
            roomMessage(testUuid(10), authorName = "Alice", authorKeyPrefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte()))
        assertEquals("Alice", message.authorDisplayName)
    }

    @Test
    fun fallsBackToUppercaseKeyPrefixHex() {
        val message = roomMessage(
            testUuid(10),
            authorName = null,
            authorKeyPrefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x0C, 0x0D),
        )
        assertEquals("AABB0C0D", message.authorDisplayName)
    }
}
