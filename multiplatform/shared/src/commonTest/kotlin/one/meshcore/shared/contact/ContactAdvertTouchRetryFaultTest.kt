package one.meshcore.shared.contact

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

/**
 * The second known-contact hear is logged and the advert is dropped when it fails. Cancellation on
 * that retry is rethrown and is not logged as a retry failure. A thrown error stays on that logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactAdvertTouchRetryFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledAdvertHearRetryIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(AdvertTouchRetryFailure(), stopped, CANCEL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(2, seeded.store.touches)
        assertEquals(1L, seeded.inner.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE))?.lastHeardTimestamp)
        assertEquals(retryingLine(FAILURE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
        assertTrue(logs.none { it.message.contains(RETRY_FAILED) })
    }

    @Test
    fun aFailedAdvertHearRetryDropsTheAdvert() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val failure = AdvertTouchRetryFailure()
        val seeded = seeded(failure, failure, FAIL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertFalse(service.handleAdvertisement(radioID, publicKey(FAIL_KEY_BYTE)))

        assertEquals(2, seeded.store.touches)
        assertEquals(1L, seeded.inner.fetchContact(radioID, publicKey(FAIL_KEY_BYTE))?.lastHeardTimestamp)
        val line = logs.single { it.message.contains(RETRY_FAILED) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(retryFailedLine(FAILURE_TEXT), line.message)
        assertEquals(retryingLine(FAILURE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
    }

    @Test
    fun anAdvertHearRetryErrorStaysLoggedAndDropsTheAdvert() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val probe = AdvertTouchRetryProbeError()
        val seeded = seeded(probe, probe, ERROR_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertFalse(service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE)))

        assertEquals(2, seeded.store.touches)
        assertEquals(1L, seeded.inner.fetchContact(radioID, publicKey(ERROR_KEY_BYTE))?.lastHeardTimestamp)
        assertEquals(retryFailedLine(PROBE_TEXT), logs.single { it.message.contains(RETRY_FAILED) }.message)
        assertEquals(retryingLine(PROBE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
    }

    private suspend fun seeded(firstError: Throwable, retryError: Throwable, keyByte: Int): SeededAdvertTouch {
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(keyByte, name = "Known"))
        val saved = inner.fetchContact(radioID, publicKey(keyByte))!!.copy(lastHeardTimestamp = 1L)
        inner.saveContact(saved)
        return SeededAdvertTouch(inner, AdvertTouchRetryFaultStore(inner, firstError, retryError))
    }
}

private const val CANCEL_KEY_BYTE = 0x58
private const val FAIL_KEY_BYTE = 0x59
private const val ERROR_KEY_BYTE = 0x5A
private const val RETRYING = "Error handling advert event (retrying once)"
private const val RETRY_FAILED = "Advert touch retry failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun retryingLine(detail: String): String = "$RETRYING: $detail"

private fun retryFailedLine(detail: String): String = "$RETRY_FAILED: $detail"

private data class SeededAdvertTouch(val inner: InMemoryContactStore, val store: AdvertTouchRetryFaultStore)

private class AdvertTouchRetryFaultStore(
    private val inner: InMemoryContactStore,
    private val firstError: Throwable,
    private val retryError: Throwable,
) : ContactStore by inner {
    var touches = 0

    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        touches += 1
        val error = when (touches) {
            1 -> firstError
            2 -> retryError
            else -> return inner.touchContactHeard(radioID, publicKey, atEpochSeconds)
        }
        throw error
    }
}

private class AdvertTouchRetryFailure : Exception("disk")

private class AdvertTouchRetryProbeError : Error("probe")
