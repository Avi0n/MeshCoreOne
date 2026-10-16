package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.channel.ChannelServiceError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomChannelAcknowledgedFaultTest {
    @Test
    fun setPreAckCancellation() = checkFault(RoomChannelAcknowledgedCase.SET, beforeAck = true, cancellation = true)

    @Test
    fun setPreAckFault() = checkFault(RoomChannelAcknowledgedCase.SET, beforeAck = true, cancellation = false)

    @Test
    fun setPostAckCancellation() = checkFault(RoomChannelAcknowledgedCase.SET, beforeAck = false, cancellation = true)

    @Test
    fun setPostAckFault() = checkFault(RoomChannelAcknowledgedCase.SET, beforeAck = false, cancellation = false)

    @Test
    fun clearPresentPreAckCancellation() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_PRESENT, beforeAck = true, cancellation = true)

    @Test
    fun clearPresentPreAckFault() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_PRESENT, beforeAck = true, cancellation = false)

    @Test
    fun clearPresentPostAckCancellation() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_PRESENT, beforeAck = false, cancellation = true)

    @Test
    fun clearPresentPostAckFault() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_PRESENT, beforeAck = false, cancellation = false)

    @Test
    fun clearOrphanPreAckCancellation() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_ORPHAN, beforeAck = true, cancellation = true)

    @Test
    fun clearOrphanPreAckFault() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_ORPHAN, beforeAck = true, cancellation = false)

    @Test
    fun clearOrphanPostAckCancellation() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_ORPHAN, beforeAck = false, cancellation = true)

    @Test
    fun clearOrphanPostAckFault() =
        checkFault(RoomChannelAcknowledgedCase.CLEAR_ORPHAN, beforeAck = false, cancellation = false)

    private fun checkFault(kind: RoomChannelAcknowledgedCase, beforeAck: Boolean, cancellation: Boolean) {
        val fixture = RoomChannelAcknowledgedFixture(kind, queued = true)
        val error = if (cancellation) {
            CancellationException(
                "Synthetic fault",
            )
        } else {
            IllegalStateException("Synthetic fault")
        }
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomChannelAcknowledgedFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val before = fixture.rows.snapshot()
                    val thrown = requireNotNull(fixture.rejectCommand(error, beforeAck))
                    if (beforeAck && !cancellation) {
                        assertTrue(thrown is ChannelServiceError.SessionError)
                        assertTrue(thrown.cause === error || thrown.cause?.cause === error)
                    } else {
                        assertTrue(error.javaClass == thrown.javaClass)
                        assertTrue(thrown === error || thrown.cause === error)
                    }
                    assertEquals(before, fixture.rows.snapshot(), "LAST: fault changed local state")
                }
            }
        } finally {
            fixture.close()
        }
    }
}
