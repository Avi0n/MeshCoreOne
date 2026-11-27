import Foundation
import MC1Services

@Observable
@MainActor
final class WatchlistRefreshCoordinator {
  struct Dependencies {
    let operations: @MainActor () -> Operations?
    let radioID: @MainActor () -> UUID?
    let connectionState: @MainActor () -> DeviceConnectionState
    let sessionIdentity: @MainActor () -> ObjectIdentifier?
    let connectionStates: @MainActor () -> AsyncStream<DeviceConnectionState>
  }

  /// Remote calls for one repeater, bound to the live connection's services.
  struct Operations {
    let savedPassword: @Sendable (ContactDTO) async -> String?
    let connectedSession: @Sendable (_ publicKeyPrefix: Data) async throws -> RemoteNodeSessionDTO?
    let createSession: @Sendable (ContactDTO) async throws -> RemoteNodeSessionDTO
    let logIn: @Sendable (_ sessionID: UUID, _ password: String, _ pathLength: UInt8) async throws -> LoginResult
    let requestStatus: @Sendable (_ sessionID: UUID) async throws -> StatusResponse
    let requestOwnerInfo: @Sendable (_ sessionID: UUID) async throws -> OwnerInfoResponse
  }

  struct Progress: Equatable {
    let completed: Int
    let total: Int
  }

  struct BatteryReading: Equatable {
    let millivolts: UInt16
    let savedAt: Date
  }

  /// Repeaters below this `FIRMWARE_VER_LEVEL` never answer owner info.
  static let ownerInfoMinimumFirmwareLevel: UInt8 = 2
  static let watchlistMaximumBackoff: TimeInterval = 4 * 3600
  private static let maxBackoffExponent = 16
  private static let maxPostHandOffPreemptions = 1
  private static let publicKeyPrefixLength = 6

  private(set) var activeContactID: UUID?
  private(set) var progress: Progress?
  private(set) var failedContactIDs: Set<UUID> = []
  private(set) var timedOutContactIDs: Set<UUID> = []
  private(set) var batteryReadings: [UUID: BatteryReading] = [:]

  var isRefreshing: Bool {
    activeRun != nil
  }

  @ObservationIgnored private var store: PersistenceStore?
  @ObservationIgnored private var now: (@MainActor () -> Date)?
  @ObservationIgnored private var onContactsChanged: (@MainActor () -> Void)?
  @ObservationIgnored private var dependencies: Dependencies?
  private var activeRun: Run?
  @ObservationIgnored private var walkTask: Task<Void, Never>?
  @ObservationIgnored private var observation: Task<Void, Never>?
  @ObservationIgnored private var ownerInfoCheckedContactIDs: Set<UUID> = []
  @ObservationIgnored private var noReplyBackoffs: [UUID: BackoffEntry] = [:]

  func configure(
    store: PersistenceStore,
    now: @escaping @MainActor () -> Date,
    onContactsChanged: @escaping @MainActor () -> Void,
    dependencies: Dependencies
  ) {
    self.store = store
    self.now = now
    self.onContactsChanged = onContactsChanged
    self.dependencies = dependencies
    startObserving()
  }

  func shutdown() {
    observation?.cancel()
    observation = nil
    if let runID = activeRun?.id {
      cancel(runID)
    }
  }

  func refresh(_ contacts: [ContactDTO]) {
    guard activeRun == nil else { return }
    guard let dependencies, dependencies.connectionState() == .ready else { return }
    guard let radioID = dependencies.radioID(),
          dependencies.operations() != nil,
          let identity = dependencies.sessionIdentity()
    else { return }

    let targets = contacts
      .filter { $0.radioID == radioID && $0.type == .repeater }
      .sorted(by: WatchlistDirectory.displayOrder)
    guard !targets.isEmpty else { return }

    let run = Run(
      id: UUID(),
      radioID: radioID,
      sessionIdentity: identity,
      targets: targets
    )
    activeRun = run
    progress = Progress(completed: 0, total: targets.count)
    walkTask = Task { await walk(run) }
  }

