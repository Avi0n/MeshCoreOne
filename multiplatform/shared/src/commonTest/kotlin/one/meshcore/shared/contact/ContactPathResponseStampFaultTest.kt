package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed path-response lastHeard stamp is logged and the radio write still runs. Cancellation
 * aborts before that write. A thrown error stays on the logged stamp path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactPathResponseStampFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledPathStampAbortsBeforeTheContactRefresh() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val store = seededStore(stopped)
        val service = service(store, logs) { contactChanges += 1 }
        val key = publicKey(KEY_BYTE)

        val thrown = assertFailsWith<CancellationException> {
            service.applyPathDiscoveryResponse(radioID, pathFor(key))
        }

        assertSame(stopped, thrown)
        val updated = store.fetchContact(radioID, key)!!
        assertEquals(0, updated.outPathLength)
        assertTrue(updated.outPath.isEmpty())
        assertEquals(PRIOR_HEARD, updated.lastHeardTimestamp)
        assertEquals(0, contactChanges)
        assertTrue(logs.none { it.message.contains(PATH_STAMP_FAILED) })
    }

    @Test
    fun aFailedPathStampKeepsTheSavedPath() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val store = seededStore(PathStampFailure())
        val service = service(store, logs) { contactChanges += 1 }
        val key = publicKey(KEY_BYTE)

        assertTrue(service.applyPathDiscoveryResponse(radioID, pathFor(key)))

        val updated = store.fetchContact(radioID, key)!!
        assertEquals(OUT_PATH_LENGTH, updated.outPathLength)
        assertTrue(updated.outPath.contentEquals(OUT_PATH))
        assertEquals(PRIOR_HEARD, updated.lastHeardTimestamp)
        assertEquals(1, contactChanges)
        val line = logs.single { it.message.contains(PATH_STAMP_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(pathStampFailedLine(FAILURE_TEXT), line.message)
    }

    @Test
    fun aPathStampErrorStaysLoggedAndThePathStands() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        var contactChanges = 0
        val store = seededStore(PathStampProbeError())
        val service = service(store, logs) { contactChanges += 1 }
        val key = publicKey(KEY_BYTE)

        assertTrue(service.applyPathDiscoveryResponse(radioID, pathFor(key)))

        val updated = store.fetchContact(radioID, key)!!
        assertEquals(OUT_PATH_LENGTH, updated.outPathLength)
        assertEquals(PRIOR_HEARD, updated.lastHeardTimestamp)
        assertEquals(1, contactChanges)
        assertEquals(
            pathStampFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(PATH_STAMP_FAILED) }.message,
        )
    }

    private suspend fun seededStore(touchError: Throwable): HeardStampFaultStore {
        val store = HeardStampFaultStore(InMemoryContactStore())
        store.saveContact(
            radioID,
            contactFrame(KEY_BYTE, name = "Far").copy(lastModified = PRIOR_MODIFIED),
        )
        val saved = store.fetchContact(radioID, publicKey(KEY_BYTE))!!
        store.saveContact(saved.copy(lastHeardTimestamp = PRIOR_HEARD))
        store.touchError = touchError
        return store
    }

    private fun pathFor(key: ByteArray): PathInfo = PathInfo(
        publicKeyPrefix = key.copyOfRange(0, PREFIX_LENGTH),
        outPathLength = OUT_PATH_LENGTH,
        outPath = OUT_PATH,
        inPathLength = 0,
        inPath = ByteArray(0),
    )

    private fun TestScope.service(
        store: HeardStampFaultStore,
        logs: MutableList<DebugLogEntryDto>,
        onContactsChanged: suspend () -> Unit,
    ): ContactService = testContactService(
        store = store,
        syncCoordinator = CallbackContactSyncCoordinator(onContactsChanged = onContactsChanged),
        logger = PersistentLogger(
            scope = this,
            category = "ContactService",
            record = { logs += it },
        ),
    )
}

private const val KEY_BYTE = 0x36
private const val PREFIX_LENGTH = 6
private const val OUT_PATH_LENGTH = 3
private const val PRIOR_HEARD = 1L
private const val PRIOR_MODIFIED = 1_234L
private val OUT_PATH = byteArrayOf(0x10, 0x20, 0x30)
private const val PATH_STAMP_FAILED = "Path response lastHeard stamp failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun pathStampFailedLine(detail: String): String = "$PATH_STAMP_FAILED: $detail"

private class PathStampFailure : Exception("disk")

private class PathStampProbeError : Error("probe")
