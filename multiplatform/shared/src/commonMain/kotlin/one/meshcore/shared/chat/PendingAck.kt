package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Tracks the pending ACKs for a single outgoing direct message across retry attempts. The firmware
 * hashes the attempt index into the expected-ACK CRC, so one logical message can produce multiple
 * [ackCodes] (one per attempt); all attempts share one entry so a late ACK from any attempt still marks
 * the message delivered. ACK codes are held as their little-endian uint32 form (see
 * [ByteArray.ackCodeLeUInt32]) for value-equal set membership. DM-only: channel broadcasts have no ACK.
 * Mirrors the Swift `PendingAck`.
 */
data class PendingAck(
    val messageID: Uuid,
    val contactID: Uuid,
    val ackCodes: Set<Long>,
    val sentAtEpochSeconds: Double,
    /**
     * The latest attempt's ACK wait, derived from the firmware's suggested timeout. `checkExpiredAcks`
     * reads it as the per-attempt floor on the give-up deadline so a slow round-trip is not failed while
     * still in flight.
     */
    val timeoutSeconds: Double,
    val isDelivered: Boolean = false,
)
