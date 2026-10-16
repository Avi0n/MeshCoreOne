package one.meshcore.shared.fixtures

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestScopeShutdownTest {
    @Test
    fun waitsForChildCleanupBeforeReturning() {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.Default)
        val cleanupStarted = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cleanupFinished = CountDownLatch(1)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.countDown()
                    assertTrue(releaseCleanup.await(DEADLINE_SECONDS, TimeUnit.SECONDS))
                    cleanupFinished.countDown()
                }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        val teardown = executor.submit { scope.cancelAndJoinBlocking() }
        try {
            assertTrue(cleanupStarted.await(DEADLINE_SECONDS, TimeUnit.SECONDS))
            assertFailsWith<TimeoutException> {
                teardown.get(PENDING_CHECK_MILLISECONDS, TimeUnit.MILLISECONDS)
            }
            releaseCleanup.countDown()
            teardown.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            assertTrue(job.isCompleted)
            assertTrue(cleanupFinished.count == 0L)
        } finally {
            releaseCleanup.countDown()
            job.cancel()
            runBlocking { job.join() }
            executor.shutdownNow()
        }
    }
}

private const val DEADLINE_SECONDS = 10L
private const val PENDING_CHECK_MILLISECONDS = 100L
