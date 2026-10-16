package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.NodeStatusSnapshotDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed telemetry fetch still falls through to demo samples. Cancellation and an error leave the history.
 */
class TelemetryHistorySnapshotsTest {
    private class HistoryProbeError : Error("probe")

    @Test
    fun aFetchFaultUsesDemoSamplesWhenDemoIsOn() = runTest {
        val shown = telemetryHistorySnapshots(
            fetch = { error("db locked") },
            demoEnabled = { true },
            demoSnapshots = { listOf(snapshot(9)) },
        )

        assertEquals(9L, shown.single().packetsSent)
    }

    @Test
    fun aFetchFaultStaysEmptyWhenDemoIsOff() = runTest {
        var builtDemo = false
        val shown = telemetryHistorySnapshots(
            fetch = { error("db locked") },
            demoEnabled = { false },
            demoSnapshots = {
                builtDemo = true
                listOf(snapshot(9))
            },
        )

        assertTrue(shown.isEmpty())
        assertFalse(builtDemo)
    }

    @Test
    fun aCancelledFetchPropagatesWithoutDemo() = runTest {
        var builtDemo = false
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            telemetryHistorySnapshots(
                fetch = { throw cancelled },
                demoEnabled = { true },
                demoSnapshots = {
                    builtDemo = true
                    listOf(snapshot(9))
                },
            )
        }

        assertSame(cancelled, thrown)
        assertFalse(builtDemo)
    }

    @Test
    fun aFetchErrorPropagatesWithoutDemo() = runTest {
        var builtDemo = false
        val fault = HistoryProbeError()
        val thrown = assertFailsWith<HistoryProbeError> {
            telemetryHistorySnapshots(
                fetch = { throw fault },
                demoEnabled = { true },
                demoSnapshots = {
                    builtDemo = true
                    listOf(snapshot(9))
                },
            )
        }

        assertSame(fault, thrown)
        assertFalse(builtDemo)
    }

    @Test
    fun aLiveHistorySkipsDemo() = runTest {
        var readDemo = false
        val shown = telemetryHistorySnapshots(
            fetch = { listOf(snapshot(7)) },
            demoEnabled = {
                readDemo = true
                true
            },
            demoSnapshots = { listOf(snapshot(9)) },
        )

        assertEquals(7L, shown.single().packetsSent)
        assertFalse(readDemo)
    }

    @Test
    fun aMissingServiceUsesDemoSamples() = runTest {
        val shown = telemetryHistorySnapshots(
            fetch = { null },
            demoEnabled = { true },
            demoSnapshots = { listOf(snapshot(9)) },
        )

        assertEquals(9L, shown.single().packetsSent)
    }

    @Test
    fun anEmptyHistoryStaysEmptyWhenDemoIsOff() = runTest {
        val shown = telemetryHistorySnapshots(
            fetch = { emptyList() },
            demoEnabled = { false },
            demoSnapshots = { listOf(snapshot(9)) },
        )

        assertTrue(shown.isEmpty())
    }

    private fun snapshot(packets: Long) = NodeStatusSnapshotDto(
        id = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
        timestamp = 1.0,
        nodePublicKey = byteArrayOf(1),
        packetsSent = packets,
    )
}
