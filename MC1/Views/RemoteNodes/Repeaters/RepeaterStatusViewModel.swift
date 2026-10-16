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
  private var statusEpoch = Epoch()
  private var neighborsEpoch = Epoch()
  private var telemetryEpoch = Epoch()
  private var ownerInfoEpoch = Epoch()
  private var discoveryEpoch = Epoch()
  private var pollsEnabled = false

  private static let discoveryDuration = 60
  private static let pollIntervalTicks = 5
  private static let discoverCommand = "discover.neighbors"

  #if DEBUG
    var discoveryTickForTesting: Duration?
    var pollIntervalTicksForTesting: Int?
    var requestNeighborsEntryCountForTesting = 0
    var fetchAllNeighborsForTesting: (@MainActor () async -> Void)?
  #endif

  private var discoveryTick: Duration {
    #if DEBUG
      discoveryTickForTesting ?? .seconds(1)
    #else
      .seconds(1)
    #endif
  }

  private var activePollIntervalTicks: Int {
    #if DEBUG
      pollIntervalTicksForTesting ?? Self.pollIntervalTicks
    #else
      Self.pollIntervalTicks
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

  /// Reads the live service from the provider so a reconnect-minted instance
  /// is used at call time. Sets only the slots this view model owns; the admin
  /// service is shared with the settings/CLI view model, so clearing here would
  /// drop its CLI handler and silently break late CLI-response delivery.
  func registerHandlers() async {
    guard let repeaterAdminService else { return }

    await repeaterAdminService.setStatusHandler { [weak self] status in
      guard await self?.helper.matchesSession(status.publicKeyPrefix) == true else { return }
      await self?.handleStatusResponse(status)
    }

    await repeaterAdminService.setNeighboursHandler { [weak self] response in
      guard await self?.helper.matchesSession(response.publicKeyPrefix) == true else { return }
      await self?.handleNeighboursResponse(response)
    }

    await repeaterAdminService.setTelemetryHandler { [weak self] response in
      guard await self?.helper.matchesSession(response.publicKeyPrefix) == true else { return }
      await self?.helper.handleTelemetryResponse(response)
    }
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

  func requestStatus(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }

    statusEpoch.bump()
    let ticket = statusEpoch.ticket()
    await helper.runRetryingSectionRequest(
      operationName: "status",
      setLoading: { self.helper.isLoadingStatus = $0 },
      setError: { self.helper.statusSectionError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.requestStatus(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { [ticket] response in
        guard ticket.isCurrent(in: self.statusEpoch) else { return }
        await self.handleStatusResponse(response)
      }
    )
  }

  private func handleStatusResponse(_ response: RemoteNodeStatus) async {
    await helper.handleStatusResponse(
      response,
      rxAirtimeSeconds: response.repeaterRxAirtimeSeconds,
      receiveErrors: response.receiveErrors
    )
  }

  // MARK: - Neighbors

  private func pollNeighbors(for session: RemoteNodeSessionDTO, ticket: Epoch.Ticket) async {
    #if DEBUG
      requestNeighborsEntryCountForTesting += 1
      if let fetchAllNeighborsForTesting {
        await fetchAllNeighborsForTesting()
      }
    #endif
    guard ticket.isCurrent(in: discoveryEpoch) else { return }
    await requestNeighbors(for: session)
  }

  func requestNeighbors(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }

    await helper.runRetryingSectionRequest(
      operationName: "neighbors",
      setLoading: { self.isLoadingNeighbors = $0 },
      setError: { self.neighborsSectionError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.fetchAllNeighbors(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { await self.handleNeighboursResponse($0) }
    )
  }

  func handleNeighboursResponse(_ response: NeighboursResponse) async {
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

    discoveryEpoch.bump()
    let ticket = discoveryEpoch.ticket()
    pollsEnabled = true
    discoverySecondsRemaining = Self.discoveryDuration

    discoverTask = Task {
      do {
        #if DEBUG
          if fetchAllNeighborsForTesting == nil {
            _ = try await repeaterAdminService.sendCommand(
              sessionID: session.id,
              command: Self.discoverCommand
            )
          }
        #else
          _ = try await repeaterAdminService.sendCommand(
            sessionID: session.id,
            command: Self.discoverCommand
          )
        #endif
      } catch is CancellationError {
        return
      } catch {
        guard ticket.isCurrent(in: self.discoveryEpoch) else { return }
        neighborsSectionError = error.userFacingMessage
        discoverySecondsRemaining = 0
        discoverTask = nil
        return
      }

      let startTime = Date.now
      var tickCount = 0

      while !Task.isCancelled, ticket.isCurrent(in: self.discoveryEpoch) {
        try? await Task.sleep(for: self.discoveryTick)
        guard !Task.isCancelled, ticket.isCurrent(in: self.discoveryEpoch) else { break }

        let elapsed = Int(Date.now.timeIntervalSince(startTime))
        let remaining = max(0, Self.discoveryDuration - elapsed)
        discoverySecondsRemaining = remaining

        tickCount += 1
        if pollsEnabled, tickCount.isMultiple(of: self.activePollIntervalTicks) {
          await pollNeighbors(for: session, ticket: ticket)
        }

        if remaining <= 0 { break }
      }

      guard ticket.isCurrent(in: self.discoveryEpoch) else { return }
      discoverySecondsRemaining = 0
      discoverTask = nil
    }
  }

  /// Dismiss keeps the countdown and neighbors already stored, and sends no further poll.
  func pauseDiscoveryPolls() {
    pollsEnabled = false
  }

  func stopDiscovery() {
    discoveryEpoch.bump()
    pollsEnabled = false
    discoverTask?.cancel()
    discoverTask = nil
    discoverySecondsRemaining = 0
  }

  func reset() {
    statusEpoch.bump()
    neighborsEpoch.bump()
    telemetryEpoch.bump()
    ownerInfoEpoch.bump()
    stopDiscovery()
    helper.status = nil
    helper.statusSectionError = nil
    helper.isLoadingStatus = false
    neighborsSectionError = nil
  }

  // MARK: - Telemetry

  func requestTelemetry(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }

    await helper.runRetryingSectionRequest(
      operationName: "telemetry",
      setLoading: { self.helper.isLoadingTelemetry = $0 },
      setError: { self.helper.telemetrySectionError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.requestTelemetry(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { await self.helper.handleTelemetryResponse($0) }
    )
  }

  // MARK: - Owner Info

  func requestOwnerInfo(for session: RemoteNodeSessionDTO) async {
    guard let repeaterAdminService else { return }
    if helper.session == nil { helper.session = session }

    await helper.runRetryingSectionRequest(
      operationName: "ownerInfo",
      setLoading: { self.isLoadingOwnerInfo = $0 },
      setError: { self.ownerInfoError = $0 },
      operation: { [repeaterAdminService] timeout in
        try await repeaterAdminService.requestOwnerInfo(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { self.applyOwnerInfo($0) }
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
