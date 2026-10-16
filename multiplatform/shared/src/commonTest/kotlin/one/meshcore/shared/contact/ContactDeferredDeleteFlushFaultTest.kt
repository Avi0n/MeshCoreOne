package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed deferred-delete flush is logged and the keys are queued again. Cancellation leaves the
 * queue clear and does not log a flush failure. A thrown error stays on that logged path. A key
 * revived by an advert during the flush is not queued again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactDeferredDeleteFlushFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledDeferredDeleteFlushLeavesTheQueueClear() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val cleanup = RecordingCleanupHandler()
        val seeded = seededStore(stopped)
        val service = service(seeded, logs, cleanup)

        assertFalse(service.handleDeviceContactDeleted(radioID, publicKey(CANCEL_KEY_BYTE)))
        val thrown = assertFailsWith<CancellationException> {
            service.flushPendingDeletedKeys()
        }

        assertSame(stopped, thrown)
        assertNotNull(seeded.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE)))
        assertFalse(service.hasPendingDeletedKey(publicKey(CANCEL_KEY_BYTE)))
        assertTrue(cleanup.invocations.isEmpty())
        assertTrue(logs.none { it.message.contains(FLUSH_FAILED) || it.message.contains(APPLYING) })
    }

    @Test
    fun aFailedDeferredDeleteFlushRequeuesTheKey() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val cleanup = RecordingCleanupHandler()
        val seeded = seededStore(DeferredDeleteFlushFailure(), FAIL_KEY_BYTE)
        val service = service(seeded, logs, cleanup)

        assertFalse(service.handleDeviceContactDeleted(radioID, publicKey(FAIL_KEY_BYTE)))
        service.flushPendingDeletedKeys()

        assertNotNull(seeded.fetchContact(radioID, publicKey(FAIL_KEY_BYTE)))
        assertTrue(service.hasPendingDeletedKey(publicKey(FAIL_KEY_BYTE)))
        assertTrue(cleanup.invocations.isEmpty())
        val line = logs.single { it.message.contains(FLUSH_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(flushFailedLine(FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(APPLYING) })
    }

    @Test
    fun aDeferredDeleteFlushErrorStaysLoggedAndRequeues() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val cleanup = RecordingCleanupHandler()
        val seeded = seededStore(DeferredDeleteFlushProbeError(), ERROR_KEY_BYTE)
        val service = service(seeded, logs, cleanup)

        assertFalse(service.handleDeviceContactDeleted(radioID, publicKey(ERROR_KEY_BYTE)))
        service.flushPendingDeletedKeys()

        assertNotNull(seeded.fetchContact(radioID, publicKey(ERROR_KEY_BYTE)))
        assertTrue(service.hasPendingDeletedKey(publicKey(ERROR_KEY_BYTE)))
        assertEquals(flushFailedLine(PROBE_TEXT), logs.single { it.message.contains(FLUSH_FAILED) }.message)
        assertTrue(logs.none { it.message.contains(APPLYING) })
        assertTrue(cleanup.invocations.isEmpty())
    }

    @Test
    fun aRevivedAdvertIsNotRequeuedWhenTheDeferredFlushFails() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val cleanup = RecordingCleanupHandler()
        val inner = InMemoryContactStore()
        val key = publicKey(REVIVED_KEY_BYTE)
        inner.saveContact(radioID, contactFrame(REVIVED_KEY_BYTE, name = "Revived"))
        lateinit var service: ContactService
        val store = DeferredDeleteFlushFaultStore(inner, DeferredDeleteFlushFailure()) {
            service.handleAdvertisement(radioID, key)
        }
        service = service(store, logs, cleanup)

        assertFalse(service.handleDeviceContactDeleted(radioID, key))
        service.flushPendingDeletedKeys()

        assertNotNull(store.fetchContact(radioID, key))
        assertFalse(service.hasPendingDeletedKey(key))
        assertTrue(service.hasPendingAdvertKey(key))
        assertEquals(flushFailedLine(FAILURE_TEXT), logs.single { it.message.contains(FLUSH_FAILED) }.message)
        assertTrue(cleanup.invocations.isEmpty())
    }

    private suspend fun seededStore(
        deleteError: Throwable,
        keyByte: Int = CANCEL_KEY_BYTE,
    ): DeferredDeleteFlushFaultStore {
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(keyByte, name = "Queued"))
        return DeferredDeleteFlushFaultStore(inner, deleteError)
    }

    private fun TestScope.service(
        store: ContactStore,
        logs: MutableList<DebugLogEntryDto>,
        cleanup: RecordingCleanupHandler,
    ): ContactService = testContactService(
        store = store,
        cleanup = cleanup,
        isInForeground = { false },
        logger = PersistentLogger(
            scope = this,
            category = "ContactService",
            record = { logs += it },
        ),
    )
}

private const val CANCEL_KEY_BYTE = 0x40
private const val FAIL_KEY_BYTE = 0x41
private const val ERROR_KEY_BYTE = 0x42
private const val REVIVED_KEY_BYTE = 0x43
private const val FLUSH_FAILED = "Overwrite oldest: deferred delete flush failed"
private const val APPLYING = "Overwrite oldest: applying"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun flushFailedLine(detail: String): String = "$FLUSH_FAILED: $detail"

private class DeferredDeleteFlushFaultStore(
    private val inner: InMemoryContactStore,
    private val deleteError: Throwable,
    private val beforeThrow: suspend () -> Unit = {},
) : ContactStore by inner {
    override suspend fun deleteContacts(
        radioID: Uuid,
        publicKeys: Collection<ByteArray>,
        skippingPublicKeys: suspend () -> Set<String>,
    ): List<Uuid> {
        if (publicKeys.isEmpty()) {
            return inner.deleteContacts(radioID, publicKeys, skippingPublicKeys)
        }
        beforeThrow()
        skippingPublicKeys()
        throw deleteError
    }
}

private class DeferredDeleteFlushFailure : Exception("disk")

private class DeferredDeleteFlushProbeError : Error("probe")
