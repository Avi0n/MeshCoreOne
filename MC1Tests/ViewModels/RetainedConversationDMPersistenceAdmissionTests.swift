import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Retained DM persistence admission")
@MainActor
struct RetainedConversationDMPersistenceAdmissionTests {
  @Test(arguments: RetainedConversationPartitionTests.ChatAction.allCases, handoffCases)
  func `identity handoff refuses new DM persistence`(
    action: RetainedConversationPartitionTests.ChatAction, scenario: (window: HandoffWindow, live: TransportType)
  ) async throws {
    try await RetainedConversationPartitionHarness.withHarness(live: scenario.live) { harness in
      let pending = try await harness.seedDirectPending()
      let source = try #require(harness.manager.currentConnection)
      let gate = Gate()
      switch scenario.window {
      case .installedTerminal: harness.manager.testIdentityBeforeCommit = { await gate.wait() }
      case .selectedTail: await harness.radio.holdSubmissions(for: .setOtherParams)
      case .finalSelection: harness.manager.testConnectionSelectionHandler = { _ in await gate.wait() }
      }
      let importing = Task {
        try await harness.importCanonicalIdentity(includeSelectedTail: scenario.window != .installedTerminal)
      }
      do {
        try await harness.waitUntil {
          if scenario.window == .selectedTail {
            return await harness.radio.submissionIsHeld(for: .setOtherParams)
          }
          return gate.arrived
        }
        let originalPartitionStillSelected = harness.environment.appState.currentRadioID == harness.contact.radioID
        let sourceQueueRefused = harness.environment.appState.connectionSnapshot(for: harness.contact.radioID) == nil
        let handoffIsConnected = harness.manager.connectionState == .connected
        try #require(originalPartitionStillSelected)
        try #require(sourceQueueRefused)
        try #require(handoffIsConnected)
        if scenario.window == .installedTerminal {
          let installedSourceIsTerminal = harness.manager.isCurrent(source) && !source.services.isAcceptingRuntimeWork
          try #require(installedSourceIsTerminal)
        } else {
          let candidate = try #require(harness.manager.desiredRequest?.attempt)
          let canonicalCandidateIsPrivate = harness.manager.currentConnection == nil && harness.manager.services == nil
            && candidate.identity.radioID == harness.canonicalRadioID && candidate.preparedServices != nil
            && candidate.binding.isActive
          try #require(canonicalCandidateIsPrivate)
        }
        let keyWriteCount = await harness.radio.sentData.filter { $0.first == CommandCode.importPrivateKey.rawValue }.count
        try #require(keyWriteCount == 1)
        let before = try await harness.store.fetchMessage(id: harness.directMessage.id)
        let pendingBefore = try await harness.store.fetchPendingSendsForMessage(messageID: harness.directMessage.id)
        let originalFailedMessageAndPending = before == harness.directMessage && pendingBefore == [pending]
        try #require(originalFailedMessageAndPending)
        let timelineBefore = harness.directModel.messages
        let framesBefore = await harness.messageFrameCount()
        try await expectLocalHistory(harness)

        await perform(action, in: harness)

        let after = try await harness.store.fetchMessage(id: harness.directMessage.id)
        let pendingAfter = try await harness.store.fetchPendingSendsForMessage(messageID: harness.directMessage.id)
        let messageUnchanged = after == before
        let pendingIdentityAndContentsUnchanged = pendingAfter == pendingBefore
        let timelineUnchanged = harness.directModel.messages == timelineBefore
        let noMessageFrame = await harness.messageFrameCount() == framesBefore
        let canonicalPending = try await harness.store.fetchPendingSends(radioID: harness.canonicalRadioID)
        let noCanonicalPending = !canonicalPending.contains { $0.messageID == harness.directMessage.id }
        #expect(messageUnchanged)
        #expect(pendingIdentityAndContentsUnchanged)
        #expect(timelineUnchanged)
        #expect(noMessageFrame)
        #expect(noCanonicalPending)
        try await expectLocalHistory(harness)

        await releaseHandoff(harness, gate: gate)
        try await importing.value
        let canonicalBPublished = harness.manager.connectedDevice?.radioID == harness.canonicalRadioID
        #expect(canonicalBPublished)
        let sourcePendingAfterHandoff = try await harness.store.fetchPendingSendsForMessage(messageID: harness.directMessage.id)
        let originalPendingPreservedAfterHandoff = sourcePendingAfterHandoff == pendingBefore
        #expect(originalPendingPreservedAfterHandoff)
        try await harness.proveLaterIO()
      } catch {
        await releaseHandoff(harness, gate: gate)
        _ = await importing.result
        throw error
      }
    }
  }

