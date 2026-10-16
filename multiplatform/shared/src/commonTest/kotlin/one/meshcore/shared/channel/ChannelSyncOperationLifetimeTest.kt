package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSyncOperationLifetimeTest {
    @Test
    fun pipelinedRejectsRetiredAdmission() = checkAdmission(ChannelSyncOperationCase.PIPELINED)

    @Test
    fun pipelinedRetiresCompletedRead() = checkRetirement(ChannelSyncOperationCase.PIPELINED)

    @Test
    fun pipelinedReusesAfterCallerCancellation() = checkCallerCancellation(ChannelSyncOperationCase.PIPELINED)

    @Test
    fun pipelinedSuppressesPublicationAfterCommit() = checkPostCommit(ChannelSyncOperationCase.PIPELINED)

    @Test
    fun pipelinedSuppressesCacheAfterHandlerRetires() = checkHandlerReturn(ChannelSyncOperationCase.PIPELINED)

    @Test
    fun pipelinedLiveCompletion() = checkLive(ChannelSyncOperationCase.PIPELINED)

    @Test
    fun serialRejectsRetiredAdmission() = checkAdmission(ChannelSyncOperationCase.SERIAL)

    @Test
    fun serialRetiresCompletedRead() = checkRetirement(ChannelSyncOperationCase.SERIAL)

    @Test
    fun serialReusesAfterCallerCancellation() = checkCallerCancellation(ChannelSyncOperationCase.SERIAL)

    @Test
    fun serialSuppressesPublicationAfterCommit() = checkPostCommit(ChannelSyncOperationCase.SERIAL)

    @Test
    fun serialSuppressesCacheAfterHandlerRetires() = checkHandlerReturn(ChannelSyncOperationCase.SERIAL)

    @Test
    fun serialLiveCompletion() = checkLive(ChannelSyncOperationCase.SERIAL)

    @Test
    fun retryRejectsRetiredAdmission() = checkAdmission(ChannelSyncOperationCase.RETRY)

    @Test
    fun retryRetiresCompletedRead() = checkRetirement(ChannelSyncOperationCase.RETRY)

    @Test
    fun retryReusesAfterCallerCancellation() = checkCallerCancellation(ChannelSyncOperationCase.RETRY)

    @Test
    fun retrySuppressesPublicationAfterCommit() = checkPostCommit(ChannelSyncOperationCase.RETRY)

    @Test
    fun retrySuppressesCacheAfterHandlerRetires() = checkHandlerReturn(ChannelSyncOperationCase.RETRY)

    @Test
    fun retryLiveCompletion() = checkLive(ChannelSyncOperationCase.RETRY)

    @Test
    fun retryRejectsEmptyAdmissionAfterRetirement() = runTest(UnconfinedTestDispatcher()) {
        val fixture = ChannelSyncOperationFixture(ChannelSyncOperationCase.RETRY)
        fixture.seed()
        fixture.service.retireChannelSyncOperations()
        val cause = runCatching {
            fixture.service.retryFailedChannels(ChannelTestIds.RADIO, emptyList())
        }.exceptionOrNull()
        assertTrue(cause is CancellationException)
        assertTrue(currentCoroutineContext().job.isActive)
        fixture.assertNoCommands()
        fixture.assertBefore()
    }

    private fun checkAdmission(kind: ChannelSyncOperationCase) = runTest(UnconfinedTestDispatcher()) {
        val fixture = ChannelSyncOperationFixture(kind)
        fixture.seed()
        fixture.service.retireChannelSyncOperations()
        val cause = runCatching { fixture.run() }.exceptionOrNull()
        assertTrue(cause is CancellationException)
        assertTrue(currentCoroutineContext().job.isActive)
        fixture.assertNoCommands()
        fixture.assertBefore()
    }

    private fun checkRetirement(kind: ChannelSyncOperationCase) = runTest(UnconfinedTestDispatcher()) {
        val fixture = ChannelSyncOperationFixture(kind)
        fixture.seed()
        fixture.armRead()
        val caller = async {
            val result = runCatching { fixture.run() }
            assertTrue(currentCoroutineContext().job.isActive)
            result
        }
        try {
            val operation = fixture.readEntered.await()
            assertNotSame(caller, operation)
            assertTrue(caller.isActive && operation.isActive)
            fixture.service.retireChannelSyncOperations()
            fixture.releaseRead()
            val result = caller.await()
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertTrue(operation.isCancelled && operation.isCompleted)
            assertFalse(caller.isCancelled)
            assertTrue(currentCoroutineContext().job.isActive)
            assertTrue(fixture.forwarded in 0..1)
            fixture.assertBefore()
        } finally {
            fixture.releaseRead()
            fixture.service.retireChannelSyncOperations()
            caller.join()
        }
    }

    private fun checkCallerCancellation(kind: ChannelSyncOperationCase) = runTest(UnconfinedTestDispatcher()) {
        val fixture = ChannelSyncOperationFixture(kind)
        fixture.seed()
        fixture.armRead()
        val caller = async { fixture.run() }
        try {
            val operation = fixture.readEntered.await()
            assertNotSame(caller, operation)
            assertTrue(caller.isActive && operation.isActive)
            caller.cancel(CancellationException("Actual originating caller cancellation"))
            caller.join()
            fixture.releaseRead()
            assertTrue(runCatching { caller.await() }.exceptionOrNull() is CancellationException)
            assertTrue(caller.isCancelled && operation.isCancelled && operation.isCompleted)
            assertTrue(currentCoroutineContext().job.isActive)
            assertTrue(fixture.forwarded in 0..1)
            fixture.assertBefore()
            val live = fixture.run()
            assertEquals(1, live.channelsSynced)
            assertTrue(live.isComplete)
            fixture.assertCommitted(handlerCalls = 1, cacheCalls = 1)
        } finally {
            fixture.releaseRead()
            fixture.service.retireChannelSyncOperations()
            caller.join()
        }
    }

    private fun checkPostCommit(kind: ChannelSyncOperationCase) = runTest(UnconfinedTestDispatcher()) {
        val fixture = ChannelSyncOperationFixture(kind)
        fixture.seed()
        fixture.retireAfterStore = true
        val cause = runCatching { fixture.run() }.exceptionOrNull()
        assertTrue(cause is CancellationException)
        assertTrue(currentCoroutineContext().job.isActive)
        fixture.assertCommitted(handlerCalls = 0, cacheCalls = 0)
    }

    private fun checkHandlerReturn(kind: ChannelSyncOperationCase) = runTest(UnconfinedTestDispatcher()) {
        val fixture = ChannelSyncOperationFixture(kind)
        fixture.seed()
        fixture.retireInHandler = true
        val caller = async {
            val result = runCatching { fixture.run() }
            assertTrue(currentCoroutineContext().job.isActive)
            result
        }
        try {
            val operation = fixture.handlerEntered.await()
            assertNotSame(caller, operation)
            assertTrue(caller.isActive && operation.isActive)
            fixture.assertCommitted(handlerCalls = 1, cacheCalls = 0)
            fixture.releaseHandler()
            val cause = caller.await().exceptionOrNull()
            assertTrue(cause is CancellationException)
            assertTrue(operation.isCancelled && operation.isCompleted)
            assertFalse(caller.isCancelled)
            assertTrue(currentCoroutineContext().job.isActive)
            fixture.assertCommitted(handlerCalls = 1, cacheCalls = 0)
        } finally {
            fixture.releaseHandler()
            fixture.service.retireChannelSyncOperations()
            caller.join()
        }
    }

    private fun checkLive(kind: ChannelSyncOperationCase) = runTest(UnconfinedTestDispatcher()) {
        val fixture = ChannelSyncOperationFixture(kind)
        fixture.seed()
        fixture.armRead()
        val caller = async { fixture.run() }
        try {
            val operation = fixture.readEntered.await()
            assertNotSame(caller, operation)
            assertTrue(caller.isActive && operation.isActive)
            fixture.releaseRead()
            val result = caller.await()
            assertEquals(1, result.channelsSynced)
            assertTrue(result.isComplete)
            assertTrue(operation.isCompleted && !operation.isCancelled)
            assertTrue(currentCoroutineContext().job.isActive)
            assertEquals(1, fixture.forwarded)
            fixture.assertCommitted(handlerCalls = 1, cacheCalls = 1)
        } finally {
            fixture.releaseRead()
            fixture.service.retireChannelSyncOperations()
            caller.join()
        }
    }
}
