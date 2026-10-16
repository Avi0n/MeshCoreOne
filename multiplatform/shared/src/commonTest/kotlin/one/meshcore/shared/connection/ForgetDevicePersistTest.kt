package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ForgetDevicePersistTest {
    @Test
    fun demoteFaultRecordsIosDumpAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        applyForgetDevicePersist(
            deleteData = false,
            persist = { error("db locked") },
            logger = connectionManagerLogger(this, recorded),
        )
        val line = recorded.single { it.message.contains(FAILED_DEMOTE_DEVICE) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("ConnectionManager", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun deleteFaultRecordsIosDumpAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        applyForgetDevicePersist(
            deleteData = true,
            persist = { error("db locked") },
            logger = connectionManagerLogger(this, recorded),
        )
        val line = recorded.single { it.message.contains(FAILED_DELETE_DEVICE_DATA) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun aCancelledPersistIsRethrown() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            applyForgetDevicePersist(
                deleteData = false,
                persist = { throw cancelled },
                logger = PersistentLogger(this@runTest, category = "ConnectionManager", record = { _ -> }),
            )
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aPersistErrorIsLoggedAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        applyForgetDevicePersist(
            deleteData = true,
            persist = { throw ForgetPersistProbeError() },
            logger = connectionManagerLogger(this, recorded),
        )
        val line = recorded.single { it.message.contains(FAILED_DELETE_DEVICE_DATA) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun aCancelledForgetDisconnectIsRethrown() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            disconnectBeforeForget(warnings::add) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aForgetDisconnectFailureIsLoggedAndReturns() = runTest {
        val warnings = mutableListOf<String>()
        disconnectBeforeForget(warnings::add) { error("link down") }
        assertEquals(listOf("$FORGET_DISCONNECT_FAILED: link down"), warnings)
        assertEquals("Forget disconnect failed", FORGET_DISCONNECT_FAILED)
    }

    @Test
    fun aForgetDisconnectErrorIsLoggedAndReturns() = runTest {
        val warnings = mutableListOf<String>()
        disconnectBeforeForget(warnings::add) { throw ForgetPersistProbeError() }
        assertEquals(listOf("$FORGET_DISCONNECT_FAILED: probe"), warnings)
    }
}

private class ForgetPersistProbeError : Error("probe")