  func refreshStale(_ contacts: [ContactDTO]) {
    guard let now else { return }
    let current = now()
    refresh(contacts.filter { contact in
      if isBackedOff(contact.id, at: current) {
        return false
      }
      guard let reading = batteryReadings[contact.id] else { return true }
      return current.timeIntervalSince(reading.savedAt) >= NodeSnapshotPolicy.minimumInterval
    })
  }

  func loadReadings(for contacts: [ContactDTO]) async {
    guard let store,
          let snapshots = try? await store.fetchLatestBatterySnapshots(nodePublicKeys: contacts.map(\.publicKey))
    else { return }
    for contact in contacts {
      batteryReadings[contact.id] = snapshots[contact.publicKey].flatMap(BatteryReading.init(snapshot:))
    }
  }

  /// A request already in flight finishes; no later repeater starts.
  func cancelRefresh() {
    if let runID = activeRun?.id {
      cancel(runID)
    }
  }

  func backoffDelay(for contactID: UUID) -> TimeInterval? {
    guard let entry = noReplyBackoffs[contactID], entry.consecutiveNoReplyCount > 0 else { return nil }
    let exponent = min(entry.consecutiveNoReplyCount - 1, Self.maxBackoffExponent)
    return min(NodeSnapshotPolicy.minimumInterval * pow(2.0, Double(exponent)), Self.watchlistMaximumBackoff)
  }

  private func isBackedOff(_ contactID: UUID, at current: Date) -> Bool {
    guard let entry = noReplyBackoffs[contactID], let delay = backoffDelay(for: contactID) else {
      return false
    }
    return current.timeIntervalSince(entry.lastNoReplyAt) < delay
  }

  private func resetBackoff(for contactID: UUID) {
    noReplyBackoffs.removeValue(forKey: contactID)
  }

  private func recordNoReply(for contactID: UUID) {
    let timestamp = now?() ?? .now
    let count = (noReplyBackoffs[contactID]?.consecutiveNoReplyCount ?? 0) + 1
    noReplyBackoffs[contactID] = BackoffEntry(consecutiveNoReplyCount: count, lastNoReplyAt: timestamp)
  }

  private func startObserving() {
    observation?.cancel()
    guard let stream = dependencies?.connectionStates() else { return }
    observation = Task { [weak self] in
      for await state in stream {
        self?.handle(state)
      }
    }
  }

  private func handle(_ state: DeviceConnectionState) {
    guard let run = activeRun else { return }
    guard let dependencies else { return }
    let sameRadio = dependencies.radioID() == run.radioID
    let sameSession = dependencies.sessionIdentity() == run.sessionIdentity
    if state.isConnected, sameRadio, sameSession { return }
    cancel(run.id)
  }

  private func cancel(_ runID: UUID) {
    guard activeRun?.id == runID else { return }
    activeRun = nil
    activeContactID = nil
    progress = nil
    walkTask?.cancel()
    walkTask = nil
  }

  private func walk(_ run: Run) async {
    var queue = run.targets.map { TargetState(contact: $0) }
    var completedCount = 0

    while !queue.isEmpty {
      guard owns(run) else { break }
      var state = queue.removeFirst()
      activeContactID = state.contact.id
      progress = Progress(completed: completedCount, total: run.targets.count)
      if !state.hasAttempted {
        failedContactIDs.remove(state.contact.id)
        timedOutContactIDs.remove(state.contact.id)
        state.hasAttempted = true
      }

      let outcome = await refreshRepeater(&state, in: run)
      guard owns(run) else { break }

      switch outcome {
      case .cancelled:
        queue.removeAll()
      case .succeeded:
        completedCount += 1
      case .failed:
        failedContactIDs.insert(state.contact.id)
        completedCount += 1
      case .noReply:
        failedContactIDs.insert(state.contact.id)
        timedOutContactIDs.insert(state.contact.id)
        recordNoReply(for: state.contact.id)
        completedCount += 1
      case let .preempted(afterHandOff):
        if !afterHandOff {
          queue.append(state)
        } else if state.postHandOffPreemptions < Self.maxPostHandOffPreemptions {
          state.postHandOffPreemptions += 1
          queue.append(state)
        } else {
          completedCount += 1
        }
      }
    }

    if activeRun?.id == run.id {
      activeRun = nil
      activeContactID = nil
      progress = nil
      walkTask = nil
    }
  }

