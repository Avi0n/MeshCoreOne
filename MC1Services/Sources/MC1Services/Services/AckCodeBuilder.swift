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
/// - **Attempt index is masked to two bits.** Firmware hashes `attempt & 0x03`,
///   so attempt 4 reuses attempt 0's code. This is benign for delivery
///   detection: a single message accumulates its attempt codes in a `Set`
///   (`pendingAcks` `ackCodes`), so the wrap is a no-op re-add and any returned
///   ACK still matches the right message. Firmware deliberately supports
///   `attempt > 3` in `composeMsgPacket`, appending the full attempt byte after
///   the hash so `packet_hash` stays unique. `expectedAck` allows `attempt < 5`
///   (4 direct + 1 flood); `MessageServiceConfig` keeps `maxAttempts <= 5`.
///   Cross-*message* collision is unaffected by the wrap and stays mitigated by
///   the per-radioID `dmQueue` serialization.
enum AckCodeBuilder {
    static func expectedAck(
        timestamp: UInt32,
        attempt: UInt8,
        text: String,
        senderPublicKey: Data
    ) -> Data {
        precondition(
            attempt < 5,
            "MessageServiceConfig caps maxAttempts at 5 (4 direct + 1 flood); attempt \(attempt) exceeds the index range and would over-wrap the & 0x03 ACK mask"
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
