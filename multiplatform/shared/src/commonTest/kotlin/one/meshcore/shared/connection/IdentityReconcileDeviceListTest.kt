package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityReconcileDeviceListTest {
    @Test
    fun aDeviceListFaultRecordsTheDumpAndReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val devices = readDevicesForReconcile(
            listAll = { error("db locked") },
            logger = connectionManagerLogger(this, recorded),
        )

        assertNull(devices)
        val line = recorded.single { it.message.contains(RECONCILE_GHOST_FAILED_PREFIX) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun aCancelledDeviceListReadStopsReconcile() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            readDevicesForReconcile(listAll = { throw cancelled }, logger = connectionManagerLogger(this, recorded))
        }

        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aDeviceListErrorIsLoggedAndReturnsNull() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val devices = readDevicesForReconcile(
            listAll = { throw ReconcileDeviceListProbeError() },
            logger = connectionManagerLogger(this, recorded),
        )

        assertNull(devices)
        val line = recorded.single { it.message.contains(RECONCILE_GHOST_FAILED_PREFIX) }
        assertTrue(line.message.contains("probe"))
    }
}

private class ReconcileDeviceListProbeError : Error("probe")
