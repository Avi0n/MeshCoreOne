import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "RepeaterStatusVM")

/// ViewModel for repeater status display
@Observable
@MainActor
final class RepeaterStatusViewModel {
  // MARK: - Shared Helper

  var helper = NodeStatusViewModel()

  // MARK: - Repeater-Only Properties

  /// Neighbor entries
  var neighbors: [NeighbourInfo] = []

  /// Loading states
  var isLoadingNeighbors = false

  /// Whether neighbors have been loaded at least once (for refresh logic)
  var neighborsLoaded = false

  /// Whether the neighbors disclosure group is expanded
  var neighborsExpanded = false

  /// Error scoped to the neighbors section, kept separate from other sections' errors.
  var neighborsSectionError: String?

  /// Hex width for neighbour identity prefixes. Set with each neighbours response so on-screen
  /// identifiers keep their width if the companion disconnects while the list is shown.
  var neighborKeyDisplayByteCount = NeighborNameResolver.minimumKeyDisplayByteCount

  /// Discovery state
  var isDiscovering: Bool {
    discoverTask != nil
  }

  var discoverySecondsRemaining = 0
  private var discoverTask: Task<Void, Never>?
  private var statusVisit: NodeStatusViewModel.TelemetryVisitToken?
  private var neighborsVisit: NodeStatusViewModel.TelemetryVisitToken?
  private var telemetryVisit: NodeStatusViewModel.TelemetryVisitToken?
  private var ownerInfoVisit: NodeStatusViewModel.TelemetryVisitToken?

  private static let discoveryDuration = 60
  private static let pollIntervalTicks = 5
  private static let discoverCommand = "discover.neighbors"

  #if DEBUG
    var discoveryDurationForTesting: Int?
  #endif

  private final class DiscoveryRun {}

  private var discoveryRun: DiscoveryRun?

  private var discoveryLength: Int {
    #if DEBUG
      discoveryDurationForTesting ?? Self.discoveryDuration
    #else
      Self.discoveryDuration
    #endif
  }

  /// Owner info text
  var ownerInfo: String?

  /// Firmware version reported by the node's owner-info response; nil when the
  /// node predates owner-info (FIRMWARE_VER_LEVEL < 2) and returns an empty string.
  var firmwareVersion: String?

  /// Owner info loading/state
  var isLoadingOwnerInfo = false
  var ownerInfoLoaded: Bool {
    ownerInfo != nil
  }

  var ownerInfoExpanded = false
  var ownerInfoError: String?

  // MARK: - Dependencies

  private var repeaterAdminServiceProvider: @MainActor () -> RepeaterAdminService? = { nil }
  var repeaterAdminService: RepeaterAdminService? {
    repeaterAdminServiceProvider()
  }

  private var deviceHashSizeProvider: @MainActor () -> Int? = { nil }
  private var deviceHashSize: Int? {
    deviceHashSizeProvider()
  }

  // MARK: - Initialization

  init() {}

  /// Nil services mirror a disconnected state; requests then no-op.
  func configure(
    repeaterAdminService: @escaping @MainActor () -> RepeaterAdminService?,
    contactService: @escaping @MainActor () -> ContactService?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?,
    deviceHashSize: @escaping @MainActor () -> Int?
  ) {
    repeaterAdminServiceProvider = repeaterAdminService
    deviceHashSizeProvider = deviceHashSize
    helper.configure(
      contactService: contactService,
      nodeSnapshotService: nodeSnapshotService
    )
  }

  /// Does not request status, neighbors, or telemetry. A container change is not a new visit.
  func rebind(
    repeaterAdminService: @escaping @MainActor () -> RepeaterAdminService?,
    contactService: @escaping @MainActor () -> ContactService?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?,
    deviceHashSize: @escaping @MainActor () -> Int?
  ) async {
    configure(
      repeaterAdminService: repeaterAdminService,
      contactService: contactService,
      nodeSnapshotService: nodeSnapshotService,
      deviceHashSize: deviceHashSize
    )
    await registerHandlers()
  }

