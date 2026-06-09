import Foundation
import Testing
@testable import MC1Services

@Suite("DeduplicationKey")
struct DeduplicationKeyTests {

    // MARK: - Content-based key format

    @Test(
        "Content-based key uses the expected prefix for DM and channel scopes",
        arguments: [
            (contactID: UUID?.some(UUID(uuidString: "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")!),
             channelIndex: UInt8?.none,
             senderNodeName: String?.none,
             timestamp: UInt32(1000),
             content: "hello",
             expectedPrefix: "dm-AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE-1000-"),
            (contactID: UUID?.none,
             channelIndex: UInt8?.some(3),
             senderNodeName: String?.some("Alice"),
             timestamp: UInt32(2000),
             content: "test",
             expectedPrefix: "ch-3-2000-Alice-"),
            (contactID: UUID?.none,
             channelIndex: UInt8?.some(0),
             senderNodeName: String?.none,
             timestamp: UInt32(500),
             content: "msg",
             expectedPrefix: "ch-0-500--"),
            (contactID: UUID?.none,
             channelIndex: UInt8?.none,
             senderNodeName: String?.none,
             timestamp: UInt32(100),
             content: "x",
             expectedPrefix: "dm-unknown-100-")
        ]
    )
    func contentBasedKeyPrefix(
        contactID: UUID?,
        channelIndex: UInt8?,
        senderNodeName: String?,
        timestamp: UInt32,
        content: String,
        expectedPrefix: String
    ) {
        let key = DeduplicationKey.contentBased(
            contactID: contactID,
            channelIndex: channelIndex,
            senderNodeName: senderNodeName,
            timestamp: timestamp,
            content: content
        )
        #expect(key.hasPrefix(expectedPrefix))
    }

    @Test("Content-based key has an 8-char hex hash suffix")
    func contentBasedKeyHashSuffix() {
        let key = DeduplicationKey.contentBased(
            contactID: UUID(), channelIndex: nil,
            senderNodeName: nil, timestamp: 1000, content: "hello"
        )
        let hashSuffix = String(key.split(separator: "-").last ?? "")
        #expect(hashSuffix.count == 8)
    }

    @Test("Content-based key is deterministic and distinguishes different content")
    func contentBasedKeyEquality() {
        let contactID = UUID()
        let keyA1 = DeduplicationKey.contentBased(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: 1, content: "aaa"
        )
        let keyA2 = DeduplicationKey.contentBased(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: 1, content: "aaa"
        )
        let keyB = DeduplicationKey.contentBased(
            contactID: contactID, channelIndex: nil,
            senderNodeName: nil, timestamp: 1, content: "bbb"
        )
        #expect(keyA1 == keyA2)
        #expect(keyA1 != keyB)
    }

    @Test("Empty content still produces a well-formed key")
    func emptyContent() {
        let key = DeduplicationKey.contentBased(
            contactID: UUID(), channelIndex: nil,
            senderNodeName: nil, timestamp: 0, content: ""
        )
        #expect(key.hasPrefix("dm-"))
        #expect(key.count > 10)
    }
}
