package one.meshcore.shared.backup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackupImportCallerCancellationTest {
    @Test
    fun queuedCallerCancellationLeavesAllFamiliesUnchanged() = checkImport(queued = true, cancel = true)

    @Test
    fun admittedCallerCancellationRollsBackAllFamilies() = checkImport(queued = false, cancel = true)

    @Test
    fun admittedLiveImportCommitsAllFamilies() = checkImport(queued = false, cancel = false)

    private fun checkImport(queued: Boolean, cancel: Boolean) {
        val fixture = BackupImportCancellationFixture(queued)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(BackupImportCancellationFixture.TIMEOUT_MS) {
                    val before = fixture.rows.seed()
                    fixture.start()
                    if (cancel) fixture.cancelOrigin()
                    val cause = fixture.releaseAndJoin()
                    if (cancel) assertTrue(cause is CancellationException) else assertEquals(null, cause)
                    assertEquals(!queued, fixture.mutationObserved)
                    if (cancel) {
                        assertEquals(
                            before,
                            fixture.rows.snapshot(),
                            "LAST: genuine caller cancellation must roll back every stored field",
                        )
                        if (!queued) {
                            fixture.retry()
                            fixture.rows.assertLive(before)
                        }
                    } else {
                        fixture.rows.assertLive(before)
                    }
                }
            }
        } finally {
            fixture.close()
        }
    }
}
