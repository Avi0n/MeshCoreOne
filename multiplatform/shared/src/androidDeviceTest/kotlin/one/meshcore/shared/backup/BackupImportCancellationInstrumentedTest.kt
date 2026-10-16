package one.meshcore.shared.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class BackupImportCancellationInstrumentedTest {
    @get:org.junit.Rule val testName = org.junit.rules.TestName()

    @Test
    fun queuedCallerCancellationLeavesAllFamiliesUnchanged() = checkImport(queued = true, cancel = true)

    @Test
    fun admittedCallerCancellationRollsBackAllFamilies() = checkImport(queued = false, cancel = true)

    @Test
    fun admittedLiveImportCommitsAllFamilies() = checkImport(queued = false, cancel = false)

    private fun checkImport(queued: Boolean, cancel: Boolean) {
        val fixture = BackupImportNativeFixture(queued)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(BackupImportNativeFixture.TIMEOUT_MS) {
                    val before = fixture.rows.seed()
                    fixture.start()
                    if (cancel) fixture.cancelOrigin()
                    val cause = fixture.releaseAndJoin()
                    if (cancel) assertTrue(cause is CancellationException) else assertEquals(null, cause)
                    assertEquals(!queued, fixture.mutationObserved)
                    if (cancel) {
                        val after = fixture.rows.snapshot()
                        fixture.record(testName.methodName, before == after)
                        assertEquals(
                            "LAST: genuine caller cancellation must roll back every stored field",
                            before,
                            after,
                        )
                        if (!queued) {
                            fixture.retry()
                            fixture.rows.assertLive(before)
                        }
                    } else {
                        fixture.rows.assertLive(before)
                        fixture.record(testName.methodName, true)
                    }
                }
            }
        } finally {
            fixture.close(testName.methodName)
        }
    }
}
