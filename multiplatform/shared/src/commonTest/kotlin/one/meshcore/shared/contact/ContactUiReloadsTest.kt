package one.meshcore.shared.contact

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ContactUiReloadsTest {
    @Test
    fun deviceTableEventsReloadAndSyncProgressDoesNot() = runTest {
        val service = testContactService()
        val reasons = mutableListOf<String>()
        val job = launch {
            collectContactUiReloads(service) { reasons += "reload" }
        }
        advanceUntilIdle()

        service.eventBroadcaster.yield(ContactServiceEvent.SyncProgress(received = 1, total = 2))
        service.eventBroadcaster.yield(ContactServiceEvent.NodeDeleted)
        service.eventBroadcaster.yield(
            ContactServiceEvent.ContactAdvertisementApplied(
                radioID = ContactTestIds.RADIO,
                publicKey = publicKey(0x01),
            ),
        )
        advanceUntilIdle()

        assertEquals(listOf("reload", "reload"), reasons)
        job.cancel()
    }

    @Test
    fun aMissingServiceStillReloadsOnListInvalidation() = runTest {
        val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val reasons = mutableListOf<String>()
        val job = launch {
            collectContactUiReloads(service = null, invalidations = invalidations) {
                reasons += "reload"
            }
        }
        advanceUntilIdle()

        invalidations.emit(Unit)
        advanceUntilIdle()

        assertEquals(listOf("reload"), reasons)
        job.cancel()
    }
}
