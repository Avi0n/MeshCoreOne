import Foundation
@testable import MC1
@testable import MC1Services
import Testing

/// Composition tests for the open-at-divider first-snapshot decision on a warm
/// coordinator.
///
/// A `ChatCoordinator` outlives the push, so on reopen a fresh `ChatViewModel`
/// reads state baked by the previous session. The open target must come from
/// the current session's unread position — never a divider the previous
/// session baked — and the timeline must stay withheld until that target
/// resolves in the items actually on screen.
@Suite("Open-at-divider composition")
@MainActor
struct ChatOpenAtDividerCompositionTests {
  @Test(arguments: [1, 18])
  func `reopen on a warm coordinator never presents the previous session's divider`(
    currentUnreadCount: Int
  ) async {
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 20)
    let staleDividerID = staleMessages[30].id
    let bakedDividerItemID = coordinator.renderState.items.first { $0.grouping.showNewMessagesDivider }?.id
    #expect(bakedDividerItemID == staleDividerID)

    // Reopen: `currentUnreadCount` messages arrived while the chat was closed.
    // The coordinator still holds the previous session's page; the fresh
    // fetch has not landed. Mirrors `ChatConversationView.init`, which only
    // attaches the shared coordinator before `configure` runs.
    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.attachCoordinator(coordinator)

    let decision = firstSnapshotDecision(for: reopenedViewModel, unreadCount: currentUnreadCount)

    // The first unread message of this session is not on screen yet, so the
    // only correct decision is to withhold: presenting spends the one-shot
    // positioning on the previous session's page.
    #expect(decision == .withhold)
  }

  @Test
  func `fresh divider computed but not yet applied to items keeps withholding`() async {
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 20)

    let newMessages = (50..<68).map { makeMessage(index: $0) }
    let currentMessages = staleMessages + newMessages

    // Populate has computed this session's divider synchronously, but the
    // rebuilt items apply later from the off-main build: the coordinator's
    // items are still the previous session's page in this window.
    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.attachCoordinator(coordinator)
    reopenedViewModel.bake.computeDividerPosition(
      from: currentMessages,
      unreadCount: newMessages.count,
      isDM: false
    )
    #expect(reopenedViewModel.bake.newMessagesDividerMessageID == newMessages[0].id)

    let decision = firstSnapshotDecision(
      for: reopenedViewModel,
      unreadCount: newMessages.count,
      initialLoadSettled: true
    )

    #expect(decision == .withhold)
  }

  @Test
  func `divider presents once the fresh page and bake are applied`() async {
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 20)

    let newMessages = (50..<68).map { makeMessage(index: $0) }
    let currentMessages = staleMessages + newMessages

    // The reopen load completed: fresh page replaced, this session's divider
    // computed, items rebaked. Mirrors `configure` binding the writer and
    // populate finishing.
    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.bindCoordinatorForTesting(coordinator)
    coordinator.replaceAllForTesting(currentMessages)
    reopenedViewModel.bake.computeDividerPosition(
      from: currentMessages,
      unreadCount: newMessages.count,
      isDM: false
    )
    reopenedViewModel.buildItems()
    await coordinator.buildItemsTask?.value

    let decision = firstSnapshotDecision(
      for: reopenedViewModel,
      unreadCount: newMessages.count,
      initialLoadSettled: true
    )

    #expect(decision == .present(target: newMessages[0].id))
  }

  @Test
  func `a fully read conversation presents immediately with no target`() async {
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 20)

    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.attachCoordinator(coordinator)

    // No unread backlog: nothing to wait for, even before the load settles.
    let decision = firstSnapshotDecision(for: reopenedViewModel, unreadCount: 0)

    #expect(decision == .present(target: nil))
  }

  @Test
  func `a settled load with no divider target presents at the bottom`() async {
    let staleMessages = (0..<50).map { makeMessage(index: $0) }
    let coordinator = await makeWarmCoordinator(staleMessages: staleMessages, staleUnreadCount: 20)

    // Populate finished without computing a divider (failed or empty fetch):
    // the escape hatch must present rather than withhold forever.
    let reopenedViewModel = ChatViewModel()
    reopenedViewModel.attachCoordinator(coordinator)

    let decision = firstSnapshotDecision(
      for: reopenedViewModel,
      unreadCount: 18,
      initialLoadSettled: true
    )

    #expect(decision == .present(target: nil))
  }
}

/// Resolve the decision exactly as `ChatConversationView.firstSnapshotDecision`
/// does, so these tests exercise the production read against real composed state.
@MainActor
private func firstSnapshotDecision(
  for viewModel: ChatViewModel,
  unreadCount: Int,
  initialLoadSettled: Bool = false,
  hasConsumed: Bool = false
) -> ChatInitialScrollPolicy.FirstSnapshotDecision {
  ChatInitialScrollPolicy.firstSnapshotDecision(
    hasConsumed: hasConsumed,
    unreadCount: unreadCount,
    initialLoadSettled: initialLoadSettled,
    dividerMessageID: viewModel.bake.newMessagesDividerMessageID,
    itemIndexByID: viewModel.itemIndexByID
  )
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
