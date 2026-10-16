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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed lastHeard stamp after an unknown-contact save is logged and the new contact is still
 * announced. Cancellation aborts before that announcement. A thrown error stays on the logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactUnknownAdvertStampFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledUnknownAdvertStampSkipsTheAnnouncement() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val discovery = RecordingContactDiscoveryNotifier()
        val seeded = seeded(stopped, CANCEL_KEY_BYTE, logs, discovery)

        val thrown = assertFailsWith<CancellationException> {
            seeded.service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(CONTACT_NAME, seeded.store.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE))?.name)
        assertTrue(discovery.notified.isEmpty())
        assertTrue(logs.none { it.message.contains(STAMP_FAILED) || it.message.contains(SAVE_FAILED) })
    }

    @Test
    fun aFailedUnknownAdvertStampStillAnnouncesTheContact() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val discovery = RecordingContactDiscoveryNotifier()
        val seeded = seeded(UnknownAdvertStampFailure(), FAIL_KEY_BYTE, logs, discovery)

        assertTrue(seeded.service.handleAdvertisement(radioID, publicKey(FAIL_KEY_BYTE)))

        assertEquals(CONTACT_NAME, seeded.store.fetchContact(radioID, publicKey(FAIL_KEY_BYTE))?.name)
        assertEquals(CONTACT_NAME, discovery.notified.single().contactName)
        val line = logs.single { it.message.contains(STAMP_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(stampFailedLine(publicKey(FAIL_KEY_BYTE), FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(SAVE_FAILED) })
    }

    @Test
    fun anUnknownAdvertStampErrorStaysLoggedAndAnnounces() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val discovery = RecordingContactDiscoveryNotifier()
        val seeded = seeded(UnknownAdvertStampProbeError(), ERROR_KEY_BYTE, logs, discovery)

        assertTrue(seeded.service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE)))

        assertEquals(CONTACT_NAME, seeded.store.fetchContact(radioID, publicKey(ERROR_KEY_BYTE))?.name)
        assertEquals(1, discovery.notified.size)
        assertEquals(
            stampFailedLine(publicKey(ERROR_KEY_BYTE), PROBE_TEXT),
            logs.single {
                it.message.contains(STAMP_FAILED)
            }.message,
        )
        assertTrue(logs.none { it.message.contains(SAVE_FAILED) })
    }

    private fun TestScope.seeded(
        stampError: Throwable,
        keyByte: Int,
        logs: MutableList<DebugLogEntryDto>,
        discovery: RecordingContactDiscoveryNotifier,
    ): SeededUnknownAdvert {
        val inner = InMemoryContactStore()
        val store = UnknownAdvertStampFaultStore(inner, stampError)
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
        )
        return SeededUnknownAdvert(store, service)
    }
}

private const val CANCEL_KEY_BYTE = 0x61
private const val FAIL_KEY_BYTE = 0x62
private const val ERROR_KEY_BYTE = 0x63
private const val CONTACT_NAME = "Fetched"
private const val STAMP_FAILED = "Advert lastHeard stamp failed"
private const val SAVE_FAILED = "Failed to save fetched contact"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun stampFailedLine(publicKey: ByteArray, detail: String): String =
    "$STAMP_FAILED key=${publicKey.toUppercaseHex()}: $detail"

private data class SeededUnknownAdvert(val store: UnknownAdvertStampFaultStore, val service: ContactService)

private class UnknownAdvertStampFaultStore(
    private val inner: InMemoryContactStore,
    private val stampError: Throwable,
) : ContactStore by inner {
    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        if (atEpochSeconds < 0L) return inner.touchContactHeard(radioID, publicKey, atEpochSeconds)
        throw stampError
    }
}

private class UnknownAdvertStampFailure : Exception("disk")

private class UnknownAdvertStampProbeError : Error("probe")
