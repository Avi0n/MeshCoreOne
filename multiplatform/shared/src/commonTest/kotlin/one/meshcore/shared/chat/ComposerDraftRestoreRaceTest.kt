package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerDraftRestoreRaceTest {
    @Test
    fun editDuringInitialDraftLoadKeepsNewText() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        composer.setText(TYPED)
        gated.release.complete(Unit)
        restoring.await()

        assertEquals(TYPED, composer.state.value.text)
    }

    @Test
    fun editThenClearDuringInitialDraftLoadKeepsEmptyComposer() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        composer.setText(TYPED)
        composer.setText("")
        gated.release.complete(Unit)
        restoring.await()

        assertEquals("", composer.state.value.text)
    }

    @Test
    fun untouchedComposerStillRestoresAfterInitialLoad() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        gated.release.complete(Unit)
        restoring.await()

        assertEquals(SAVED, composer.state.value.text)
    }

    @Test
    fun acceptedSendDuringInitialLoadDoesNotRestoreSentText() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val sent = mutableListOf<String>()
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES, sent::add)
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        composer.setText(TYPED)
        assertTrue(composer.send(isReady = true))
        gated.release.complete(Unit)
        restoring.await()
        runCurrent()

        assertEquals(listOf(TYPED), sent)
        assertEquals("", composer.state.value.text)
    }

    @Test
    fun laterQuickReplyWinsOverAnOlderSuspendedDiskRestore() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        composer.restoreDraft(quickReply = TYPED)
        gated.release.complete(Unit)
        restoring.await()

        assertEquals(TYPED, composer.state.value.text)
    }

    @Test
    fun closedComposerRejectsThePendingDiskRestore() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        composer.close()
        gated.release.complete(Unit)
        restoring.await()

        assertEquals("", composer.state.value.text)
    }

    @Test
    fun budgetAndFocusChangesDoNotDiscardAnUntouchedDraft() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        composer.updateMaxBytes(MAX_BYTES - 1)
        composer.requestFocusNow()
        gated.release.complete(Unit)
        restoring.await()

        assertEquals(SAVED, composer.state.value.text)
        assertEquals(MAX_BYTES - 1, composer.state.value.maxBytes)
        assertEquals(1, composer.state.value.focusRequest)
    }

    @Test
    fun explicitEmptyEditDuringInitialLoadDoesNotRestoreOldText() = runTest {
        val preferences = InMemoryPreferencesStore()
        DraftStore.create(preferences).setDraft(SAVED, ID)
        val gated = GatedRead(preferences)
        val drafts = DraftStore.loading(gated, backgroundScope)
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        val restoring = async { composer.restoreDraft() }
        runCurrent()
        assertTrue(gated.entered.isCompleted)
        assertFalse(restoring.isCompleted)

        composer.setText("")
        gated.release.complete(Unit)
        restoring.await()

        assertEquals("", composer.state.value.text)
    }

    private class GatedRead(private val backing: PreferencesStore) : PreferencesStore by backing {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun getString(key: String): String? {
            check(key == DraftStore.STORAGE_KEY)
            val value = backing.getString(key)
            entered.complete(Unit)
            release.await()
            return value
        }
    }

    private companion object {
        val ID = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        const val SAVED = "previous saved draft"
        const val TYPED = "new text while loading"
        const val MAX_BYTES = 150
    }
}
