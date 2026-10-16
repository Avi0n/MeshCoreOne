import MC1Services
import SwiftUI

/// ViewModel for room server status display
@Observable
@MainActor
final class RoomStatusViewModel {
  // MARK: - Shared Helper

  var helper = NodeStatusViewModel()
  private var statusEpoch = Epoch()
  #if DEBUG
    var requestStatusForTesting: (@Sendable (UUID) async throws -> StatusResponse)?
  #endif

  func reset() {
    statusEpoch.bump()
    helper.status = nil
    helper.statusSectionError = nil
    helper.isLoadingStatus = false
  }

  // MARK: - Dependencies

  private var roomAdminServiceProvider: @MainActor () -> RoomAdminService? = { nil }
  var roomAdminService: RoomAdminService? {
    roomAdminServiceProvider()
  }

  // MARK: - Initialization

  init() {}

  /// Nil services mirror a disconnected state; requests then no-op.
  func configure(
    roomAdminService: @escaping @MainActor () -> RoomAdminService?,
    contactService: @escaping @MainActor () -> ContactService?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?
  ) {
    roomAdminServiceProvider = roomAdminService
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
    guard let roomAdminService else { return }

    await roomAdminService.setStatusHandler { [weak self] status in
      guard await self?.helper.matchesSession(status.publicKeyPrefix) == true else { return }
      await self?.handleStatusResponse(status)
    }

    await roomAdminService.setTelemetryHandler { [weak self] response in
      guard await self?.helper.matchesSession(response.publicKeyPrefix) == true else { return }
      await self?.helper.handleTelemetryResponse(response)
    }
  }

  /// Clear every handler slot on the shared admin service. Only for true
  /// surface teardown (sheet dismiss); calling it on a segment switch would
  /// wipe the CLI handler the settings view model relies on.
  func cleanup() async {
    guard let roomAdminService else { return }
    await roomAdminService.clearHandlers()
  }

  /// Clear only this view model's status/telemetry handler slots, leaving the settings view
  /// model's CLI handler intact. For the merged admin surface's status-segment teardown.
  func clearStatusHandlers() async {
    guard let roomAdminService else { return }
    await roomAdminService.clearStatusHandlers()
  }

  // MARK: - Status

  func requestStatus(for session: RemoteNodeSessionDTO) async {
    guard let roomAdminService else { return }
    if helper.session == nil { helper.session = session }

    statusEpoch.bump()
    let ticket = statusEpoch.ticket()
    #if DEBUG
      let hookedStatus = requestStatusForTesting
    #endif
    await helper.runRetryingSectionRequest(
      operationName: "status",
      setLoading: { self.helper.isLoadingStatus = $0 },
      setError: { self.helper.statusSectionError = $0 },
      operation: { [roomAdminService] timeout in
        #if DEBUG
          if let hookedStatus {
            return try await hookedStatus(session.id)
          }
        #endif
        return try await roomAdminService.requestStatus(sessionID: session.id, timeout: timeout)
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
      postedCount: response.roomServerPostedCount,
      postPushCount: response.roomServerPostPushCount
    )
  }

  // MARK: - Telemetry

  func requestTelemetry(for session: RemoteNodeSessionDTO) async {
    guard let roomAdminService else { return }
    if helper.session == nil { helper.session = session }

    await helper.runRetryingSectionRequest(
      operationName: "telemetry",
      setLoading: { self.helper.isLoadingTelemetry = $0 },
      setError: { self.helper.telemetrySectionError = $0 },
      operation: { [roomAdminService] timeout in
        try await roomAdminService.requestTelemetry(sessionID: session.id, timeout: timeout)
      },
      onSuccess: { await self.helper.handleTelemetryResponse($0) }
    )
  }

  // MARK: - Room-Only Display

  var postsReceivedDisplay: String {
    guard let count = helper.status?.roomServerPostedCount else { return NodeStatusViewModel.emDash }
    return count.formatted()
  }

  var postsPushedDisplay: String {
    guard let count = helper.status?.roomServerPostPushCount else { return NodeStatusViewModel.emDash }
    return count.formatted()
  }
}
