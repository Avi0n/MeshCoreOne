import CryptoKit
import Foundation

private let attemptMask: UInt8 = 0x03
private let ackCodeByteCount = 4

/// Computes the expected ACK CRC for an outgoing direct message, mirroring
/// firmware `BaseChatMesh::sendMessage`'s derivation:
///
///     sha256( LE32(timestamp) || byte(attempt & 0x03) || text || pubkey )[0..3]
///
/// Used to populate `pendingAcks` *before* the send round-trip so the
/// persistent ACK listener cannot race ahead of `trackPendingAck`.
///
/// # Firmware contract invariants
///
/// - **Recipient is not in the hash.** Same sender + same text + same UInt32
///   timestamp + same `attempt & 0x03` produces an identical ACK code across
///   *every* contact. Currently low-risk because regular DMs are serialized
///   through a per-radioID `dmQueue` (`SendQueue<DirectMessageEnvelope>`), so
///   two regular DMs cannot complete in the same wall-clock second on one
///   radio; the reaction path bypasses that queue via the single-shot send,
///   but each reaction carries a per-target message-hash discriminator. Any
///   future feature that dispatches identical-text sends in parallel (bulk
///   send, broadcast-to-many, scripted sends, or any pipelined DM path that
///   removes the serialized `dmQueue` guarantee) must either serialize with
///   ≥1s spacing or replace `pendingAcks`'s ackCode lookup with a
///   `[Data: Set<UUID>]` index in `MessageService.handleAcknowledgement`.
/// - **Attempt index is masked to two bits.** Firmware applies `& 0x03`, so
///   only four distinct ACK codes exist for a given (text, timestamp, sender)
///   tuple. Attempt 4 collides with attempt 0, attempt 5 with attempt 1, etc.
///   `expectedAck` rejects `attempt >= 4` outright; `MessageServiceConfig`
///   must keep `maxAttempts <= 4`.
enum AckCodeBuilder {
    static func expectedAck(
        timestamp: UInt32,
        attempt: UInt8,
        text: String,
        senderPublicKey: Data
    ) -> Data {
        precondition(
            attempt < 4,
            "firmware masks attempt & 0x03; attempt \(attempt) would collide with attempt \(attempt & attemptMask)"
        )
        var input = Data()
        var le = timestamp.littleEndian
        withUnsafeBytes(of: &le) { input.append(contentsOf: $0) }
        input.append(attempt & attemptMask)
        input.append(contentsOf: text.utf8)
        input.append(senderPublicKey)
        return Data(SHA256.hash(data: input).prefix(ackCodeByteCount))
    }
}
