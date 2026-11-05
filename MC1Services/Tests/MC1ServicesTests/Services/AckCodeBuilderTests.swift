import CryptoKit
import Foundation
@testable import MC1Services
import Testing

@Suite("AckCodeBuilder.expectedAck")
struct AckCodeBuilderTests {
  /// Golden vector reproduced in-test from the firmware formula:
  ///   sha256( LE32(timestamp) || byte(attempt & 0x03) || text || pubkey )
  /// and take bytes [0..3].
  @Test
  func `matches firmware formula for known fixture`() {
    let timestamp: UInt32 = 0x6624_AABB
    let attempt: UInt8 = 2
    let text = "hello"
    let pubkey = Data(repeating: 0xAA, count: 32)

    let code = AckCodeBuilder.expectedAck(
      timestamp: timestamp,
      attempt: attempt,
      text: text,
      senderPublicKey: pubkey
    )

    var input = Data()
    var ts = timestamp.littleEndian
    withUnsafeBytes(of: &ts) { input.append(contentsOf: $0) }
    input.append(attempt & 0x03)
    input.append(contentsOf: text.utf8)
    input.append(pubkey)
    let expected = Data(SHA256.hash(data: input).prefix(4))

    #expect(code == expected)
  }

  @Test
  func `different texts produce different codes`() {
    let pubkey = Data(repeating: 0x01, count: 32)
    let a = AckCodeBuilder.expectedAck(timestamp: 1, attempt: 0, text: "hi", senderPublicKey: pubkey)
    let b = AckCodeBuilder.expectedAck(timestamp: 1, attempt: 0, text: "bye", senderPublicKey: pubkey)
    #expect(a != b)
  }

  @Test
  func `attempts 0..3 produce four distinct codes`() {
    let pubkey = Data(repeating: 0x02, count: 32)
    let codes = (0..<4).map {
      AckCodeBuilder.expectedAck(timestamp: 100, attempt: UInt8($0), text: "hi", senderPublicKey: pubkey)
    }
    #expect(Set(codes).count == 4)
  }

  /// The firmware masks the attempt to two bits; each message retains all of its ACK codes.
  @Test
  func `attempt 4 wraps to attempt 0's code`() {
    let pubkey = Data(repeating: 0x03, count: 32)
    let attempt0 = AckCodeBuilder.expectedAck(timestamp: 100, attempt: 0, text: "hi", senderPublicKey: pubkey)
    let attempt4 = AckCodeBuilder.expectedAck(timestamp: 100, attempt: 4, text: "hi", senderPublicKey: pubkey)
    #expect(attempt0 == attempt4)
  }

  @Test
  func `attempts 5 through 7 reuse attempts 1 through 3`() {
    let pubkey = Data(repeating: 0x04, count: 32)
    for (later, earlier) in [(UInt8(5), UInt8(1)), (6, 2), (7, 3)] {
      let wrapped = AckCodeBuilder.expectedAck(timestamp: 100, attempt: later, text: "hi", senderPublicKey: pubkey)
      let first = AckCodeBuilder.expectedAck(timestamp: 100, attempt: earlier, text: "hi", senderPublicKey: pubkey)
      #expect(wrapped == first)
    }
  }
}
