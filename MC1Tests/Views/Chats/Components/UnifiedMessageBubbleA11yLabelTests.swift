import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
@Suite("UnifiedMessageBubble accessibility label")
struct UnifiedMessageBubbleA11yLabelTests {
  @Test
  func `incoming message label includes sender, text, and footer fragments`() {
    let message = MessageBubbleTestData.incomingChannel(
      text: "hello world",
      pathNodes: nil,
      regionScope: "NORTHWEST"
    )
    let configuration = MessageBubbleConfiguration(
      showSenderName: true,
      showsIncomingAvatars: false
    )
    let bundle = MessageBubbleTestData.messageItem(
      message: message,
      formattedPath: "A to B to C",
      showIncomingHopCount: true,
      showIncomingRegion: true,
      senderResolution: NodeNameResolution(displayName: "Alice", matchKind: .exact)
    )
    let bubble = UnifiedMessageBubble(
      message: message,
      contactName: "Alice",
      deviceName: "Me",
      configuration: configuration,
      item: bundle.item,
      layout: FragmentLayout(content: bundle.item.content),
      imageResolver: bundle.imageResolver
    )

    let expected = "Alice: hello world"
      + ", \(L10n.Chats.Chats.Message.HopCount.accessibilityLabel(bundle.item.footer.hopCount))"
      + ", \(L10n.Chats.Chats.Message.Path.accessibilityLabel("A to B to C"))"
      + ", \(L10n.Chats.Chats.Message.Region.accessibilityLabel("NORTHWEST"))"
    #expect(bubble.accessibilityMessageLabel == expected)
  }

  @Test
  func `outgoing message label includes status text`() {
    let message = MessageBubbleTestData.outgoingDM(text: "ok", status: .pending)
    let bundle = MessageBubbleTestData.messageItem(message: message)
    let bubble = UnifiedMessageBubble(
      message: message,
      contactName: "Alice",
      deviceName: "Me",
      configuration: .directMessage,
      item: bundle.item,
      layout: FragmentLayout(content: bundle.item.content),
      imageResolver: bundle.imageResolver
    )
    let label = bubble.accessibilityMessageLabel
    #expect(label.contains(L10n.Chats.Chats.Message.Status.sending))
  }

  @Test
  func `fallback sender label includes possible-match disclosure`() {
    let message = MessageBubbleTestData.incomingChannel(text: "hi", senderNodeName: nil)
    let configuration = MessageBubbleConfiguration(
      showSenderName: true,
      showsIncomingAvatars: false
    )
    let bundle = MessageBubbleTestData.messageItem(
      message: message,
      senderResolution: NodeNameResolution(displayName: "Alice", matchKind: .fallback)
    )
    let bubble = UnifiedMessageBubble(
      message: message,
      contactName: "Alice",
      deviceName: "Me",
      configuration: configuration,
      item: bundle.item,
      layout: FragmentLayout(content: bundle.item.content),
      imageResolver: bundle.imageResolver
    )
    let label = bubble.accessibilityMessageLabel
    #expect(label.hasPrefix("Alice: "))
    #expect(label.contains(L10n.Chats.Chats.Message.Sender.possibleMatch))
  }

  @Test
  func `label composition is stable across repeated reads`() {
    let message = MessageBubbleTestData.outgoingDM(text: "stable", status: .sent)
    let bundle = MessageBubbleTestData.messageItem(message: message)
    let bubble = UnifiedMessageBubble(
      message: message,
      contactName: "Bob",
      deviceName: "Me",
      configuration: .directMessage,
      item: bundle.item,
      layout: FragmentLayout(content: bundle.item.content),
      imageResolver: bundle.imageResolver
    )
    let first = bubble.accessibilityMessageLabel
    let second = bubble.accessibilityMessageLabel
    #expect(first == second)
    #expect(first.contains("stable"))
  }

  @Test
  func `a reading speaks the reading`() {
    let stored = "MECP/0/M01"
    let bubble = chatBubble(stored: stored)
    let reading = MECPCatalog.displayText(stored)
    #expect(
      bubble.accessibilityMessageLabel
        == "Alice: \(L10n.Chats.Chats.Message.readingAccessibility(reading))"
    )
  }

  private func chatBubble(stored: String) -> UnifiedMessageBubble {
    let message = MessageBubbleTestData.incomingChannel(
      text: stored,
      pathLength: 0,
      pathNodes: nil
    )
    let bundle = MessageBubbleTestData.messageItem(
      message: message,
      senderResolution: NodeNameResolution(displayName: "Alice", matchKind: .exact)
    )
    return UnifiedMessageBubble(
      message: message,
      contactName: "Alice",
      deviceName: "Me",
      configuration: MessageBubbleConfiguration(
        showSenderName: true,
        showsIncomingAvatars: false
      ),
      item: bundle.item,
      layout: FragmentLayout(content: bundle.item.content),
      imageResolver: bundle.imageResolver
    )
  }
}
