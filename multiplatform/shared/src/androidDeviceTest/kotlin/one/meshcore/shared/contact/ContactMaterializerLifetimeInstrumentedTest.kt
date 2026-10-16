package one.meshcore.shared.contact

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ContactMaterializerLifetimeInstrumentedTest {
    @Test
    fun alreadyRetiredAdmission() = exercise("admission") { f ->
        val events = f.queue()
        f.service.retireContactSyncOperations()
        assertNull(f.invoke())
        assertTrue(f.requestedKeys.isEmpty())
        assertEquals(0, f.saves)
        assertEquals(0, f.touches)
        assertEquals(0, f.refreshes)
        f.assertEmpty()
        assertTrue(f.eventSnapshot(events).isEmpty())
        assertTrue(currentCoroutineContext().job.isActive)
    }

    @Test
    fun actualCallerCancellationWins() = exercise("caller") { f ->
        val events = f.queue()
        f.holdReturn = true
        val caller = async { f.invoke() }
        try {
            val operation = f.completed.await()
            assertTrue(caller.isActive && operation.isActive && caller.owns(operation))
            assertEquals(listOf(f.key.toList()), f.requestedKeys.map { it.toList() })
            caller.cancel()
            f.service.retireContactSyncOperations()
            f.release.complete(Unit)
            var cancelled = false
            try {
                caller.await()
            } catch (_: CancellationException) {
                cancelled = true
            }
            caller.join()
            operation.join()
            assertTrue(cancelled && caller.isCancelled && operation.isCancelled)
            assertEquals(0, f.saves)
            assertEquals(0, f.touches)
            assertEquals(0, f.refreshes)
            f.assertEmpty()
            assertTrue(f.eventSnapshot(events).isEmpty())
            assertTrue(currentCoroutineContext().job.isActive)
        } finally {
            f.release.complete(Unit)
            caller.cancel()
            caller.join()
        }
    }

    @Test
    fun committedStampRetirementSuppressesPublication() = exercise("publication") { f ->
        val events = f.queue()
        var committedRetirement = false
        f.onTouchCommitted = {
            f.service.retireContactSyncOperations()
            committedRetirement = true
        }
        assertNull(f.invoke())
        f.assertCommittedStamp()
        assertEquals(listOf(f.key.toList()), f.requestedKeys.map { it.toList() })
        assertEquals(1, f.saves)
        assertEquals(1, f.touches)
        assertTrue(committedRetirement && f.service.contactSyncRetired.isCompleted)
        assertEquals(0, f.refreshes)
        assertTrue(f.eventSnapshot(events).isEmpty())
        assertTrue(currentCoroutineContext().job.isActive)
    }

    @Test
    fun liveMaterializationStillPublishes() = exercise("live") { f ->
        val events = f.queue()
        val result = f.invoke()
        assertNotNull(result)
        f.assertCommittedStamp()
        assertEquals(listOf(f.key.toList()), f.requestedKeys.map { it.toList() })
        assertEquals(1, f.saves)
        assertEquals(1, f.touches)
        assertEquals(1, f.refreshes)
        assertEquals(listOf(ContactServiceEvent.ContactAdvertisementApplied(f.radio, f.key)), f.eventSnapshot(events))
        assertTrue(f.service.hasPendingAdvertKey(f.key))
        assertFalse(f.service.contactSyncRetired.isCompleted)
        assertTrue(currentCoroutineContext().job.isActive)
    }

    private fun exercise(
        case: String,
        block: suspend CoroutineScope.(ContactMaterializerLifetimeNativeFixture) -> Unit,
    ) {
        val fixture = ContactMaterializerLifetimeNativeFixture()
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            runBlocking(dispatcher) { withTimeout(TIMEOUT_MS) { block(fixture) } }
            receipt(case, "outcome")
        } finally {
            fixture.release.complete(Unit)
            var finished = false
            try {
                runBlocking(dispatcher) {
                    withTimeout(TIMEOUT_MS) { fixture.service.finishEvents() }
                }
                finished = true
            } finally {
                dispatcher.close()
                check(executor.awaitTermination(TIMEOUT_MS, TimeUnit.MILLISECONDS))
                if (finished) receipt(case, "teardown")
            }
        }
    }

    private fun receipt(case: String, kind: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, RECEIPT_DIRECTORY)
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$case.$kind.json").writeText("{\"case\":\"$case\",\"kind\":\"$kind\",\"passed\":true}")
    }

    private fun Job.owns(other: Job): Boolean = this === other || children.any { it.owns(other) }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val RECEIPT_DIRECTORY = "materializer-lifetime-native"
    }
}
