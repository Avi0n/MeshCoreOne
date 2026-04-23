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
enum AckCodeBuilder {
    static func expectedAck(
        timestamp: UInt32,
        attempt: UInt8,
        text: String,
        senderPublicKey: Data
    ) -> Data {
        var input = Data()
        var le = timestamp.littleEndian
        withUnsafeBytes(of: &le) { input.append(contentsOf: $0) }
        input.append(attempt & attemptMask)
        input.append(contentsOf: text.utf8)
        input.append(senderPublicKey)
        return Data(SHA256.hash(data: input).prefix(ackCodeByteCount))
    }
}
