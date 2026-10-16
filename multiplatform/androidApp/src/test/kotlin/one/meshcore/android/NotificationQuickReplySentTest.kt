package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NotificationQuickReplySentTest {
    @Test
    fun aCancelledSendPropagates() = runBlocking {
        val cancelled = CancellationException("stopped")
        try {
            notificationQuickReplySent { throw cancelled }
            fail("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
    }

    @Test
    fun aSendFailureIsNotSent() = runBlocking {
        assertFalse(notificationQuickReplySent { error("radio down") })
    }

    @Test
    fun anAcceptedSendStaysAccepted() = runBlocking {
        assertTrue(notificationQuickReplySent { true })
        assertFalse(notificationQuickReplySent { false })
    }

    @Test
    fun aSendErrorPropagates() = runBlocking {
        val probe = QuickReplyProbeError()
        try {
            notificationQuickReplySent { throw probe }
            fail("expected error")
        } catch (thrown: QuickReplyProbeError) {
            assertEquals(probe, thrown)
        }
    }

    private class QuickReplyProbeError : Error("probe")
}
