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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Mirrors the Swift `DraftStoreTests`: set/draft/clear round-trip, restart survival (a second store on the
 * same backing prefs), whitespace-empty removal, verbatim non-empty storage, the `draftToApply` restore
 * guard, key isolation across dm/channel and radios, cleared-state persistence, and batch channel-slot
 * vacating. The disk seam is the shared [InMemoryPreferencesStore], the unit-test double for the
 * DataStore-backed production store.
 */
class DraftStoreTest {
    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val contact = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")

    private fun dm(r: Uuid = radio, c: Uuid = contact) = ChatConversationID.dm(r, c)

    private fun channel(r: Uuid = radio, index: Int) = ChatConversationID.channel(r, index)

    @Test
    fun persistFaultKeepsTheInMemoryDraft() = runTest {
        val warnings = mutableListOf<String>()
        val prefs = failingPrefs { error("datastore down") }
        val store = DraftStore.create(prefs, warnings::add)
        val id = dm()

        store.setDraft("hello", id)

        assertEquals("hello", store.draft(id))
        assertEquals(listOf("Draft persist failed: datastore down"), warnings)
        assertNull(DraftStore.create(prefs).draft(id))
    }

    @Test
    fun aCancelledPersistPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val store = DraftStore.create(failingPrefs { throw cancelled }, warnings::add)
        val thrown = assertFailsWith<CancellationException> {
            store.setDraft("hello", dm())
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aPersistErrorPropagates() = runTest {
        val probe = DraftPersistProbeError()
        val warnings = mutableListOf<String>()
        val store = DraftStore.create(failingPrefs { throw probe }, warnings::add)
        val thrown = assertFailsWith<DraftPersistProbeError> {
            store.setDraft("hello", dm())
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun setDraftDraftClearRoundTrip() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val id = dm()

        assertNull(store.draft(id))

        store.setDraft("hello", id)
        assertEquals("hello", store.draft(id))

        store.clearDraft(id)
        assertNull(store.draft(id))
    }

    @Test
    fun draftSurvivesANewStoreOnTheSamePrefs() = runTest {
        val prefs = InMemoryPreferencesStore()
        val id = dm()

        DraftStore.create(prefs).setDraft("persisted", id)

        assertEquals("persisted", DraftStore.create(prefs).draft(id))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun loadingReturnsBeforeDiskAndDraftReadsAwaitTheSharedLoad() = runTest {
        val backing = InMemoryPreferencesStore()
        val id = dm()
        DraftStore.create(backing).setDraft("persisted", id)
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val gated = object : PreferencesStore by backing {
            override suspend fun getString(key: String): String? {
                readStarted.complete(Unit)
                releaseRead.await()
                return backing.getString(key)
            }
        }

        val store = DraftStore.loading(gated, backgroundScope)
        runCurrent()
        assertTrue(readStarted.isCompleted)
        val read = async { store.draft(id) }
        runCurrent()
        assertFalse(read.isCompleted)

        releaseRead.complete(Unit)
        assertEquals("persisted", read.await())
    }

    @Test
    fun whitespaceOnlyInputRemovesTheEntry() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val id = dm()

        store.setDraft("real", id)
        assertEquals("real", store.draft(id))

        store.setDraft("   \n\t ", id)
        assertNull(store.draft(id))
    }

    @Test
    fun nonEmptyDraftWithTrailingNewlineIsStoredVerbatim() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val id = dm()

        store.setDraft("hi\n", id)
        assertEquals("hi\n", store.draft(id))
    }

    @Test
    fun draftToApplyReturnsTheDraftWhenTheFieldIsEmpty() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val id = dm()
        store.setDraft("saved", id)

        assertEquals("saved", store.draftToApply(currentText = "", id = id))
    }

    @Test
    fun draftToApplyReturnsNullWhenTheFieldHasText() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val id = dm()
        store.setDraft("saved", id)

