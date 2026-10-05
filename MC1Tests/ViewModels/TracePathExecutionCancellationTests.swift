import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

/// Controllable sendTrace stand-in. Completing after cancel is a late ACK.
@MainActor
private final class ControllableTraceSend {
  private(set) var sendCount = 0
  var hangUntilComplete = true
  var info = MessageSentInfo(route: 1, expectedAck: Data(), suggestedTimeoutMs: 1000)
  private var continuations: [CheckedContinuation<MessageSentInfo, Error>] = []

  func send(tag: UInt32, flags: UInt8, path: Data) async throws -> MessageSentInfo {
    sendCount += 1
    guard hangUntilComplete else { return info }
    return try await withCheckedThrowingContinuation { continuations.append($0) }
  }

  func completeOldest() {
    guard !continuations.isEmpty else { return }
    continuations.removeFirst().resume(returning: info)
  }

  func failOldest(_ error: Error) {
    guard !continuations.isEmpty else { return }
    continuations.removeFirst().resume(throwing: error)
  }
}

/// Controllable saved-path lookup. Completing after cancel is a late match.
@MainActor
private final class ControllableSavedPathLookup {
  private(set) var lookupCount = 0
  var result: SavedTracePathDTO?
  private var continuation: CheckedContinuation<SavedTracePathDTO?, Never>?

  func lookup() async -> SavedTracePathDTO? {
    lookupCount += 1
    return await withCheckedContinuation { continuation = $0 }
  }

  func complete() {
    continuation?.resume(returning: result)
    continuation = nil
  }
}

@Suite("Trace Path execution cancellation")
@MainActor
struct TracePathExecutionCancellationTests {
  private func makeContact() -> ContactDTO {
    let contact = Contact(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data([0xAB] + Array(repeating: UInt8(0x00), count: 31)),
      name: "Repeater",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      lastHeardTimestamp: 0
    )
    return ContactDTO(from: contact)
  }

  private func makeViewModel(
    send: ControllableTraceSend? = nil,
    savedPath: ControllableSavedPathLookup? = nil
  ) -> TracePathViewModel {
    let viewModel = TracePathViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { nil },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    if let send {
      viewModel.sendTraceForTesting = { tag, flags, path in
        try await send.send(tag: tag, flags: flags, path: path)
      }
    }
    if let savedPath {
      viewModel.matchingSavedPathForTesting = {
        await savedPath.lookup()
      }
    }
    viewModel.addNode(makeContact())
    return viewModel
  }

  private func sampleTraceInfo(tag: UInt32) -> TraceInfo {
    TraceInfo(
      tag: tag,
      authCode: 0,
      flags: 0,
      pathLength: 1,
      path: [
        TraceNode(hash: 0xAB, snr: 5.0),
        TraceNode(hash: nil, snr: 3.0)
      ]
    )
  }

