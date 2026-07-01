import Foundation
@testable import MC1Services
import Testing

@Suite("ChatCoordinator")
@MainActor
struct ChatCoordinatorTests {
  @Test
  func `append adds a new message and bumps renderStateID`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let radioID = UUID()
    let contactID = UUID()
    let message = MessageDTO.testDirectMessage(radioID: radioID, contactID: contactID)

    let before = coordinator.renderStateID
    let inserted = coordinator.append(message)

    #expect(inserted)
    #expect(coordinator.messages.count == 1)
    #expect(coordinator.messagesByID[message.id] == message)
    #expect(coordinator.renderStateID == before &+ 1)
  }

  @Test
  func `append is idempotent on duplicate id`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let message = MessageDTO.testDirectMessage()
    _ = coordinator.append(message)

    let countBefore = coordinator.messages.count
    let renderIDBefore = coordinator.renderStateID
    let inserted = coordinator.append(message)

    #expect(!inserted)
    #expect(coordinator.messages.count == countBefore)
    #expect(coordinator.renderStateID == renderIDBefore)
  }

  @Test
  func `update no-ops on missing id`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let renderIDBefore = coordinator.renderStateID

    coordinator.update(messageID: UUID()) { dto in
      dto = MessageDTO.testDirectMessage()
    }

    #expect(coordinator.messages.isEmpty)
    #expect(coordinator.renderStateID == renderIDBefore)
  }

  @Test
  func `update mutates an existing message in place and bumps renderStateID`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let message = MessageDTO.testDirectMessage(text: "before")
    _ = coordinator.append(message)
    let renderIDBefore = coordinator.renderStateID

    coordinator.update(messageID: message.id) { dto in
      dto = MessageDTO.testDirectMessage(id: message.id, text: "after")
    }

    #expect(coordinator.messages.first?.text == "after")
    #expect(coordinator.renderStateID == renderIDBefore &+ 1)
  }

  @Test
  func `renderStateID increments on every mutation`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let initial = coordinator.renderStateID

    coordinator.replaceAll([MessageDTO.testDirectMessage()])
    _ = coordinator.append(MessageDTO.testDirectMessage())
    coordinator.update(messageID: coordinator.messages[0].id) { _ in }
    coordinator.remove(messageID: coordinator.messages[0].id)

    #expect(coordinator.renderStateID == initial &+ 4)
  }

  /// Regression: when `rebuildItems` completes but a fresher mutation has
  /// advanced `renderStateID`, `setRenderState` rejects the build and the
  /// `renderStateInvalidated` callback must fire so the view model knows
  /// to reassemble inputs and call `rebuildItems` again.
  @Test
  func `rebuildItems fires renderStateInvalidated when setRenderState rejects stale build`() async {
    let coordinator = ChatCoordinator.makeForTesting()
    let message = MessageDTO.testDirectMessage()
    _ = coordinator.append(message)

    let invalidatedBox = MainActorBox<Int>(value: 0)
    coordinator.renderStateInvalidated = {
      invalidatedBox.value += 1
    }

    // Build minimal inputs for a single message. The off-main builder
    // loop must complete (not be cancelled) so the stale-reject path runs.
    let inputs: [(MessageDTO, MessageBuildInputs)] = [
      (message, MessageBuildInputs(
        messageID: message.id,
        previewState: .idle,
        loadedPreview: nil,
        cachedURL: nil,
        hasInlineImageRef: false,
        hasPreviewImageRef: false,
        hasPreviewIconRef: false,
        imageIsGIF: false,
        formattedText: nil,
        baseColor: .incoming,
        formattedPath: nil,
        senderResolution: NodeNameResolution(displayName: "", matchKind: .unresolved),
        showTimestamp: false,
        showDirectionGap: false,
        showSenderName: false,
        showNewMessagesDivider: false
      ))
    ]

    // Kick off rebuildItems, which captures the current renderStateID.
    coordinator.rebuildItems(inputs: inputs, envInputs: .default)

    // Advance renderStateID before the off-main build lands on main,
    // so setRenderState will reject the result.
    _ = coordinator.append(MessageDTO.testDirectMessage())

    // Wait for the in-flight build task to finish. The off-main loop
    // and the trailing MainActor.run (which fires the invalidation
    // callback on the stale-reject path) both complete before `.value`
    // returns.
    await coordinator.buildItemsTask?.value

    // The off-main build finished; renderState must be unchanged (reject)
    // and the invalidation callback must have fired exactly once.
    #expect(coordinator.renderState.items.isEmpty)
    #expect(invalidatedBox.value == 1)
  }

  @Test
  func `setRenderState returns false on stale capturedID`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let staleID = coordinator.renderStateID
    _ = coordinator.append(MessageDTO.testDirectMessage())

    let applied = coordinator.setRenderState(
      ChatRenderState.empty.with(hasMoreMessages: false),
      capturedID: staleID
    )

    #expect(!applied)
    #expect(coordinator.renderState.hasMoreMessages == true)
  }

  @Test
  func `setRenderState applies when capturedID matches`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let currentID = coordinator.renderStateID

    let applied = coordinator.setRenderState(
      ChatRenderState.empty.with(hasMoreMessages: false),
      capturedID: currentID
    )

    #expect(applied)
    #expect(coordinator.renderState.hasMoreMessages == false)
  }

  @Test
  func `enqueueReload unions IDs into pendingReloadIDs`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let id1 = UUID()
    let id2 = UUID()

    coordinator.enqueueReload(updatedMessageIDs: [id1])
    coordinator.enqueueReload(updatedMessageIDs: [id2])

    // The drain Task is scheduled but cannot run before this
    // synchronous test returns, so `pendingReloadIDs` still holds
    // both unioned IDs at this point.
    #expect(coordinator.pendingReloadIDs.contains(id1))
    #expect(coordinator.pendingReloadIDs.contains(id2))
    #expect(coordinator.reloadInFlight)
  }

  /// Ack / retry / fail / heard-repeat / reaction events route through
  /// `enqueueReload` and then `applyReloadedIDs`, which refreshes the
  /// canonical DTO in `messages`. The coordinator invokes
  /// `renderItemRebuilder` for each refreshed ID so the view model
  /// rebuilds the affected `MessageItem` in `renderState.items`; without
  /// that callback the rendered bubble would stay visually stale until
  /// an unrelated event forced a full timeline rebuild.
  @Test
  func `applyReloadedIDs invokes renderItemRebuilder after refreshing a DTO`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)

    let radioID = UUID()
    let contactID = UUID()
    let conversationID = ChatConversationID.dm(radioID: radioID, contactID: contactID)
    let coordinator = registry.coordinator(for: conversationID)

    let initial = MessageDTO.testDirectMessage(
      radioID: radioID,
      contactID: contactID,
      text: "before",
      status: .sending
    )
    try await dataStore.saveMessage(initial)
    _ = coordinator.append(initial)

    let rebuiltIDs = MainActorBox<[UUID]>(value: [])
    coordinator.renderItemRebuilder = { id in
      rebuiltIDs.value.append(id)
    }

    // Simulate an ack landing in the store after the coordinator has
    // already loaded the message: status flips from `.sending` to
    // `.sent` via the normal update path.
    try await dataStore.updateMessageStatus(id: initial.id, status: .sent)

    coordinator.enqueueReload(messageID: initial.id)

    // Drain the coalesced reload. The implementation schedules a
    // detached Task, so yield until `reloadInFlight` clears.
    let deadline = ContinuousClock.now + .seconds(1)
    while coordinator.reloadInFlight, ContinuousClock.now < deadline {
      await Task.yield()
    }

    #expect(!coordinator.reloadInFlight)
    #expect(rebuiltIDs.value == [initial.id])
    #expect(coordinator.messagesByID[initial.id]?.status == .sent)
  }

  /// Regression: once a row has flipped to `.failed`, a stale event-stream
  /// `applyStatusUpdate(.pending)` (e.g., a delayed `messageStatusResolved`
  /// landing after the queue marked the row terminal) must not flicker the
  /// bubble back to "Sending". Only an explicit user-initiated retry
  /// (`userInitiated: true`) is allowed to downgrade `.failed → .pending`.
  @Test
  func `applyStatusUpdate blocks .failed -> .pending unless userInitiated`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let message = MessageDTO.testDirectMessage(status: .pending)
    _ = coordinator.append(message)

    coordinator.applyStatusUpdate(messageID: message.id, status: .failed)
    #expect(coordinator.messagesByID[message.id]?.status == .failed)

    // Non-user-initiated downgrade is blocked.
    coordinator.applyStatusUpdate(messageID: message.id, status: .pending)
    #expect(coordinator.messagesByID[message.id]?.status == .failed)

    // User-initiated retry bypasses the guard.
    coordinator.applyStatusUpdate(messageID: message.id, status: .pending, userInitiated: true)
    #expect(coordinator.messagesByID[message.id]?.status == .pending)
  }

  @Test
  func `fresh coordinator starts in .uninitialized phase`() {
    let coordinator = ChatCoordinator.makeForTesting()
    #expect(coordinator.renderState.phase == .uninitialized)
  }

  @Test
  func `beginLoading transitions .uninitialized to .loading`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let renderIDBefore = coordinator.renderStateID

    coordinator.beginLoading()

    #expect(coordinator.renderState.phase == .loading)
    #expect(coordinator.renderStateID == renderIDBefore &+ 1)
  }

  @Test
  func `beginLoading is a no-op once .loaded`() {
    let coordinator = ChatCoordinator.makeForTesting()
    coordinator.replaceAll([])
    let renderIDBefore = coordinator.renderStateID

    coordinator.beginLoading()

    #expect(coordinator.renderState.phase == .loaded)
    #expect(coordinator.renderStateID == renderIDBefore)
  }

  @Test
  func `beginLoading is a no-op while already .loading`() {
    let coordinator = ChatCoordinator.makeForTesting()
    coordinator.beginLoading()
    let renderIDBefore = coordinator.renderStateID

    coordinator.beginLoading()

    #expect(coordinator.renderState.phase == .loading)
    #expect(coordinator.renderStateID == renderIDBefore)
  }

  @Test
  func `markLoaded transitions .uninitialized to .loaded`() {
    let coordinator = ChatCoordinator.makeForTesting()
    let renderIDBefore = coordinator.renderStateID

    coordinator.markLoaded()

    #expect(coordinator.renderState.phase == .loaded)
    #expect(coordinator.renderStateID == renderIDBefore &+ 1)
  }

  @Test
  func `markLoaded transitions .loading to .loaded`() {
    let coordinator = ChatCoordinator.makeForTesting()
    coordinator.beginLoading()
    let renderIDBefore = coordinator.renderStateID

    coordinator.markLoaded()

    #expect(coordinator.renderState.phase == .loaded)
    #expect(coordinator.renderStateID == renderIDBefore &+ 1)
  }

  @Test
  func `markLoaded is idempotent when already .loaded`() {
    let coordinator = ChatCoordinator.makeForTesting()
    coordinator.markLoaded()
    let renderIDBefore = coordinator.renderStateID

    coordinator.markLoaded()

    #expect(coordinator.renderState.phase == .loaded)
    #expect(coordinator.renderStateID == renderIDBefore)
  }

  @Test
  func `replaceAll transitions phase to .loaded`() {
    let coordinator = ChatCoordinator.makeForTesting()
    #expect(coordinator.renderState.phase == .uninitialized)

    coordinator.replaceAll([MessageDTO.testDirectMessage()])

    #expect(coordinator.renderState.phase == .loaded)
  }

  @Test
  func `replaceAll with empty list still transitions to .loaded`() {
    let coordinator = ChatCoordinator.makeForTesting()
    coordinator.beginLoading()

    coordinator.replaceAll([])

    #expect(coordinator.renderState.phase == .loaded)
    #expect(coordinator.messages.isEmpty)
  }

  /// Multi-view-model scenario: two `ChatViewModel`s pointing at the same
  /// conversation (iPad split view, sheet dismissal) share a single
  /// coordinator instance from the registry. When one VM's load
  /// completes, both VMs observe `phase == .loaded` because both read
  /// through the shared `renderState`. The empty-state gate stays
  /// consistent across siblings.
  @Test
  func `phase is observed identically by sibling view models sharing one coordinator`() throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)

    let radioID = UUID()
    let contactID = UUID()
    let conversationID = ChatConversationID.dm(radioID: radioID, contactID: contactID)
    let coordinatorA = registry.coordinator(for: conversationID)
    let coordinatorB = registry.coordinator(for: conversationID)

    #expect(coordinatorA === coordinatorB)
    #expect(coordinatorA.renderState.phase == .uninitialized)

    coordinatorA.replaceAll([])

    #expect(coordinatorA.renderState.phase == .loaded)
    #expect(coordinatorB.renderState.phase == .loaded)
  }

  /// Same regression scope as `applyReloadedIDs_invokesRenderItemRebuilder`,
  /// but verifies the rebuilder is not invoked for IDs that are not present
  /// in the coordinator's canonical `messages` array. Paginated-out
  /// messages must not fire a per-ID rebuild because there is no
  /// corresponding `MessageItem` to refresh.
  @Test
  func `applyReloadedIDs skips renderItemRebuilder for unknown IDs`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let registry = ChatCoordinatorRegistry(dataStore: dataStore)

    let radioID = UUID()
    let contactID = UUID()
    let conversationID = ChatConversationID.dm(radioID: radioID, contactID: contactID)
    let coordinator = registry.coordinator(for: conversationID)

    let pagedOut = MessageDTO.testDirectMessage(
      radioID: radioID,
      contactID: contactID,
      text: "paged out",
      status: .sent
    )
    try await dataStore.saveMessage(pagedOut)

    let rebuiltIDs = MainActorBox<[UUID]>(value: [])
    coordinator.renderItemRebuilder = { id in
      rebuiltIDs.value.append(id)
    }

    coordinator.enqueueReload(messageID: pagedOut.id)

    let deadline = ContinuousClock.now + .seconds(1)
    while coordinator.reloadInFlight, ContinuousClock.now < deadline {
      await Task.yield()
    }

    #expect(rebuiltIDs.value.isEmpty)
  }
}

/// Test-only main-actor mutable box for closure-side recording.
@MainActor
private final class MainActorBox<Value> {
  var value: Value
  init(value: Value) {
    self.value = value
  }
}
