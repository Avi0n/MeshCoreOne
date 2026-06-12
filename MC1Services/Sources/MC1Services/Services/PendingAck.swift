import Foundation

/// Tracks pending ACKs for a single outgoing direct message across retry attempts.
///
/// The firmware hashes the retry attempt index into the expected-ACK CRC, so a
/// single logical message can produce multiple `ackCodes` (one per attempt).
/// All attempts for the same message share one `PendingAck` entry.
///
/// DM-only: channel/room broadcasts do not generate ACKs and are not tracked here.
struct PendingAck: Sendable {
    let messageID: UUID
    let contactID: UUID
    var ackCodes: Set<Data>
    var sentAt: Date
    var timeout: TimeInterval
    var isDelivered: Bool = false

    init(
        messageID: UUID,
        contactID: UUID,
        ackCodes: Set<Data>,
        sentAt: Date,
        timeout: TimeInterval,
        isDelivered: Bool = false
    ) {
        self.messageID = messageID
        self.contactID = contactID
        self.ackCodes = ackCodes
        self.sentAt = sentAt
        self.timeout = timeout
        self.isDelivered = isDelivered
    }

    var isExpired: Bool {
        !isDelivered && Date().timeIntervalSince(sentAt) > timeout
    }
}