        assertNull(store.draftToApply(currentText = "typing", id = id))
    }

    @Test
    fun draftToApplyReturnsNullWhenNoDraftExists() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())

        assertNull(store.draftToApply(currentText = "", id = dm()))
    }

    @Test
    fun dmAndChannelDraftsAtMatchingIdentifiersDoNotCollide() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val dmID = dm()
        val channelID = channel(index = 3)

        store.setDraft("dm-text", dmID)
        store.setDraft("channel-text", channelID)

        assertEquals("dm-text", store.draft(dmID))
        assertEquals("channel-text", store.draft(channelID))
    }

    @Test
    fun sameChannelIndexOnDifferentRadiosIsIsolated() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val radioA = Uuid.normalized("00000000-0000-0000-0000-00000000000A")
        val radioB = Uuid.normalized("00000000-0000-0000-0000-00000000000B")

        store.setDraft("a", channel(radioA, 1))
        store.setDraft("b", channel(radioB, 1))

        assertEquals("a", store.draft(channel(radioA, 1)))
        assertEquals("b", store.draft(channel(radioB, 1)))
    }

    @Test
    fun clearDraftPersistsTheDeletionAcrossAFreshStore() = runTest {
        val prefs = InMemoryPreferencesStore()
        val id = dm()

        val first = DraftStore.create(prefs)
        first.setDraft("persisted", id)
        first.clearDraft(id)

        assertNull(DraftStore.create(prefs).draft(id))
    }

    @Test
    fun whitespaceClearPersistsTheDeletionAcrossAFreshStore() = runTest {
        val prefs = InMemoryPreferencesStore()
        val id = dm()

        val first = DraftStore.create(prefs)
        first.setDraft("persisted", id)
        first.setDraft("   \n ", id)

        assertNull(DraftStore.create(prefs).draft(id))
    }

    @Test
    fun clearDraftOnANeverSetIdIsANoOp() = runTest {
        val prefs = InMemoryPreferencesStore()
        val id = channel(index = 4)

        val store = DraftStore.create(prefs)
        store.clearDraft(id)
        assertNull(store.draft(id))

        assertNull(DraftStore.create(prefs).draft(id))
    }

    @Test
    fun clearChannelDraftsClearsTheGivenSlotsAndPersists() = runTest {
        val prefs = InMemoryPreferencesStore()
        val store = DraftStore.create(prefs)

        store.setDraft("one", channel(index = 1))
        store.setDraft("two", channel(index = 2))
        store.setDraft("keep", channel(index = 3))

        store.clearChannelDrafts(radioID = radio, indices = setOf(1, 2))

        assertNull(store.draft(channel(index = 1)))
        assertNull(store.draft(channel(index = 2)))
        assertEquals("keep", store.draft(channel(index = 3)))

        val reloaded = DraftStore.create(prefs)
        assertNull(reloaded.draft(channel(index = 1)))
        assertEquals("keep", reloaded.draft(channel(index = 3)))
    }

    @Test
    fun clearChannelDraftsAcrossRadiosLeavesDmDraftsIntact() = runTest {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val radioA = Uuid.normalized("00000000-0000-0000-0000-00000000000A")
        val radioB = Uuid.normalized("00000000-0000-0000-0000-00000000000B")

        store.setDraft("a1", channel(radioA, 1))
        store.setDraft("b1", channel(radioB, 1))
        store.setDraft("dm", dm(radioA, contact))

        store.clearChannelDrafts(slotsByRadio = mapOf(radioA to setOf(1), radioB to setOf(1)))

        assertNull(store.draft(channel(radioA, 1)))
        assertNull(store.draft(channel(radioB, 1)))
        assertEquals("dm", store.draft(dm(radioA, contact)))
    }

    private fun failingPrefs(fail: () -> Nothing): PreferencesStore =
        object : PreferencesStore by InMemoryPreferencesStore() {
            override suspend fun putString(key: String, value: String) {
                fail()
            }
        }
}

private class DraftPersistProbeError : Error("probe")