  /// Reads the live service from the provider so a reconnect-minted instance
  /// is used at call time. Sets only the slots this view model owns; the admin
  /// service is shared with the settings/CLI view model, so clearing here would
  /// drop its CLI handler and silently break late CLI-response delivery.
  func registerHandlers() async {
    guard let repeaterAdminService else { return }

    await repeaterAdminService.setStatusHandler(
      helper.visitGatedHandler(
        prefix: \.publicKeyPrefix,
        visit: { [weak self] in await self?.statusVisit },
        body: { [weak self] status, visit in
          await self?.handleStatusResponse(status, visit: visit)
        }
      )
    )

    await repeaterAdminService.setNeighboursHandler(
      helper.visitGatedHandler(
        prefix: \.publicKeyPrefix,
        visit: { [weak self] in await self?.neighborsVisit },
        body: { [weak self] response, visit in
          await self?.handleNeighboursResponse(response, visit: visit)
        }
      )
    )

    await repeaterAdminService.setTelemetryHandler(
      helper.visitGatedHandler(
        prefix: \.publicKeyPrefix,
        visit: { [weak self] in await self?.telemetryVisit },
        body: { [weak self] response, visit in
          await self?.helper.handleTelemetryResponse(response, visit: visit)
        }
      )
    )
  }

  /// Clear every handler slot on the shared admin service. Only for true
  /// surface teardown (sheet dismiss); calling it on a segment switch would
  /// wipe the CLI handler the settings view model relies on.
  func cleanup() async {
    guard let repeaterAdminService else { return }
    await repeaterAdminService.clearHandlers()
  }

  /// Clear only this view model's status/neighbours/telemetry handler slots, leaving the
  /// settings view model's CLI handler intact. For the merged admin surface's status-segment teardown.
  func clearStatusHandlers() async {
    guard let repeaterAdminService else { return }
    await repeaterAdminService.clearStatusHandlers()
  }

  // MARK: - Status

  func noteTelemetryVisitAppeared() {
    helper.beginTelemetryVisit()
  }

  func noteTelemetryVisitDisappeared() {
    helper.endTelemetryVisit()
    clearRepeaterTelemetryVisit()
  }

  private func clearRepeaterTelemetryVisit() {
    neighbors = []
    isLoadingNeighbors = false
    neighborsLoaded = false
    neighborsExpanded = false
    neighborsSectionError = nil
    ownerInfo = nil
    firmwareVersion = nil
    isLoadingOwnerInfo = false
    ownerInfoExpanded = false
    ownerInfoError = nil
    stopDiscovery()
  }

  func requestStatus(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }
    let visit = helper.captureTelemetryVisit()
    statusVisit = visit

