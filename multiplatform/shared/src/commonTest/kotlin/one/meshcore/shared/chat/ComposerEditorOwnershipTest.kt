package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerEditorOwnershipTest {
    @Test
    fun `send reads the shared editor before the presentation collector catches up`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        store.setDraft(SAVED, ID)
        val owners = ChatDraftEditors(store, backgroundScope)
        val editor = owners.editor(ID)
        editor.awaitInitialized()
        val sent = mutableListOf<String>()
        val old = ComposerViewModel(backgroundScope, editor, MAX_BYTES, sent::add)
        val newer = ComposerViewModel(backgroundScope, editor, MAX_BYTES) {}
        newer.setText(LIVE)
        assertEquals(SAVED, old.state.value.text)

        assertTrue(old.send(isReady = true))

        assertEquals(listOf(LIVE), sent)
        assertEquals("", editor.text.value)
    }

    @Test
    fun `share appends to the shared editor before the presentation collector catches up`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        store.setDraft(SAVED, ID)
        val editor = ChatDraftEditors(store, backgroundScope).editor(ID)
        editor.awaitInitialized()
        val old = ComposerViewModel(backgroundScope, editor, MAX_BYTES) {}
        val newer = ComposerViewModel(backgroundScope, editor, MAX_BYTES) {}
        newer.setText(LIVE)
        assertEquals(SAVED, old.state.value.text)

        old.insertShared(SHARED)

        assertEquals("$LIVE $SHARED", editor.text.value)
        assertEquals("$LIVE $SHARED", old.state.value.text)
    }

    @Test
    fun `reopened presentation retains text and uses its own send callback`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(store, backgroundScope)
        val oldSent = mutableListOf<String>()
        val newSent = mutableListOf<String>()
        val old = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES, oldSent::add)
        old.setText(LIVE)
        old.close()
        val reopened = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES, newSent::add)
        assertEquals(LIVE, reopened.state.value.text)

        assertFalse(old.send(isReady = true))
        assertTrue(reopened.send(isReady = true))

        assertTrue(oldSent.isEmpty())
        assertEquals(listOf(LIVE), newSent)
    }

    @Test
    fun `retired slot editor cannot send share or accept a parked reply`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(store, backgroundScope)
        val editor = owners.editor(ID)
        val sent = mutableListOf<String>()
        val composer = ComposerViewModel(backgroundScope, editor, MAX_BYTES, sent::add)
        composer.setText(LIVE)
        owners.retireChannelDrafts(ChatFixtures.RADIO, setOf(CHANNEL))

        assertFalse(composer.acceptsDraft)
        assertFalse(composer.send(isReady = true))
        composer.insertShared(SHARED)
        composer.restoreDraft(quickReply = SHARED)

        assertEquals(LIVE, editor.text.value)
        assertTrue(sent.isEmpty())
        assertEquals("", owners.editor(ID).text.value)
    }

    @Test
    fun `shutdown editor cannot send or consume a parked reply`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(store, backgroundScope)
        val sent = mutableListOf<String>()
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES, sent::add)
        composer.setText(LIVE)
        owners.shutdown()

        assertFalse(composer.acceptsDraft)
        assertFalse(composer.send(isReady = true))
        composer.restoreDraft(quickReply = SHARED)

        assertTrue(sent.isEmpty())
        assertEquals(LIVE, store.draft(ID))
    }

    private companion object {
        const val CHANNEL = 3
        const val MAX_BYTES = 150
        val ID = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL)
        const val SAVED = "saved draft"
        const val LIVE = "newer draft"
        const val SHARED = "https://example.test"
    }
}
