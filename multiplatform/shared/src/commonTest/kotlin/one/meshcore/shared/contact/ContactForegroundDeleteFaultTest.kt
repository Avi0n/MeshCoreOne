package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed foreground overwrite-oldest delete is logged and queued for a later flush. Cancellation
 * leaves the queue clear and does not log a delete failure. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactForegroundDeleteFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledForegroundDeleteLeavesTheQueueClear() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seededStore(stopped, CANCEL_KEY_BYTE)
        val service = loggedAdvertService(seeded, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.handleDeviceContactDeleted(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertNotNull(seeded.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE)))
        assertFalse(service.hasPendingDeletedKey(publicKey(CANCEL_KEY_BYTE)))
        assertTrue(logs.none { it.message.contains(DELETE_FAILED) || it.message.contains(CLEANUP_COMPLETE) })
    }

    @Test
    fun aFailedForegroundDeleteRequeuesTheKey() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seededStore(ForegroundDeleteFailure(), FAIL_KEY_BYTE)
        val service = loggedAdvertService(seeded, logs)

        assertFalse(service.handleDeviceContactDeleted(radioID, publicKey(FAIL_KEY_BYTE)))

        assertNotNull(seeded.fetchContact(radioID, publicKey(FAIL_KEY_BYTE)))
        assertTrue(service.hasPendingDeletedKey(publicKey(FAIL_KEY_BYTE)))
        val line = logs.single { it.message.contains(DELETE_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(deleteFailedLine(publicKey(FAIL_KEY_BYTE), FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(CLEANUP_COMPLETE) })
    }

    @Test
    fun aForegroundDeleteErrorStaysLoggedAndRequeues() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seededStore(ForegroundDeleteProbeError(), ERROR_KEY_BYTE)
        val service = loggedAdvertService(seeded, logs)

        assertFalse(service.handleDeviceContactDeleted(radioID, publicKey(ERROR_KEY_BYTE)))

        assertNotNull(seeded.fetchContact(radioID, publicKey(ERROR_KEY_BYTE)))
        assertTrue(service.hasPendingDeletedKey(publicKey(ERROR_KEY_BYTE)))
        assertEquals(
            deleteFailedLine(publicKey(ERROR_KEY_BYTE), PROBE_TEXT),
            logs.single { it.message.contains(DELETE_FAILED) }.message,
        )
        assertTrue(logs.none { it.message.contains(CLEANUP_COMPLETE) })
    }

    private suspend fun seededStore(deleteError: Throwable, keyByte: Int): DeleteContactFaultStore {
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(keyByte, name = "Victim"))
        val faultId = inner.fetchContact(radioID, publicKey(keyByte))!!.id
        return DeleteContactFaultStore(inner, faultId, deleteError)
    }
}

private const val CANCEL_KEY_BYTE = 0x47
private const val FAIL_KEY_BYTE = 0x48
private const val ERROR_KEY_BYTE = 0x49
private const val DELETE_FAILED = "Overwrite oldest: failed to delete contact"
private const val CLEANUP_COMPLETE = "cleanup complete"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"
private const val KEY_PREFIX_BYTES = 6

private fun deleteFailedLine(publicKey: ByteArray, detail: String): String {
    val prefix = publicKey.copyOfRange(0, KEY_PREFIX_BYTES).toUppercaseHex()
    return "$DELETE_FAILED $prefix...: $detail"
}

private class ForegroundDeleteFailure : Exception("disk")

private class ForegroundDeleteProbeError : Error("probe")
