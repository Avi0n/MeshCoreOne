import CryptoKit
import Foundation
import Testing
@testable import MC1Services

@Suite("AckCodeBuilder.expectedAck")
struct AckCodeBuilderTests {

    // Golden vector reproduced in-test from the firmware formula:
    //   sha256( LE32(timestamp) || byte(attempt & 0x03) || text || pubkey )
    // and take bytes [0..3].
    @Test("matches firmware formula for known fixture")
    func goldenVector() {
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

    @Test("different texts produce different codes")
    func differentTextDifferentCode() {
        let pubkey = Data(repeating: 0x01, count: 32)
        let a = AckCodeBuilder.expectedAck(timestamp: 1, attempt: 0, text: "hi", senderPublicKey: pubkey)
        let b = AckCodeBuilder.expectedAck(timestamp: 1, attempt: 0, text: "bye", senderPublicKey: pubkey)
        #expect(a != b)
    }

    @Test("attempts 0..3 produce four distinct codes")
    func directAttemptsDistinct() {
        let pubkey = Data(repeating: 0x02, count: 32)
        let codes = (0..<4).map {
            AckCodeBuilder.expectedAck(timestamp: 100, attempt: UInt8($0), text: "hi", senderPublicKey: pubkey)
        }
        #expect(Set(codes).count == 4)
    }

    // The firmware masks the attempt index with & 0x03, so the flood attempt
    // (index 4) intentionally reuses attempt 0's code. This is safe: a single
    // message accumulates its codes in a Set, so the wrap is a no-op re-add and
    // any returned ACK still matches the right message.
    @Test("attempt 4 wraps to attempt 0's code")
    func floodAttemptWrapsToAttemptZero() {
        let pubkey = Data(repeating: 0x03, count: 32)
        let attempt0 = AckCodeBuilder.expectedAck(timestamp: 100, attempt: 0, text: "hi", senderPublicKey: pubkey)
        let attempt4 = AckCodeBuilder.expectedAck(timestamp: 100, attempt: 4, text: "hi", senderPublicKey: pubkey)
        #expect(attempt0 == attempt4)
    }
}
