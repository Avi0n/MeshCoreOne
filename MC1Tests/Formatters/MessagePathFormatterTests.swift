import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("MessagePathFormatter Tests")
struct MessagePathFormatterTests {
    // MARK: - Direct Path Tests

    @Test("pathLength 0 with no pathNodes returns Flood (zero-hop flood)")
    func floodPathLengthZero() {
        let message = createMessage(pathLength: 0, pathNodes: nil)
        let result = MessagePathFormatter.format(message)
        #expect(result == L10n.Chats.Chats.Message.Path.flood)
    }

    @Test("pathLength 0xFF returns Direct")
    func directPathLengthMax() {
        let message = createMessage(pathLength: 0xFF, pathNodes: nil)
        let result = MessagePathFormatter.format(message)
        #expect(result == L10n.Chats.Chats.Message.Path.direct)
    }

    @Test("pathLength 1 with 0xFF destination marker returns Direct")
    func directWithDestinationMarker() {
        let message = createMessage(pathLength: 1, pathNodes: Data([0xFF]))
        let result = MessagePathFormatter.format(message)
        #expect(result == L10n.Chats.Chats.Message.Path.direct)
    }

    // MARK: - Path Nodes Tests

    @Test("Single node path formats correctly")
    func singleNode() {
        let message = createMessage(pathLength: 1, pathNodes: Data([0xA3]))
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3")
    }

    @Test("Three node path formats with commas")
    func threeNodes() {
        let message = createMessage(pathLength: 3, pathNodes: Data([0xA3, 0x7F, 0x42]))
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3,7F,42")
    }

    @Test("Four node path shows all nodes")
    func fourNodes() {
        let message = createMessage(pathLength: 4, pathNodes: Data([0xA3, 0x7F, 0x42, 0xB2]))
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3,7F,42,B2")
    }

    // MARK: - Truncation Tests

    @Test("Six node path shows all nodes (no truncation)")
    func sixNodesNotTruncated() {
        let message = createMessage(
            pathLength: 6,
            pathNodes: Data([0xA3, 0x7F, 0x42, 0xB2, 0xC1, 0xD4])
        )
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3,7F,42,B2,C1,D4")
    }

    @Test("Seven node path truncates with ellipsis")
    func sevenNodesTruncated() {
        let message = createMessage(
            pathLength: 7,
            pathNodes: Data([0xA3, 0x7F, 0x42, 0xB2, 0xC1, 0xD4, 0xE5])
        )
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3,7F,42…C1,D4,E5")
    }

    @Test("Ten node path truncates correctly")
    func tenNodesTruncated() {
        let message = createMessage(
            pathLength: 10,
            pathNodes: Data([0xA3, 0x7F, 0x42, 0xB2, 0xC1, 0xD4, 0xE5, 0xF6, 0x11, 0x22])
        )
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3,7F,42…F6,11,22")
    }

    // MARK: - Boundary & Edge Case Tests

    @Test("Five node path shows all nodes (boundary before truncation)")
    func fiveNodesNoTruncation() {
        let message = createMessage(
            pathLength: 5,
            pathNodes: Data([0xA3, 0x7F, 0x42, 0xB2, 0xC1])
        )
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3,7F,42,B2,C1")
    }

    @Test("Zero-byte node formats correctly")
    func zeroByteNode() {
        let message = createMessage(pathLength: 2, pathNodes: Data([0x00, 0xA3]))
        let result = MessagePathFormatter.format(message)
        #expect(result == "00,A3")
    }

    // MARK: - Fallback Tests

    @Test("Missing pathNodes on flood-routed returns Flood")
    func fallbackToFlood() {
        let message = createMessage(pathLength: 3, pathNodes: nil)
        let result = MessagePathFormatter.format(message)
        #expect(result == L10n.Chats.Chats.Message.Path.flood)
    }

    // MARK: - Edge Case Tests

    @Test("pathLength doesn't match pathNodes count - shows actual nodes")
    func mismatchedPathData() {
        // pathLength says 5, but only 3 nodes in data - should show what we have
        let message = createMessage(pathLength: 5, pathNodes: Data([0xA3, 0x7F, 0x42]))
        let result = MessagePathFormatter.format(message)
        #expect(result == "A3,7F,42")
    }

    // MARK: - Helper

    private func createMessage(pathLength: UInt8, pathNodes: Data?) -> MessageDTO {
        let message = Message(
            deviceID: UUID(),
            contactID: UUID(),
            text: "Test",
            directionRawValue: MessageDirection.incoming.rawValue,
            statusRawValue: MessageStatus.delivered.rawValue,
            pathLength: pathLength,
            pathNodes: pathNodes
        )
        return MessageDTO(from: message)
    }
}
