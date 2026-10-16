package one.meshcore.shared.room.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomInfoPersistTest {
    @Test
    fun aSuccessfulWriteReportsTrue() = runTest {
        assertTrue(persistRoomInfoChange { })
    }

    @Test
    fun aPersistFaultReportsFalse() = runTest {
        assertFalse(persistRoomInfoChange { error("db locked") })
    }

    @Test
    fun aDisconnectedRadioDoesNotPersist() = runTest {
        var ran = false
        assertFalse(persistRoomInfoChange(isRadioReady = false) { ran = true })
        assertFalse(ran)
    }

    @Test
    fun aCancelledWritePropagates() = runTest {
        val cancelled = CancellationException("superseded")
        assertSame(cancelled, assertFailsWith<CancellationException> { persistRoomInfoChange { throw cancelled } })
    }

    @Test
    fun aWriteErrorPropagates() = runTest {
        val probe = RoomInfoProbeError()
        assertSame(probe, assertFailsWith<RoomInfoProbeError> { persistRoomInfoChange { throw probe } })
    }
}

private class RoomInfoProbeError : Error("probe")
