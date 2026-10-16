package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConversationListContactRetentionTest {
    @Test
    fun allContactIDsChangeWithoutChangingTheConversationSnapshot() = runTest {
        val owner = Owner()
        owner.store.contactIDs = setOf(ChatFixtures.CONTACT)
        owner.vm.reload()
        val generation = owner.vm.state.value.snapshotGeneration
        assertEquals(setOf(ChatFixtures.CONTACT), owner.vm.state.value.retainingDirectContactIDs)
        assertTrue(owner.vm.state.value.snapshot.all.isEmpty())

        owner.store.contactIDs = emptySet()
        owner.vm.reload()
        assertEquals(emptySet(), owner.vm.state.value.retainingDirectContactIDs)
        assertEquals(generation, owner.vm.state.value.snapshotGeneration)
    }

    @Test
    fun failedIDReadPublishesUnknownRatherThanRetainingCachedIDs() = runTest {
        val owner = Owner()
        owner.store.contactIDs = setOf(ChatFixtures.CONTACT)
        owner.vm.reload()
        owner.store.fetchContactIDsFailure = IllegalStateException("read failed")
        owner.vm.reload()
        assertNull(owner.vm.state.value.retainingDirectContactIDs)
        assertNull(owner.vm.state.value.errorMessage)
        assertFalse(owner.vm.state.value.isLoading)
    }

    @Test
    fun cancelledIDReadPropagatesAndKeepsThePriorCommittedIDs() = runTest {
        val owner = Owner()
        owner.store.contactIDs = setOf(ChatFixtures.CONTACT)
        owner.vm.reload()
        owner.store.fetchContactIDsFailure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { owner.vm.reload() }
        assertEquals(setOf(ChatFixtures.CONTACT), owner.vm.state.value.retainingDirectContactIDs)
    }

    @Test
    fun disconnectedOwnerClearsContactIDsAndRows() = runTest {
        val owner = Owner()
        owner.store.contactIDs = setOf(ChatFixtures.CONTACT)
        owner.store.contacts = listOf(ChatFixtures.contact("Alice", lastMessageDate = 1.0))
        owner.vm.reload()
        owner.radioID = null
        owner.vm.reload()
        assertNull(owner.vm.state.value.retainingDirectContactIDs)
        assertTrue(owner.vm.state.value.snapshot.all.isEmpty())
    }

    @Test
    fun oldRadioQueryCannotPublishAfterTheOwnerChanges() = runTest {
        val owner = Owner()
        val queryStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.store.contactIDs = setOf(ChatFixtures.CONTACT)
        owner.store.beforeContactIDsFetch = {
            queryStarted.complete(Unit)
            release.await()
        }
        val pending = launch { owner.vm.reload() }
        queryStarted.await()
        owner.radioID = Uuid.random()
        release.complete(Unit)
        pending.join()
        assertNull(owner.vm.state.value.retainingDirectContactIDs)
        assertFalse(owner.vm.state.value.hasLoadedOnce)

        owner.store.beforeContactIDsFetch = {}
        owner.store.contactIDs = emptySet()
        owner.vm.reload()
        assertEquals(emptySet(), owner.vm.state.value.retainingDirectContactIDs)
    }

    @Test
    fun replacedStoreCannotPublishRowsOrAnOldLoadError() = runTest {
        val owner = Owner()
        val queryStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.store.fetchContactsFailure = IllegalStateException("old store failure")
        owner.store.beforeContactIDsFetch = {
            queryStarted.complete(Unit)
            release.await()
        }
        val pending = launch { owner.vm.reload() }
        queryStarted.await()
        owner.store = FakeConversationListStore()
        release.complete(Unit)
        pending.join()
        assertNull(owner.vm.state.value.errorMessage)
        assertFalse(owner.vm.state.value.hasLoadedOnce)
        owner.vm.reload()
        assertEquals(emptySet(), owner.vm.state.value.retainingDirectContactIDs)
    }

    @Test
    fun cancelledNonCooperativeQueryCannotPublishItsFinalResult() = runTest {
        val owner = Owner()
        owner.store.contactIDs = setOf(ChatFixtures.CONTACT)
        owner.vm.reload()
        val queryStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        owner.store.contactIDs = emptySet()
        owner.store.beforeContactIDsFetch = {
            withContext(NonCancellable) {
                queryStarted.complete(Unit)
                release.await()
            }
        }
        val pending = launch { owner.vm.reload() }
        queryStarted.await()
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertEquals(setOf(ChatFixtures.CONTACT), owner.vm.state.value.retainingDirectContactIDs)
    }

    @Test
    fun failedReadsForANewRadioCannotRetainOldRadioRowsOrPreviews() = runTest {
        val owner = Owner()
        val contact = ChatFixtures.contact("Alice", id = ChatFixtures.CONTACT, lastMessageDate = 1.0)
        owner.store.contacts = listOf(contact)
        owner.store.channels = listOf(ChatFixtures.channel("General", lastMessageDate = 1.0))
        owner.store.rooms = listOf(ChatFixtures.room("Room", lastMessageDate = 1.0))
        owner.store.contactPreviews = mapOf(contact.id to listOf(ChatFixtures.message(text = "old preview")))
        owner.vm.reload()
        assertTrue(owner.vm.state.value.snapshot.all.isNotEmpty())
        assertEquals("old preview", owner.vm.state.value.previews[contact.id])

        owner.radioID = Uuid.random()
        owner.store.fetchContactsFailure = IllegalStateException("unavailable")
        owner.store.fetchChannelsFailure = IllegalStateException("unavailable")
        owner.store.fetchRoomsFailure = IllegalStateException("unavailable")
        owner.vm.reload()
        assertTrue(owner.vm.state.value.snapshot.all.isEmpty())
        assertTrue(owner.vm.state.value.previews.isEmpty())
        assertEquals(emptySet(), owner.vm.state.value.retainingDirectContactIDs)
    }

    @Test
    fun snapshotAndIDsPublishTogetherAfterTheIDReadCompletes() = runTest {
        val owner = Owner()
        val contact = ChatFixtures.contact("Alice", lastMessageDate = 1.0)
        owner.store.contacts = listOf(contact)
        owner.store.contactIDs = setOf(contact.id)
        val release = CompletableDeferred<Unit>()
        owner.store.beforeContactIDsFetch = { release.await() }
        val pending = launch { owner.vm.reload() }
        runCurrent()
        assertTrue(owner.vm.state.value.snapshot.all.isEmpty())
        assertNull(owner.vm.state.value.retainingDirectContactIDs)
        release.complete(Unit)
        pending.join()
        assertEquals(listOf(contact.id), owner.vm.state.value.snapshot.all.map { it.id })
        assertEquals(setOf(contact.id), owner.vm.state.value.retainingDirectContactIDs)
    }

    private class Owner {
        var store = FakeConversationListStore()
        var radioID: Uuid? = ChatFixtures.RADIO
        val vm = ConversationListViewModel(
            ConversationListViewModel.Dependencies(
                errorMessages = { ChatErrorMessages.developerEnglish() },
                store = { store },
                radioId = { radioID },
            ),
        )
    }
}
