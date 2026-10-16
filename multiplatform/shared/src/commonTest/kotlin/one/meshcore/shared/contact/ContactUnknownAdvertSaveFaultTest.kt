package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed unknown-contact save is logged and the advert is not announced. Cancellation is not
 * logged as a save failure. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactUnknownAdvertSaveFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledUnknownAdvertSaveIsNotLogged() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val trace = mutableListOf<DebugLogEntryDto>()
        val discovery = RecordingContactDiscoveryNotifier()
        val seeded = seeded(stopped, CANCEL_KEY_BYTE, logs, trace, discovery)

        val thrown = assertFailsWith<CancellationException> {
            seeded.service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertNull(seeded.store.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE)))
        assertTrue(discovery.notified.isEmpty())
        assertTrue(logs.none { it.message.contains(SAVE_FAILED) || it.message.contains(STAMP_FAILED) })
        assertTrue(trace.none { it.message.contains(SAVE_TRACE) })
    }

    @Test
    fun aFailedUnknownAdvertSaveIsLoggedAndNotAnnounced() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val trace = mutableListOf<DebugLogEntryDto>()
        val discovery = RecordingContactDiscoveryNotifier()
        val seeded = seeded(UnknownAdvertSaveFailure(), FAIL_KEY_BYTE, logs, trace, discovery)

        assertFalse(seeded.service.handleAdvertisement(radioID, publicKey(FAIL_KEY_BYTE)))

        assertNull(seeded.store.fetchContact(radioID, publicKey(FAIL_KEY_BYTE)))
        assertTrue(discovery.notified.isEmpty())
        val line = logs.single { it.message.contains(SAVE_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(saveFailedLine(FAILURE_TEXT), line.message)
        assertEquals(
            saveTraceLine(publicKey(FAIL_KEY_BYTE), FAILURE_TEXT),
            trace.single { it.message.contains(SAVE_TRACE) }.message,
        )
        assertTrue(logs.none { it.message.contains(STAMP_FAILED) })
    }

    @Test
    fun anUnknownAdvertSaveErrorStaysLoggedAndUnannounced() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val trace = mutableListOf<DebugLogEntryDto>()
        val discovery = RecordingContactDiscoveryNotifier()
        val seeded = seeded(UnknownAdvertSaveProbeError(), ERROR_KEY_BYTE, logs, trace, discovery)

        assertFalse(seeded.service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE)))

        assertNull(seeded.store.fetchContact(radioID, publicKey(ERROR_KEY_BYTE)))
        assertTrue(discovery.notified.isEmpty())
        assertEquals(
            saveFailedLine(PROBE_TEXT),
            logs.single { it.message.contains(SAVE_FAILED) }.message,
        )
        assertTrue(logs.none { it.message.contains(STAMP_FAILED) })
        assertEquals(
            saveTraceLine(publicKey(ERROR_KEY_BYTE), PROBE_TEXT),
            trace.single { it.message.contains(SAVE_TRACE) }.message,
        )
    }

    private fun TestScope.seeded(
        saveError: Throwable,
        keyByte: Int,
        logs: MutableList<DebugLogEntryDto>,
        trace: MutableList<DebugLogEntryDto>,
        discovery: RecordingContactDiscoveryNotifier,
    ): SeededUnknownAdvertSave {
        val inner = InMemoryContactStore()
        val store = UnknownAdvertSaveFaultStore(inner, saveError)
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = keyByte, name = CONTACT_NAME)
        }
        val service = testContactService(
            session = session,
            store = store,
            discoveryNotifier = discovery,
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
        return SeededUnknownAdvertSave(store, service)
    }
}

private const val CANCEL_KEY_BYTE = 0x67
private const val FAIL_KEY_BYTE = 0x68
private const val ERROR_KEY_BYTE = 0x69
private const val CONTACT_NAME = "Fetched"
private const val SAVE_FAILED = "Failed to save fetched contact"
private const val SAVE_TRACE = "B2 0x80 saveContact FAILED"
private const val STAMP_FAILED = "Advert lastHeard stamp failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun saveFailedLine(detail: String): String = "$SAVE_FAILED: $detail"

private fun saveTraceLine(publicKey: ByteArray, detail: String): String =
    "$SAVE_TRACE key=${publicKey.toUppercaseHex()}: $detail"

private data class SeededUnknownAdvertSave(val store: UnknownAdvertSaveFaultStore, val service: ContactService)

private class UnknownAdvertSaveFaultStore(private val inner: InMemoryContactStore, private val saveError: Throwable) :
    ContactStore by inner {
    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
        if (frame.publicKey.isEmpty()) return inner.saveContact(radioID, frame)
        throw saveError
    }
}

private class UnknownAdvertSaveFailure : Exception("disk")

private class UnknownAdvertSaveProbeError : Error("probe")