  @Test(arguments: RetainedConversationPartitionTests.ChatAction.allCases, offlineCases)
  func `disconnected DM persistence requires same partition`(
    action: RetainedConversationPartitionTests.ChatAction, scenario: (canonicalB: Bool, live: TransportType)
  ) async throws {
    try await RetainedConversationPartitionHarness.withHarness(live: scenario.live) { harness in
      let pending = try await harness.seedDirectPending()
      if scenario.canonicalB { try await harness.importCanonicalIdentity() }
      await harness.environment.appState.disconnect(reason: .userInitiated)
      let before = try await harness.store.fetchMessage(id: harness.directMessage.id)
      let pendingBefore = try await harness.store.fetchPendingSendsForMessage(messageID: harness.directMessage.id)
      let seededOriginalWorkSurvives = before == harness.directMessage && pendingBefore == [pending]
      try #require(seededOriginalWorkSurvives)
      let fullyDisconnected = harness.manager.connectionState == .disconnected && harness.manager.currentConnection == nil
      try #require(fullyDisconnected)
      let framesBefore = await harness.messageFrameCount()
      if !scenario.canonicalB { try await expectLocalHistory(harness) }

      await perform(action, in: harness)

      let after = try await harness.store.fetchMessage(id: harness.directMessage.id)
      let pendingAfter = try await harness.store.fetchPendingSendsForMessage(messageID: harness.directMessage.id)
      if scenario.canonicalB {
        let foreignMessageUnchanged = after == before
        let foreignPendingUnchanged = pendingAfter == pendingBefore
        #expect(foreignMessageUnchanged)
        #expect(foreignPendingUnchanged)
      } else {
        let expectedMessage = harness.directMessage.copy { $0.status = .pending }
        let samePartitionMessagePending = after == expectedMessage
        let replacement = try #require(pendingAfter.first)
        let originalPendingReplacedInA = pendingAfter.count == 1 && replacement.id != pending.id
          && replacement.radioID == harness.contact.radioID && replacement.messageID == harness.directMessage.id
          && replacement.contactID == harness.contact.id && replacement.isResend == (action == .sendAgain)
        #expect(samePartitionMessagePending)
        #expect(originalPendingReplacedInA)
        try await expectLocalHistory(harness)
      }
      let noOfflineMessageFrame = await harness.messageFrameCount() == framesBefore
      let canonicalPending = try await harness.store.fetchPendingSends(radioID: harness.canonicalRadioID)
      let noCanonicalPending = !canonicalPending.contains { $0.messageID == harness.directMessage.id }
      #expect(noOfflineMessageFrame)
      #expect(noCanonicalPending)
    }
  }

