package one.meshcore.shared.tools.ui

import one.meshcore.shared.trace.TraceHop
import one.meshcore.shared.trace.TraceResult
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The per-hop signal reading during and after a batch. While the batch is still going the row has to show
 * the run that just landed; averaging mid-batch hides the swing the operator started the batch to watch.
 */
class TraceHopSignalTest {
    private fun hop(snr: Double) = TraceHop(
        hashBytes = byteArrayOf(0x11),
        resolvedName = "Alpha",
        snr = snr,
        isStartNode = false,
        isEndNode = false,
    )

    private fun run(snr: Double, success: Boolean = true) = TraceResult(
        hops = listOf(
            TraceHop(null, "Me", 0.0, isStartNode = true, isEndNode = false),
            hop(snr),
        ),
        durationMs = 100,
        success = success,
        errorMessage = null,
        tracedPathBytes = byteArrayOf(0x11),
        hashSize = 1,
    )

    private val batch = listOf(run(10.0), run(-4.0))

    @Test
    fun aRunningBatchReadsTheLatestRun() {
        assertEquals(
            -4.0,
            traceHopSignalSnr(hop(10.0), index = 1, batchResults = batch, isBatchInProgress = true),
            "the row follows the run that just landed",
        )
    }

    @Test
    fun aCompletedBatchReadsTheAverage() {
        assertEquals(
            3.0,
            traceHopSignalSnr(hop(10.0), index = 1, batchResults = batch, isBatchInProgress = false),
        )
    }

    @Test
    fun aSingleRunReadsItsOwnSnr() {
        assertEquals(
            10.0,
            traceHopSignalSnr(hop(10.0), index = 1, batchResults = listOf(run(10.0)), isBatchInProgress = false),
        )
    }

    @Test
    fun anAllFailedBatchFallsBackToTheHopsOwnSnr() {
        val failures = listOf(run(0.0, success = false), run(0.0, success = false))
        assertEquals(
            7.5,
            traceHopSignalSnr(hop(7.5), index = 1, batchResults = failures, isBatchInProgress = true),
        )
    }
}
