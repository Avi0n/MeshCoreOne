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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The first known-contact hear retries once after an ordinary failure. Cancellation on that first
 * hear is rethrown and does not log a retry. A thrown error stays on the retry path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactAdvertTouchFirstFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledFirstAdvertHearDoesNotRetry() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(stopped, CANCEL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(1, seeded.store.touches)
        assertEquals(1L, seeded.inner.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE))?.lastHeardTimestamp)
        assertTrue(logs.none { it.message.contains(RETRYING) || it.message.contains(RETRY_FAILED) })
    }

    @Test
    fun aFailedFirstAdvertHearRetriesAndStamps() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(AdvertTouchFirstFailure(), FAIL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertTrue(service.handleAdvertisement(radioID, publicKey(FAIL_KEY_BYTE)))

        assertEquals(2, seeded.store.touches)
        assertEquals(
            CONTACT_TEST_NOW_SECONDS,
            seeded.inner.fetchContact(radioID, publicKey(FAIL_KEY_BYTE))?.lastHeardTimestamp,
        )
        val line = logs.single { it.message.contains(RETRYING) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals(retryingLine(FAILURE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(RETRY_FAILED) })
    }

    @Test
    fun anAdvertHearErrorRetriesAndStamps() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(AdvertTouchFirstProbeError(), ERROR_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertTrue(service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE)))

        assertEquals(2, seeded.store.touches)
        assertEquals(
            CONTACT_TEST_NOW_SECONDS,
            seeded.inner.fetchContact(radioID, publicKey(ERROR_KEY_BYTE))?.lastHeardTimestamp,
        )
        assertEquals(retryingLine(PROBE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
        assertTrue(logs.none { it.message.contains(RETRY_FAILED) })
    }

    private suspend fun seeded(firstError: Throwable, keyByte: Int): SeededAdvertTouchFirst {
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(keyByte, name = "Known"))
        val saved = inner.fetchContact(radioID, publicKey(keyByte))!!.copy(lastHeardTimestamp = 1L)
        inner.saveContact(saved)
        return SeededAdvertTouchFirst(inner, AdvertTouchFirstFaultStore(inner, firstError))
    }
}

private const val CANCEL_KEY_BYTE = 0x5B
private const val FAIL_KEY_BYTE = 0x5C
private const val ERROR_KEY_BYTE = 0x5D
private const val RETRYING = "Error handling advert event (retrying once)"
private const val RETRY_FAILED = "Advert touch retry failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun retryingLine(detail: String): String = "$RETRYING: $detail"

private data class SeededAdvertTouchFirst(val inner: InMemoryContactStore, val store: AdvertTouchFirstFaultStore)

private class AdvertTouchFirstFaultStore(private val inner: InMemoryContactStore, private val firstError: Throwable) :
    ContactStore by inner {
    var touches = 0

    override suspend fun touchContactHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        touches += 1
        if (touches == 1) throw firstError
        return inner.touchContactHeard(radioID, publicKey, atEpochSeconds)
    }
}

private class AdvertTouchFirstFailure : Exception("disk")

private class AdvertTouchFirstProbeError : Error("probe")
