import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// A shared coordinator can retain a previous open's Bake, so readiness must
/// use this open's divider and the current flag-bearing row together.
@Suite("Open-at-divider composition")
@MainActor
struct ChatOpenAtDividerCompositionTests {
  @Test
  func `reopen on a warm coordinator never presents the previous session's divider`() async {
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 20)
    let staleDividerID = staleMessages[30].id
    let bakedDividerItemID = coordinator.renderState.items.first { $0.grouping.showNewMessagesDivider }?.id
    #expect(bakedDividerItemID == staleDividerID)

    // Attach without populating so the previous open's page remains cached.
    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.attachCoordinator(coordinator)
    reopenedViewModel.timeline.stageOpen(makeConversation(unreadCount: 1))

    #expect(reopenedViewModel.timeline.firstSnapshot == .withhold)
  }

  @Test
  func `a fresh divider presents only after its baked row is applied`() async {
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 20)

    let newMessages = (50..<68).map { makeMessage(index: $0) }
    let currentMessages = staleMessages + newMessages

    // The divider is computed while the coordinator still holds the previous Bake.
    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.attachCoordinator(coordinator)
    reopenedViewModel.timeline.stageOpen(makeConversation(unreadCount: newMessages.count))
    reopenedViewModel.bake.computeDividerPosition(
      from: currentMessages,
      unreadCount: newMessages.count,
      isDM: false
    )
    #expect(reopenedViewModel.bake.newMessagesDividerMessageID == newMessages[0].id)

    reopenedViewModel.timeline.completePopulate(.loaded)

    #expect(reopenedViewModel.timeline.firstSnapshot == .withhold)
    reopenedViewModel.bindCoordinatorForTesting(coordinator)
    coordinator.replaceAllForTesting(currentMessages)
    reopenedViewModel.buildItems()
    await coordinator.buildItemsTask?.value

    #expect(reopenedViewModel.timeline.firstSnapshot == .present(target: newMessages[0].id))
  }

  @Test
  func `a warm page containing the divider message but baked without the divider withholds`() async {
    // Prior bake had no unread backlog, so items carry no divider flag even
    // though this open treats those messages as unread.
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 0)
    #expect(!coordinator.renderState.items.contains { $0.grouping.showNewMessagesDivider })

    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.attachCoordinator(coordinator)
    reopenedViewModel.timeline.stageOpen(makeConversation(unreadCount: 8))
    reopenedViewModel.bake.computeDividerPosition(from: staleMessages, unreadCount: 8, isDM: false)
    #expect(reopenedViewModel.bake.newMessagesDividerMessageID == staleMessages[42].id)

    #expect(reopenedViewModel.timeline.firstSnapshot == .withhold)
  }
}

private func makeConversation(unreadCount: Int) -> ChatConversationType {
  .channel(ChannelDTO(
    id: UUID(), radioID: UUID(), index: 1, name: "Fixture", secret: Data(),
    isEnabled: true, lastMessageDate: nil, unreadCount: unreadCount
  ))
}

/// A coordinator carrying a previous session's fully baked page, including its
/// "New Messages" divider, built through the real bake path.
@MainActor
private func makeWarmCoordinator(
  staleMessages: [MessageDTO],
  staleUnreadCount: Int
) async -> ChatCoordinator {
  let coordinator = ChatCoordinator.makeForTesting()
  let previousSession = ChatViewModel()
  previousSession.bindCoordinatorForTesting(coordinator)
  coordinator.replaceAllForTesting(staleMessages)
  previousSession.bake.computeDividerPosition(
    from: staleMessages,
    unreadCount: staleUnreadCount,
    isDM: false
  )
  previousSession.buildItems()
  await coordinator.buildItemsTask?.value
  coordinator.markLoadedForTesting()
  return coordinator
}

private func makeMessage(index: Int) -> MessageDTO {
  let timestamp = UInt32(1_700_000_000 + index * 60)
  return MessageDTO(
    id: UUID(),
    radioID: UUID(),
    contactID: UUID(),
    channelIndex: nil,
    text: "message \(index)",
    timestamp: timestamp,
    createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
    direction: .outgoing,
    status: .sent,
    textType: .plain,
    ackCode: nil,
    pathLength: 0,
    snr: nil,
    senderKeyPrefix: nil,
    senderNodeName: nil,
    isRead: true,
    replyToID: nil,
    roundTripTime: nil,
    heardRepeats: 0,
    retryAttempt: 0,
    maxRetryAttempts: 0
  )
}
