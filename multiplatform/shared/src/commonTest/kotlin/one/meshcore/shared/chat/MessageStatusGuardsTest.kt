package one.meshcore.shared.chat

import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.MessageStatus.DELIVERED
import one.meshcore.shared.model.MessageStatus.FAILED
import one.meshcore.shared.model.MessageStatus.PENDING
import one.meshcore.shared.model.MessageStatus.RETRYING
import one.meshcore.shared.model.MessageStatus.SENDING
import one.meshcore.shared.model.MessageStatus.SENT
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exhaustive coverage of the W2-spec §2 status write guards. One assertion per transition/guard so a
 * spec diff maps to a failing case: the monotonic `.delivered` rule, the terminal-safe retry/clear
 * rules, the ACK terminal guard, and the userInitiated in-place rules (`.failed` -> `.pending` and the
 * `.delivered` downgrade).
 */
class MessageStatusGuardsTest {
    private fun ack(current: MessageStatus, incoming: MessageStatus) =
        MessageStatusGuards.allowsAckWrite(current, incoming)

    private fun inPlace(current: MessageStatus, incoming: MessageStatus, userInitiated: Boolean) =
        MessageStatusGuards.allowsInPlaceUpdate(current, incoming, userInitiated)

    @Test
    fun `allowsUnlessDelivered blocks only a delivered row`() {
        assertFalse(MessageStatusGuards.allowsUnlessDelivered(DELIVERED))
        for (status in MessageStatus.entries.filter { it != DELIVERED }) {
            assertTrue(MessageStatusGuards.allowsUnlessDelivered(status), "should allow over $status")
        }
    }

    @Test
    fun `allowsClearRetryingToSent blocks terminal rows only`() {
        assertFalse(MessageStatusGuards.allowsClearRetryingToSent(DELIVERED))
        assertFalse(MessageStatusGuards.allowsClearRetryingToSent(FAILED))
        for (status in listOf(PENDING, SENDING, SENT, RETRYING)) {
            assertTrue(MessageStatusGuards.allowsClearRetryingToSent(status), "should allow from $status")
        }
    }

    @Test
    fun `allowsRetryStatus blocks terminal rows only`() {
        assertFalse(MessageStatusGuards.allowsRetryStatus(DELIVERED))
        assertFalse(MessageStatusGuards.allowsRetryStatus(FAILED))
        assertTrue(MessageStatusGuards.allowsRetryStatus(PENDING))
        assertTrue(MessageStatusGuards.allowsRetryStatus(SENT))
    }

    @Test
    fun `allowsAckWrite permits the sent to delivered upgrade but no terminal change`() {
        assertTrue(ack(SENT, DELIVERED)) // the legitimate late upgrade
        assertFalse(ack(DELIVERED, SENT)) // delivered is monotonic: a later .sent is refused
        assertTrue(ack(DELIVERED, DELIVERED)) // an idempotent .delivered write is allowed
        assertFalse(ack(FAILED, DELIVERED)) // a late ACK cannot flip a just-failed row to delivered
        assertTrue(ack(FAILED, FAILED)) // an idempotent .failed write is allowed
    }

    @Test
    fun `allowsInPlaceUpdate blocks a delivered downgrade unless userInitiated`() {
        assertFalse(inPlace(DELIVERED, PENDING, userInitiated = false))
        assertFalse(inPlace(DELIVERED, SENT, userInitiated = false))
        assertTrue(inPlace(DELIVERED, PENDING, userInitiated = true))
        assertTrue(inPlace(DELIVERED, DELIVERED, userInitiated = false)) // idempotent is never blocked
    }

    @Test
    fun `allowsInPlaceUpdate blocks failed to pending unless userInitiated`() {
        assertFalse(inPlace(FAILED, PENDING, userInitiated = false))
        assertTrue(inPlace(FAILED, PENDING, userInitiated = true))
        assertTrue(inPlace(FAILED, SENT, userInitiated = false)) // only .failed -> .pending is guarded
    }

    @Test
    fun `allowsInPlaceUpdate permits ordinary forward transitions`() {
        assertTrue(inPlace(SENT, DELIVERED, userInitiated = false))
        assertTrue(inPlace(PENDING, SENT, userInitiated = false))
    }

    @Test
    fun `clearsFailureSeen only on a new failed write`() {
        assertTrue(MessageStatusGuards.clearsFailureSeen(SENT, FAILED))
        assertFalse(MessageStatusGuards.clearsFailureSeen(FAILED, FAILED))
        assertFalse(MessageStatusGuards.clearsFailureSeen(SENT, SENT))
        assertFalse(MessageStatusGuards.failureSeenAfterWrite(SENT, FAILED, previouslySeen = true))
        assertTrue(MessageStatusGuards.failureSeenAfterWrite(FAILED, FAILED, previouslySeen = true))
    }
}