  @Test(arguments: RetainedConversationPartitionTests.ChatAction.allCases, liveCases)
  func `current DM persistence reaches real queue`(
    action: RetainedConversationPartitionTests.ChatAction, scenario: (phase: DeviceConnectionState, live: TransportType)
  ) async throws {
    try await RetainedConversationPartitionHarness.withHarness(live: scenario.live) { harness in
      let pending = try await harness.seedDirectPending()
      let link = try #require(harness.manager.installedLink)
      harness.manager.transition(link, to: scenario.phase)
      let currentSourceAdmitted = harness.environment.appState.connectionSnapshot(for: harness.contact.radioID) != nil
      try #require(currentSourceAdmitted)
      let framesBefore = await harness.messageFrameCount()
      await harness.radio.rejectNext(.sendMessage)
      await harness.radio.holdSubmissions(for: .sendMessage)
      do {
        await perform(action, in: harness)
        try await harness.waitUntil { await harness.radio.submissionIsHeld(for: .sendMessage) }
        let stored = try await harness.store.fetchMessage(id: harness.directMessage.id)
        let pendingAtSend = try await harness.store.fetchPendingSendsForMessage(messageID: harness.directMessage.id)
        let replacement = try #require(pendingAtSend.first)
        let actualQueueEntered = pendingAtSend.count == 1 && replacement.id != pending.id
          && replacement.radioID == harness.contact.radioID && replacement.messageID == harness.directMessage.id
          && replacement.contactID == harness.contact.id && replacement.isResend == (action == .sendAgain)
          && replacement.attemptCount == Self.firstDrainAttempt
        let storedMessagePending = stored?.status == .pending
        #expect(actualQueueEntered)
        #expect(storedMessagePending)
        await harness.radio.releaseSubmissions(for: .sendMessage)
        await harness.joinCurrentChatDrain()
        let actualMessageFrame = await harness.messageFrameCount() == framesBefore + 1
        let settledMessage = try await harness.store.fetchMessage(id: harness.directMessage.id)
        let pendingAfter = try await harness.store.fetchPendingSendsForMessage(messageID: harness.directMessage.id)
        let realRejectionSettled = settledMessage?.status == .failed && pendingAfter.isEmpty
        #expect(actualMessageFrame)
        #expect(realRejectionSettled)
        harness.manager.transition(link, to: .ready)
        try await harness.proveLaterIO()
      } catch {
        await harness.radio.releaseSubmissions(for: .sendMessage)
        await harness.joinCurrentChatDrain()
        throw error
      }
    }
  }

  private func perform(
    _ action: RetainedConversationPartitionTests.ChatAction, in harness: RetainedConversationPartitionHarness
  ) async {
    switch action {
    case .retry: await harness.directModel.retryMessage(harness.directMessage)
    case .sendAgain: await harness.directModel.sendAgain(harness.directMessage)
    }
  }

  private func expectLocalHistory(
    _ harness: RetainedConversationPartitionHarness, sourceLocation: SourceLocation = #_sourceLocation
  ) async throws {
    let history = try await harness.directModel.dataStore?.fetchMessages(contactID: harness.contact.id)
    let retainedLocalHistoryReadable = history?.contains { $0.id == harness.directMessage.id } == true
    #expect(retainedLocalHistoryReadable, sourceLocation: sourceLocation)
  }

  private func releaseHandoff(_ harness: RetainedConversationPartitionHarness, gate: Gate) async {
    gate.open()
    await harness.radio.releaseSubmissions(for: .setOtherParams)
    harness.manager.testIdentityBeforeCommit = nil
    harness.manager.testConnectionSelectionHandler = nil
  }

  enum HandoffWindow: CaseIterable, Sendable { case installedTerminal, selectedTail, finalSelection }

  private nonisolated static let handoffCases: [(window: HandoffWindow, live: TransportType)] =
    [TransportType.bluetooth, .wifi].flatMap { live in
      HandoffWindow.allCases.map { (window: $0, live: live) }
    }

  private nonisolated static let offlineCases: [(canonicalB: Bool, live: TransportType)] =
    [TransportType.bluetooth, .wifi].flatMap { live in
      [false, true].map { (canonicalB: $0, live: live) }
    }

  private nonisolated static let liveCases: [(phase: DeviceConnectionState, live: TransportType)] =
    [TransportType.bluetooth, .wifi].flatMap { live in
      [DeviceConnectionState.connected, .syncing, .ready].map { (phase: $0, live: live) }
    }

  private static let firstDrainAttempt = 1

  @MainActor
  private final class Gate {
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
}
