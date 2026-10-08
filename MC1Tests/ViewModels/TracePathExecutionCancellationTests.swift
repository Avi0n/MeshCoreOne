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

@MainActor
private final class AppendGate {
  private(set) var started = false
  private var continuation: CheckedContinuation<Void, Never>?

  func wait() async {
    started = true
    await withCheckedContinuation { continuation = $0 }
  }

  func release() {
    continuation?.resume()
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
    send: ControllableTraceSend? = nil
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
    viewModel.addNode(makeContact())
    return viewModel
  }

  private func traceInfo(tag: UInt32) -> TraceInfo {
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

  private func makeServices() throws -> ServiceContainer {
    try ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true)),
      radioID: UUID()
    )
  }

  @Test
  func `hiding during the response wait still accepts the response`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "wait should be armed") {
      viewModel.pendingTagForTesting != nil
    }
    viewModel.setWorkspaceVisible(false)
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)

    #expect(viewModel.result?.success == true)
    #expect(viewModel.isRunning == false)
  }

  @Test
  func `explicit cancel drops a late ACK and a late response`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "send should start") {
      send.sendCount == 1
    }
    viewModel.cancelExecution()
    send.completeOldest()
    try await Task.sleep(for: .milliseconds(30))

    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.result == nil)
    #expect(viewModel.pendingTagForTesting == nil)
    #expect(viewModel.hasActiveTimeoutTaskForTesting == false)
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `explicit cancel during the response wait drops a late response`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "wait should be armed") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.cancelExecution()
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)

    #expect(viewModel.result == nil)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.completedResults.isEmpty)
  }

  @Test
  func `explicit cancel between batch members keeps completed results and drops a late ACK`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "first member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await waitUntil(timeout: .seconds(2), "second send should start") {
      send.sendCount == 2
    }

    viewModel.cancelBatchTrace()
    send.completeOldest()
    try await Task.sleep(for: .milliseconds(40))

    #expect(viewModel.completedResults.count == 1)
    #expect(viewModel.completedResults[0].success)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
    #expect(send.sendCount == 2)
  }

  @Test
  func `replacement startTrace drops the old send cleanup`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "first member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await waitUntil(timeout: .seconds(2), "second send should start") {
      send.sendCount == 2
    }

    #expect(viewModel.completedResults.count == 1)
    viewModel.startTrace()
    send.completeOldest()
    try await Task.sleep(for: .milliseconds(40))

    #expect(viewModel.completedResults.isEmpty)
    #expect(viewModel.errorMessage == nil)
    try await waitUntil(timeout: .seconds(1), "replacement send should be in flight") {
      send.sendCount == 3 && viewModel.isRunning
    }
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
  func `reset clears the path and results and does not append a run`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let saved = try await store.createSavedTracePath(
      radioID: UUID(),
      name: "North",
      pathBytes: Data([0xAB]),
      hashSize: 1,
      initialRun: nil
    )
    let gate = AppendGate()
    let viewModel = makeViewModel(send: ControllableTraceSend())
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { store },
      session: { nil },
      advertisementService: { nil },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    viewModel.beforeTraceRunAppendForTesting = { await gate.wait() }
    viewModel.throwBeforeTagForTesting = { throw MeshCoreError.timeout }
    viewModel.addNode(makeContact())
    viewModel.activeSavedPath = saved
    viewModel.result = TraceResult.sendFailed("stale", attemptedPath: [0xAB], hashSize: 1)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "append should reach the gate") {
      gate.started
    }
    viewModel.reset()
    gate.release()
    try await Task.sleep(for: .milliseconds(50))

    let stored = try await store.fetchSavedTracePath(id: saved.id)
    #expect(stored?.runs.isEmpty == true)
    #expect(viewModel.outboundPath.isEmpty)
    #expect(viewModel.result == nil)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.completedResults.isEmpty)
  }

  @Test
  func `a new advertisement service receives the response without a reset`() async throws {
    let oldServices = try makeServices()
    var currentServices: ServiceContainer? = oldServices
    let viewModel = makeViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { currentServices?.advertisementService },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    viewModel.setPendingTagForTesting(0x00C0_FFEE)
    viewModel.isRunning = true
    viewModel.startListening()

    oldServices.advertisementService.finishEvents()
    let newServices = try makeServices()
    currentServices = newServices
    viewModel.reattachIfWaiting()
    newServices.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: traceInfo(tag: 0x00C0_FFEE), radioID: UUID())
    )

    try await waitUntil(timeout: .seconds(1), "rebound service should deliver the response") {
      viewModel.result != nil
    }
    #expect(viewModel.result?.success == true)
    #expect(viewModel.outboundPath.count == 1)
  }

  @Test
  func `reset does not attach again on the following ready`() async throws {
    let services = try makeServices()
    var currentServices: ServiceContainer? = services
    let viewModel = makeViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { currentServices?.advertisementService },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    viewModel.addNode(makeContact())
    viewModel.setPendingTagForTesting(0x00C0_FFEE)
    viewModel.startListening()
    viewModel.reset()

    let replacement = try makeServices()
    currentServices = replacement
    viewModel.reattachIfWaiting()
    replacement.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: traceInfo(tag: 0x00C0_FFEE), radioID: UUID())
    )
    try await Task.sleep(for: .milliseconds(50))

    #expect(viewModel.result == nil)
    #expect(viewModel.outboundPath.isEmpty)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `stream end with a nil provider keeps the run waiting for a later ready`() async throws {
    let services = try makeServices()
    var currentServices: ServiceContainer? = services
    let viewModel = makeViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { currentServices?.advertisementService },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    viewModel.setPendingTagForTesting(0x00C0_FFEE)
    viewModel.isRunning = true
    viewModel.startListening()
    services.advertisementService.finishEvents()
    currentServices = nil
    try await Task.sleep(for: .milliseconds(40))

    #expect(viewModel.pendingTagForTesting == 0x00C0_FFEE)
    #expect(viewModel.result == nil)
    #expect(viewModel.isRunning)
    #expect(viewModel.errorMessage == nil)

    let ready = try makeServices()
    currentServices = ready
    viewModel.reattachIfWaiting()
    ready.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: traceInfo(tag: 0x00C0_FFEE), radioID: UUID())
    )
    try await waitUntil(timeout: .seconds(1), "later ready should accept the tag") {
      viewModel.result != nil
    }
  }

  @Test
  func `a throw after the tag leaves it in place until the deadline`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.unackedTimeoutSecondsForTesting = 0.05
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "send should start") {
      send.sendCount == 1
    }
    send.failOldest(MeshCoreError.timeout)
    try await Task.sleep(for: .milliseconds(20))

    #expect(viewModel.pendingTagForTesting != nil)
    #expect(viewModel.errorMessage == nil)

    try await waitUntil(timeout: .seconds(1), "deadline should fail the run") {
      viewModel.errorMessage != nil
    }
    #expect(viewModel.pendingTagForTesting == nil)
    #expect(viewModel.result == nil)
  }

  @Test
  func `a throw before the tag sets errorMessage while the ticket is current`() async throws {
    let viewModel = makeViewModel(send: ControllableTraceSend())
    viewModel.throwBeforeTagForTesting = { throw MeshCoreError.timeout }
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "pre-tag failure should surface") {
      viewModel.errorMessage != nil
    }
    #expect(viewModel.pendingTagForTesting == nil)
    #expect(viewModel.isRunning == false)
  }

  @Test
  func `response then expiry keeps the success`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 0.05
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "tag should be assigned") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await Task.sleep(for: .milliseconds(80))
    viewModel.expireWaitingRunIfDeadlinePassed()

    #expect(viewModel.result?.success == true)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `expiry then response does not set result`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 0.05
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "tag should be assigned") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    try await waitUntil(timeout: .seconds(1), "deadline should win") {
      viewModel.errorMessage != nil
    }
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)

    #expect(viewModel.result == nil)
  }

  @Test
  func `hidden errorMessage stays until clearError`() async throws {
    let viewModel = makeViewModel()
    viewModel.errorAutoClearDelay = .milliseconds(40)
    viewModel.setWorkspaceVisible(false)
    viewModel.setError("radio silent")
    try await Task.sleep(for: .milliseconds(80))
    viewModel.setWorkspaceVisible(true)

    #expect(viewModel.errorMessage == "radio silent")
    viewModel.clearError()
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `hiding cancels a clear that was armed while visible`() async throws {
    let viewModel = makeViewModel()
    viewModel.errorAutoClearDelay = .milliseconds(40)
    viewModel.setWorkspaceVisible(true)
    viewModel.setError("radio silent")
    viewModel.setWorkspaceVisible(false)
    try await Task.sleep(for: .milliseconds(80))

    #expect(viewModel.errorMessage == "radio silent")
  }

  @Test
  func `hidden result-sheet dismiss does not cancel the batch and presents again`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.setWorkspaceVisible(true)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "first member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await waitUntil(timeout: .seconds(2), "second send should start") {
      send.sendCount == 2
    }

    viewModel.setWorkspaceVisible(false)
    viewModel.noteResultSheetDismissed()
    #expect(viewModel.isRunning)
    #expect(send.sendCount == 2)

    viewModel.setWorkspaceVisible(true)
    let presented = viewModel.consumeStoredResultForPresentation()
    #expect(presented?.success == true)
    #expect(viewModel.consumeStoredResultForPresentation() == nil)
    #expect(viewModel.isRunning)
  }

  @Test
  func `visible result-sheet dismiss cancels the remainder and does not present again`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.setWorkspaceVisible(true)
    viewModel.startTrace()

    try await waitUntil(timeout: .seconds(1), "first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil(timeout: .seconds(1), "first member should be waiting") {
      viewModel.pendingTagForTesting != nil
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await waitUntil(timeout: .seconds(2), "second send should start") {
      send.sendCount == 2
    }

    let shown = viewModel.consumeStoredResultForPresentation()
    #expect(shown != nil)
    viewModel.noteResultSheetDismissed()

    #expect(viewModel.isRunning == false)
    #expect(viewModel.consumeStoredResultForPresentation() == nil)
    try await Task.sleep(for: .milliseconds(40))
    #expect(send.sendCount == 2)
  }
}
