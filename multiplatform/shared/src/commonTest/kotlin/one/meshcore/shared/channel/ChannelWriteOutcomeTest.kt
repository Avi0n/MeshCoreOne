package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelWriteOutcomeTest {
    @Test
    fun aSuccessfulWriteReadsTheSavedRow() = runTest {
        val store = CountingChannelStore()
        val radioId = ChannelTestIds.RADIO
        val dto = channelDto(id = ChannelTestIds.fresh(), radioID = radioId, index = SAVED_SLOT, name = "Ops")
        store.seed(dto)
        var ran = false

        val outcome = testChannelService(store = store).runChannelWrite(radioId, SAVED_SLOT) {
            ran = true
        }

        val written = outcome as ChannelWriteOutcome.Written
        assertTrue(ran)
        assertEquals(1, store.fetches)
        assertEquals(dto.id, written.channel?.id)
        assertEquals("Ops", written.channel?.name)
    }

    @Test
    fun aMissingReadBackStillCountsAsWritten() = runTest {
        val store = CountingChannelStore()

        val outcome = testChannelService(store = store).runChannelWrite(ChannelTestIds.RADIO, SAVED_SLOT) {}

        assertNull((outcome as ChannelWriteOutcome.Written).channel)
        assertEquals(1, store.fetches)
    }

    @Test
    fun aChannelServiceErrorStaysThatError() = runTest {
        val store = CountingChannelStore()
        val notConnected = ChannelServiceError.NotConnected()

        val outcome = testChannelService(store = store).runChannelWrite(ChannelTestIds.RADIO, SAVED_SLOT) {
            throw notConnected
        }

        val failed = outcome as ChannelWriteOutcome.Failed
        assertSame(notConnected, (failed.error as ChannelActionError.Service).error)
        assertEquals(0, store.fetches)
    }

    @Test
    fun anotherExceptionBecomesASessionError() = runTest {
        val store = CountingChannelStore()
        val failure = IllegalStateException("write failed")

        val outcome = testChannelService(store = store).runChannelWrite(ChannelTestIds.RADIO, SAVED_SLOT) {
            throw failure
        }

        val serviceError = ((outcome as ChannelWriteOutcome.Failed).error as ChannelActionError.Service).error
        assertTrue(serviceError is ChannelServiceError.SessionError)
        assertSame(failure, serviceError.cause)
        assertEquals(0, store.fetches)
    }

    @Test
    fun anErrorBecomesAFailedWrite() = runTest {
        val store = CountingChannelStore()
        val fault = ChannelWriteProbeFault()

        val outcome = testChannelService(store = store).runChannelWrite(ChannelTestIds.RADIO, SAVED_SLOT) {
            throw fault
        }

        val serviceError = ((outcome as ChannelWriteOutcome.Failed).error as ChannelActionError.Service).error
        assertSame(fault, serviceError.cause)
        assertEquals(0, store.fetches)
    }

    @Test
    fun aReadBackErrorIsFailedAfterTheWriteRuns() = runTest {
        val store = CountingChannelStore()
        val fault = ChannelWriteProbeFault()
        store.fetchFailure = fault
        var ran = false

        val outcome = testChannelService(store = store).runChannelWrite(ChannelTestIds.RADIO, SAVED_SLOT) {
            ran = true
        }

        val serviceError = ((outcome as ChannelWriteOutcome.Failed).error as ChannelActionError.Service).error
        assertTrue(ran)
        assertSame(fault, serviceError.cause)
        assertEquals(1, store.fetches)
    }

    @Test
    fun aCancelledWriteRethrowsBeforeTheReadBack() = runTest {
        val store = CountingChannelStore()
        val cancelled = CancellationException("superseded")

        val thrown = assertFailsWith<CancellationException> {
            testChannelService(store = store).runChannelWrite(ChannelTestIds.RADIO, SAVED_SLOT) {
                throw cancelled
            }
        }

        assertSame(cancelled, thrown)
        assertEquals(0, store.fetches)
    }
}

private class CountingChannelStore(private val backing: InMemoryChannelStore = InMemoryChannelStore()) :
    ChannelStore by backing {
    var fetches: Int = 0
    var fetchFailure: Throwable? = null

    override suspend fun fetchChannel(radioID: Uuid, index: Int): ChannelDto? {
        fetches += 1
        fetchFailure?.let { throw it }
        return backing.fetchChannel(radioID, index)
    }

    suspend fun seed(dto: ChannelDto) = backing.seedChannel(dto)
}

private class ChannelWriteProbeFault : Error("probe")

private const val SAVED_SLOT = 2