    await helper.runVisitedSectionRequest(
      visit: visit,
      operationName: "status",
      setLoading: { self.helper.isLoadingStatus = $0 },
      setError: { self.helper.statusSectionError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.requestStatus(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { await self.handleStatusResponse($0, visit: visit) }
    )
  }

  private func handleStatusResponse(
    _ response: RemoteNodeStatus,
    visit: NodeStatusViewModel.TelemetryVisitToken
  ) async {
    await helper.handleStatusResponse(
      response,
      rxAirtimeSeconds: response.repeaterRxAirtimeSeconds,
      receiveErrors: response.receiveErrors,
      visit: visit
    )
  }

  // MARK: - Neighbors

  func requestNeighbors(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }

    let visit = helper.captureTelemetryVisit()
    neighborsVisit = visit

    await helper.runVisitedSectionRequest(
      visit: visit,
      operationName: "neighbors",
      setLoading: { self.isLoadingNeighbors = $0 },
      setError: { self.neighborsSectionError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.fetchAllNeighbors(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { await self.handleNeighboursResponse($0, visit: visit) }
    )
  }

  func handleNeighboursResponse(
    _ response: NeighboursResponse,
    visit: NodeStatusViewModel.TelemetryVisitToken
  ) async {
    guard helper.allowsTelemetryWrite(visit) else { return }
    neighbors = response.neighbours
    neighborKeyDisplayByteCount = NeighborNameResolver.keyDisplayByteCount(deviceHashSize: deviceHashSize)
    isLoadingNeighbors = false
    neighborsLoaded = true

    let entries = response.neighbours.map {
      NeighborSnapshotEntry(publicKeyPrefix: $0.publicKeyPrefix, snr: $0.snr, secondsAgo: $0.secondsAgo)
    }
    await helper.enrichNeighbors(entries)
  }

  // MARK: - Discovery

  func startDiscovery(for session: RemoteNodeSessionDTO) {
    guard let repeaterAdminService, !isDiscovering else { return }
    let visit = helper.captureTelemetryVisit()
    let run = beginDiscoveryRun()

    discoverTask = Task {
      do {
        _ = try await repeaterAdminService.sendCommand(
          sessionID: session.id,
          command: Self.discoverCommand
        )
      } catch {
        if helper.allowsTelemetryWrite(visit), discoveryRun === run {
          neighborsSectionError = error.userFacingMessage
        }
        endDiscovery(run)
        return
      }

      let startTime = Date.now
      var tickCount = 0
      while !Task.isCancelled {
        let elapsed = Int(Date.now.timeIntervalSince(startTime))
        let remaining = max(0, discoveryLength - elapsed)
        discoverySecondsRemaining = remaining
        if remaining <= 0 { break }
        try? await Task.sleep(for: .seconds(1))
        guard !Task.isCancelled else { break }

        tickCount += 1
        if tickCount.isMultiple(of: Self.pollIntervalTicks) {
          await requestNeighbors(for: session)
        }
      }
      endDiscovery(run)
    }
  }

  #if DEBUG
    func startDiscoveryForTesting() {
      let run = beginDiscoveryRun()
      discoverTask = Task {
        try? await Task.sleep(for: .seconds(discoveryLength))
        endDiscovery(run)
      }
    }
  #endif

  func stopDiscovery() {
    discoverTask?.cancel()
    discoverTask = nil
    discoveryRun = nil
    discoverySecondsRemaining = 0
  }

  private func beginDiscoveryRun() -> DiscoveryRun {
    let run = DiscoveryRun()
    discoveryRun = run
    discoverySecondsRemaining = discoveryLength
    return run
  }

  /// A replacement discovery and `stopDiscovery` keep the countdown they installed.
  private func endDiscovery(_ run: DiscoveryRun) {
    guard discoveryRun === run, !Task.isCancelled else { return }
    discoverTask = nil
    discoveryRun = nil
    discoverySecondsRemaining = 0
  }

  // MARK: - Telemetry

  func requestTelemetry(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }

    let visit = helper.captureTelemetryVisit()
    telemetryVisit = visit

    await helper.runVisitedSectionRequest(
      visit: visit,
      operationName: "telemetry",
      setLoading: { self.helper.isLoadingTelemetry = $0 },
      setError: { self.helper.telemetrySectionError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.requestTelemetry(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { await self.helper.handleTelemetryResponse($0, visit: visit) }
    )
  }

  // MARK: - Owner Info

  func requestOwnerInfo(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }

    let visit = helper.captureTelemetryVisit()
    ownerInfoVisit = visit

    await helper.runVisitedSectionRequest(
      visit: visit,
      operationName: "ownerInfo",
      setLoading: { self.isLoadingOwnerInfo = $0 },
      setError: { self.ownerInfoError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.requestOwnerInfo(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { response in
        guard self.helper.allowsTelemetryWrite(visit) else { return }
        self.applyOwnerInfo(response)
      }
    )
  }

  /// Applies an owner-info response to the display state, mapping an empty firmware
  /// string (nodes predating owner-info, FIRMWARE_VER_LEVEL < 2) to nil so the
  /// firmware row stays hidden rather than showing a blank value.
  func applyOwnerInfo(_ response: OwnerInfoResponse) {
    ownerInfo = response.ownerInfo
    firmwareVersion = response.firmwareVersion.isEmpty ? nil : response.firmwareVersion
  }

  // MARK: - Repeater-Only Display

  var receiveErrorsDisplay: String? {
    guard let count = helper.status?.receiveErrors, count > 0 else { return nil }
    return count.formatted()
  }
}
