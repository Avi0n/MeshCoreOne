package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class RunSyncPhaseTest {
    @Test
    fun aCancelledSyncPhasePropagates() = runTest {
        val cancelled = CancellationException("stopped")
        var reported = false
        val thrown = assertFailsWith<CancellationException> {
            runSyncPhase(
                onFailure = {
                    reported = true
                    null
                },
            ) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertFalse(reported)
    }

    @Test
    fun aSyncPhaseSuccessSkipsTheReport() = runTest {
        var reported = false
        val value = runSyncPhase(
            onFailure = {
                reported = true
                null
            },
        ) { "synced" }
        assertEquals("synced", value)
        assertFalse(reported)
    }

    @Test
    fun aFailedContactsPhaseLogsTheIosLineAndReturnsNull() = runTest {
        val warnings = mutableListOf<String>()
        val value = runSyncPhase(
            onFailure = { error ->
                warnings += syncPhaseFailLine(PHASE_CONTACTS, error.message ?: error.toString())
                null
            },
        ) { error("radio down") }
        assertEquals(null, value)
        assertEquals(listOf("[Sync] State → .failed: radio down"), warnings)
    }

    @Test
    fun aFailedResyncAttemptLogsAndReturnsFalse() = runTest {
        val warnings = mutableListOf<String>()
        val value = runSyncPhase(
            onFailure = { error ->
                warnings += resyncFailedLine(error.message ?: error.toString())
                false
            },
        ) { error("radio down") }
        assertEquals(false, value)
        assertEquals(listOf("Resync failed: radio down"), warnings)
    }

    @Test
    fun aSyncPhaseErrorIsReportedAndBecomesNull() = runTest {
        val probe = SyncPhaseProbeError()
        val warnings = mutableListOf<String>()
        val value = runSyncPhase(
            onFailure = { error ->
                warnings += syncPhaseFailLine(PHASE_MESSAGES, error.message ?: error.toString())
                null
            },
        ) { throw probe }
        assertEquals(null, value)
        assertEquals(listOf("[Sync] Message polling failed after contacts/channels: probe"), warnings)
    }
}

private class SyncPhaseProbeError : Error("probe")
