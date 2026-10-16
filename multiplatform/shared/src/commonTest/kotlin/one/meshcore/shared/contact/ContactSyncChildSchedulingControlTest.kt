package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContactSyncChildSchedulingControlTest {
    @Test
    fun terminalChildCanReturnToActiveCallerBeforeLaterCleanup() = runTest {
        val service = testContactService()
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        var cleanupFinished = false
        var continuedBeforeCleanup = false
        val caller = async {
            val originalContext = currentCoroutineContext()
            val result = try {
                service.withContactSyncOperation {
                    entered.complete(currentCoroutineContext().job)
                    release.await()
                    VALUE
                }
            } catch (cancelled: CancellationException) {
                originalContext.ensureActive()
                if (!service.contactSyncRetired.isCompleted) throw cancelled
                null
            }
            originalContext.ensureActive()
            continuedBeforeCleanup = !cleanupFinished
            result
        }
        try {
            val operation = entered.await()
            assertTrue(caller.isActive && operation.isActive)
            service.retireContactSyncOperations()
            assertNull(caller.await())
            operation.join()
            assertTrue(operation.isCancelled && caller.isCompleted)
            assertTrue(continuedBeforeCleanup)
            assertFalse(cleanupFinished)
            assertTrue(currentCoroutineContext().job.isActive)
            cleanupFinished = true
        } finally {
            release.complete(Unit)
            caller.cancel()
            caller.join()
        }
    }

    @Test
    fun trueOuterCancellationWinsEvenWhenTerminalSignalIsComplete() = runTest {
        val service = testContactService()
        val entered = CompletableDeferred<Job>()
        val release = CompletableDeferred<Unit>()
        var outerContinued = false
        val caller = async {
            val originalContext = currentCoroutineContext()
            try {
                service.withContactSyncOperation {
                    entered.complete(currentCoroutineContext().job)
                    release.await()
                }
            } catch (cancelled: CancellationException) {
                originalContext.ensureActive()
                if (!service.contactSyncRetired.isCompleted) throw cancelled
            }
            outerContinued = true
        }
        try {
            val operation = entered.await()
            caller.cancel()
            service.retireContactSyncOperations()
            release.complete(Unit)
            assertFailsWith<CancellationException> { caller.await() }
            operation.join()
            assertTrue(caller.isCancelled && operation.isCancelled)
            assertFalse(outerContinued)
            assertTrue(currentCoroutineContext().job.isActive)
        } finally {
            release.complete(Unit)
            caller.cancel()
            caller.join()
        }
    }

    private companion object {
        const val VALUE = "completed command"
    }
}
