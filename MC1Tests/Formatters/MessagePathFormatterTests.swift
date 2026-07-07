import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MessagePathFormatter Tests")
struct MessagePathFormatterTests {
  // MARK: - Direct Path Tests

  @Test
  func `pathLength 0 with no pathNodes returns Flood (zero-hop flood)`() {
    let message = createMessage(pathLength: 0, pathNodes: nil)
    let result = MessagePathFormatter.format(message)
    #expect(result == L10n.Chats.Chats.Message.Path.flood)
  }

  @Test
  func `pathLength 0xFF returns Direct`() {
    let message = createMessage(pathLength: 0xFF, pathNodes: nil)
    let result = MessagePathFormatter.format(message)
    #expect(result == L10n.Chats.Chats.Message.Path.direct)
  }

  @Test
  func `pathLength 1 with 0xFF destination marker returns Direct`() {
    let message = createMessage(pathLength: 1, pathNodes: Data([0xFF]))
    let result = MessagePathFormatter.format(message)
    #expect(result == L10n.Chats.Chats.Message.Path.direct)
  }

  // MARK: - Path Nodes Tests

  @Test
  func `Single node path formats correctly`() {
    let message = createMessage(pathLength: 1, pathNodes: Data([0xA3]))
    let result = MessagePathFormatter.format(message)
    #expect(result == "A3")
  }

  @Test
  func `Three node path formats with commas`() {
    let message = createMessage(pathLength: 3, pathNodes: Data([0xA3, 0x7F, 0x42]))
    let result = MessagePathFormatter.format(message)
    #expect(result == "A3,7F,42")
  }

  @Test
  func `Zero-byte node formats correctly`() {
    let message = createMessage(pathLength: 2, pathNodes: Data([0x00, 0xA3]))
    let result = MessagePathFormatter.format(message)
    #expect(result == "00,A3")
  }

  // MARK: - Fallback Tests

  @Test
  func `Missing pathNodes on flood-routed returns Flood`() {
    let message = createMessage(pathLength: 3, pathNodes: nil)
    let result = MessagePathFormatter.format(message)
    #expect(result == L10n.Chats.Chats.Message.Path.flood)
  }

  // MARK: - Edge Case Tests

  @Test
  func `pathLength doesn't match pathNodes count - shows actual nodes`() {
    // pathLength says 5, but only 3 nodes in data - should show what we have
    let message = createMessage(pathLength: 5, pathNodes: Data([0xA3, 0x7F, 0x42]))
    let result = MessagePathFormatter.format(message)
    #expect(result == "A3,7F,42")
  }

  // MARK: - Multibyte Hash Mode Tests

  @Test
  func `Mode-1 path chunks bytes into 2-byte hops`() {
    // 0x42 = mode 1 (2 bytes/hop), 2 hops → 4 wire bytes → ["A1B2","C3D4"].
    let message = createMessage(
      pathLength: 0x42,
      pathNodes: Data([0xA1, 0xB2, 0xC3, 0xD4])
    )
    #expect(MessagePathFormatter.format(message) == "A1B2,C3D4")
  }

  @Test
  func `Mode-2 path chunks bytes into 3-byte hops`() {
    // 0x82 = mode 2 (3 bytes/hop), 2 hops → 6 wire bytes → ["010203","040506"].
    let message = createMessage(
      pathLength: 0x82,
      pathNodes: Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06])
    )
    #expect(MessagePathFormatter.format(message) == "010203,040506")
  }

  // MARK: - Truncation Tests

  @Test
  func `Four-node path is shown in full (at the cap)`() {
    let message = createMessage(pathLength: 4, pathNodes: Data([0xA3, 0x7F, 0x42, 0xB2]))
    #expect(MessagePathFormatter.format(message) == "A3,7F,42,B2")
  }

  @Test
  func `Path longer than four nodes collapses the middle to a tight ellipsis`() {
    let message = createMessage(pathLength: 6, pathNodes: Data([0xA3, 0x7F, 0x42, 0xB2, 0xC1, 0xD0]))
    #expect(MessagePathFormatter.format(message) == "A3,7F…C1,D0")
  }

  // MARK: - Helper

  private func createMessage(pathLength: UInt8, pathNodes: Data?) -> MessageDTO {
    let message = Message(
      radioID: UUID(),
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
