package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomChannelWriterCancellationTest {
    @Test
    fun saveQueuedCancellation() = checkWriter(RoomChannelWriterCase.SAVE, queued = true, cancel = true)

    @Test
    fun saveAdmittedCancellation() = checkWriter(RoomChannelWriterCase.SAVE, queued = false, cancel = true)

    @Test
    fun saveLive() = checkWriter(RoomChannelWriterCase.SAVE, queued = false, cancel = false)

    @Test
    fun batchQueuedCancellation() = checkWriter(RoomChannelWriterCase.BATCH, queued = true, cancel = true)

    @Test
    fun batchAdmittedCancellation() = checkWriter(RoomChannelWriterCase.BATCH, queued = false, cancel = true)

    @Test
    fun batchLive() = checkWriter(RoomChannelWriterCase.BATCH, queued = false, cancel = false)

    @Test
    fun deleteQueuedCancellation() = checkWriter(RoomChannelWriterCase.DELETE, queued = true, cancel = true)

    @Test
    fun deleteAdmittedCancellation() = checkWriter(RoomChannelWriterCase.DELETE, queued = false, cancel = true)

    @Test
    fun deleteLive() = checkWriter(RoomChannelWriterCase.DELETE, queued = false, cancel = false)

    @Test
    fun messagesQueuedCancellation() = checkWriter(RoomChannelWriterCase.MESSAGES, queued = true, cancel = true)

    @Test
    fun messagesAdmittedCancellation() = checkWriter(RoomChannelWriterCase.MESSAGES, queued = false, cancel = true)

    @Test
    fun messagesLive() = checkWriter(RoomChannelWriterCase.MESSAGES, queued = false, cancel = false)

    private fun checkWriter(kind: RoomChannelWriterCase, queued: Boolean, cancel: Boolean) {
        val fixture = RoomChannelWriterFixture(kind, queued)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomChannelWriterFixture.TIMEOUT_MS) {
                    fixture.rows.seed()
                    val before = fixture.rows.snapshot()
                    fixture.start()
                    if (cancel) fixture.cancelOrigin()
                    val cause = fixture.releaseAndJoin()
                    if (cancel) assertTrue(cause is CancellationException) else assertEquals(null, cause)
                    assertEquals(!queued, fixture.mutationObserved)
                    assertEquals(
                        if (cancel) before else fixture.rows.expected(kind),
                        fixture.rows.snapshot(),
                        "LAST: originating writer cancellation or lawful live policy",
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }
}
