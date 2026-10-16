package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the `ChatConversationID.draftStorageKey` encoding [DraftStore] keys on, mirroring the Swift
 * `DraftStoreTests` "draftStorageKey encodes dm and channel keys in the pinned format" case. The format
 * is a stored contract: a change strands every existing draft under its old key.
 */
class DraftStorageKeyTest {
    private val radio = Uuid.normalized("11111111-1111-1111-1111-111111111111")
    private val contact = Uuid.normalized("22222222-2222-2222-2222-222222222222")

    @Test
    fun dmKeyIsPinned() {
        assertEquals(
            "11111111-1111-1111-1111-111111111111|dm|22222222-2222-2222-2222-222222222222",
            ChatConversationID.dm(radio, contact).draftStorageKey,
        )
    }

    @Test
    fun channelKeyIsPinned() {
        assertEquals(
            "11111111-1111-1111-1111-111111111111|ch|5",
            ChatConversationID.channel(radio, 5).draftStorageKey,
        )
    }
}
