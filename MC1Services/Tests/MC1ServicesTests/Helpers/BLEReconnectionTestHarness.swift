import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@MainActor
final class BLEReconnectionTestHarness {
  @MainActor
  final class Gate {
    private var continuations: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false

    func wait() async {
      guard !isOpen else { return }
      await withCheckedContinuation { continuations.append($0) }
    }

    func open() {
      isOpen = true
      let waiting = continuations
      continuations.removeAll()
      waiting.forEach { $0.resume() }
    }
  }

  enum RebuildError: Error {
    case failed
  }

  let clock = TestClock()
  let coordinator: BLEReconnectionCoordinator
  var rebuilds: [BLEReconnectionCoordinator.Cycle] = []
  var exhausted: [BLEReconnectionCoordinator.Cycle] = []
  var timeouts: [BLEReconnectionCoordinator.Cycle] = []
  var remainingFailures = 0
  var autoReconnecting = false
  var onRebuild: (@MainActor (BLEReconnectionCoordinator.Cycle) async -> Void)?
  var onExhausted: (@MainActor (BLEReconnectionCoordinator.Cycle) async -> Void)?
  var onAutoReconnectQuery: (@MainActor () async -> Void)?
  private var tasks: [Task<Void, Never>] = []
  private var gateReleases: [() -> Void] = []
  private var activeTransportQueries = 0

  init(uiTimeout: TimeInterval, maxUIWindow: TimeInterval) {
    coordinator = BLEReconnectionCoordinator(
      uiTimeoutDuration: uiTimeout,
      maxConnectingUIWindow: maxUIWindow,
      clock: clock
    )
    coordinator.onRebuild = { [weak self] cycle in
      guard let self else { throw CancellationError() }
      rebuilds.append(cycle)
      await onRebuild?(cycle)
      if remainingFailures > 0 {
        remainingFailures -= 1
        throw RebuildError.failed
      }
    }
    coordinator.onExhausted = { [weak self] cycle in
      guard let self else { return }
      exhausted.append(cycle)
      await onExhausted?(cycle)
    }
    coordinator.onTimeout = { [weak self] cycle in
      self?.timeouts.append(cycle)
    }
    coordinator.isAutoReconnecting = { [weak self] in
      guard let self else { return false }
      activeTransportQueries += 1
      defer { activeTransportQueries -= 1 }
      await onAutoReconnectQuery?()
      return autoReconnecting
    }
  }

  func begin(deviceID: UUID = UUID(), requestID: UUID = UUID()) -> BLEReconnectionCoordinator.Cycle {
    coordinator.begin(deviceID: deviceID, requestID: requestID)
  }

  func complete(_ cycle: BLEReconnectionCoordinator.Cycle) -> Task<Void, Never> {
    let task = Task { await coordinator.complete(cycle) }
    tasks.append(task)
    return task
  }

  func gate() -> Gate {
    let gate = Gate()
    gateReleases.append { gate.open() }
    return gate
  }

  func cleanup() async {
    coordinator.clear()
    gateReleases.forEach { $0() }
    tasks.forEach { $0.cancel() }
    for task in tasks {
      await task.value
    }
    do {
      try await waitUntil("cancelled transport queries should settle") { self.activeTransportQueries == 0 }
    } catch {
      Issue.record(error)
    }
    onRebuild = nil
    onExhausted = nil
    onAutoReconnectQuery = nil
    #expect(clock.sleeperCount == 0)
  }
}

@MainActor
func withReconnectionHarness(
  uiTimeout: TimeInterval = 10,
  maxUIWindow: TimeInterval = 60,
  _ body: @MainActor (BLEReconnectionTestHarness) async throws -> Void
) async rethrows {
  let harness = BLEReconnectionTestHarness(uiTimeout: uiTimeout, maxUIWindow: maxUIWindow)
  do {
    try await body(harness)
  } catch {
    await harness.cleanup()
    throw error
  }
  await harness.cleanup()
}
