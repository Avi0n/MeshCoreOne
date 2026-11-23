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
    let createSession: @Sendable (ContactDTO) async throws -> RemoteNodeSessionDTO
    let logIn: @Sendable (_ sessionID: UUID, _ password: String, _ pathLength: UInt8) async throws -> LoginResult
    let resetPath: @Sendable (ContactDTO) async throws -> Void
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

  /// Refreshes repeaters with no battery snapshot, or one at least a snapshot window old.
  func refreshStale(_ contacts: [ContactDTO]) {
    guard let now else { return }
    let current = now()
    refresh(contacts.filter { contact in
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
    targets: for (index, target) in run.targets.enumerated() {
      guard owns(run) else { break }
      activeContactID = target.id
      progress = Progress(completed: index, total: run.targets.count)
      failedContactIDs.remove(target.id)
      timedOutContactIDs.remove(target.id)
      let outcome = await refreshRepeater(target, in: run)
      guard owns(run) else { break }
      switch outcome {
      case .cancelled:
        break targets
      case .succeeded:
        break
      case .failed:
        failedContactIDs.insert(target.id)
      case .noReply:
        failedContactIDs.insert(target.id)
        timedOutContactIDs.insert(target.id)
      }
    }
    if activeRun?.id == run.id {
      activeRun = nil
      activeContactID = nil
      progress = nil
      walkTask = nil
    }
  }

  /// Logs in first so status and owner info are answered.
  private func refreshRepeater(_ target: ContactDTO, in run: Run) async -> TargetOutcome {
    let sessionID: UUID
    let login: LoginResult
    do {
      (sessionID, login) = try await logIn(target, in: run)
    } catch is CancellationError {
      return .cancelled
    } catch {
      guard owns(run) else { return .cancelled }
      return Self.isTimeout(error) ? .noReply : .failed
    }

    var failed = false
    do {
      try await refreshBattery(for: target, sessionID: sessionID, in: run)
    } catch is CancellationError {
      return .cancelled
    } catch {
      guard owns(run) else { return .cancelled }
      failed = true
    }
    do {
      try await refreshFirmware(for: target, sessionID: sessionID, login: login, statusAnswered: !failed, in: run)
    } catch is CancellationError {
      return .cancelled
    } catch {
      guard owns(run) else { return .cancelled }
      failed = true
    }
    return failed ? .failed : .succeeded
  }

  /// A login with no reply over a stored route resets it and retries once by flood,
  /// matching `NodeAuthenticationSheet`.
  private func logIn(_ target: ContactDTO, in run: Run) async throws -> (UUID, LoginResult) {
    let operations = try operations(for: run)
    let password = await operations.savedPassword(target) ?? ""
    let session = try await operations.createSession(target)
    do {
      let result = try await self.operations(for: run).logIn(session.id, password, target.outPathLength)
      return (session.id, result)
    } catch let error where Self.isTimeout(error) && !target.isFloodRouted {
      try await self.operations(for: run).resetPath(target)
      let result = try await self.operations(for: run).logIn(session.id, password, PacketBuilder.floodPathSentinel)
      return (session.id, result)
    }
  }

  private func refreshBattery(for target: ContactDTO, sessionID: UUID, in run: Run) async throws {
    let status = try await operations(for: run).requestStatus(sessionID)
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
    login: LoginResult,
    statusAnswered: Bool,
    in run: Run
  ) async throws {
    if let level = login.firmwareLevel, level < Self.ownerInfoMinimumFirmwareLevel {
      try await recordMissingFirmware(for: target, in: run)
      return
    }
    let info: OwnerInfoResponse
    do {
      info = try await operations(for: run).requestOwnerInfo(sessionID)
    } catch let error where statusAnswered && Self.isTimeout(error) {
      // The repeater answered status, so a silent owner-info request means it lacks the request.
      try await recordMissingFirmware(for: target, in: run)
      return
    }
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

  private enum TargetOutcome {
    case succeeded, failed, noReply, cancelled
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
