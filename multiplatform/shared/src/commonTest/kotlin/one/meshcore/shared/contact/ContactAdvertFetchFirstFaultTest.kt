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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The first advert lookup retries once after an ordinary failure. Cancellation on that first lookup
 * is rethrown and does not log a retry. A thrown error stays on the retry path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactAdvertFetchFirstFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledFirstAdvertLookupDoesNotRetry() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(stopped, CANCEL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        val thrown = assertFailsWith<CancellationException> {
            service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(1, seeded.store.fetches)
        assertEquals(1L, seeded.inner.fetchContact(radioID, publicKey(CANCEL_KEY_BYTE))?.lastHeardTimestamp)
        assertTrue(logs.none { it.message.contains(RETRYING) || it.message.contains(RETRY_FAILED) })
    }

    @Test
    fun aFailedFirstAdvertLookupRetriesAndHears() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(AdvertFetchFirstFailure(), FAIL_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertTrue(service.handleAdvertisement(radioID, publicKey(FAIL_KEY_BYTE)))

        assertEquals(2, seeded.store.fetches)
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
    fun anAdvertLookupErrorRetriesAndHears() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val seeded = seeded(AdvertFetchFirstProbeError(), ERROR_KEY_BYTE)
        val service = loggedAdvertService(seeded.store, logs)

        assertTrue(service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE)))

        assertEquals(2, seeded.store.fetches)
        assertEquals(
            CONTACT_TEST_NOW_SECONDS,
            seeded.inner.fetchContact(radioID, publicKey(ERROR_KEY_BYTE))?.lastHeardTimestamp,
        )
        assertEquals(retryingLine(PROBE_TEXT), logs.single { it.message.contains(RETRYING) }.message)
        assertTrue(logs.none { it.message.contains(RETRY_FAILED) })
    }

    private suspend fun seeded(firstError: Throwable, keyByte: Int): SeededAdvertFetchFirst {
        val inner = InMemoryContactStore()
        inner.saveContact(radioID, contactFrame(keyByte, name = "Known"))
        val saved = inner.fetchContact(radioID, publicKey(keyByte))!!.copy(lastHeardTimestamp = 1L)
        inner.saveContact(saved)
        return SeededAdvertFetchFirst(inner, AdvertFetchFirstFaultStore(inner, firstError))
    }
}

private const val CANCEL_KEY_BYTE = 0x4D
private const val FAIL_KEY_BYTE = 0x4E
private const val ERROR_KEY_BYTE = 0x4F
private const val RETRYING = "Error handling advert event (retrying once)"
private const val RETRY_FAILED = "Advert touch retry failed"
private const val FAILURE_TEXT = "disk"
private const val PROBE_TEXT = "probe"

private fun retryingLine(detail: String): String = "$RETRYING: $detail"

private data class SeededAdvertFetchFirst(val inner: InMemoryContactStore, val store: AdvertFetchFirstFaultStore)

private class AdvertFetchFirstFaultStore(private val inner: InMemoryContactStore, private val firstError: Throwable) :
    ContactStore by inner {
    var fetches = 0

    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? {
        fetches += 1
        if (fetches == 1) throw firstError
        return inner.fetchContact(radioID, publicKey)
    }
}

private class AdvertFetchFirstFailure : Exception("disk")

private class AdvertFetchFirstProbeError : Error("probe")
