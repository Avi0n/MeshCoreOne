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

  func completeAll() {
    let pending = continuations
    continuations.removeAll()
    for continuation in pending {
      continuation.resume(returning: info)
    }
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

@Suite("Trace Path execution cancellation", .timeLimit(.minutes(1)))
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
    clock: (any Clock<Duration>)? = nil
  ) -> TracePathViewModel {
    let viewModel = TracePathViewModel(clock: clock)
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

  private func advanceBatchBuffer(on clock: TestClock) async throws {
    try await waitUntil("batch buffer should park") { clock.sleeperCount == 1 }
    clock.advance(by: .milliseconds(499))
    try #require(clock.sleeperCount == 1)
    clock.advance(by: .milliseconds(1))
    try #require(clock.sleeperCount == 0)
  }

  @Test
  func `hiding during the response wait still accepts the response`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let viewModel = makeViewModel(send: send)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.startTrace()

    try await waitUntil("wait should be armed") {
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
    defer {
      viewModel.reset()
      send.completeAll()
    }
    let execution = Task { await viewModel.runTrace() }

    try await waitUntil("send should start") {
      send.sendCount == 1
    }
    viewModel.cancelExecution()
    send.completeOldest()
    await execution.value

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
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.startTrace()

    try await waitUntil("wait should be armed") {
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
    let clock = TestClock()
    let viewModel = makeViewModel(send: send, clock: clock)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    let execution = Task { await viewModel.runBatchTrace() }

    try await waitUntil("first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil("first member should be waiting") {
      clock.sleeperCount == 1
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await advanceBatchBuffer(on: clock)
    try await waitUntil("second send should start") {
      send.sendCount == 2
    }

    viewModel.cancelBatchTrace()
    send.completeOldest()
    await execution.value

    #expect(viewModel.completedResults.count == 1)
    #expect(viewModel.completedResults[0].success)
    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
    #expect(send.sendCount == 2)
  }

  @Test
  func `replacement startTrace drops the old send cleanup`() async throws {
    let send = ControllableTraceSend()
    let clock = TestClock()
    let viewModel = makeViewModel(send: send, clock: clock)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.startTrace()

    try await waitUntil("first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil("first member should be waiting") {
      clock.sleeperCount == 1
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await advanceBatchBuffer(on: clock)
    try await waitUntil("second send should start") {
      send.sendCount == 2
    }

    #expect(viewModel.completedResults.count == 1)
    let oldExecution = try #require(viewModel.executionTaskForTesting)
    viewModel.startTrace()
    send.completeOldest()
    await oldExecution.value

    #expect(viewModel.completedResults.isEmpty)
    #expect(viewModel.errorMessage == nil)
    try await waitUntil("replacement send should be in flight") {
      send.sendCount == 3 && viewModel.isRunning
    }
  }

  @Test
  func `canceled send error does not record a failure`() async throws {
    let send = ControllableTraceSend()
    let viewModel = makeViewModel(send: send)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    let execution = Task { await viewModel.runTrace() }

    try await waitUntil("send should start") {
      send.sendCount == 1
    }

    viewModel.cancelExecution()
    send.failOldest(MeshCoreError.timeout)
    await execution.value

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
    var appendFinished = false
    viewModel.beforeTraceRunAppendForTesting = { await gate.wait() }
    viewModel.afterTraceRunAppendForTesting = { appendFinished = true }
    viewModel.throwBeforeTagForTesting = { throw MeshCoreError.timeout }
    viewModel.addNode(makeContact())
    viewModel.activeSavedPath = saved
    viewModel.result = TraceResult.sendFailed("stale", attemptedPath: [0xAB], hashSize: 1)
    viewModel.startTrace()

    try await waitUntil("append should reach the gate") {
      gate.started
    }
    viewModel.reset()
    gate.release()
    try await waitUntil("abandoned append should finish") { appendFinished }

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
    defer { viewModel.reset() }
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

    try await waitUntil("rebound service should deliver the response") {
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
    defer { viewModel.reset() }
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
    let listener = try #require(viewModel.traceEventsTaskForTesting)
    viewModel.reset()
    await listener.value

    let replacement = try makeServices()
    currentServices = replacement
    viewModel.reattachIfWaiting()
    #expect(replacement.advertisementService.eventBroadcaster.subscriberCount == 0)
    replacement.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: traceInfo(tag: 0x00C0_FFEE), radioID: UUID())
    )

    #expect(viewModel.result == nil)
    #expect(viewModel.outboundPath.isEmpty)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `stream end with a nil provider keeps the run waiting for a later ready`() async throws {
    let services = try makeServices()
    var currentServices: ServiceContainer? = services
    let viewModel = makeViewModel()
    defer { viewModel.reset() }
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
    let listener = try #require(viewModel.traceEventsTaskForTesting)
    services.advertisementService.finishEvents()
    currentServices = nil
    await listener.value

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
    try await waitUntil("later ready should accept the tag") {
      viewModel.result != nil
    }
  }

  @Test
  func `a throw after the tag leaves it in place until the deadline`() async throws {
    let send = ControllableTraceSend()
    let clock = TestClock()
    let viewModel = makeViewModel(send: send, clock: clock)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.unackedTimeoutSecondsForTesting = 5
    viewModel.startTrace()

    try await waitUntil("send should start") {
      send.sendCount == 1
    }
    send.failOldest(MeshCoreError.timeout)
    try await waitUntil("unacked response deadline should park") { clock.sleeperCount == 1 }

    clock.advance(by: .milliseconds(4999))
    try #require(clock.sleeperCount == 1)
    #expect(viewModel.pendingTagForTesting != nil)
    #expect(viewModel.errorMessage == nil)

    clock.advance(by: .milliseconds(1))
    try #require(clock.sleeperCount == 0)
    try await waitUntil("deadline should fail the run") {
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

    try await waitUntil("pre-tag failure should surface") {
      viewModel.errorMessage != nil
    }
    #expect(viewModel.pendingTagForTesting == nil)
    #expect(viewModel.isRunning == false)
  }

  @Test
  func `response then expiry keeps the success`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let clock = TestClock()
    let viewModel = makeViewModel(send: send, clock: clock)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 5
    viewModel.startTrace()

    try await waitUntil("tag should be assigned") {
      clock.sleeperCount == 1
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    let timeout = try #require(viewModel.timeoutTaskForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    await timeout.value
    clock.advance(by: .seconds(5))
    viewModel.expireWaitingRunIfDeadlinePassed()

    #expect(viewModel.result?.success == true)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `expiry then response does not set result`() async throws {
    let send = ControllableTraceSend()
    send.hangUntilComplete = false
    let clock = TestClock()
    let viewModel = makeViewModel(send: send, clock: clock)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 5
    viewModel.startTrace()

    try await waitUntil("tag should be assigned") {
      clock.sleeperCount == 1
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    clock.advance(by: .seconds(5))
    try await waitUntil("deadline should win") {
      viewModel.errorMessage != nil
    }
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)

    #expect(viewModel.result == nil)
  }

  @Test
  func `hidden errorMessage stays until clearError`() {
    let clock = TestClock()
    let viewModel = makeViewModel(clock: clock)
    defer { viewModel.reset() }
    viewModel.errorAutoClearDelay = .seconds(1)
    viewModel.setWorkspaceVisible(false)
    viewModel.setError("radio silent")
    #expect(viewModel.errorAutoClearTaskForTesting == nil)
    #expect(clock.sleeperCount == 0)
    clock.advance(by: .seconds(1))
    viewModel.setWorkspaceVisible(true)

    #expect(viewModel.errorMessage == "radio silent")
    viewModel.clearError()
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `hiding cancels a clear that was armed while visible`() async throws {
    let clock = TestClock()
    let viewModel = makeViewModel(clock: clock)
    defer { viewModel.reset() }
    viewModel.errorAutoClearDelay = .seconds(1)
    viewModel.setWorkspaceVisible(true)
    viewModel.setError("radio silent")
    try await waitUntil("visible error clear should park") { clock.sleeperCount == 1 }
    let errorClear = try #require(viewModel.errorAutoClearTaskForTesting)
    viewModel.setWorkspaceVisible(false)
    try #require(clock.sleeperCount == 0)
    await errorClear.value
    clock.advance(by: .seconds(1))

    #expect(viewModel.errorMessage == "radio silent")
  }

  @Test
  func `hidden result-sheet dismiss does not cancel the batch and presents again`() async throws {
    let send = ControllableTraceSend()
    let clock = TestClock()
    let viewModel = makeViewModel(send: send, clock: clock)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.setWorkspaceVisible(true)
    viewModel.startTrace()

    try await waitUntil("first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil("first member should be waiting") {
      clock.sleeperCount == 1
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await advanceBatchBuffer(on: clock)
    try await waitUntil("second send should start") {
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

  @Test(arguments: [false, true])
  func `cancelling an acknowledged batch wait does not republish its result`(dismissSheet: Bool) async throws {
    let send = ControllableTraceSend()
    let clock = TestClock()
    let viewModel = makeViewModel(send: send, clock: clock)
    defer {
      viewModel.reset()
      send.completeAll()
    }
    viewModel.responseTimeoutSecondsForTesting = 30
    viewModel.batchEnabled = true
    viewModel.batchSize = 3
    viewModel.setWorkspaceVisible(true)
    let batchTask = Task { await viewModel.runBatchTrace() }
    defer {
      viewModel.cancelExecution()
      batchTask.cancel()
    }

    try await waitUntil("first send should start") {
      send.sendCount == 1
    }
    send.completeOldest()
    try await waitUntil("first member should be waiting") {
      clock.sleeperCount == 1
    }
    let tag = try #require(viewModel.pendingTagForTesting)
    viewModel.handleTraceResponse(traceInfo(tag: tag), radioID: nil)
    try await advanceBatchBuffer(on: clock)
    try await waitUntil("second send should start") {
      send.sendCount == 2
    }
    send.completeOldest()
    try await waitUntil("second member should be waiting for its response") {
      clock.sleeperCount == 1
    }
    let cancelledTag = try #require(viewModel.pendingTagForTesting)
    let resultID = try #require(viewModel.resultID)

    let shown = viewModel.consumeStoredResultForPresentation()
    #expect(shown != nil)
    if dismissSheet {
      viewModel.noteResultSheetDismissed()
    } else {
      viewModel.cancelExecution()
    }

    #expect(viewModel.isRunning == false)
    #expect(viewModel.consumeStoredResultForPresentation() == nil)
    await batchTask.value
    #expect(viewModel.resultID == resultID)
    #expect(viewModel.consumeStoredResultForPresentation() == nil)
    #expect(viewModel.completedResults.count == 1)
    #expect(viewModel.pendingTagForTesting == nil)
    #expect(send.sendCount == 2)

    viewModel.handleTraceResponse(traceInfo(tag: cancelledTag), radioID: nil)
    #expect(viewModel.resultID == resultID)
    #expect(viewModel.completedResults.count == 1)
    #expect(viewModel.consumeStoredResultForPresentation() == nil)
  }
}
