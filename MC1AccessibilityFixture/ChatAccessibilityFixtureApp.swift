import MC1Services
import SwiftUI
import UIKit

@main
struct ChatAccessibilityFixtureApp: App {
  var body: some Scene {
    WindowGroup {
      Transcript()
    }
  }

  private struct Transcript: View {
    @State private var isAtBottom = true
    @State private var unreadCount = 0
    @State private var voiceOverFocus = ""

    var body: some View {
      let rows = Fixture.rows(longDay: ProcessInfo.processInfo.arguments.contains("-long-day"))
      let factory = ChatCellContentFactory(
        contactName: "Alice",
        deviceName: "Me",
        configuration: .directMessage,
        theme: .default,
        openURL: OpenURLAction { _ in .handled },
        resolver: BubbleResolver(
          message: { item in rows.first { $0.item.id == item.id }?.message },
          image: { _ in nil }
        ),
        actions: BubbleActions(
          onRetryMessage: { _ in },
          onLongPress: { _ in },
          onImageTap: { _ in },
          onRetryInlineImage: { _ in },
          onRequestPreviewFetch: { _ in },
          onManualPreviewFetch: { _ in },
          onMapPreviewTap: { _ in },
          snapshotResolver: { _ in nil },
          requestSnapshot: { _ in },
          retrySnapshot: { _ in },
          onTranslationAction: { _ in }
        )
      )
      ChatTiledView(
        items: rows.map(\.item),
        cellContent: { factory.makeContent(for: $0) },
        isAtBottom: $isAtBottom,
        unreadCount: $unreadCount,
        initialScrollTargetID: rows.first?.item.id,
        accessibilityHeadingIDs: rows.map(\.item).filter(\.grouping.showDayDivider).map(\.id)
      )
      .onReceive(NotificationCenter.default.publisher(for: UIAccessibility.elementFocusedNotification)) { _ in
        guard ProcessInfo.processInfo.arguments.contains("-verify-heading-focus"),
              let element = UIAccessibility.focusedElement(using: .notificationVoiceOver) as? NSObject else { return }
        voiceOverFocus = "\(element.accessibilityLabel ?? "")|header:\(element.accessibilityTraits.contains(.header))|frame:\(!element.accessibilityFrame.isEmpty)"
      }
      .overlay(alignment: .bottomLeading) {
        if ProcessInfo.processInfo.arguments.contains("-verify-heading-focus") {
          Text("Focus diagnostics")
            .font(.caption2)
            .accessibilityIdentifier("fixture.voiceOverFocus")
            .accessibilityValue(voiceOverFocus)
            .allowsHitTesting(false)
        }
      }
    }
  }

  private enum Fixture {
    static let radioID = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
    static let contactID = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!

    struct Row {
      let message: MessageDTO
      let item: MessageItem
    }

    static func rows(longDay: Bool) -> [Row] {
      let messagesPerDay = longDay ? 24 : 12
      let messageCount = messagesPerDay * 3
      var calendar = Calendar(identifier: .gregorian)
      calendar.timeZone = TimeZone(secondsFromGMT: 0)!
      return (0..<messageCount).map { index in
        let date = calendar.date(from: DateComponents(
          year: 2026, month: 9, day: 28 + index / messagesPerDay, hour: 12, minute: index % messagesPerDay
        ))!
        let message = MessageDTO(
          id: UUID(uuid: (0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, UInt8(index))),
          radioID: radioID,
          contactID: contactID,
          channelIndex: nil,
          text: "Fixture message \(index)",
          timestamp: UInt32(date.timeIntervalSince1970),
          createdAt: date,
          direction: .incoming,
          status: .delivered,
          textType: .plain,
          ackCode: nil,
          pathLength: 0,
          snr: nil,
          senderKeyPrefix: nil,
          senderNodeName: "Alice",
          isRead: true,
          replyToID: nil,
          roundTripTime: nil,
          heardRepeats: 0,
          retryAttempt: 0,
          maxRetryAttempts: 0
        )
        let inputs = MessageBuildInputs(
          messageID: message.id,
          previewState: .idle,
          loadedPreview: nil,
          cachedURL: nil,
          isInlineImageURL: false,
          hasInlineImageRef: false,
          hasPreviewImageRef: false,
          hasPreviewIconRef: false,
          imageIsGIF: false,
          formattedText: nil,
          baseColor: .incoming,
          formattedPath: nil,
          senderResolution: NodeNameResolution(displayName: "Alice", matchKind: .exact),
          showTimestamp: false,
          showDirectionGap: false,
          showSenderName: false,
          showNewMessagesDivider: false,
          showDayDivider: index % messagesPerDay == 0
        )
        return Row(message: message, item: MessageFragmentBuilder.makeItem(
          for: message, inputs: inputs, envInputs: .default
        ))
      }
    }
  }
}
