package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
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
 * The second advert lookup is logged and reported as a failed touch when it fails. Cancellation on
 * that retry is rethrown and is not logged as a retry failure. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactAdvertFetchRetryFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledAdvertLookupRetryIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(AdvertFetchRetryFailure(), stopped, CANCEL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(2, seeded.store.fetches)
        assertNotNull(seeded.inner.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE)))
        assertEquals(retryingLine(FAILURE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
        assertTrue(logs.none { it.message.contains(RETRY_FAILED) })
    }

    @Test
    fun aFailedAdvertLookupRetryReportsTheTouchFailed() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val failure = AdvertFetchRetryFailure()
        val seeded = seeded(failure, failure, FAIL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertFalse(service.handleAdvertisement(radioID, publicKey(FAIL_KEY_BYTE)))

        assertEquals(2, seeded.store.fetches)
        assertNotNull(seeded.inner.fetchContact(radioID, publicKey(FAIL_KEY_BYTE)))
        val line = logs.single { it.message.contains(RETRY_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(retryFailedLine(FAILURE_TEXT), line.message)
        assertEquals(retryingLine(FAILURE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
    }

    @Test
    fun anAdvertLookupRetryErrorStaysLoggedAsAFailedTouch() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val probe = AdvertFetchRetryProbeError()
        val seeded = seeded(probe, probe, ERROR_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertFalse(service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE)))

        assertEquals(2, seeded.store.fetches)
        assertEquals(retryFailedLine(PROBE_TEXT), logs.single { it.message.contains(RETRY_FAILED) }.message)
        assertEquals(retryingLine(PROBE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
    }

    private suspend fun seeded(firstError: Throwable, retryError: Throwable, keyByte: Int): SeededAdvertFetch {
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(keyByte, name = "Known"))
        return SeededAdvertFetch(inner, AdvertFetchRetryFaultStore(inner, firstError, retryError))
    }
}

private const val CANCEL_KEY_BYTE = 0x4A
private const val FAIL_KEY_BYTE = 0x4B
private const val ERROR_KEY_BYTE = 0x4C
private const val RETRYING = "Error handling advert event (retrying once)"
private const val RETRY_FAILED = "Advert touch retry failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun retryingLine(detail: String): String = "$RETRYING: $detail"

private fun retryFailedLine(detail: String): String = "$RETRY_FAILED: $detail"

private data class SeededAdvertFetch(val inner: InMemoryContactStore, val store: AdvertFetchRetryFaultStore)

private class AdvertFetchRetryFaultStore(
    private val inner: InMemoryContactStore,
    private val firstError: Throwable,
    private val retryError: Throwable,
) : ContactStore by inner {
    var fetches = 0

    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
        fetches += 1
        val error = when (fetches) {
            1 -> firstError
            2 -> retryError
            else -> return inner.fetchContact(radioID, publicKey)
        }
        throw error
    }
}

private class AdvertFetchRetryFailure : Exception("disk")

private class AdvertFetchRetryProbeError : Error("probe")
