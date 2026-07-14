import Foundation
@testable import MC1
@testable import MC1Services
import Testing

private func makeMessage(id: UUID = UUID(), index: Int) -> MessageDTO {
  let timestamp = UInt32(1_700_000_000 + index * 60)
  return MessageDTO(
    id: id,
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

@Suite("ChatRenderState")
@MainActor
struct ChatRenderStateTests {
  @Test
  func `empty has expected field defaults`() {
    let empty = ChatRenderState.empty
    #expect(empty.items.isEmpty)
    #expect(empty.itemIndexByID.isEmpty)
    #expect(empty.hasMoreMessages == true)
    #expect(empty.isLoadingOlder == false)
    #expect(empty.totalFetchedCount == 0)
    #expect(empty.phase == .uninitialized)
  }

  @Test
  func `with(...) replaces only specified fields`() {
    let updated = ChatRenderState.empty.with(hasMoreMessages: false, totalFetchedCount: 42)

    #expect(updated.hasMoreMessages == false)
    #expect(updated.totalFetchedCount == 42)
    #expect(updated.isLoadingOlder == ChatRenderState.empty.isLoadingOlder)
    #expect(updated.items == ChatRenderState.empty.items)
    #expect(updated.itemIndexByID == ChatRenderState.empty.itemIndexByID)
    #expect(updated.phase == ChatRenderState.empty.phase)
  }

  @Test
  func `with(phase:) replaces only the phase`() {
    let loading = ChatRenderState.empty.with(phase: .loading)
    #expect(loading.phase == .loading)
    #expect(loading.items == ChatRenderState.empty.items)
    #expect(loading.hasMoreMessages == ChatRenderState.empty.hasMoreMessages)

    let loaded = loading.with(phase: .loaded)
    #expect(loaded.phase == .loaded)
  }

  @Test
  func `appendingItem preserves phase`() {
    let loaded = ChatRenderState.empty.with(phase: .loaded)
    let after = loaded.appendingItem(makeFakeMessageItem(id: UUID(), senderName: "sender"))

    #expect(after.phase == .loaded)
  }

  @Test
  func `updatingItem preserves phase`() async {
    let (viewModel, coordinator) = await makeChatSetup(messageCount: 3)
    coordinator.markLoadedForTesting()
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    let target = viewModel.renderState.items[0]
    let replaced = makeFakeMessageItem(id: target.id, senderName: "replaced")
    let after = viewModel.renderState.updatingItem(id: target.id) { _ in replaced }

    #expect(after.phase == viewModel.renderState.phase)
  }

  @Test
  func `removingItem preserves phase`() async {
    let messages = (0..<2).map { makeMessage(index: $0) }
    let (viewModel, coordinator) = await makeChatSetup(messages: messages)
    coordinator.markLoadedForTesting()
    viewModel.buildItems()
    await coordinator.buildItemsTask?.value

    let after = viewModel.renderState.removingItem(id: messages[0].id)

    #expect(after.phase == viewModel.renderState.phase)
  }

  @Test
  func `itemIndexByID matches items after buildItems`() async {
    let (viewModel, _) = await makeChatSetup(messageCount: 5)

    let state = viewModel.renderState
    #expect(state.items.count == 5)
    for (index, item) in state.items.enumerated() {
      #expect(state.itemIndexByID[item.id] == index)
    }
  }

  @Test
  func `Two builds with identical inputs produce equal render states`() async {
    let ids = (0..<3).map { _ in UUID() }
    let inputs = ids.enumerated().map { makeMessage(id: $1, index: $0) }

    let (viewModel1, _) = await makeChatSetup(messages: inputs)
    let (viewModel2, _) = await makeChatSetup(messages: inputs)

    #expect(viewModel1.renderState == viewModel2.renderState)
  }

  @Test
  func `ChatViewModel accessors mirror renderState field-for-field`() async {
    let (viewModel, _) = await makeChatSetup(messageCount: 3)

    let state = viewModel.renderState
    #expect(viewModel.items.count == state.items.count)
    #expect(viewModel.itemIndexByID == state.itemIndexByID)
    #expect(viewModel.hasMoreMessages == state.hasMoreMessages)
    #expect(viewModel.isLoadingOlder == state.isLoadingOlder)
    #expect(viewModel.totalFetchedCount == state.totalFetchedCount)
  }

  @Test
  func `updatingItem replaces a single row, leaves others untouched`() async {
    let (viewModel, _) = await makeChatSetup(messageCount: 3)

    let target = viewModel.renderState.items[1]
    let replaced = makeFakeMessageItem(id: target.id, senderName: "replaced")
    let updated = viewModel.renderState.updatingItem(id: target.id) { _ in replaced }

    #expect(updated.items[1].envelope.senderName == "replaced")
    #expect(updated.items[0] == viewModel.renderState.items[0])
    #expect(updated.items[2] == viewModel.renderState.items[2])
    #expect(updated.itemIndexByID == viewModel.renderState.itemIndexByID)
  }

  @Test
  func `updatingItem no-ops on missing ID`() async {
    let (viewModel, _) = await makeChatSetup(messageCount: 2)

    let before = viewModel.renderState
    let after = before.updatingItem(id: UUID()) { _ in
      makeFakeMessageItem(id: UUID(), senderName: "ignored")
    }
    #expect(before == after)
  }

  @Test
  func `removingItem removes the row and rebuilds the index`() async {
    let messages = (0..<3).map { makeMessage(index: $0) }
    let (viewModel, _) = await makeChatSetup(messages: messages)

    let removedID = messages[0].id
    let after = viewModel.renderState.removingItem(id: removedID)

    #expect(after.items.count == 2)
    #expect(after.itemIndexByID[removedID] == nil)
    for (index, item) in after.items.enumerated() {
      #expect(after.itemIndexByID[item.id] == index)
    }
  }

  @Test
  func `removingItem no-ops on missing ID`() async {
    let (viewModel, _) = await makeChatSetup(messageCount: 2)

    let before = viewModel.renderState
    let after = before.removingItem(id: UUID())
    #expect(before == after)
  }

  @Test
  func `newMessagesDividerItemID resolves to the first divider-flagged item`() {
    let plain = makeFakeMessageItem(id: UUID(), senderName: "a")
    let flagged = makeFakeMessageItem(id: UUID(), senderName: "b", showNewMessagesDivider: true)
    let trailing = makeFakeMessageItem(id: UUID(), senderName: "c")
    let state = ChatRenderState.empty
      .appendingItem(plain)
      .appendingItem(flagged)
      .appendingItem(trailing)

    #expect(state.newMessagesDividerItemID == flagged.id)
  }

  @Test
  func `newMessagesDividerItemID is nil when no item carries the divider`() {
    let state = ChatRenderState.empty
      .appendingItem(makeFakeMessageItem(id: UUID(), senderName: "a"))
      .appendingItem(makeFakeMessageItem(id: UUID(), senderName: "b"))

    #expect(state.newMessagesDividerItemID == nil)
  }

  @Test
  func `appendingItem appends and updates totalFetchedCount`() {
    let initial = ChatRenderState.empty
    let item = makeFakeMessageItem(id: UUID(), senderName: "sender")
    let after = initial.appendingItem(item)

    #expect(after.items.count == 1)
    #expect(after.items[0].id == item.id)
    #expect(after.itemIndexByID[item.id] == 0)
    #expect(after.totalFetchedCount == initial.totalFetchedCount + 1)
  }
}

@MainActor
private func makeChatSetup(messageCount: Int) async -> (ChatViewModel, ChatCoordinator) {
  await makeChatSetup(messages: (0..<messageCount).map { makeMessage(index: $0) })
}

@MainActor
private func makeChatSetup(messages: [MessageDTO]) async -> (ChatViewModel, ChatCoordinator) {
  let viewModel = ChatViewModel()
  let coordinator = ChatCoordinator.makeForTesting()
  viewModel.bindCoordinatorForTesting(coordinator)
  coordinator.replaceAllForTesting(messages)
  viewModel.buildItems()
  await coordinator.buildItemsTask?.value
  return (viewModel, coordinator)
}

@MainActor
private func makeFakeMessageItem(
  id: UUID,
  senderName: String,
  showNewMessagesDivider: Bool = false
) -> MessageItem {
  MessageItem(
    id: id,
    envelope: MessageEnvelope(
      messageID: id,
      isOutgoing: true,
      senderName: senderName,
      senderResolution: NodeNameResolution(displayName: senderName, matchKind: .exact),
      status: .sent,
      date: Date(timeIntervalSince1970: 1_700_000_000),
      hasFailed: false,
      containsSelfMention: false,
      mentionSeen: false
    ),
    content: [],
    footer: MessageFooter(
      showHop: false,
      hopCount: 0,
      formattedPath: nil,
      regionToShow: nil,
      sendTimeToShow: nil,
      sendTimeWasCorrected: false,
      showStatusRow: false,
      status: .sent,
      isChannelMessage: false,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      sendCount: 0
    ),
    grouping: GroupingFlags(
      showTimestamp: false,
      showDirectionGap: false,
      showSenderName: false,
      showNewMessagesDivider: showNewMessagesDivider
    ),
    shouldRequestPreviewFetch: false
  )
}
