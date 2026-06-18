import Testing
import Foundation
@testable import MC1Services
@testable import MC1

@MainActor
@Suite("UnifiedMessageBubble accessibility label")
struct UnifiedMessageBubbleA11yLabelTests {

    @Test("incoming message label includes sender, text, and footer fragments")
    func incomingMessage_labelIncludesSenderTextAndFooter() {
        let message = MessageBubbleTestData.incomingChannel(
            text: "hello world",
            pathNodes: nil,
            regionScope: "NORTHWEST"
        )
        let configuration = MessageBubbleConfiguration(
            showSenderName: true
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
            + ", \(L10n.Chats.Chats.Message.HopCount.accessibilityLabel(message.hopCount))"
            + ", \(L10n.Chats.Chats.Message.Path.accessibilityLabel("A to B to C"))"
            + ", \(L10n.Chats.Chats.Message.Region.accessibilityLabel("NORTHWEST"))"
        #expect(bubble.accessibilityMessageLabel == expected)
    }

    @Test("outgoing message label includes status text")
    func outgoingMessage_labelIncludesStatus() {
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

    @Test("fallback sender label includes possible-match disclosure")
    func fallbackSender_labelIncludesPossibleMatch() {
        let message = MessageBubbleTestData.incomingChannel(text: "hi", senderNodeName: nil)
        let configuration = MessageBubbleConfiguration(
            showSenderName: true
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

    @Test("label composition is stable across repeated reads")
    func dynamicTypeMatrix_labelStableAcrossSizes() {
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
}
