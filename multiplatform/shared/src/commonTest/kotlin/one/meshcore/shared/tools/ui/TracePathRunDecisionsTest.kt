package one.meshcore.shared.tools.ui

import one.meshcore.shared.trace.TraceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracePathRunDecisionsTest {
    @Test
    fun aSuccessfulRunHasNoFailureMessage() {
        assertNull(traceRunFailureMessage(listOf(trace(success = true)), ALL_FAILED, FAILED))
    }

    @Test
    fun oneFailedRunKeepsItsOwnMessage() {
        val failed = trace(success = false, errorMessage = "timeout")
        assertEquals("timeout", traceRunFailureMessage(listOf(failed), ALL_FAILED, FAILED))
    }

    @Test
    fun oneFailedRunWithoutAMessageUsesTheFallback() {
        val failed = trace(success = false, errorMessage = null)
        assertEquals(FAILED, traceRunFailureMessage(listOf(failed), ALL_FAILED, FAILED))
    }

    @Test
    fun aBatchWhereEveryRunFailedUsesTheCount() {
        val failed = trace(success = false, errorMessage = "timeout")
        assertEquals(
            "all 2 failed",
            traceRunFailureMessage(listOf(failed, failed), ALL_FAILED, FAILED),
        )
    }

    @Test
    fun aSuccessfulBatchOpensTheListOnlyOffTheMap() {
        val outcomes = listOf(trace(success = false), trace(success = true))
        assertTrue(traceBatchShouldOpenList(outcomes, mapMode = false))
        assertFalse(traceBatchShouldOpenList(outcomes, mapMode = true))
        assertFalse(traceBatchShouldOpenList(listOf(trace(success = false)), mapMode = false))
    }

    @Test
    fun theInlineResultHidesOnlyWhileTheSheetShowsThisPath() {
        assertFalse(
            traceListShowsInlineResult(
                showingSheet = true,
                resultSucceeded = true,
                resultMatchesPath = true,
            ),
        )
        assertTrue(
            traceListShowsInlineResult(
                showingSheet = true,
                resultSucceeded = false,
                resultMatchesPath = true,
            ),
        )
        assertTrue(
            traceListShowsInlineResult(
                showingSheet = false,
                resultSucceeded = true,
                resultMatchesPath = true,
            ),
        )
        assertTrue(
            traceListShowsInlineResult(
                showingSheet = true,
                resultSucceeded = true,
                resultMatchesPath = false,
            ),
        )
    }

    private fun trace(success: Boolean, errorMessage: String? = null) = TraceResult(
        hops = emptyList(),
        durationMs = 0,
        success = success,
        errorMessage = errorMessage,
        tracedPathBytes = byteArrayOf(1),
        hashSize = 1,
    )

    private companion object {
        const val ALL_FAILED = "all %d failed"
        const val FAILED = "failed"
    }
}