  @Test
  func `cancel before send ACK drops a late ACK without recording failure`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "send should start") {
      send.sendCount == 1
    }

    viewModel.cancelExecution()
    send.completeOldest()
    try await Task.sleep(for: .milliseconds(30))

    #expect(send.sendCount == 1)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.result == nil)
    #expect(viewModel.completedResults.isEmpty)
    #expect(viewModel.hasActiveTimeoutTaskForTesting == false)
    #expect(viewModel.pendingTagForTesting == nil)
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `inactive before send ACK still completes the generation`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "send should start") {
      send.sendCount == 1
    }

    viewModel.noteWorkspaceVisible(false)
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "timeout wait should be armed") {
      viewModel.hasActiveTimeoutTaskForTesting && viewModel.pendingTagForTesting != nil
    }

    #expect(viewModel.isRunning)
    #expect(viewModel.errorMessage == nil)
    #expect(send.sendCount == 1)
  }

  @Test
  func `cancel during response wait ignores a late trace response`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "timeout wait should be armed") {
      viewModel.hasActiveTimeoutTaskForTesting && viewModel.pendingTagForTesting != nil
    }

    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.cancelExecution()

    viewModel.handleTraceResponse(sampleTraceInfo(tag: tag), radioID: nil)

    #expect(viewModel.result == nil)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.hasActiveTimeoutTaskForTesting == false)
    #expect(viewModel.completedResults.isEmpty)
  }

  @Test
  func `inactive during response wait still applies the response`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "timeout wait should be armed") {
      viewModel.hasActiveTimeoutTaskForTesting && viewModel.pendingTagForTesting != nil
    }

    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.noteWorkspaceVisible(false)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: tag), radioID: nil)

    #expect(viewModel.result?.success == true)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `cancel during saved-path lookup does not send`() async throws {
    let send = ControllableTraceSend()
    let lookup = ControllableSavedPathLookup()
    let viewModel = makeViewModel(send: send, savedPath: lookup)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "lookup should start") {
      lookup.lookupCount == 1
    }

    viewModel.cancelExecution()
    lookup.complete()
    try await Task.sleep(for: .milliseconds(30))

    #expect(send.sendCount == 0)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.activeSavedPath == nil)
  }

  @Test
  func `inactive during saved-path lookup still sends`() async throws {
    let send = ControllableTraceSend()
    let lookup = ControllableSavedPathLookup()
    let viewModel = makeViewModel(send: send, savedPath: lookup)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "lookup should start") {
      lookup.lookupCount == 1
    }

    viewModel.noteWorkspaceVisible(false)
    lookup.complete()
    try await waitUntil(timeout: .seconds(1), "send should start after lookup") {
      send.sendCount == 1
    }

    #expect(viewModel.isRunning)
  }

  @Test
  func `cancel between batch members keeps completed results and drops a late ACK`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first batch send should start") {
      send.sendCount == 1
    }
    send.completeOldest()

    try await waitUntil(timeout: .seconds(1), "first batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: tag), radioID: nil)

    try await waitUntil(timeout: .seconds(2), "second batch send should start") {
      send.sendCount == 2
    }

    #expect(viewModel.completedResults.count == 1)
    viewModel.cancelExecution()
    send.completeOldest()
    try await Task.sleep(for: .milliseconds(30))

    #expect(send.sendCount == 2)
    #expect(viewModel.completedResults.count == 1)
    #expect(viewModel.completedResults[0].success)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.hasActiveTimeoutTaskForTesting == false)
  }

  @Test
  func `inactive between batch members still sends the next member`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first batch send should start") {
      send.sendCount == 1
    }
    send.completeOldest()

    try await waitUntil(timeout: .seconds(1), "first batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: tag), radioID: nil)

    viewModel.noteWorkspaceVisible(false)

    try await waitUntil(timeout: .seconds(2), "second batch send should start") {
      send.sendCount == 2
    }

    #expect(viewModel.isRunning)
    #expect(viewModel.completedResults.count == 1)
  }

  @Test
  func `old send cleanup does not clear a replacement operation`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first send should start") {
      send.sendCount == 1
    }

    viewModel.startTrace()
    try await waitUntil(timeout: .seconds(1), "replacement send should start") {
      send.sendCount == 2
    }

    send.completeOldest()
    try await Task.sleep(for: .milliseconds(30))

    #expect(viewModel.isRunning)
    #expect(viewModel.pendingTagForTesting != nil)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.hasActiveTimeoutTaskForTesting == false)

    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "replacement timeout wait should arm") {
      viewModel.hasActiveTimeoutTaskForTesting
    }
    #expect(viewModel.isRunning)
  }

  @Test
  func `canceled send error does not record a failure`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "send should start") {
      send.sendCount == 1
    }

    viewModel.cancelExecution()
    send.failOldest(MeshCoreError.timeout)
    try await Task.sleep(for: .milliseconds(30))

    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.completedResults.isEmpty)
    #expect(viewModel.result == nil)
    #expect(viewModel.isRunning == false)
  }

  @Test
  func `reset clears the workspace without a timeout record`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "send should start") {
      send.sendCount == 1
    }

    viewModel.reset()
    send.completeOldest()
    try await Task.sleep(for: .milliseconds(30))

    #expect(viewModel.outboundPath.isEmpty)
    #expect(viewModel.result == nil)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.completedResults.isEmpty)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.pendingTagForTesting == nil)
  }

  @Test
  func `listener restart does not bump the generation`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    viewModel.allowListeningWithoutServiceForTesting = true
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "timeout wait should be armed") {
      viewModel.pendingTagForTesting != nil
    }

    let generation = viewModel.executionGenerationForTesting
    let subscribed = viewModel.subscribeCountForTesting
    viewModel.startListening()
    viewModel.startListening()
    #expect(viewModel.subscribeCountForTesting == subscribed)
    #expect(viewModel.executionGenerationForTesting == generation)

    viewModel.simulateEventStreamFinishedForTesting()
    #expect(viewModel.subscribeCountForTesting == subscribed + 1)
    #expect(viewModel.executionGenerationForTesting == generation)
    #expect(viewModel.isRunning)
  }

  @Test
  func `foreground return expires a passed deadline`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    send.info = MessageSentInfo(route: 1, expectedAck: Data(), suggestedTimeoutMs: 60_000)
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "timeout wait should be armed") {
      viewModel.hasActiveTimeoutTaskForTesting && viewModel.pendingTagForTesting != nil
    }

    viewModel.setTraceDeadlineForTesting(.distantPast)
    viewModel.expireTraceIfDeadlinePassed()

    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == L10n.Contacts.Contacts.Trace.Error.noResponse)
    #expect(viewModel.pendingTagForTesting == nil)
  }

  @Test
  func `hidden failure stays until clearError`() async throws {
    let viewModel = makeViewModel()
    viewModel.errorAutoClearDelay = .seconds(4)
    viewModel.noteWorkspaceVisible(false)
    viewModel.setError(L10n.Contacts.Contacts.Trace.Error.noResponse)

    viewModel.noteWorkspaceVisible(true)
    try await Task.sleep(for: .seconds(5))

    #expect(viewModel.errorMessage == L10n.Contacts.Contacts.Trace.Error.noResponse)
    viewModel.clearError()
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `reentry starts listening without restarting a trace`() {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.allowListeningWithoutServiceForTesting = true
    viewModel.startListening()
    viewModel.noteWorkspaceVisible(false)
    viewModel.startListening()

    #expect(viewModel.isRunning == false)
    #expect(send.sendCount == 0)
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `hidden first batch success presents once when finishing on screen`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.batchEnabled = true
    viewModel.batchSize = 2
    viewModel.noteWorkspaceVisible(false)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first batch send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "first batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let firstTag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: firstTag), radioID: nil)

    try await waitUntil(timeout: .seconds(1), "first success should publish resultID") {
      viewModel.resultID != nil
    }
    let firstResultID = try #require(viewModel.resultID)
    viewModel.noteWorkspaceVisible(true)
    #expect(viewModel.takeUnpresentedResultIfNeeded() == nil)

    try await waitUntil(timeout: .seconds(2), "second batch send should start") {
      send.sendCount == 2
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "second batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let secondTag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: secondTag), radioID: nil)

    try await waitUntil(timeout: .seconds(2), "batch should finish with a new resultID") {
      viewModel.isRunning == false && viewModel.resultID != firstResultID
    }
    #expect(viewModel.takeUnpresentedResultIfNeeded() == nil)
  }

  @Test
  func `hidden batch finish presents once on return without changing resultID`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.batchEnabled = true
    viewModel.batchSize = 2
    viewModel.noteWorkspaceVisible(false)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first batch send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "first batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let firstTag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: firstTag), radioID: nil)
    try await waitUntil(timeout: .seconds(1), "first success should publish resultID") {
      viewModel.resultID != nil
    }
    let firstResultID = try #require(viewModel.resultID)

    try await waitUntil(timeout: .seconds(2), "second batch send should start") {
      send.sendCount == 2
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "second batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let secondTag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: secondTag), radioID: nil)

    try await waitUntil(timeout: .seconds(2), "batch should finish while hidden") {
      viewModel.isRunning == false
    }
    #expect(viewModel.resultID == firstResultID)

    viewModel.noteWorkspaceVisible(true)
    let presented = try #require(viewModel.takeUnpresentedResultIfNeeded())
    #expect(presented.success)
    #expect(viewModel.resultID == firstResultID)
    #expect(viewModel.takeUnpresentedResultIfNeeded() == nil)
  }

  @Test
  func `visible first batch success does not change resultID at batch end`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.batchEnabled = true
    viewModel.batchSize = 2
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first batch send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "first batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let firstTag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: firstTag), radioID: nil)
    try await waitUntil(timeout: .seconds(1), "first success should publish resultID") {
      viewModel.resultID != nil
    }
    let firstResultID = try #require(viewModel.resultID)

    try await waitUntil(timeout: .seconds(2), "second batch send should start") {
      send.sendCount == 2
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "second batch member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let secondTag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(sampleTraceInfo(tag: secondTag), radioID: nil)

    try await waitUntil(timeout: .seconds(2), "batch should finish") {
      viewModel.isRunning == false
    }
    #expect(viewModel.resultID == firstResultID)
  }
}
