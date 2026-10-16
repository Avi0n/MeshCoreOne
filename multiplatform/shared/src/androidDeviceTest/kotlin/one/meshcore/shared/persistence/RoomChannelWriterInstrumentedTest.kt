package one.meshcore.shared.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomChannelWriterInstrumentedTest {
    @get:Rule val testName = TestName()

    @Test
    fun saveQueuedCancellation() = checkWriter(RoomChannelWriterNativeCase.SAVE, queued = true, cancel = true)

    @Test
    fun saveAdmittedCancellation() = checkWriter(RoomChannelWriterNativeCase.SAVE, queued = false, cancel = true)

    @Test
    fun saveLive() = checkWriter(RoomChannelWriterNativeCase.SAVE, queued = false, cancel = false)

    @Test
    fun batchQueuedCancellation() = checkWriter(RoomChannelWriterNativeCase.BATCH, queued = true, cancel = true)

    @Test
    fun batchAdmittedCancellation() = checkWriter(RoomChannelWriterNativeCase.BATCH, queued = false, cancel = true)

    @Test
    fun batchLive() = checkWriter(RoomChannelWriterNativeCase.BATCH, queued = false, cancel = false)

    @Test
    fun deleteQueuedCancellation() = checkWriter(RoomChannelWriterNativeCase.DELETE, queued = true, cancel = true)

    @Test
    fun deleteAdmittedCancellation() = checkWriter(RoomChannelWriterNativeCase.DELETE, queued = false, cancel = true)

    @Test
    fun deleteLive() = checkWriter(RoomChannelWriterNativeCase.DELETE, queued = false, cancel = false)

    @Test
    fun messagesQueuedCancellation() = checkWriter(RoomChannelWriterNativeCase.MESSAGES, queued = true, cancel = true)

    @Test
    fun messagesAdmittedCancellation() =
        checkWriter(RoomChannelWriterNativeCase.MESSAGES, queued = false, cancel = true)

    @Test
    fun messagesLive() = checkWriter(RoomChannelWriterNativeCase.MESSAGES, queued = false, cancel = false)

    private fun checkWriter(kind: RoomChannelWriterNativeCase, queued: Boolean, cancel: Boolean) {
        val fixture = RoomChannelWriterNativeFixture(kind, queued)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomChannelWriterNativeFixture.TIMEOUT_MS) {
                    fixture.rows.seed()
                    val before = fixture.rows.snapshot()
                    fixture.start()
                    if (cancel) fixture.cancelOrigin()
                    val cause = fixture.releaseAndJoin()
                    if (cancel) assertTrue(cause is CancellationException) else assertEquals(null, cause)
                    assertEquals(!queued, fixture.mutationObserved)
                    val expected = if (cancel) before else fixture.rows.expected(kind)
                    val actual = fixture.rows.snapshot()
                    fixture.record(testName.methodName, expected == actual, true)
                    assertEquals("LAST: originating writer cancellation or lawful live policy", expected, actual)
                }
            }
        } finally {
            fixture.close(testName.methodName)
        }
    }
}
