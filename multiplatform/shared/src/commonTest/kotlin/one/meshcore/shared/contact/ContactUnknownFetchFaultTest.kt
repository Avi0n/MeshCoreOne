package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed unknown-contact fetch is logged and rethrown as a session error. Cancellation is not
 * logged. A thrown error from the radio is wrapped, logged, and rethrown on that same path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactUnknownFetchFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledUnknownContactFetchIsNotLogged() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val trace = mutableListOf<DebugLogEntryDto>()
        val service = service(stopped, logs, trace)

        val thrown = assertFailsWith<CancellationException> {
            service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertTrue(logs.none { it.message.contains(FETCH_FAILED) })
        assertTrue(trace.none { it.message.contains(FETCH_THREW) })
    }

    @Test
    fun aFailedUnknownContactFetchIsLoggedAndRethrown() = runTest(UnconfinedTestDispatcher()) {
        val failure = UnknownFetchFailure()
        val logs = mutableListOf<DebugLogEntryDto>()
        val trace = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryContactStore()
        val service = service(failure, logs, trace, store)

        val thrown = assertFailsWith<ContactServiceError.SessionError> {
            service.handleAdvertisement(radioID, publicKey(FAIL_KEY_BYTE))
        }

        assertSame(failure, thrown.cause)
        assertEquals(FAILURE_TEXT, thrown.message)
        assertNull(store.fetchContact(radioID, publicKey(FAIL_KEY_BYTE)))
        val line = logs.single { it.message.contains(FETCH_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(fetchFailedLine(FAILURE_TEXT), line.message)
        assertEquals(
            fetchThrewLine(publicKey(FAIL_KEY_BYTE), FAILURE_TEXT),
            trace.single { it.message.contains(FETCH_THREW) }.message,
        )
    }

    @Test
    fun anUnknownContactFetchErrorIsLoggedAsASessionError() = runTest(UnconfinedTestDispatcher()) {
        val probe = UnknownFetchProbeError()
        val logs = mutableListOf<DebugLogEntryDto>()
        val trace = mutableListOf<DebugLogEntryDto>()
        val service = service(probe, logs, trace)

        val thrown = assertFailsWith<ContactServiceError.SessionError> {
            service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE))
        }

        assertSame(probe, thrown.cause)
        assertEquals(fetchFailedLine(PROBE_TEXT), logs.single { it.message.contains(FETCH_FAILED) }.message)
        assertEquals(
            fetchThrewLine(publicKey(ERROR_KEY_BYTE), PROBE_TEXT),
            trace.single { it.message.contains(FETCH_THREW) }.message,
        )
    }

    private fun TestScope.service(
        failure: Throwable,
        logs: MutableList<DebugLogEntryDto>,
        trace: MutableList<DebugLogEntryDto>,
        store: InMemoryContactStore = InMemoryContactStore(),
    ): ContactService = testContactService(
        session = FakeContactSession().apply { onGetContact = { throw failure } },
        store = store,
        logger = PersistentLogger(
            scope = this,
            category = "AdvertisementService",
            record = { logs += it },
        ),
        discoverTrace = PersistentLogger(
            scope = this,
            category = "discover-trace",
            record = { trace += it },
        ),
    )
}

private const val CANCEL_KEY_BYTE = 0x5E
private const val FAIL_KEY_BYTE = 0x5F
private const val ERROR_KEY_BYTE = 0x60
private const val FETCH_FAILED = "Failed to fetch contact"
private const val FETCH_THREW = "B2 getContact THREW"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun fetchFailedLine(detail: String): String = "$FETCH_FAILED: $detail"

private fun fetchThrewLine(publicKey: ByteArray, detail: String): String =
    "$FETCH_THREW key=${publicKey.toUppercaseHex()}: $detail"

private class UnknownFetchFailure : Exception("disk")

private class UnknownFetchProbeError : Error("probe")
