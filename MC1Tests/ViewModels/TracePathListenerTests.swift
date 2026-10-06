import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

/// Verifies the trace listener targets the current connection's
/// `AdvertisementService`. The `ServiceContainer` is rebuilt on every
/// connection and finishes its event stream on teardown, so a listener
/// established once and never refreshed would keep iterating the torn-down
/// container's finished stream and silently drop every trace response.
@Suite("Trace Path Listener Resubscription")
@MainActor
struct TracePathListenerTests {
  private static let testTag: UInt32 = 0x00C0_FFEE
  private static let pollAttempts = 200
  private static let pollIntervalMs = 5

  private func makeServices() throws -> ServiceContainer {
    try ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true)),
      radioID: UUID()
    )
  }

  private func makeTraceInfo(tag: UInt32) -> TraceInfo {
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

  /// Polls until the view model publishes a result or the deadline passes.
  private func waitForResult(on viewModel: TracePathViewModel) async -> Bool {
    for _ in 0..<Self.pollAttempts {
      if viewModel.result != nil { return true }
      try? await Task.sleep(for: .milliseconds(Self.pollIntervalMs))
    }
    return viewModel.result != nil
  }

  @Test
  func `Listener established after a late connect receives trace responses`() async throws {
    var currentServices: ServiceContainer?
    let viewModel = TracePathViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { currentServices?.advertisementService },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))

    // Opened while disconnected: no services exist, so this subscribes to nothing.
    viewModel.startListening()

    // Connect: a fresh container appears and the hosting view re-invokes
    // startListening via its servicesVersion-keyed task.
    let services = try makeServices()
    currentServices = services
    viewModel.startListening()

    viewModel.setPendingTagForTesting(Self.testTag)
    services.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: makeTraceInfo(tag: Self.testTag), radioID: UUID())
    )

    #expect(await waitForResult(on: viewModel), "Trace response after a late connect should produce a result")
    viewModel.stopListening()
  }

  @Test
  func `Listener re-established after a container rebuild receives trace responses`() async throws {
    let oldServices = try makeServices()
    var currentServices: ServiceContainer? = oldServices

    let viewModel = TracePathViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { currentServices?.advertisementService },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    viewModel.startListening()

    // Transport loss: the old container finishes its event stream and a
    // replacement container takes its place.
    oldServices.advertisementService.finishEvents()
    let newServices = try makeServices()
    currentServices = newServices
    viewModel.startListening()

    viewModel.setPendingTagForTesting(Self.testTag)
    newServices.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: makeTraceInfo(tag: Self.testTag), radioID: UUID())
    )

    #expect(await waitForResult(on: viewModel), "Trace response after a rebuild should reach the re-subscribed listener")
    viewModel.stopListening()
  }

  @Test
  func `finished stream on the same service is not subscribed again`() async throws {
    let services = try makeServices()
    let viewModel = try await makeRunningTrace(services: services)
    viewModel.eventStreamEndedForTesting = false
    try await Task.yield()
    viewModel.eventStreamEndedForTesting = false

    let subscribed = viewModel.subscribeCountForTesting
    services.advertisementService.finishEvents()
    try await waitUntil(timeout: .seconds(1), "finished stream should settle") {
      viewModel.eventStreamEndedForTesting
    }

    #expect(viewModel.subscribeCountForTesting == subscribed)
    #expect(viewModel.isRunning)
    viewModel.stopListening()
  }

  @Test
  func `hidden rebuild listens on the replacement service`() async throws {
    let servicesA = try makeServices()
    var current: ServiceContainer? = servicesA
    let viewModel = try await makeRunningTrace(current: { current })
    let servicesB = try makeServices()
    current = servicesB
    viewModel.noteWorkspaceVisible(false)
    viewModel.startListening()

    let tag = try #require(viewModel.pendingTagForTesting)
    servicesB.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: makeTraceInfo(tag: tag), radioID: UUID())
    )

    #expect(await waitForResult(on: viewModel))
    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == nil)
    viewModel.stopListening()
  }

  @Test
  func `passed deadline on a stale listener waits for the replacement service`() async throws {
    let servicesA = try makeServices()
    var current: ServiceContainer? = servicesA
    let viewModel = try await makeRunningTrace(current: { current })
    current = nil
    viewModel.setTraceDeadlineForTesting(.distantPast)
    viewModel.expireTraceIfDeadlinePassed()

    #expect(viewModel.isRunning)
    #expect(viewModel.pendingTagForTesting != nil)
    #expect(viewModel.traceDeadlineForTesting == .distantPast)
    #expect(viewModel.hasActiveTimeoutTaskForTesting)

    let servicesB = try makeServices()
    current = servicesB
    viewModel.startListening()

    #expect(viewModel.isRunning == false)
    #expect(viewModel.errorMessage == L10n.Contacts.Contacts.Trace.Error.noResponse)
    #expect(viewModel.pendingTagForTesting == nil)
    viewModel.stopListening()
  }

  @Test
  func `replacement listener accepts a response before the deadline`() async throws {
    let servicesA = try makeServices()
    var current: ServiceContainer? = servicesA
    let viewModel = try await makeRunningTrace(current: { current })
    viewModel.setTraceDeadlineForTesting(.distantFuture)
    let servicesB = try makeServices()
    current = servicesB
    viewModel.noteWorkspaceVisible(false)
    viewModel.startListening()

    #expect(viewModel.isRunning)
    #expect(viewModel.errorMessage == nil)
    let tag = try #require(viewModel.pendingTagForTesting)
    servicesB.advertisementService.eventBroadcaster.yield(
      .traceResponse(traceInfo: makeTraceInfo(tag: tag), radioID: UUID())
    )

    #expect(await waitForResult(on: viewModel))
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.result?.success == true)
    viewModel.stopListening()
  }

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

  private func makeRunningTrace(
    services: ServiceContainer
  ) async throws -> TracePathViewModel {
    var current: ServiceContainer? = services
    return try await makeRunningTrace(current: { current })
  }

  private func makeRunningTrace(
    current: @escaping @MainActor () -> ServiceContainer?
  ) async throws -> TracePathViewModel {
    let viewModel = TracePathViewModel()
    viewModel.configure(dependencies: TracePathViewModel.Dependencies(
      dataStore: { nil },
      session: { nil },
      advertisementService: { current()?.advertisementService },
      connectedDevice: { nil },
      bestAvailableLocation: { nil }
    ))
    viewModel.sendTraceForTesting = { _, _, _ in
      MessageSentInfo(route: 1, expectedAck: Data(), suggestedTimeoutMs: 60000)
    }
    viewModel.addNode(makeContact())
    let generationBefore = viewModel.executionGenerationForTesting
    viewModel.startListening()
    #expect(viewModel.subscribeCountForTesting == 1)
    #expect(viewModel.subscribedGenerationForTesting == generationBefore)
    viewModel.startTrace()
    #expect(viewModel.subscribeCountForTesting == 2)
    #expect(viewModel.executionGenerationForTesting == generationBefore + 1)
    #expect(viewModel.subscribedGenerationForTesting == viewModel.executionGenerationForTesting)
    try await waitUntil(timeout: .seconds(1), "trace should be running") {
      viewModel.isRunning && viewModel.pendingTagForTesting != nil
    }
    return viewModel
  }
}
