import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MessageBubbleConfiguration")
struct MessageBubbleConfigurationTests {
  private func createContact(prefix: [UInt8], name: String, lastAdvertTimestamp: UInt32, nickname: String? = nil) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(prefix + Array(repeating: UInt8(0), count: 32 - prefix.count)),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: lastAdvertTimestamp,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nickname,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func createMessage(senderKeyPrefix: Data?, senderNodeName: String? = nil) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: nil,
      channelIndex: 0,
      text: "Test",
      timestamp: 0,
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: senderKeyPrefix,
      senderNodeName: senderNodeName,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  @Test
  func `channel sender resolver marks short prefix match as fallback`() {
    let older = createContact(prefix: [0xAA, 0x01], name: "Older", lastAdvertTimestamp: 100)
    let newer = createContact(prefix: [0xAA, 0x02], name: "Newer", lastAdvertTimestamp: 200)

    let result = MessageBubbleConfiguration.resolveSenderName(
      for: createMessage(senderKeyPrefix: Data([0xAA])),
      contacts: [older, newer]
    )

    #expect(result.displayName == "Newer")
    #expect(result.matchKind == .fallback)
  }

  @Test
  func `channel sender resolver marks unique short prefix match as exact`() {
    let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha", lastAdvertTimestamp: 100)

    let result = MessageBubbleConfiguration.resolveSenderName(
      for: createMessage(senderKeyPrefix: Data([0xAA])),
      contacts: [contact]
    )

    #expect(result.displayName == "Alpha")
    #expect(result.matchKind == .exact)
  }

  @Test
  func `buildNicknameLookup maps a unique name to its nickname`() {
    let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha", lastAdvertTimestamp: 100, nickname: "Rico")

    let lookup = MessageBubbleConfiguration.buildNicknameLookup(from: [contact])

    #expect(lookup["alpha"] == "Rico")
  }

  @Test
  func `buildNicknameLookup drops ambiguous colliding names`() {
    let a = createContact(prefix: [0xAA, 0x01], name: "Bob", lastAdvertTimestamp: 100, nickname: "First")
    let b = createContact(prefix: [0xAA, 0x02], name: "bob", lastAdvertTimestamp: 200, nickname: "Second")

    let lookup = MessageBubbleConfiguration.buildNicknameLookup(from: [a, b])

    #expect(lookup["bob"] == nil)
  }

  @Test
  func `buildNicknameLookup ignores contacts without a nickname`() {
    let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha", lastAdvertTimestamp: 100, nickname: nil)

    let lookup = MessageBubbleConfiguration.buildNicknameLookup(from: [contact])

    #expect(lookup.isEmpty)
  }

  @Test
  func `channel sender resolver attaches unverified nickname on name match`() {
    let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha", lastAdvertTimestamp: 100, nickname: "Rico")
    let lookup = MessageBubbleConfiguration.buildNicknameLookup(from: [contact])

    let result = MessageBubbleConfiguration.resolveSenderName(
      for: createMessage(senderKeyPrefix: nil, senderNodeName: "Alpha"),
      contacts: [contact],
      nicknamesByLoweredName: lookup
    )

    #expect(result.displayName == "Alpha")
    #expect(result.matchKind == .exact)
    #expect(result.unverifiedNickname == "Rico")
  }

  @Test
  func `channel sender resolver has no nickname when name does not match`() {
    let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha", lastAdvertTimestamp: 100, nickname: "Rico")
    let lookup = MessageBubbleConfiguration.buildNicknameLookup(from: [contact])

    let result = MessageBubbleConfiguration.resolveSenderName(
      for: createMessage(senderKeyPrefix: nil, senderNodeName: "Charlie"),
      contacts: [contact],
      nicknamesByLoweredName: lookup
    )

    #expect(result.unverifiedNickname == nil)
  }
}
