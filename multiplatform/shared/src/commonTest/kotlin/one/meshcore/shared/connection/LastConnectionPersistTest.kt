package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LastConnectionPersistTest {
    @Test
    fun persistFaultRecordsDumpAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        applyLastConnectionPersist(
            persist = { error("datastore locked") },
            logger = connectionManagerLogger(this, recorded),
            failLine = FAILED_PERSIST_DISCONNECT_DIAGNOSTIC,
        )
        val line = recorded.single { it.message.contains(FAILED_PERSIST_DISCONNECT_DIAGNOSTIC) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("ConnectionManager", line.category)
        assertTrue(line.message.contains("datastore locked"))
    }

    @Test
    fun connectionIntentAndClearPrefixesArePinned() {
        assertEquals("Failed to persist connection intent", FAILED_PERSIST_CONNECTION_INTENT)
        assertEquals("Failed to clear last connection", FAILED_CLEAR_LAST_CONNECTION)
    }

    @Test
    fun cancellationRethrows() = runTest {
        val cancelled = CancellationException("torn down")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val thrown = assertFailsWith<CancellationException> {
            applyLastConnectionPersist(
                persist = { throw cancelled },
                logger = connectionManagerLogger(this@runTest, recorded),
                failLine = FAILED_PERSIST_BOND_VERIFICATION,
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aLastConnectionErrorIsLoggedAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        applyLastConnectionPersist(
            persist = { throw LastConnectionProbeError() },
            logger = connectionManagerLogger(this, recorded),
            failLine = FAILED_PERSIST_LAST_CONNECTION,
        )
        val line = recorded.single { it.message.contains(FAILED_PERSIST_LAST_CONNECTION) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun anEmptyLastConnectionIsForgotten() = runTest {
        val forgotten = readLastConnectionForgotten(readDeviceId = { null }, warning = { _ -> })
        assertTrue(forgotten)
    }

    @Test
    fun aStoredLastConnectionIsRemembered() = runTest {
        val stored = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        val forgotten = readLastConnectionForgotten(readDeviceId = { stored }, warning = { _ -> })
        assertFalse(forgotten)
    }

    @Test
    fun aForgottenReadExceptionStaysRemembered() = runTest {
        val warnings = mutableListOf<String>()
        val forgotten = readLastConnectionForgotten(
            readDeviceId = { error("datastore locked") },
            warning = warnings::add,
        )
        assertFalse(forgotten)
        assertEquals("$FAILED_READ_LAST_CONNECTION: datastore locked", warnings.single())
    }

    @Test
    fun aForgottenReadErrorStaysRemembered() = runTest {
        val warnings = mutableListOf<String>()
        val forgotten = readLastConnectionForgotten(
            readDeviceId = { throw LastConnectionProbeError() },
            warning = warnings::add,
        )
        assertFalse(forgotten)
        assertEquals("$FAILED_READ_LAST_CONNECTION: probe", warnings.single())
    }

    @Test
    fun aCancelledForgottenReadIsRethrown() = runTest {
        val cancelled = CancellationException("torn down")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            readLastConnectionForgotten(readDeviceId = { throw cancelled }, warning = warnings::add)
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aLastDeviceIdReadReturnsTheStoredId() = runTest {
        val stored = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        val fallback = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        val read = readLastConnectedDeviceId(readDeviceId = { stored }, warning = { _ -> }, fallback = fallback)
        assertEquals(stored, read)
    }

    @Test
    fun aMissingLastDeviceIdStaysMissing() = runTest {
        val fallback = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        val read = readLastConnectedDeviceId(readDeviceId = { null }, warning = { _ -> }, fallback = fallback)
        assertEquals(null, read)
    }

    @Test
    fun aLastDeviceIdReadExceptionUsesTheDeletedRow() = runTest {
        val fallback = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        val warnings = mutableListOf<String>()
        val read = readLastConnectedDeviceId(
            readDeviceId = { error("datastore locked") },
            warning = warnings::add,
            fallback = fallback,
        )
        assertEquals(fallback, read)
        assertEquals("$FAILED_READ_LAST_CONNECTION: datastore locked", warnings.single())
    }

    @Test
    fun aLastDeviceIdReadErrorUsesTheDeletedRow() = runTest {
        val fallback = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        val warnings = mutableListOf<String>()
        val read = readLastConnectedDeviceId(
            readDeviceId = { throw LastConnectionProbeError() },
            warning = warnings::add,
            fallback = fallback,
        )
        assertEquals(fallback, read)
        assertEquals("$FAILED_READ_LAST_CONNECTION: probe", warnings.single())
    }

    @Test
    fun aCancelledLastDeviceIdReadIsRethrown() = runTest {
        val cancelled = CancellationException("torn down")
        val fallback = Uuid.normalized("22222222-2222-2222-2222-222222222222")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            readLastConnectedDeviceId(
                readDeviceId = { throw cancelled },
                warning = warnings::add,
                fallback = fallback,
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aStoredRadioIdIsReturned() = runTest {
        val stored = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        val warnings = mutableListOf<String>()
        val read = readLastConnectionRadioId(readRadioId = { stored }, warning = warnings::add)
        assertEquals(stored, read)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aMissingRadioIdStaysMissing() = runTest {
        val warnings = mutableListOf<String>()
        val read = readLastConnectionRadioId(readRadioId = { null }, warning = warnings::add)
        assertEquals(null, read)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aRadioIdReadExceptionSkips() = runTest {
        val warnings = mutableListOf<String>()
        val read = readLastConnectionRadioId(
            readRadioId = { error("datastore locked") },
            warning = warnings::add,
        )
        assertEquals(null, read)
        assertEquals("$FAILED_READ_LAST_CONNECTION: datastore locked", warnings.single())
    }

    @Test
    fun aRadioIdReadErrorSkips() = runTest {
        val warnings = mutableListOf<String>()
        val read = readLastConnectionRadioId(
            readRadioId = { throw LastConnectionProbeError() },
            warning = warnings::add,
        )
        assertEquals(null, read)
        assertEquals("$FAILED_READ_LAST_CONNECTION: probe", warnings.single())
    }

    @Test
    fun aCancelledRadioIdReadIsRethrown() = runTest {
        val cancelled = CancellationException("torn down")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            readLastConnectionRadioId(readRadioId = { throw cancelled }, warning = warnings::add)
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }
}

private class LastConnectionProbeError : Error("probe")
