package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftEditorsTest {
    @Test
    fun `lookup retains editor and isolates other conversations`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(store, backgroundScope)
        val editor = owners.editor(ID)
        editor.setText(LIVE)

        assertSame(editor, owners.editor(ID))
        assertEquals(LIVE, owners.editor(ID).text.value)
        assertNotSame(editor, owners.editor(OTHER))
        assertEquals("", owners.editor(OTHER).text.value)
        val foreign = owners.editor(ChatConversationID.channel(OTHER_RADIO, CHANNEL))
        assertNotSame(editor, foreign)
        foreign.setText(OTHER_TEXT)
        assertEquals(LIVE, editor.text.value)
        assertEquals(OTHER_TEXT, foreign.text.value)
    }

    @Test
    fun `edit before scheduled hydration starts cannot be overwritten by disk`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        store.setDraft(SAVED, ID)
        val editor = ChatDraftEditors(store, backgroundScope).editor(ID)
        editor.setText(LIVE)

        runCurrent()
        editor.awaitInitialized()

        assertEquals(LIVE, editor.text.value)
        editor.flush()
        assertEquals(LIVE, store.draft(ID))
    }

    @Test
    fun `clean seed flush and equal edit do not write`() = runTest {
        val backing = InMemoryPreferencesStore()
        DraftStore.create(backing).setDraft(SAVED, ID)
        val preferences = GatedPreferences(backing)
        val store = DraftStore.create(preferences)
        val editor = ChatDraftEditors(store, backgroundScope).editor(ID)
        editor.awaitInitialized()
        editor.flush()
        editor.setText(SAVED)
        editor.flush()

        assertEquals(SAVED, editor.text.value)
        assertEquals(0, preferences.writes)
    }

    @Test
    fun `flush writes latest text and intentional whitespace removes saved draft`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val editor = ChatDraftEditors(store, backgroundScope).editor(ID)
        editor.setText(SAVED)
        editor.setText(LIVE)
        editor.flush()
        assertEquals(LIVE, store.draft(ID))

        editor.setText(" \n")
        editor.flush()

        assertEquals(" \n", editor.text.value)
        assertNull(store.draft(ID))
    }

    @Test
    fun `suspended save completion does not mark a newer edit clean`() = runTest {
        val backing = InMemoryPreferencesStore()
        val preferences = GatedPreferences(backing)
        val store = DraftStore.create(preferences)
        val editor = ChatDraftEditors(store, backgroundScope).editor(ID)
        editor.setText(SAVED)
        val gate = preferences.holdNextWrite()
        val saving = async { editor.flush() }
        try {
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            editor.setText(LIVE)
            gate.release.complete(Unit)
            saving.await()
            editor.flush()

            assertEquals(LIVE, DraftStore.create(backing).draft(ID))
        } finally {
            gate.release.complete(Unit)
            saving.cancel()
        }
    }

    @Test
    fun `reset retains identity and a new edit waits for admitted old save and clear`() = runTest {
        val backing = InMemoryPreferencesStore()
        val preferences = GatedPreferences(backing)
        val store = DraftStore.create(preferences)
        val owners = ChatDraftEditors(store, backgroundScope)
        val editor = owners.editor(ID)
        val controls = listOf(
            OTHER,
            ChatConversationID.channel(ChatFixtures.RADIO, OTHER_CHANNEL),
            ChatConversationID.channel(OTHER_RADIO, CHANNEL),
        ).associateWith { id ->
            owners.editor(id).also {
                it.setText(OTHER_TEXT)
                it.flush()
            }
        }
        editor.setText(SAVED)
        val gate = preferences.holdNextWrite()
        val saving = async { editor.flush() }
        val resetting = async { owners.resetChannelDrafts(SLOTS) }
        try {
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertFalse(resetting.isCompleted)
            assertSame(editor, owners.editor(ID))
            assertEquals("", editor.text.value)
            editor.setText(LIVE)
            val latest = async { editor.flush() }
            runCurrent()
            assertFalse(latest.isCompleted)
            gate.release.complete(Unit)
            saving.await()
            resetting.await()
            latest.await()

            assertEquals(LIVE, editor.text.value)
            val cold = DraftStore.create(backing)
            assertEquals(LIVE, cold.draft(ID))
            for ((id, retained) in controls) {
                assertSame(retained, owners.editor(id))
                assertEquals(OTHER_TEXT, retained.text.value)
                assertEquals(OTHER_TEXT, cold.draft(id))
            }
        } finally {
            gate.release.complete(Unit)
            saving.cancel()
            resetting.cancel()
        }
    }

    @Test
    fun `cancelled retirement waiter cannot expose old slot to replacement lookup`() = runTest {
        val backing = InMemoryPreferencesStore()
        val preferences = GatedPreferences(backing)
        val store = DraftStore.create(preferences)
        store.setDraft(SAVED, ID)
        val owners = ChatDraftEditors(store, backgroundScope)
        val old = owners.editor(ID)
        old.awaitInitialized()
        old.setText(LIVE)
        val gate = preferences.holdNextWrite()
        val saving = async { old.flush() }
        val retiring = async { owners.retireChannelDrafts(ChatFixtures.RADIO, setOf(CHANNEL)) }
        try {
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertTrue(old.isRetired)
            retiring.cancel()
            val replacement = owners.editor(ID)
            assertNotSame(old, replacement)
            val seeding = async { replacement.awaitInitialized() }
            runCurrent()
            assertFalse(seeding.isCompleted)
            assertEquals("", replacement.text.value)
            old.setText("obsolete")
            old.flush()
            gate.release.complete(Unit)
            saving.await()
            seeding.await()

            assertEquals("", replacement.text.value)
            assertNull(DraftStore.create(backing).draft(ID))
            replacement.setText(LIVE)
            replacement.flush()
            assertEquals(LIVE, DraftStore.create(backing).draft(ID))
        } finally {
            gate.release.complete(Unit)
            saving.cancel()
            retiring.cancel()
        }
    }

    @Test
    fun `overlapping reset then retirement leaves replacement blocked until both settle`() = runTest {
        val backing = InMemoryPreferencesStore()
        val preferences = GatedPreferences(backing)
        val store = DraftStore.create(preferences)
        store.setDraft(SAVED, ID)
        store.setDraft(OTHER_TEXT, OTHER)
        val owners = ChatDraftEditors(store, backgroundScope)
        val old = owners.editor(ID)
        old.awaitInitialized()
        val gate = preferences.holdNextWrite()
        val first = async { owners.resetChannelDrafts(SLOTS) }
        val second = async { owners.retireChannelDrafts(ChatFixtures.RADIO, setOf(CHANNEL)) }
        try {
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertFalse(second.isCompleted)
            val replacement = owners.editor(ID)
            replacement.setText(LIVE)
            val saving = async { replacement.flush() }
            runCurrent()
            assertFalse(saving.isCompleted)
            gate.release.complete(Unit)
            first.await()
            second.await()
            saving.await()

            assertEquals(LIVE, DraftStore.create(backing).draft(ID))
            assertEquals(OTHER_TEXT, DraftStore.create(backing).draft(OTHER))
            assertTrue(old.isRetired)
        } finally {
            gate.release.complete(Unit)
            first.cancel()
            second.cancel()
        }
    }

    @Test
    fun `failed clear stays closed until explicit retry actually persists absent cache`() = runTest {
        val backing = InMemoryPreferencesStore()
        val preferences = GatedPreferences(backing)
        val store = DraftStore.create(preferences)
        store.setDraft(SAVED, ID)
        val owners = ChatDraftEditors(store, backgroundScope)
        val old = owners.editor(ID)
        old.awaitInitialized()
        val gate = preferences.holdNextWrite(fail = true)
        gate.release.complete(Unit)

        assertFailsWith<CancellationException> {
            owners.retireChannelDrafts(ChatFixtures.RADIO, setOf(CHANNEL))
        }
        assertTrue(gate.entered.isCompleted)
        assertEquals(SAVED, DraftStore.create(backing).draft(ID))
        val failed = owners.editor(ID)
        assertFailsWith<CancellationException> { failed.awaitInitialized() }
        assertEquals("", failed.text.value)

        owners.retireChannelDrafts(ChatFixtures.RADIO, setOf(CHANNEL))
        val replacement = owners.editor(ID)
        replacement.awaitInitialized()

        assertNull(DraftStore.create(backing).draft(ID))
        assertEquals("", replacement.text.value)
        assertTrue(failed.isRetired)
    }

    @Test
    fun `reset cancels hydration without leaving reusable editor initialization cancelled`() = runTest {
        val backing = InMemoryPreferencesStore()
        DraftStore.create(backing).setDraft(SAVED, ID)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val preferences = object : PreferencesStore by backing {
            override suspend fun getString(key: String): String? {
                val value = backing.getString(key)
                entered.complete(Unit)
                release.await()
                return value
            }
        }
        val store = DraftStore.loading(preferences, backgroundScope)
        val owners = ChatDraftEditors(store, backgroundScope)
        val editor = owners.editor(ID)
        val resetting = async { owners.resetChannelDrafts(SLOTS) }
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            release.complete(Unit)
            resetting.await()
            editor.awaitInitialized()
            assertSame(editor, owners.editor(ID))
            assertEquals("", editor.text.value)
            editor.setText(LIVE)
            editor.flush()
            assertEquals(LIVE, DraftStore.create(backing).draft(ID))
        } finally {
            release.complete(Unit)
            resetting.cancel()
        }
    }

    @Test
    fun `flush all includes unmounted editors and shutdown rejects further edits`() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(store, backgroundScope)
        val editor = owners.editor(ID)
        val other = owners.editor(OTHER)
        editor.setText(LIVE)
        other.setText(OTHER_TEXT)
        owners.flushAll()
        assertEquals(LIVE, store.draft(ID))
        assertEquals(OTHER_TEXT, store.draft(OTHER))
        editor.setText(SAVED)

        owners.shutdown()
        editor.setText("obsolete")
        editor.flush()

        assertEquals(SAVED, store.draft(ID))
        assertTrue(editor.isRetired)
        assertFailsWith<IllegalStateException> { owners.editor(ID) }
    }

    @Test
    fun `shutdown closes input before waiting for its final write`() = runTest {
        val backing = InMemoryPreferencesStore()
        val preferences = GatedPreferences(backing)
        val store = DraftStore.create(preferences)
        val owners = ChatDraftEditors(store, backgroundScope)
        val editor = owners.editor(ID)
        editor.setText(SAVED)
        val gate = preferences.holdNextWrite()
        val shutdown = async { owners.shutdown() }
        try {
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertFalse(editor.acceptsInput)
            editor.setText(LIVE)
            assertEquals(SAVED, editor.text.value)
            gate.release.complete(Unit)
            shutdown.await()

            assertEquals(SAVED, DraftStore.create(backing).draft(ID))
            assertTrue(editor.isRetired)
        } finally {
            gate.release.complete(Unit)
            shutdown.cancel()
        }
    }

    private class GatedPreferences(private val backing: PreferencesStore) : PreferencesStore by backing {
        var writes = 0
            private set
        private var next: WriteGate? = null

        fun holdNextWrite(fail: Boolean = false): WriteGate = WriteGate(fail).also {
            check(next == null)
            next = it
        }

        override suspend fun putString(key: String, value: String) {
            writes += 1
            val gate = next
            next = null
            if (gate != null) {
                gate.entered.complete(Unit)
                gate.release.await()
                if (gate.fail) throw CancellationException("controlled clear cancellation")
            }
            backing.putString(key, value)
        }
    }

    private class WriteGate(val fail: Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private companion object {
        const val CHANNEL = 3
        const val OTHER_CHANNEL = 4
        val OTHER_RADIO = Uuid.normalized("A0000000-0000-0000-0000-000000000002")
        val ID = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL)
        val OTHER = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)
        val SLOTS = mapOf(ChatFixtures.RADIO to setOf(CHANNEL))
        const val SAVED = "saved draft"
        const val LIVE = "newer live draft"
        const val OTHER_TEXT = "unrelated direct draft"
    }
}