  private func refreshRepeater(_ state: inout TargetState, in run: Run) async -> TargetOutcome {
    let target = state.contact

    if !state.statusDone {
      switch await ensureSessionAndStatus(&state, in: run) {
      case .ok:
        break
      case let .terminal(outcome):
        return outcome
      }
    }

    guard let sessionID = state.sessionID else {
      return state.statusTimedOut ? .noReply : .failed
    }

    if target.myNodeFirmwareVersion == nil || !ownerInfoCheckedContactIDs.contains(target.id) {
      do {
        try await refreshFirmware(
          for: target,
          sessionID: sessionID,
          login: state.login,
          statusAnswered: state.statusAnswered,
          in: run
        )
      } catch is CancellationError {
        return .cancelled
      } catch {
        guard owns(run) else { return .cancelled }
        if let afterHandOff = Self.preemption(from: error) {
          return .preempted(afterHandOff: afterHandOff)
        }
        state.statusOrOwnerFailed = true
      }
    }

    if state.statusOrOwnerFailed {
      return state.statusTimedOut && !state.statusAnswered ? .noReply : .failed
    }
    return .succeeded
  }

  private enum StepResult {
    case ok
    case terminal(TargetOutcome)
  }

  private func ensureSessionAndStatus(_ state: inout TargetState, in run: Run) async -> StepResult {
    let target = state.contact

    // Re-queue after a status preemption keeps the session ID and skips re-login.
    if let sessionID = state.sessionID, !state.needsRelogin {
      return await fetchStatus(for: target, sessionID: sessionID, state: &state, in: run)
    }

    do {
      if state.sessionID == nil,
         let connected = try await operations(for: run).connectedSession(Data(target.publicKey.prefix(Self.publicKeyPrefixLength))) {
        state.sessionID = connected.id
        do {
          try await performStatus(for: target, sessionID: connected.id, in: run)
          state.statusAnswered = true
          state.statusDone = true
          return .ok
        } catch is CancellationError {
          return .terminal(.cancelled)
        } catch {
          guard owns(run) else { return .terminal(.cancelled) }
          if let afterHandOff = Self.preemption(from: error) {
            state.sessionID = nil
            return .terminal(.preempted(afterHandOff: afterHandOff))
          }
          guard Self.isTimeout(error) else {
            state.statusOrOwnerFailed = true
            state.statusDone = true
            return .ok
          }
          state.needsRelogin = true
        }
      }

      let sessionID: UUID
      if let existingID = state.sessionID {
        sessionID = existingID
      } else {
        let created = try await operations(for: run).createSession(target)
        sessionID = created.id
        state.sessionID = sessionID
      }

      let password = try await operations(for: run).savedPassword(target) ?? ""
      let login = try await operations(for: run).logIn(sessionID, password, target.outPathLength)
      resetBackoff(for: target.id)
      state.login = login
      state.needsRelogin = false
    } catch is CancellationError {
      return .terminal(.cancelled)
    } catch {
      guard owns(run) else { return .terminal(.cancelled) }
      if let afterHandOff = Self.preemption(from: error) {
        return .terminal(.preempted(afterHandOff: afterHandOff))
      }
      return .terminal(Self.isTimeout(error) ? .noReply : .failed)
    }

    guard let sessionID = state.sessionID else {
      return .terminal(.failed)
    }
    return await fetchStatus(for: target, sessionID: sessionID, state: &state, in: run)
  }

  private func fetchStatus(
    for target: ContactDTO,
    sessionID: UUID,
    state: inout TargetState,
    in run: Run
  ) async -> StepResult {
    do {
      try await performStatus(for: target, sessionID: sessionID, in: run)
      state.statusAnswered = true
      state.statusDone = true
      return .ok
    } catch is CancellationError {
      return .terminal(.cancelled)
    } catch {
      guard owns(run) else { return .terminal(.cancelled) }
      if let afterHandOff = Self.preemption(from: error) {
        return .terminal(.preempted(afterHandOff: afterHandOff))
      }
      state.statusOrOwnerFailed = true
      state.statusTimedOut = Self.isTimeout(error)
      state.statusDone = true
      return .ok
    }
  }

