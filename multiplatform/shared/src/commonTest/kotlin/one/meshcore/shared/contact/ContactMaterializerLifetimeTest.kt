package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContactMaterializerLifetimeTest {
    @Test
    fun alreadyRetiredMaterializerDoesNotAdmitCommandsOrEffects() = runTest {
        val f = ContactMaterializerLifetimeFixture()
        val events = f.queue()
        f.service.retireContactSyncOperations()
        val result = f.invoke()
        assertTrue(currentCoroutineContext().job.isActive)
        assertTrue(f.service.contactSyncRetired.isCompleted)
        assertEquals(
            listOf(true, emptyList<Any>(), 0, 0, 0, emptyList<Any>(), emptyList<Any>()),
            listOf(
                result == null,
                f.fake.getContactKeys.map { it.toList() },
                f.saves,
                f.touches,
                f.refreshes,
                f.eventSnapshot(events),
                f.storedSnapshot(),
            ),
            "LAST: retired materializer admitted radio or persistence/publication effects",
        )
    }

    @Test
    fun actualCallerCancellationStillPropagatesWhenServiceAlsoRetires() = runTest {
        val f = ContactMaterializerLifetimeFixture()
        val events = f.queue()
        f.holdReturn = true
        val caller = async { f.invoke() }
        try {
            val operation = f.completed.await()
            assertTrue(caller.isActive && operation.isActive && caller.owns(operation))
            assertEquals(listOf(f.key.toList()), f.fake.getContactKeys.map { it.toList() })
            caller.cancel()
            f.service.retireContactSyncOperations()
            f.release.complete(Unit)
            assertFailsWith<CancellationException> { caller.await() }
            caller.join()
            operation.join()
            assertTrue(caller.isCancelled && operation.isCancelled)
            assertTrue(currentCoroutineContext().job.isActive)
            assertEquals(0, f.saves)
            assertEquals(0, f.touches)
            assertEquals(0, f.refreshes)
            assertTrue(f.eventSnapshot(events).isEmpty())
            assertTrue(f.storedSnapshot().isEmpty())
        } finally {
            f.release.complete(Unit)
            caller.cancel()
            caller.join()
        }
    }

    @Test
    fun successfulCommittedStampThenRetirementSuppressesPublication() = runTest {
        val f = ContactMaterializerLifetimeFixture()
        val events = f.queue()
        var retiredAfterCommit = false
        f.onTouchCommitted = {
            f.service.retireContactSyncOperations()
            retiredAfterCommit = true
        }
        val result = f.invoke()
        f.assertCommittedStamp()
        assertEquals(listOf(f.key.toList()), f.fake.getContactKeys.map { it.toList() })
        assertEquals(1, f.saves)
        assertEquals(1, f.touches)
        assertTrue(retiredAfterCommit && f.service.contactSyncRetired.isCompleted)
        assertTrue(currentCoroutineContext().job.isActive)
        assertEquals(
            listOf(true, emptyList<Any>(), 0),
            listOf(result == null, f.eventSnapshot(events), f.refreshes),
            "LAST: materializer published after a committed stamp retired its operation",
        )
    }

    private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }
}
