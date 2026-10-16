import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
@Suite("RoomMessageBubble accessibility label")
struct RoomMessageBubbleA11yLabelTests {
  @Test
  func `incoming follow-up label still includes the author`() {
    let message = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0xAA]),
      authorName: "Alice",
      text: "follow up",
      timestamp: 100
    )
    let bubble = RoomMessageBubble(
      message: message,
      showTimestamp: false,
      showSenderName: false,
      showAvatar: true
    )

    #expect(bubble.accessibilityMessageLabel == "Alice: follow up")
  }

  @Test
  func `incoming cluster-start announces the author once`() {
    let message = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0xAA]),
      authorName: "Alice",
      text: "hello",
      timestamp: 100
    )
    let bubble = RoomMessageBubble(
      message: message,
      showTimestamp: false,
      showSenderName: true,
      showAvatar: true
    )

    #expect(bubble.accessibilityMessageLabel == "Alice: hello")
    #expect(bubble.accessibilityMessageLabel.components(separatedBy: "Alice").count == 2)
  }

  @Test
  func `outgoing label includes status once`() {
    let message = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0x42]),
      authorName: "Me",
      text: "hello",
      timestamp: 100,
      isFromSelf: true,
      status: .sent
    )
    let bubble = RoomMessageBubble(
      message: message,
      showTimestamp: false,
      showSenderName: false,
      showAvatar: false
    )
    #expect(bubble.accessibilityMessageLabel == "hello, \(message.accessibilityStatusLabel)")
    let statusCount = bubble.accessibilityMessageLabel.components(separatedBy: message.accessibilityStatusLabel).count
    #expect(statusCount == 2)
  }

  @Test
  func `failed outgoing exposes a retry accessibility action`() {
    var retried = false
    let message = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0x42]),
      authorName: "Me",
      text: "hello",
      timestamp: 100,
      isFromSelf: true,
      status: .failed
    )
    let bubble = RoomMessageBubble(
      message: message,
      showTimestamp: false,
      showSenderName: false,
      showAvatar: false,
      onRetry: { retried = true }
    )
    #expect(bubble.accessibilityShowsRetryAction)
    bubble.performAccessibilityRetry()
    #expect(retried)
  }

  @Test
  func `sent outgoing does not expose a retry accessibility action`() {
    let message = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0x42]),
      authorName: "Me",
      text: "hello",
      timestamp: 100,
      isFromSelf: true,
      status: .sent
    )
    let bubble = RoomMessageBubble(
      message: message,
      showTimestamp: false,
      showSenderName: false,
      showAvatar: false,
      onRetry: {}
    )
    #expect(!bubble.accessibilityShowsRetryAction)
  }

  @Test
  func `https body text yields a named open-link action`() {
    let message = RoomMessageDTO(
      sessionID: UUID(),
      authorKeyPrefix: Data([0xAA]),
      authorName: "Alice",
      text: "see https://example.com",
      timestamp: 100
    )
    let bubble = RoomMessageBubble(
      message: message,
      showTimestamp: false,
      showSenderName: true,
      showAvatar: true
    )
    let formatted = MessageText.buildFormattedText(
      text: message.text,
      isOutgoing: false,
      currentUserName: nil,
      isHighContrast: false,
      outgoingTextColor: .white,
      hashtagColor: .blue,
      identityGamut: IdentityGamut(
        hueAnchors: [18, 25, 44, 77, 120, 180, 215, 255, 307, 343],
        saturation: 0.45...0.70
      ),
      identityBackgroundLuminances: [0.2, 0.8]
    ).text
    let actions = bubble.accessibilityLinkActions(formatted: formatted)
    #expect(actions.contains { $0.url.host() == "example.com" })
    #expect(actions.contains { $0.name == L10n.Chats.Chats.Message.Action.openWebLink("example.com") })
  }
}
