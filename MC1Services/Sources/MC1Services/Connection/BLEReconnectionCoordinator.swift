import Foundation

/// Owns the retry and presentation timeout for one OS auto-reconnect cycle.
@MainActor
final class BLEReconnectionCoordinator {
  struct Cycle: Sendable, Equatable {
    let id: UUID
    let generation: Int
    let deviceID: UUID
    let requestID: UUID
  }

  private enum Timing {
    static let uiTimeout: TimeInterval = 15
    static let maxUIWindow: TimeInterval = 60
    static let rebuildRetry: Duration = .seconds(2)
  }

  private enum RebuildOutcome {
    case rebuilt
    case exhausted
    case superseded
  }

  private struct RebuildOperation {
    let cycleID: UUID
    let task: Task<RebuildOutcome, Never>
  }

  var onRebuild: (@MainActor (Cycle) async throws -> Void)?
  var onExhausted: (@MainActor (Cycle) async -> Void)?
  var onTimeout: (@MainActor (Cycle) async -> Void)?
  var isAutoReconnecting: @MainActor () async -> Bool = { false }

  private(set) var currentCycle: Cycle?
  private(set) var reconnectGeneration = 0

  var reconnectingDeviceID: UUID? {
    currentCycle?.deviceID
  }

  private let uiTimeoutDuration: Duration
  private let maxConnectingUIWindow: Duration
  private let clock: any Clock<Duration>
  private var elapsedInUIWindow: (@Sendable () -> Duration)?
  private var timeoutTask: Task<Void, Never>?
  private var timeoutID: UUID?
  private var rebuildOperation: RebuildOperation?

  init(
    uiTimeoutDuration: TimeInterval = Timing.uiTimeout,
    maxConnectingUIWindow: TimeInterval = Timing.maxUIWindow,
    clock: (any Clock<Duration>)? = nil
  ) {
    self.uiTimeoutDuration = .seconds(uiTimeoutDuration)
    self.maxConnectingUIWindow = .seconds(maxConnectingUIWindow)
    self.clock = clock ?? ContinuousClock()
  }

  @discardableResult
  func begin(deviceID: UUID, requestID: UUID) -> Cycle {
    clear()
    let cycle = Cycle(id: UUID(), generation: reconnectGeneration, deviceID: deviceID, requestID: requestID)
    currentCycle = cycle
    elapsedInUIWindow = clock.elapsedSinceNow()
    return cycle
  }

  func isCurrent(_ cycle: Cycle) -> Bool {
    currentCycle == cycle
  }

  func isRebuilding(_ cycle: Cycle) -> Bool {
    isCurrent(cycle) && rebuildOperation?.cycleID == cycle.id
  }

  func armTimeout(_ cycle: Cycle) {
    guard isCurrent(cycle), rebuildOperation?.cycleID != cycle.id else { return }
    cancelTimeout()
    let id = UUID()
    timeoutID = id
    let clock = clock
    let duration = uiTimeoutDuration
    timeoutTask = Task { @concurrent [weak self] in
      do {
        try await clock.sleep(for: duration)
      } catch {
        return
      }
      await self?.handleUITimeout(cycle, timeoutID: id)
    }
  }

  func restartTimeout(deviceID: UUID) {
    guard let cycle = currentCycle, cycle.deviceID == deviceID else { return }
    elapsedInUIWindow = clock.elapsedSinceNow()
    armTimeout(cycle)
  }

  func cancelTimeout() {
    timeoutID = nil
    timeoutTask?.cancel()
    timeoutTask = nil
  }

  func clear() {
    cancelTimeout()
    rebuildOperation?.task.cancel()
    rebuildOperation = nil
    currentCycle = nil
    elapsedInUIWindow = nil
    reconnectGeneration += 1
  }

  func complete(_ cycle: Cycle) async {
    guard isCurrent(cycle), let onRebuild else { return }
    guard rebuildOperation?.cycleID != cycle.id else { return }
    cancelTimeout()

    let task = Task { [weak self] in
      guard let self, isCurrent(cycle) else { return RebuildOutcome.superseded }
      return await rebuild(cycle, action: onRebuild)
    }
    rebuildOperation = RebuildOperation(cycleID: cycle.id, task: task)
    let outcome = await task.value
    guard rebuildOperation?.cycleID == cycle.id else { return }
    rebuildOperation = nil
    guard isCurrent(cycle) else { return }

    switch outcome {
    case .rebuilt:
      finish(cycle)
    case .exhausted:
      // Cleanup may join the rebuild worker, so its result is consumed before handing off.
      await onExhausted?(cycle)
      finish(cycle)
    case .superseded:
      finish(cycle)
    }
  }

  private func rebuild(
    _ cycle: Cycle,
    action: @MainActor (Cycle) async throws -> Void
  ) async -> RebuildOutcome {
    do {
      try Task.checkCancellation()
      try await action(cycle)
      return isCurrent(cycle) && !Task.isCancelled ? .rebuilt : .superseded
    } catch {
      guard isCurrent(cycle), !Task.isCancelled else { return .superseded }
    }

    do {
      try await clock.sleep(for: Timing.rebuildRetry)
      guard isCurrent(cycle), !Task.isCancelled else { return .superseded }
      try await action(cycle)
      return isCurrent(cycle) && !Task.isCancelled ? .rebuilt : .superseded
    } catch {
      return isCurrent(cycle) && !Task.isCancelled ? .exhausted : .superseded
    }
  }

  private func handleUITimeout(_ cycle: Cycle, timeoutID id: UUID) async {
    guard isCurrent(cycle), timeoutID == id, !Task.isCancelled else { return }
    let autoReconnecting = await isAutoReconnecting()
    guard isCurrent(cycle), timeoutID == id, !Task.isCancelled else { return }

    if autoReconnecting, let elapsedInUIWindow, elapsedInUIWindow() < maxConnectingUIWindow {
      armTimeout(cycle)
      return
    }

    timeoutTask = nil
    timeoutID = nil
    await onTimeout?(cycle)
    if !autoReconnecting {
      finish(cycle)
    }
  }

  private func finish(_ cycle: Cycle) {
    guard isCurrent(cycle) else { return }
    currentCycle = nil
    elapsedInUIWindow = nil
  }
}

private extension Clock where Duration == Swift.Duration {
  func elapsedSinceNow() -> @Sendable () -> Duration {
    let start = now
    return { start.duration(to: now) }
  }
}
