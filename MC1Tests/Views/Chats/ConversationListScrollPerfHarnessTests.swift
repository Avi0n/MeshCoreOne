import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import SwiftUI
import Testing
import UIKit

/// Scroll-cost guards for the Chats conversation list.
@Suite("ConversationListScrollPerfHarness", .serialized)
@MainActor
struct ConversationListScrollPerfHarnessTests {
  private static let frameBudget = Duration.milliseconds(16)

  private func makeContact(
    radioID: UUID,
    id: UUID = UUID(),
    name: String,
    avatarImageData: Data? = nil
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: radioID,
      publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
      name: name,
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: Date(),
      unreadCount: 0,
      avatarImageData: avatarImageData
    )
  }

  private func makeDirectMessage(
    radioID: UUID,
    contactID: UUID,
    timestamp: UInt32,
    text: String
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: radioID,
      contactID: contactID,
      channelIndex: nil,
      text: text,
      timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: false,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  private func makeAvatarData(seed: UInt8) -> Data {
    let size = 128
    let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))
    let image = renderer.image { context in
      UIColor(
        red: CGFloat(seed) / 255,
        green: CGFloat(255 - seed) / 255,
        blue: 0.4,
        alpha: 1
      ).setFill()
      context.fill(CGRect(x: 0, y: 0, width: size, height: size))
    }
    return image.jpegData(compressionQuality: 0.85) ?? Data(repeating: seed, count: 4000)
  }

  private func findScrollView(in view: UIView) -> UIScrollView? {
    if let scrollView = view as? UIScrollView { return scrollView }
    for subview in view.subviews {
      if let found = findScrollView(in: subview) { return found }
    }
    return nil
  }

  /// List rows must not attach per-cell warm tasks; LazyVStack recycles cells during scroll.
  @Test
  func `conversation list source has no per-row dwell prewarm task`() throws {
    let url = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .appendingPathComponent("MC1/Views/Chats/ConversationListContent.swift")
    let source = try String(contentsOf: url, encoding: .utf8)
    #expect(!source.contains("prewarmOnDwell"))
    #expect(!source.contains(".task(id: conversation.id)"))
  }

  @Test
  func `hosted list programmatic scroll stays under hitch budget`() {
    let radioID = UUID()
    let viewModel = ChatViewModel()
    viewModel.hasLoadedOnce = true

    var others: [Conversation] = []
    let rowCount = 40
    for index in 0..<rowCount {
      let contact = makeContact(
        radioID: radioID,
        name: "Scroll \(index)",
        avatarImageData: index % 2 == 0 ? makeAvatarData(seed: UInt8(index + 20)) : nil
      )
      others.append(.direct(contact))
      viewModel.lastMessageCache[contact.id] = makeDirectMessage(
        radioID: radioID,
        contactID: contact.id,
        timestamp: UInt32(5000 + index),
        text: "row \(index)"
      )
    }
    viewModel.conversationSnapshot = ConversationSnapshot(favorites: [], others: others)

    let harness = HostedConversationList(
      viewModel: viewModel,
      appState: AppState(),
      theme: .ember
    )
    let controller = UIHostingController(rootView: harness)
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
    window.rootViewController = controller
    window.isHidden = false
    window.layoutIfNeeded()
    for _ in 0..<15 {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.01))
    }

    guard let scrollView = findScrollView(in: window) else {
      window.isHidden = true
      Issue.record("No UIScrollView under hosted conversation list")
      return
    }

    let maxOffset = max(0, scrollView.contentSize.height - scrollView.bounds.height)
    #expect(maxOffset > 100, "Expected scrollable content, contentHeight=\(scrollView.contentSize.height)")

    let clock = ContinuousClock()
    let steps = 25
    var total = Duration.zero
    for step in 0...steps {
      let y = maxOffset * CGFloat(step) / CGFloat(steps)
      let started = clock.now
      scrollView.setContentOffset(CGPoint(x: 0, y: y), animated: false)
      window.layoutIfNeeded()
      total += clock.now - started
    }

    let average = total / (steps + 1)
    let averageMs = average / .milliseconds(1)
    print(
      "[DBG-chatlist-hitch] hostedListScrollStep avgMs=\(averageMs) steps=\(steps + 1) maxOffset=\(maxOffset) rows=\(rowCount)"
    )

    window.isHidden = true
    #expect(
      average < Self.frameBudget,
      "Programmatic scroll step through \(rowCount) conversation rows averaged \(averageMs)ms (budget \(Self.frameBudget))."
    )
  }
}

// MARK: - Hosted list shell

private struct HostedConversationList: View {
  let viewModel: ChatViewModel
  let appState: AppState
  let theme: Theme
  @State private var selectedFilter: ChatFilter = .all

  var body: some View {
    ConversationListContent(
      viewModel: viewModel,
      favoriteConversations: viewModel.favoriteConversations,
      otherConversations: viewModel.nonFavoriteConversations,
      selectedFilter: $selectedFilter,
      hasLoadedOnce: true,
      emptyStateMessage: ("None", "None", "message"),
      onNavigate: { _ in },
      onRequestRoomAuth: { _ in },
      onDeleteConversation: { _ in }
    )
    .environment(\.appState, appState)
    .environment(\.appTheme, theme)
  }
}
