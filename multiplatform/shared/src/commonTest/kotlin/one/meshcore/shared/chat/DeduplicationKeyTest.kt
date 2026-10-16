package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Mirrors the iOS `DeduplicationKeyTests`: the content-based key format, hash suffix, and determinism. */
class DeduplicationKeyTest {
    @Test
    fun dmScopeUsesExpectedPrefix() {
        val key = DeduplicationKey.contentBased(
            contactID = Uuid.normalized("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
            channelIndex = null,
            senderNodeName = null,
            timestamp = 1000,
            content = "hello",
        )
        assertTrue(key.startsWith("dm-AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE-1000-"))
    }

    @Test
    fun channelScopeWithSenderUsesExpectedPrefix() {
        val key = DeduplicationKey.contentBased(
            contactID = null,
            channelIndex = 3,
            senderNodeName = "Alice",
            timestamp = 2000,
            content = "test",
        )
        assertTrue(key.startsWith("ch-3-2000-Alice-"))
    }

    @Test
    fun channelScopeWithoutSenderLeavesEmptySegment() {
        val key = DeduplicationKey.contentBased(
            contactID = null,
            channelIndex = 0,
            senderNodeName = null,
            timestamp = 500,
            content = "msg",
        )
        assertTrue(key.startsWith("ch-0-500--"))
    }

    @Test
    fun dmScopeWithoutContactUsesUnknownPlaceholder() {
        val key = DeduplicationKey.contentBased(
            contactID = null,
            channelIndex = null,
            senderNodeName = null,
            timestamp = 100,
            content = "x",
        )
        assertTrue(key.startsWith("dm-unknown-100-"))
    }

    @Test
    fun keyHasEightCharHexHashSuffix() {
        val key = DeduplicationKey.contentBased(
            contactID = Uuid.normalized("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
            channelIndex = null,
            senderNodeName = null,
            timestamp = 1000,
            content = "hello",
        )
        val hashSuffix = key.substringAfterLast('-')
        assertEquals(EXPECTED_HASH_HEX_CHARS, hashSuffix.length)
    }

    @Test
    fun keyIsDeterministicAndDistinguishesContent() {
        val contactID = Uuid.normalized("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        val keyA1 = DeduplicationKey.contentBased(contactID, null, null, 1, "aaa")
        val keyA2 = DeduplicationKey.contentBased(contactID, null, null, 1, "aaa")
        val keyB = DeduplicationKey.contentBased(contactID, null, null, 1, "bbb")
        assertEquals(keyA1, keyA2)
        assertNotEquals(keyA1, keyB)
    }

    @Test
    fun emptyContentStillProducesWellFormedKey() {
        val key = DeduplicationKey.contentBased(
            contactID = Uuid.normalized("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"),
            channelIndex = null,
            senderNodeName = null,
            timestamp = 0,
            content = "",
        )
        assertTrue(key.startsWith("dm-"))
        assertTrue(key.length > MIN_WELL_FORMED_KEY_LENGTH)
    }

    private companion object {
        const val EXPECTED_HASH_HEX_CHARS = 8
        const val MIN_WELL_FORMED_KEY_LENGTH = 10
    }
}