  private func performStatus(for target: ContactDTO, sessionID: UUID, in run: Run) async throws {
    let status = try await operations(for: run).requestStatus(sessionID)
    resetBackoff(for: target.id)
    guard owns(run), let store else { throw CancellationError() }
    let metrics = NodeStatusMetrics(
      status: status,
      rxAirtimeSeconds: status.repeaterRxAirtimeSeconds,
      receiveErrors: status.receiveErrors
    )
    let snapshot = try await store.recordWatchlistStatus(nodePublicKey: target.publicKey, status: metrics)
    guard owns(run) else { throw CancellationError() }
    batteryReadings[target.id] = BatteryReading(snapshot: snapshot)
  }

  private func refreshFirmware(
    for target: ContactDTO,
    sessionID: UUID,
    login: LoginResult?,
    statusAnswered: Bool,
    in run: Run
  ) async throws {
    if let level = login?.firmwareLevel, level < Self.ownerInfoMinimumFirmwareLevel {
      try await recordMissingFirmware(for: target, in: run)
      ownerInfoCheckedContactIDs.insert(target.id)
      return
    }
    let info: OwnerInfoResponse
    do {
      info = try await operations(for: run).requestOwnerInfo(sessionID)
      resetBackoff(for: target.id)
    } catch let error where statusAnswered && Self.isTimeout(error) {
      // The repeater answered status, so a silent owner-info request means it lacks the request.
      try await recordMissingFirmware(for: target, in: run)
      ownerInfoCheckedContactIDs.insert(target.id)
      return
    }
    ownerInfoCheckedContactIDs.insert(target.id)
    guard owns(run), let store else { throw CancellationError() }
    _ = try await store.updateWatchlistFirmware(
      contactID: target.id,
      radioID: run.radioID,
      version: info.firmwareVersion
    )
    onContactsChanged?()
  }

  /// Records a checked reading with no version, keeping any version already saved.
  private func recordMissingFirmware(for target: ContactDTO, in run: Run) async throws {
    guard target.myNodeFirmwareVersion == nil else { return }
    guard owns(run), let store else { throw CancellationError() }
    _ = try await store.updateWatchlistFirmware(contactID: target.id, radioID: run.radioID, version: "")
    onContactsChanged?()
  }

  /// Reads the live services before each call; a nil read means the run no longer owns them.
  private func operations(for run: Run) throws -> Operations {
    guard owns(run), let operations = dependencies?.operations() else { throw CancellationError() }
    return operations
  }

  private func owns(_ run: Run) -> Bool {
    guard activeRun?.id == run.id, let dependencies else { return false }
    return dependencies.connectionState().isConnected
      && dependencies.radioID() == run.radioID
      && dependencies.sessionIdentity() == run.sessionIdentity
  }

  private static func isTimeout(_ error: any Error) -> Bool {
    switch error {
    case RemoteNodeError.timeout, MeshCoreError.timeout: true
    default: false
    }
  }

  private static func preemption(from error: any Error) -> Bool? {
    switch error {
    case let MeshCoreError.preempted(afterHandOff):
      afterHandOff
    case let RemoteNodeError.sessionError(.preempted(afterHandOff)):
      afterHandOff
    default:
      nil
    }
  }

  private enum TargetOutcome {
    case succeeded, failed, noReply, cancelled
    case preempted(afterHandOff: Bool)
  }

  private struct TargetState {
    let contact: ContactDTO
    var hasAttempted = false
    var postHandOffPreemptions = 0
    var sessionID: UUID?
    var login: LoginResult?
    var needsRelogin = false
    var statusAnswered = false
    var statusDone = false
    var statusOrOwnerFailed = false
    var statusTimedOut = false
  }

  private struct BackoffEntry {
    var consecutiveNoReplyCount: Int
    var lastNoReplyAt: Date
  }

  private struct Run {
    let id: UUID
    let radioID: UUID
    let sessionIdentity: ObjectIdentifier
    let targets: [ContactDTO]
  }
}

extension WatchlistRefreshCoordinator.BatteryReading {
  init?(snapshot: NodeStatusSnapshotDTO) {
    guard let millivolts = snapshot.batteryMillivolts else { return nil }
    self.init(millivolts: millivolts, savedAt: snapshot.timestamp)
  }
}
