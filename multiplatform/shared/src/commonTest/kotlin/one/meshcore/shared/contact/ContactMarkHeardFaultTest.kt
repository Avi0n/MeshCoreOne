package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed ping lastHeard stamp is logged and markHeard returns. Cancellation aborts the stamp.
 * A thrown error stays logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactMarkHeardFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledPingStampAbortsMarkHeard() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val store = seededStore(stopped)
        val service = loggedContactService(store, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.markHeard(radioID, publicKey(KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(PRIOR_HEARD, store.fetchContact(radioID, publicKey(KEY_BYTE))?.lastHeardTimestamp)
        assertTrue(logs.none { it.message.contains(PING_STAMP_FAILED) })
    }

    @Test
    fun aFailedPingStampIsLoggedAndMarkHeardReturns() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val store = seededStore(MarkHeardStampFailure())
        val service = loggedContactService(store, logs)

        service.markHeard(radioID, publicKey(KEY_BYTE))

        assertEquals(PRIOR_HEARD, store.fetchContact(radioID, publicKey(KEY_BYTE))?.lastHeardTimestamp)
        val line = logs.single { it.message.contains(PING_STAMP_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(pingStampFailedLine(FAILURE_TEXT), line.message)
    }

    @Test
    fun aPingStampErrorStaysLogged() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val store = seededStore(MarkHeardProbeError())
        val service = loggedContactService(store, logs)

        service.markHeard(radioID, publicKey(KEY_BYTE))

        assertEquals(PRIOR_HEARD, store.fetchContact(radioID, publicKey(KEY_BYTE))?.lastHeardTimestamp)
        assertEquals(
            pingStampFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(PING_STAMP_FAILED) }.message,
        )
    }

    private suspend fun seededStore(touchError: Throwable): HeardStampFaultStore {
        val store = HeardStampFaultStore(InMemoryContactStore())
        store.saveContact(
            radioID,
            contactFrame(KEY_BYTE, name = "Pinged").copy(lastAdvertTimestamp = 50, lastModified = 50),
        )
        val saved = store.fetchContact(radioID, publicKey(KEY_BYTE))!!
        store.saveContact(saved.copy(lastHeardTimestamp = PRIOR_HEARD))
        store.touchError = touchError
        return store
    }
}

private const val KEY_BYTE = 0x34
private const val PRIOR_HEARD = 1L
private const val PING_STAMP_FAILED = "Ping lastHeard stamp failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun pingStampFailedLine(detail: String): String = "$PING_STAMP_FAILED: $detail"

private class MarkHeardStampFailure : Exception("disk")

private class MarkHeardProbeError : Error("probe")
