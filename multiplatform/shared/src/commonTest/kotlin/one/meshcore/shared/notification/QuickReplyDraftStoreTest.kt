package one.meshcore.shared.notification

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** In-memory quick-reply draft round-trip (W2-spec §13.5), mirroring Swift save/consumeDraft. */
class QuickReplyDraftStoreTest {
    private val key = ConversationKey.direct(NotificationFixtures.CONTACT)

    @Test
    fun `save then consume returns the draft`() = runTest {
        val store = QuickReplyDraftStore()
        store.saveDraft(key, "on my way")
        assertEquals("on my way", store.consumeDraft(key))
    }

    @Test
    fun `consume removes the draft so a second read is null`() = runTest {
        val store = QuickReplyDraftStore()
        store.saveDraft(key, "hello")
        store.consumeDraft(key)
        assertNull(store.consumeDraft(key))
    }

    @Test
    fun `a later save overwrites the earlier draft`() = runTest {
        val store = QuickReplyDraftStore()
        store.saveDraft(key, "first")
        store.saveDraft(key, "second")
        assertEquals("second", store.consumeDraft(key))
    }

    @Test
    fun `drafts are isolated per conversation`() = runTest {
        val store = QuickReplyDraftStore()
        val channelKey = ConversationKey.channel(NotificationFixtures.RADIO, channelIndex = 2)
        store.saveDraft(key, "dm draft")
        store.saveDraft(channelKey, "channel draft")
        assertEquals("dm draft", store.consumeDraft(key))
        assertEquals("channel draft", store.consumeDraft(channelKey))
    }

    @Test
    fun `missing draft is null`() = runTest {
        assertNull(QuickReplyDraftStore().consumeDraft(key))
    }
}
