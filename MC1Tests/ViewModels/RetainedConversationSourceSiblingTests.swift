import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Retained conversation source siblings")
@MainActor
struct RetainedConversationSourceSiblingTests {
  @Test(arguments: RetainedConversationPartitionTests.RoomAction.allCases)
  func `terminal installed source refuses room mutation`(action: RetainedConversationPartitionTests.RoomAction) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let gate = Gate()
      harness.manager.testIdentityBeforeCommit = { await gate.wait() }
      let transition = Task { try await harness.importCanonicalIdentity() }
      do {
        try await harness.waitUntil { await gate.arrived }
        let source = harness.manager.currentConnection
        let installedAIsTerminal = source?.device.radioID == harness.room.radioID
          && source?.services.isAcceptingRuntimeWork == false
        try #require(installedAIsTerminal)
        let sourceStillManagerCurrent = source.map { harness.manager.isCurrent($0) } == true
        try #require(sourceStillManagerCurrent)
        let before = try await harness.store.fetchRoomMessages(sessionID: harness.room.id)
        let framesBefore = await harness.messageFrameCount()

        switch action {
        case .post: await harness.roomModel.sendMessage(text: Self.roomPost)
        case .retry: await harness.roomModel.retryMessage(id: harness.roomMessage.id)
        }

        let after = try await harness.store.fetchRoomMessages(sessionID: harness.room.id)
        let historyUnchanged = before == after
        let noTerminalFrame = await harness.messageFrameCount() == framesBefore
        #expect(historyUnchanged)
        #expect(noTerminalFrame)
        await gate.open()
        try await transition.value
        harness.manager.testIdentityBeforeCommit = nil
        try await harness.proveLaterIO()
      } catch {
        await gate.open()
        _ = try? await transition.value
        harness.manager.testIdentityBeforeCommit = nil
        throw error
      }
    }
  }

  @Test(arguments: [DeviceConnectionState.connected, .syncing, .ready])
  func `ordinary installed source can load room history`(phase: DeviceConnectionState) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let link = harness.manager.installedLink
      let installed = link != nil
      try #require(installed)
      guard let link else { return }
      harness.manager.transition(link, to: phase)
      let appState = harness.environment.appState
      let radioID = harness.room.radioID
      let model = RoomConversationViewModel()
      model.configure(
        roomServerService: { appState.connectionSnapshot(for: radioID)?.services.roomServerService },
        dataStore: { appState.connectionSnapshot(for: radioID)?.services.dataStore },
        syncCoordinator: { appState.connectionSnapshot(for: radioID)?.services.syncCoordinator },
        notificationService: { appState.connectionSnapshot(for: radioID)?.services.notificationService }
      )

      await model.loadMessages(for: harness.room)

      let loadedStoredHistory = model.messages.contains { $0.id == harness.roomMessage.id }
      #expect(loadedStoredHistory)
      harness.manager.transition(link, to: .ready)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: RetainedConversationPartitionTests.RoomAction.allCases)
  func `same partition room actions still reach real wire`(action: RetainedConversationPartitionTests.RoomAction) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let framesBefore = await harness.messageFrameCount()
      await harness.radio.rejectNext(.sendMessage)

      switch action {
      case .post:
        await harness.roomModel.sendMessage(text: Self.roomPost)
        try await harness.waitUntil {
          await (try? harness.store.fetchRoomMessages(sessionID: harness.room.id).contains {
            $0.text == Self.roomPost && $0.status == .failed
          }) == true
        }
      case .retry:
        await harness.roomModel.retryMessage(id: harness.roomMessage.id)
        let updated = try await harness.store.fetchRoomMessage(id: harness.roomMessage.id)
        let actualRetryRecorded = updated?.retryAttempt == harness.roomMessage.retryAttempt + 1
        #expect(actualRetryRecorded)
      }

      let actualSendEntered = await harness.messageFrameCount() == framesBefore + 1
      #expect(actualSendEntered)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: [false, true])
  func `offline retry only mutates current partition`(canonicalB: Bool) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      if canonicalB { try await harness.importCanonicalIdentity() }
      await harness.environment.appState.disconnect(reason: .userInitiated)
      let before = try await harness.store.fetchMessage(id: harness.directMessage.id)
      let framesBefore = await harness.messageFrameCount()

      await harness.directModel.retryMessage(harness.directMessage)

      let sourcePending = try await harness.store.fetchPendingSends(radioID: harness.contact.radioID)
      let pendingMatchesAdmission = sourcePending.contains { $0.messageID == harness.directMessage.id } == !canonicalB
      #expect(pendingMatchesAdmission)
      let after = try await harness.store.fetchMessage(id: harness.directMessage.id)
      let statusMatchesAdmission = canonicalB ? after == before : after?.status == .pending
      #expect(statusMatchesAdmission)
      let noOfflineFrame = await harness.messageFrameCount() == framesBefore
      #expect(noOfflineFrame)
      let foreignPending = try await harness.store.fetchPendingSends(radioID: harness.canonicalRadioID)
      let noForeignPending = !foreignPending.contains { $0.messageID == harness.directMessage.id }
      #expect(noForeignPending)
    }
  }

  @Test
  func `Persist-to-signal split-boundary equivalence preserves accepted A work`() async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let envelope = DirectMessageEnvelope(
        messageID: harness.directMessage.id, contactID: harness.contact.id, isResend: true
      )
      let pending = PendingSendDTO(envelope: envelope, radioID: harness.contact.radioID)
      _ = try await harness.store.replacePendingSendForRetry(messageID: harness.directMessage.id, dto: pending)
      try await harness.importCanonicalIdentity()
      let acceptedBefore = try await harness.store.fetchPendingSends(radioID: harness.contact.radioID)
      let acceptedAExists = acceptedBefore.contains { $0.id == pending.id }
      try #require(acceptedAExists)
      let framesBefore = await harness.messageFrameCount()
      var refusedNewQueue = false

      do {
        try await harness.directModel.signalDMEnqueued(envelope)
      } catch ChatSendQueueServiceError.notConnected {
        refusedNewQueue = true
      }
      await harness.joinCurrentChatDrain()

      #expect(refusedNewQueue)
      let noForeignFrame = await harness.messageFrameCount() == framesBefore
      #expect(noForeignFrame)
      let acceptedAfter = try await harness.store.fetchPendingSends(radioID: harness.contact.radioID)
      let acceptedWorkPreserved = acceptedAfter == acceptedBefore
      #expect(acceptedWorkPreserved)
      let foreignPending = try await harness.store.fetchPendingSends(radioID: harness.canonicalRadioID)
      let noForeignPending = !foreignPending.contains { $0.messageID == harness.directMessage.id }
      #expect(noForeignPending)
      try await harness.proveLaterIO()
    }
  }

  private actor Gate {
    private(set) var arrived = false
    private var opened = false
    private var continuation: CheckedContinuation<Void, Never>?

    func wait() async {
      arrived = true
      guard !opened else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      opened = true
      continuation?.resume()
      continuation = nil
    }
  }

  private static let roomPost = "Refuse terminal A post"
}
