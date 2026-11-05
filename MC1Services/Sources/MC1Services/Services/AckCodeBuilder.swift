import CryptoKit
import Foundation

private let attemptMask: UInt8 = 0x03
private let ackCodeByteCount = 4

/// Matches `BaseChatMesh::composeMsgPacket` so ACKs can be tracked before the send returns.
/// Hash input is LE32(timestamp), attempt & 0x03, UTF-8 text, then sender public key.
/// The recipient is excluded, so identical inputs can share an ACK code across contacts.
/// Attempts 4...7 reuse codes 0...3; firmware appends the full attempt byte after the text
/// to distinguish packet hashes, while `PendingAck.ackCodes` retains each attempt's code.
enum AckCodeBuilder {
  static func expectedAck(
    timestamp: UInt32,
    attempt: UInt8,
    text: String,
    senderPublicKey: Data
  ) -> Data {
    precondition(
      attempt < 8,
      "DM attempt bytes run 0...7; attempt \(attempt) exceeds the index range and would over-wrap the & 0x03 ACK mask"
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
